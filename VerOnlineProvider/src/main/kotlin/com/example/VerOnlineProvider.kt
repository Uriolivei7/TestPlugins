package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo.

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

class VerOnlineProvider : MainAPI() {
    override var mainUrl = "https://www.verseriesonline.net"
    override var name = "VerOnline"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            // CAMBIO AQUÍ: Usamos mainUrl directamente para la página principal
            Pair("Últimas Series", mainUrl), // Probamos la URL principal directamente
            // Si la página principal no muestra las series directamente,
            // podríamos necesitar otra URL como "$mainUrl/series-online/genero/todos-los-generos"
            // o buscar si hay una página principal de "series" que no sea un género.
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when {
                name.contains("Series") -> TvType.TvSeries
                else -> TvType.Others
            }
            try {
                Log.d("VerOnline", "getMainPage - Intentando obtener URL: $url")
                val doc = app.get(url).document
                Log.d("VerOnline", "getMainPage - HTML recibido para $url (primeros 1000 chars): ${doc.html().take(1000)}")

                val homeItems = doc.select("a.th-hover").mapNotNull { aElement ->
                    val title = aElement.selectFirst("div.th-title")?.text()
                    val link = aElement.attr("href")
                    val img = aElement.selectFirst("img")?.attr("src")
                        ?: aElement.selectFirst("img")?.attr("data-src")

                    if (title != null && link.isNotBlank()) {
                        TvSeriesSearchResponse(
                            name = title,
                            url = fixUrl(link),
                            posterUrl = img,
                            type = tvType,
                            apiName = this.name
                        )
                    } else null
                }
                Log.d("VerOnline", "getMainPage - Encontrados ${homeItems.size} ítems para $url")
                HomePageList(name, homeItems)
            } catch (e: Exception) {
                Log.e("VerOnline", "Error al obtener la página principal para $url: ${e.message} - ${e.stackTraceToString()}", e)
                null
            }
        }.filterNotNull()

        items.addAll(homePageLists)
        return HomePageResponse(items.toList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // CAMBIO AQUÍ: La URL de búsqueda `/buscar?q=` parece no funcionar.
        // Si el sitio no tiene una URL de búsqueda simple, a veces se puede hacer una "búsqueda"
        // simplemente buscando en la página principal o una categoría si es posible.
        // Por ahora, dejamos la URL de búsqueda, pero necesitamos la URL *correcta* de búsqueda.
        val url = "$mainUrl/buscar?q=$query" // Esta URL ha estado dando 404. Necesitamos la real.
        Log.d("VerOnline", "search - Intentando buscar en URL: $url")
        try {
            val doc = app.get(url).document
            Log.d("VerOnline", "search - HTML recibido para $url (primeros 1000 chars): ${doc.html().take(1000)}")

            val searchResults = doc.select("a.th-hover").mapNotNull { aElement ->
                val title = aElement.selectFirst("div.th-title")?.text()
                val link = aElement.attr("href")
                val img = aElement.selectFirst("img")?.attr("src")
                    ?: aElement.selectFirst("img")?.attr("data-src")

                if (title != null && link.isNotBlank()) {
                    TvSeriesSearchResponse(
                        name = title,
                        url = fixUrl(link),
                        posterUrl = img,
                        type = TvType.TvSeries,
                        apiName = this.name
                    )
                } else null
            }
            Log.d("VerOnline", "search - Encontrados ${searchResults.size} resultados para '$query'")
            return searchResults
        } catch (e: Exception) {
            Log.e("VerOnline", "Error en la búsqueda para '$query' en URL $url: ${e.message} - ${e.stackTraceToString()}", e)
            return emptyList()
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("VerOnline", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("VerOnline", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("VerOnline", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("VerOnline", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("VerOnline", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            Log.e("VerOnline", "load - ERROR al obtener el documento para URL: $cleanUrl - ${e.message} - ${e.stackTraceToString()}", e)
            return null
        }

        Log.d("VerOnline", "load - HTML recibido para la URL de la serie (primeros 2000 chars): ${doc.html().take(2000)}")
        Log.d("VerOnline", "load - ¿Contiene 'serie-episodes'? ${doc.html().contains("serie-episodes")}")
        Log.d("VerOnline", "load - ¿Contiene 'episode-list'? ${doc.html().contains("episode-list")}")
        Log.d("VerOnline", "load - ¿Contiene 'season-list'? ${doc.html().contains("season-list")}")

        val tvType = TvType.TvSeries
        val title = doc.selectFirst("h1.post-title")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""
        val poster = doc.selectFirst("div.full-content-inner img.lazy-loaded")?.attr("data-src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""
        val description = doc.selectFirst("div.full_content-desc p")?.text()
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content") ?: ""
        val tags = emptyList<String>()

        val allEpisodes = ArrayList<Episode>()

        val seasonElements = doc.select("div.season-list a.th-hover")
        Log.d("VerOnline", "load - Temporadas encontradas en la página principal: ${seasonElements.size}")

        if (seasonElements.isNotEmpty()) {
            seasonElements.apmap { seasonElement ->
                val seasonUrl = fixUrl(seasonElement.attr("href"))
                val seasonName = seasonElement.selectFirst("div.th-title")?.text()?.trim()
                    ?: "Temporada Desconocida"
                val seasonNumber = Regex("""Temporada\s*(\d+)""").find(seasonName)?.groupValues?.get(1)?.toIntOrNull()

                Log.d("VerOnline", "load - Intentando cargar temporada: $seasonName ($seasonUrl)")

                val seasonDoc = try {
                    app.get(seasonUrl).document
                } catch (e: Exception) {
                    Log.e("VerOnline", "load - ERROR al obtener documento de temporada $seasonName ($seasonUrl): ${e.message}", e)
                    return@apmap
                }

                val episodesInSeason = seasonDoc.select("div#serie-episodes div.episode-list div.saisoin_LI2").mapNotNull { episodeElement ->
                    val aElement = episodeElement.selectFirst("a")
                    val epurl = fixUrl(aElement?.attr("href") ?: "")
                    val epTitleText = aElement?.selectFirst("span")?.text() ?: ""

                    val episodeNumber = Regex("""Capítulo\s*(\d+)""").find(epTitleText)?.groupValues?.get(1)?.toIntOrNull()
                    val finalSeasonNumber = seasonNumber ?: Regex("""temporada-(\d+)""").find(epurl)?.groupValues?.get(1)?.toIntOrNull()

                    val realimg = poster

                    if (epurl.isNotBlank() && epTitleText.isNotBlank()) {
                        Log.d("VerOnline", "load - Episodio encontrado: Título='$epTitleText', URL='$epurl', Temporada=${finalSeasonNumber}, Episodio=$episodeNumber")
                        Episode(
                            data = EpisodeLoadData(epTitleText, epurl).toJson(),
                            name = epTitleText,
                            season = finalSeasonNumber,
                            episode = episodeNumber,
                            posterUrl = realimg
                        )
                    } else {
                        Log.w("VerOnline", "load - Episodio incompleto encontrado en temporada $seasonName: URL=$epurl, Título=$epTitleText")
                        null
                    }
                }
                allEpisodes.addAll(episodesInSeason)
            }
        } else {
            Log.w("VerOnline", "load - No se encontraron elementos de temporada. Intentando extraer episodios directamente de la URL de la serie.")
            val episodesDirectly = doc.select("div#serie-episodes div.episode-list div.saisoin_LI2").mapNotNull { episodeElement ->
                val aElement = episodeElement.selectFirst("a")
                val epurl = fixUrl(aElement?.attr("href") ?: "")
                val epTitleText = aElement?.selectFirst("span")?.text() ?: ""

                val episodeNumber = Regex("""Capítulo\s*(\d+)""").find(epTitleText)?.groupValues?.get(1)?.toIntOrNull()
                val seasonNumber = Regex("""temporada-(\d+)""").find(epurl)?.groupValues?.get(1)?.toIntOrNull()

                val realimg = poster

                if (epurl.isNotBlank() && epTitleText.isNotBlank()) {
                    Log.d("VerOnline", "load - Episodio encontrado (directo): Título='$epTitleText', URL='$epurl', Temporada=$seasonNumber, Episodio=$episodeNumber")
                    Episode(
                        data = EpisodeLoadData(epTitleText, epurl).toJson(),
                        name = epTitleText,
                        season = seasonNumber,
                        episode = episodeNumber,
                        posterUrl = realimg
                    )
                } else {
                    Log.w("VerOnline", "load - Episodio incompleto encontrado (directo): URL=$epurl, Título=$epTitleText")
                    null
                }
            }
            allEpisodes.addAll(episodesDirectly)
        }

        Log.d("VerOnline", "load - Total de episodios encontrados (final): ${allEpisodes.size}")

        return TvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            apiName = this.name,
            type = tvType,
            episodes = allEpisodes,
            posterUrl = poster,
            backgroundPosterUrl = poster,
            plot = description,
            tags = tags
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("VerOnline", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("VerOnline", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("VerOnline", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("VerOnline", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("VerOnline", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("VerOnline", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("VerOnline", "loadLinks - ERROR al obtener el documento para URL: $targetUrl - ${e.message} - ${e.stackTraceToString()}", e)
            return false
        }

        val streamerElements = doc.select("li.streamer")

        if (streamerElements.isEmpty()) {
            Log.w("VerOnline", "loadLinks - No se encontraron elementos 'li.streamer' en la página del episodio. No se pudieron extraer enlaces.")
            return false
        }

        var foundLinks = false
        streamerElements.apmap { streamerElement ->
            val encodedUrl = streamerElement.attr("data-url")
            val serverName = streamerElement.selectFirst("span[id*='player_V_DIV_5']")?.text()
                ?: streamerElement.selectFirst("span")?.text()?.replace("OPCIÓN ", "Opción ")?.trim()

            if (encodedUrl.isNotBlank()) {
                val base64Part = encodedUrl.substringAfter("/streamer/")

                try {
                    val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                    val decodedUrl = String(decodedBytes, UTF_8)
                    Log.d("VerOnline", "loadLinks - Decodificado URL para $serverName: $decodedUrl")

                    val extracted = loadExtractor(
                        url = fixUrl(decodedUrl),
                        referer = targetUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    if (extracted) foundLinks = true

                } catch (e: IllegalArgumentException) {
                    Log.e("VerOnline", "loadLinks - Error al decodificar Base64 de $encodedUrl: ${e.message}")
                } catch (e: Exception) {
                    Log.e("VerOnline", "loadLinks - Error general al procesar link de $serverName ($encodedUrl): ${e.message} - ${e.stackTraceToString()}", e)
                }
            }
        }
        return foundLinks
    }
}