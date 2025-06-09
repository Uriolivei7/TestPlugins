package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

class VerOnlineProvider : MainAPI() {
    override var mainUrl = "https://www.veronline.cfd"
    override var name = "VerOnline"
    // Modificado: Solo soporta TvSeries y Anime/Cartoon si la página es exclusivamente de series.
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime, // Mantener si el sitio tiene animes también
        TvType.Cartoon, // Mantener si el sitio tiene cartoons también
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        // URLs actualizadas basadas en tu observación y HTML.
        // Se asume que solo hay "series-online.html".
        // Si hay una URL diferente para "Estrenos de Series", deberías investigarla y añadirla.
        val urls = listOf(
            Pair("Últimas Series", "$mainUrl/series-online.html"), // URL CORREGIDA
            // Eliminadas las URLs de películas
            // Si hay una URL específica para "Estrenos de Series", añádela aquí
            // Ejemplo: Pair("Estrenos de Series", "$mainUrl/estrenos-series.html"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when {
                name.contains("Series") -> TvType.TvSeries
                else -> TvType.Others // TvType.Others en caso de que añadas algo que no sea directamente serie
            }
            try {
                Log.d("VerOnline", "getMainPage - Intentando obtener URL: $url")
                val doc = app.get(url).document
                Log.d("VerOnline", "getMainPage - HTML recibido para $url (primeros 1000 chars): ${doc.html().take(1000)}")
                // Selector CSS actualizado basado en image_1006dc.jpg
                val homeItems = doc.select("div.shortstory.radius-3").mapNotNull { articleElement ->
                    val aElement = articleElement.selectFirst("a")
                    val title = aElement?.attr("title") // Título de la serie
                    val link = aElement?.attr("href") // Enlace a la serie
                    val img = aElement?.selectFirst("img")?.attr("src") // URL del póster

                    if (title != null && link != null) {
                        newTvSeriesSearchResponse( // Usar newTvSeriesSearchResponse ya que solo son series
                            title,
                            fixUrl(link)
                        ) {
                            this.type = tvType
                            this.posterUrl = img
                        }
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
        return newHomePageResponse(items.toList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Asumiendo que la búsqueda ahora también se hace en el dominio principal
        // y que el sitio manejará si es solo de series.
        // ¡DEBES VERIFICAR LA URL DE BÚSQUEDA EN EL NAVEGADOR!
        val url = "$mainUrl/?s=$query" // Esta URL es la misma que antes, pero el problema es el contenido de la respuesta.
        Log.d("VerOnline", "search - Intentando buscar en URL: $url")
        try {
            val doc = app.get(url).document
            Log.d("VerOnline", "search - HTML recibido para $url (primeros 1000 chars): ${doc.html().take(1000)}")
            // Selector CSS actualizado para la búsqueda, asumiendo que usa la misma estructura
            return doc.select("div.shortstory.radius-3").mapNotNull { articleElement ->
                val aElement = articleElement.selectFirst("a")
                val title = aElement?.attr("title")
                val link = aElement?.attr("href")
                val img = aElement?.selectFirst("img")?.attr("src")

                if (title != null && link != null) {
                    newTvSeriesSearchResponse( // Cambiado de newAnimeSearchResponse a newTvSeriesSearchResponse
                        title,
                        fixUrl(link)
                    ) {
                        this.type = TvType.TvSeries // Asegurarse que el tipo es TvSeries
                        this.posterUrl = img
                    }
                } else null
            }
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

        // Tipo siempre será TvSeries ya que no hay películas.
        val tvType = TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""
        val description = doc.selectFirst("div.entry-content p")?.text()
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content") ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = doc.select("div#seasons ul.episodios li").mapNotNull { episodeElement ->
            val epurl = fixUrl(episodeElement.selectFirst("a")?.attr("href") ?: "")
            val epTitleText = episodeElement.selectFirst("div.episodiotitle div.epst")?.text()
                ?: episodeElement.selectFirst("div.episodiotitle a")?.text()
                ?: episodeElement.selectFirst("h3")?.text()
                ?: Regex("""(?i)episodio\s*(\d+)""").find(epurl)?.groupValues?.get(1)?.let { "Episodio $it" } ?: ""

            val numerandoText = episodeElement.selectFirst("div.episodiotitle div.numerando")?.text()
            val seasonNumber = numerandoText?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
            val episodeNumber = numerandoText?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()
                ?: Regex("""(?i)episodio\s*(\d+)""").find(epurl)?.groupValues?.get(1)?.toIntOrNull()

            val realimg = episodeElement.selectFirst("div.imagen img")?.attr("src")
                ?: episodeElement.selectFirst("img")?.attr("src")

            if (epurl.isNotBlank() && epTitleText.isNotBlank()) {
                newEpisode(
                    EpisodeLoadData(epTitleText, epurl).toJson()
                ) {
                    this.name = epTitleText
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.posterUrl = realimg
                }
            } else {
                Log.w("VerOnline", "load - Episodio incompleto encontrado: URL=$epurl, Título=$epTitleText")
                null
            }
        }

        return newTvSeriesLoadResponse( // Siempre TvSeries
            name = title,
            url = cleanUrl,
            type = tvType,
            episodes = episodes,
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    data class SortedEmbed(
        val servername: String,
        val link: String,
        val type: String
    )

    data class DataLinkEntry(
        val file_id: String,
        val video_language: String,
        val sortedEmbeds: List<SortedEmbed>
    )

    private fun decryptLink(encryptedLinkBase64: String, secretKey: String): String? {
        try {
            val encryptedBytes = Base64.decode(encryptedLinkBase64, Base64.DEFAULT)

            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ivSpec = IvParameterSpec(ivBytes)

            val cipherTextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)

            val keySpec = SecretKeySpec(secretKey.toByteArray(UTF_8), "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decryptedBytes = cipher.doFinal(cipherTextBytes)

            return String(decryptedBytes, UTF_8)
        } catch (e: Exception) {
            Log.e("VerOnline", "Error al descifrar link: ${e.message} - ${e.stackTraceToString()}", e)
            return null
        }
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

        val iframeSrc = doc.selectFirst("div[id*=\"player-ajax\"] iframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")
            ?: doc.selectFirst("div#full-video iframe")?.attr("src")
            ?: doc.selectFirst("div#player_div_1 iframe")?.attr("src")
            ?: doc.selectFirst("iframe[src*='embed']")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("VerOnline", "No se encontró iframe del reproductor con los selectores específicos en VerOnline. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                Log.d("VerOnline", "Encontrados ${directMatches.size} enlaces directos en script de página principal.")
                directMatches.apmap { directUrl ->
                    loadExtractor(fixUrl(directUrl), targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("VerOnline", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("VerOnline", "Iframe encontrado: $iframeSrc")

        when {
            iframeSrc.contains("xupalace.org") -> {
                Log.d("VerOnline", "loadLinks - Detectado Xupalace.org iframe: $iframeSrc")
                val xupalaceDoc = try {
                    app.get(fixUrl(iframeSrc)).document
                } catch (e: Exception) {
                    Log.e("VerOnline", "Error al obtener el contenido del iframe de Xupalace ($iframeSrc): ${e.message} - ${e.stackTraceToString()}")
                    return false
                }

                val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
                val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

                if (elementsWithOnclick.isEmpty()) {
                    Log.w("VerOnline", "No se encontraron elementos con 'go_to_playerVast' en xupalace.org.")
                    return false
                }

                val foundXupalaceLinks = mutableListOf<String>()
                for (element: Element in elementsWithOnclick) {
                    val onclickAttr = element.attr("onclick")
                    val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)

                    if (matchPlayerUrl != null) {
                        val videoUrl = matchPlayerUrl.groupValues[1]
                        Log.d("VerOnline", "Xupalace: Encontrada URL: $videoUrl")
                        if (videoUrl.isNotBlank()) {
                            foundXupalaceLinks.add(videoUrl)
                        }
                    } else {
                        Log.w("VerOnline", "Xupalace: No se pudo extraer la URL del onclick: $onclickAttr")
                    }
                }
                if (foundXupalaceLinks.isNotEmpty()) {
                    foundXupalaceLinks.apmap { playerUrl ->
                        loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                    }
                    return true
                }
                return false
            }
            iframeSrc.contains("re.veronline.cfd/embed.php") || iframeSrc.contains("re.sololatino.net/embed.php") || iframeSrc.contains("re.anizone.net/embed.php") -> {
                Log.d("VerOnline", "loadLinks - Detectado re.veronline.cfd/embed.php o similar iframe: $iframeSrc")
                val embedDoc = try {
                    app.get(fixUrl(iframeSrc)).document
                } catch (e: Exception) {
                    Log.e("VerOnline", "Error al obtener el contenido del iframe de re.veronline.cfd ($iframeSrc): ${e.message} - ${e.stackTraceToString()}")
                    return false
                }

                val regexGoToPlayerUrl = Regex("""go_to_player\('([^']+)'""")
                val elementsWithOnclick = embedDoc.select("*[onclick*='go_to_player']")

                if (elementsWithOnclick.isEmpty()) {
                    Log.w("VerOnline", "No se encontraron elementos con 'go_to_player' en re.veronline.cfd/embed.php o similar.")
                    return false
                }

                val foundEmbedLinks = mutableListOf<String>()
                for (element: Element in elementsWithOnclick) {
                    val onclickAttr = element.attr("onclick")
                    val matchPlayerUrl = regexGoToPlayerUrl.find(onclickAttr)

                    if (matchPlayerUrl != null) {
                        val videoUrl = matchPlayerUrl.groupValues[1]
                        Log.d("VerOnline", "re.veronline.cfd: Encontrada URL: $videoUrl")
                        if (videoUrl.isNotBlank()) {
                            foundEmbedLinks.add(videoUrl)
                        }
                    } else {
                        Log.w("VerOnline", "re.veronline.cfd: No se pudo extraer la URL del onclick: $onclickAttr")
                    }
                }
                if (foundEmbedLinks.isNotEmpty()) {
                    foundEmbedLinks.apmap { playerUrl ->
                        loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                    }
                    return true
                }
                return false
            }
            iframeSrc.contains("embed69.org") -> {
                Log.d("VerOnline", "loadLinks - Detectado embed69.org iframe: $iframeSrc")
                val frameDoc = try {
                    app.get(fixUrl(iframeSrc)).document
                } catch (e: Exception) {
                    Log.e("VerOnline", "Error al obtener el contenido del iframe ($iframeSrc): ${e.message} - ${e.stackTraceToString()}")
                    return false
                }

                val scriptContent = frameDoc.select("script").map { it.html() }.joinToString("\n")

                val dataLinkRegex = """const dataLink = (\[.*?\]);""".toRegex()
                val dataLinkJsonString = dataLinkRegex.find(scriptContent)?.groupValues?.get(1)

                if (dataLinkJsonString.isNullOrBlank()) {
                    Log.e("VerOnline", "No se encontró la variable dataLink en el script de embed69.org.")
                    return false
                }

                Log.d("VerOnline", "dataLink JSON string encontrado: $dataLinkJsonString")

                val dataLinkEntries = tryParseJson<List<DataLinkEntry>>(dataLinkJsonString)

                if (dataLinkEntries.isNullOrEmpty()) {
                    Log.e("VerOnline", "Error al parsear dataLink JSON o está vacío.")
                    return false
                }

                val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"

                val foundEmbed69Links = mutableListOf<String>()
                for (entry in dataLinkEntries) {
                    for (embed in entry.sortedEmbeds) {
                        if (embed.type == "video") {
                            val decryptedLink = decryptLink(embed.link, secretKey)
                            if (decryptedLink != null) {
                                Log.d("VerOnline", "Link desencriptado para ${embed.servername}: $decryptedLink")
                                foundEmbed69Links.add(decryptedLink)
                            }
                        }
                    }
                }
                if (foundEmbed69Links.isNotEmpty()) {
                    foundEmbed69Links.apmap { playerUrl ->
                        loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                    }
                    return true
                }
                return false
            }
            else -> {
                Log.w("VerOnline", "Tipo de iframe desconocido o no manejado: $iframeSrc. Intentando cargar extractor directamente.")
                return loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
            }
        }
    }
}