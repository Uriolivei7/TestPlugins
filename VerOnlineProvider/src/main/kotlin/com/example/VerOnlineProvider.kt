package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
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

    private fun log(message: String) {
        Log.d(name, message)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val mainPageResponse = app.get(mainUrl)
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)

        log("getMainPage - HTML de la página principal cargado (primeros 1000 chars): ${mainPageDoc.html().take(1000)}")

        // Buscar directamente por los contenedores de las listas de series
        // que están dentro de "dle-content" y tienen la clase "short griddler-list".
        val sectionsContainers = mainPageDoc.select("div#dle-content div.short.griddler-list")

        if (sectionsContainers.isEmpty()) {
            log("getMainPage - ERROR: No se encontraron los contenedores de series (div#dle-content div.short.griddler-list).")
            return null
        }
        log("getMainPage - Se encontraron ${sectionsContainers.size} contenedores de secciones de series.")

        // Iterar sobre cada contenedor de sección para extraer las series
        sectionsContainers.forEachIndexed { index, sectionContainer ->
            // Intentar obtener el título de la sección.
            // A veces el h1 está un nivel más arriba de 'dle-content',
            // o puede que haya un h2 dentro de la sección.
            // Para ser más robustos, podemos intentar subir y buscar un h1 o h2.
            // Basado en image_f430fa.png, el h1 está fuera de dle-content, pero en la misma section.

            // Navegar hacia el padre de 'dle-content' para encontrar el h1 asociado
            val sectionParent = sectionContainer.parents().firstOrNull { it.id() == "dle-content" }?.parent()
            val sectionTitleElement = sectionParent?.selectFirst("h1.maintitle")
                ?: sectionParent?.selectFirst("h2") // Si no es h1, buscar h2

            val sectionName = sectionTitleElement?.text()?.trim() ?: "Sección ${index + 1}"

            val seriesElements = sectionContainer.select("div.short_in") // Los ítems individuales dentro del contenedor de la lista.

            val series = seriesElements.mapNotNull { element ->
                val aElement = element.selectFirst("a.short_img_box.with_mask") // Selector más específico para el enlace principal.
                val link = aElement?.attr("href")
                val imgElement = aElement?.selectFirst("img")
                val img = imgElement?.attr("data-src") ?: imgElement?.attr("src") // Prefer data-src
                val title = aElement?.selectFirst("div.short_title")?.text() ?: aElement?.attr("title")

                if (title != null && link != null && img != null) {
                    TvSeriesSearchResponse(
                        name = title.trim(),
                        url = fixUrl(link),
                        posterUrl = fixUrl(img),
                        type = TvType.TvSeries,
                        apiName = this.name
                    )
                } else {
                    log("getMainPage - Ítem incompleto para '$sectionName' (index $index): Título='$title', Link='$link', Img='$img'")
                    null
                }
            }
            if (series.isNotEmpty()) {
                items.add(HomePageList(sectionName, series))
                log("getMainPage - Encontrados ${series.size} ítems para '$sectionName'")
            } else {
                log("getMainPage - No se encontraron ítems para '$sectionName' (index $index).")
            }
        }

        if (items.isEmpty()) {
            log("getMainPage - ADVERTENCIA: No se agregaron listas de series a la página principal. Posiblemente no se encontraron series.")
        }

        return HomePageResponse(items.toList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = ArrayList<SearchResponse>()
        val mainPageResponse = app.get(mainUrl)
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)
        val csrfToken = mainPageDoc.select("meta[name=csrf-token]").attr("content")

        if (csrfToken.isBlank()) {
            log("search - ERROR: No se pudo obtener el token CSRF para la búsqueda.")
            return emptyList()
        }
        log("search - Token CSRF para búsqueda: $csrfToken")

        val searchUrl = "$mainUrl/livesearch"
        val postData = mapOf(
            "search" to query,
            "_token" to csrfToken
        )

        log("search - Intentando POST de búsqueda a URL: $searchUrl con datos: $postData")
        try {
            val res = app.post(
                url = searchUrl,
                data = postData,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl
                )
            )
            val doc = Jsoup.parse(res.text)

            log("search - HTML de búsqueda recibido (primeros 1000 chars): ${doc.html().take(1000)}")

            // Los resultados de búsqueda directa (livesearch) parecen ser simples <a> tags.
            // Ejemplo de la imagen 'image_f44813.jpg' cuando escribes "sym-bid",
            // los resultados aparecen en un div (#searchsuggestions) que contiene <a> tags.
            val items = doc.select("a") // Asume que la respuesta AJAX contiene solo los <a> tags.
            for (item in items) {
                val link = item.attr("href") ?: ""
                val title = item.text() ?: "" // El texto del <a> es el título.

                if (title.isNotBlank() && link.isNotBlank()) {
                    searchResults.add(
                        TvSeriesSearchResponse(
                            name = title,
                            url = fixUrl(link),
                            posterUrl = null, // No hay póster en la respuesta directa de livesearch.
                            type = TvType.TvSeries, // Asumimos TvSeries por defecto.
                            apiName = this.name
                        )
                    )
                }
            }
            log("search - Encontrados ${searchResults.size} resultados para '$query'")
            return searchResults
        } catch (e: Exception) {
            log("search - Error en la búsqueda para '$query' en URL $searchUrl: ${e.message} - ${e.stackTraceToString()}")
            return emptyList()
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        log("load - URL de entrada: $url")

        var cleanUrl = url
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(url)
        if (parsedEpisodeData != null) {
            cleanUrl = parsedEpisodeData.url
            log("load - URL limpia por JSON de EpisodeLoadData: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                log("load - URL limpiada con HTTPS: $cleanUrl")
            }
            log("load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            log("load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            log("load - ERROR al obtener el documento para URL: $cleanUrl - ${e.message} - ${e.stackTraceToString()}")
            return null
        }

        log("load - HTML recibido para la URL de la serie (primeros 2000 chars): ${doc.html().take(2000)}")

        val title = doc.selectFirst("h1.movs-title")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""
        val poster = doc.selectFirst("div.film-poster img")?.attr("data-src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""
        val plot = doc.selectFirst("div.description.full")?.text()
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content") ?: ""
        val year = doc.selectFirst("div.info-text:contains(Año) span")?.text()?.toIntOrNull()
        val tags = emptyList<String>()

        val allEpisodes = ArrayList<Episode>()

        // Selectores basados en la estructura de episodios que he visto en sitios similares
        // y el logcat anterior que mostraba "ul.listing.items.full li"
        val episodeElements = doc.select("ul.listing.items.full li")

        if (episodeElements.isNotEmpty()) {
            log("load - Parece ser una página de serie con episodios listados directamente.")
            episodeElements.mapNotNull { episodeElement ->
                val epLink = episodeElement.selectFirst("a")?.attr("href") ?: ""
                val epTitleText = episodeElement.selectFirst("div.name")?.text() ?: ""
                val ssEpiText = episodeElement.selectFirst("div.ss-epi")?.text()

                val seasonNumber = ssEpiText?.substringAfter("Temporada ")?.substringBefore(" ")?.toIntOrNull()
                val episodeNumber = ssEpiText?.substringAfter("Episodio ")?.toIntOrNull()

                if (epLink.isNotBlank() && epTitleText.isNotBlank()) {
                    log("load - Episodio: Título='$epTitleText', URL='$epLink', Temporada=$seasonNumber, Episodio=$episodeNumber")
                    Episode(
                        data = EpisodeLoadData(epTitleText, fixUrl(epLink)).toJson(),
                        name = epTitleText,
                        season = seasonNumber,
                        episode = episodeNumber,
                        posterUrl = poster
                    )
                } else {
                    log("load - Episodio incompleto: URL=$epLink, Título=$epTitleText")
                    null
                }
            }.let {
                allEpisodes.addAll(it)
            }
        } else {
            log("load - No se encontraron episodios en 'ul.listing.items.full li'. La estructura puede haber cambiado o no hay episodios listados directamente.")
        }

        log("load - Total de episodios encontrados (final): ${allEpisodes.size}")

        val recommendations = doc.select("div.item").mapNotNull { recElement ->
            val recTitle = recElement.selectFirst("h3 a")?.text()
            val recLink = recElement.selectFirst("a")?.attr("href")
            val recPoster = recElement.selectFirst("img")?.attr("src")

            if (recTitle != null && recLink != null && recPoster != null) {
                TvSeriesSearchResponse(
                    name = recTitle,
                    url = fixUrl(recLink),
                    posterUrl = fixUrl(recPoster),
                    type = TvType.TvSeries,
                    apiName = this.name
                )
            } else null
        }

        return TvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            apiName = this.name,
            type = TvType.TvSeries,
            episodes = allEpisodes,
            posterUrl = fixUrl(poster),
            backgroundPosterUrl = fixUrl(poster),
            plot = plot,
            year = year,
            tags = tags,
            recommendations = recommendations
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("loadLinks - Data de entrada: $data")

        var targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            log("loadLinks - URL final del episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(data)
            log("loadLinks - URL final de película (directa o ya limpia y con fixUrl): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            log("loadLinks - ERROR: La URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            log("loadLinks - ERROR al obtener el documento para URL: $targetUrl - ${e.message} - ${e.stackTraceToString()}")
            return false
        }

        val streamerElements = doc.select("li.streamer")

        var foundLinks = false
        if (streamerElements.isNotEmpty()) {
            log("loadLinks - Se encontraron ${streamerElements.size} elementos 'li.streamer'. Procesando...")
            streamerElements.apmap { streamerElement ->
                val encodedUrl = streamerElement.attr("data-url") ?: ""
                val serverName = streamerElement.selectFirst("span[id*='player_V_DIV_5']")?.text()
                    ?: streamerElement.selectFirst("span")?.text()?.replace("OPCIÓN ", "Opción ")?.trim()
                    ?: "Servidor Desconocido"

                if (encodedUrl.isNotBlank()) {
                    val base64Part = encodedUrl.substringAfter("/streamer/")

                    try {
                        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                        val decodedUrl = String(decodedBytes, UTF_8)
                        log("loadLinks - Decodificado URL para $serverName: $decodedUrl")

                        val linkType = if (decodedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = serverName,
                                url = fixUrl(decodedUrl),
                                referer = targetUrl,
                                quality = 0,
                                headers = emptyMap(),
                                extractorData = null,
                                type = linkType
                            )
                        )
                        foundLinks = true

                    } catch (e: IllegalArgumentException) {
                        log("loadLinks - Error al decodificar Base64 de $encodedUrl: ${e.message}")
                    } catch (e: Exception) {
                        log("loadLinks - Error general al procesar link de $serverName ($encodedUrl): ${e.message} - ${e.stackTraceToString()}")
                    }
                }
            }
        } else {
            log("loadLinks - No se encontraron elementos 'li.streamer' en la página del episodio. Buscando alternativas.")

            val iframeSrc = doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
                ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                log("loadLinks - Encontrado iframe directo en la página: $iframeSrc. Intentando ExtractorLink.")
                val iframeLinkType = if (iframeSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "Directo Iframe",
                        url = fixUrl(iframeSrc),
                        referer = targetUrl,
                        quality = 0,
                        headers = emptyMap(),
                        extractorData = null,
                        type = iframeLinkType
                    )
                )
                foundLinks = true
            }

            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                log("loadLinks - Encontrados enlaces directos en scripts. Intentando ExtractorLink.")
                directMatches.apmap { directUrl ->
                    val directLinkType = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "Directo Script",
                            url = fixUrl(directUrl),
                            referer = targetUrl,
                            quality = 0,
                            headers = emptyMap(),
                            extractorData = null,
                            type = directLinkType
                        )
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}