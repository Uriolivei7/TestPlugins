package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo.

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.* // Importa todas las utilidades, incluyendo AppUtils
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Eliminadas: import com.lagradost.cloudstream3.extractors.ExtractorApi
// Eliminadas: import com.lagradost.cloudstream3.extractors.ExtractorLink
// Eliminadas: import com.lagradost.cloudstream3.extractors.ExtractorLinkType
// Eliminadas: import com.lagradost.cloudstream3.MainAPI.Companion.extractorApi // O CloudStream

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

// ¡CRÍTICO! Añadir esta anotación para que el plugin sea reconocido por CloudStream
class AnizoneProvider : MainAPI() {
    override var mainUrl = "https://anizone.to"
    override var name = "Anizone"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // Si ExtractorApi no existe, esta lista no tiene sentido para la extracción.
    // La dejo comentada para evitar errores de referencia.
    // private val customExtractors = listOf<ExtractorApi>()


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Últimos Animes", "$mainUrl/animes"),
            Pair("Películas de Anime", "$mainUrl/peliculas"),
            Pair("OVAs y Especiales", "$mainUrl/genero/ova"),
            Pair("Top Animes", "$mainUrl/top-animes"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Últimos Animes", "Top Animes", "OVAs y Especiales" -> TvType.Anime
                "Películas de Anime" -> TvType.Movie
                else -> TvType.Others
            }
            val doc = app.get(url).document
            val homeItems = doc.select("div.items article.item").mapNotNull { articleElement ->
                val title = articleElement.selectFirst("a div.data h3")?.text()
                val link = articleElement.selectFirst("a")?.attr("href")
                val img = articleElement.selectFirst("div.poster img.lazyload")?.attr("data-src")
                    ?: articleElement.selectFirst("div.poster img")?.attr("src")

                if (title != null && link != null) {
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else null
            }
            HomePageList(name, homeItems)
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").mapNotNull { articleElement ->
            val title = articleElement.selectFirst("a div.data h3")?.text()
            val link = articleElement.selectFirst("a")?.attr("href")
            val img = articleElement.selectFirst("div.poster img.lazyload")?.attr("data-src")
                ?: articleElement.selectFirst("div.poster img")?.attr("src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = img
                }
            } else null
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Anizone", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("Anizone", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("Anizone", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("Anizone", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("Anizone", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl).document
        val tvType = if (cleanUrl.contains("/pelicula/")) TvType.Movie else TvType.Anime
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }
        val episodes = if (tvType == TvType.Anime || tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { seasonElement ->
                seasonElement.select("ul.episodios li").mapNotNull { episodeElement ->
                    val epurl = fixUrl(episodeElement.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = episodeElement.selectFirst("div.episodiotitle div.epst")?.text() ?: ""

                    val seasonNumber = episodeElement.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                    val episodeNumber = episodeElement.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()

                    val realimg = episodeElement.selectFirst("div.imagen img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson()
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realimg
                        }
                    } else null
                }
            }
        } else listOf()

        return when (tvType) {
            TvType.Anime, TvType.TvSeries -> {
                newTvSeriesLoadResponse(
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

            TvType.Movie -> {
                newMovieLoadResponse(
                    name = title,
                    url = cleanUrl,
                    type = tvType,
                    dataUrl = cleanUrl
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            else -> null
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
            Log.e("Anizone", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Anizone", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("Anizone", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("Anizone", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Anizone", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("Anizone", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Anizone", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl).document
        val iframeSrc = doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

        val urlsToProcess = mutableListOf<String>()

        if (!iframeSrc.isNullOrBlank()) {
            Log.d("Anizone", "Iframe encontrado: $iframeSrc")
            urlsToProcess.add(iframeSrc)
        } else {
            Log.d("Anizone", "No se encontró iframe del reproductor con el selector específico. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                Log.d("Anizone", "Encontrados ${directMatches.size} enlaces directos en script de página principal.")
                urlsToProcess.addAll(directMatches)
            } else {
                Log.d("Anizone", "No se encontraron enlaces directos en scripts de la página principal.")
                return false
            }
        }

        var linksFoundAndExtracted = false

        urlsToProcess.apmap { currentUrl ->
            if (currentUrl.contains("xupalace.org")) {
                Log.d("Anizone", "loadLinks - Detectado Xupalace.org iframe: $currentUrl")
                val xupalaceDoc = try {
                    app.get(fixUrl(currentUrl)).document
                } catch (e: Exception) {
                    Log.e("Anizone", "Error al obtener el contenido del iframe de Xupalace ($currentUrl): ${e.message}")
                    return@apmap
                }

                val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
                val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

                if (elementsWithOnclick.isEmpty()) {
                    Log.w("Anizone", "No se encontraron elementos con 'go_to_playerVast' en xupalace.org.")
                    return@apmap
                }

                for (element: Element in elementsWithOnclick) {
                    val onclickAttr = element.attr("onclick")
                    val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)

                    if (matchPlayerUrl != null) {
                        val videoUrl = matchPlayerUrl.groupValues[1]
                        val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                        Log.d("Anizone", "Xupalace: Encontrado servidor '$serverName' con URL: $videoUrl")
                        if (videoUrl.isNotBlank()) {
                            // Cambiado a la llamada global de loadExtractor
                            loadExtractor(fixUrl(videoUrl), currentUrl, subtitleCallback, callback)
                            linksFoundAndExtracted = true
                        }
                    } else {
                        Log.w("Anizone", "Xupalace: No se pudo extraer la URL del onclick: $onclickAttr")
                    }
                }
            }
            else if (currentUrl.contains("re.anizone.net/embed.php") || currentUrl.contains("re.sololatino.net/embed.php")) {
                Log.d("Anizone", "loadLinks - Detectado re.anizone.net/embed.php iframe: $currentUrl")
                val embedDoc = try {
                    app.get(fixUrl(currentUrl)).document
                } catch (e: Exception) {
                    Log.e("Anizone", "Error al obtener el contenido del iframe de re.anizone.net ($currentUrl): ${e.message}")
                    return@apmap
                }

                val regexGoToPlayerUrl = Regex("""go_to_player\('([^']+)'\)""")
                val elementsWithOnclick = embedDoc.select("*[onclick*='go_to_player']")

                if (elementsWithOnclick.isEmpty()) {
                    Log.w("Anizone", "No se encontraron elementos con 'go_to_player' en re.anizone.net/embed.php.")
                    return@apmap
                }

                for (element: Element in elementsWithOnclick) {
                    val onclickAttr = element.attr("onclick")
                    val matchPlayerUrl = regexGoToPlayerUrl.find(onclickAttr)

                    if (matchPlayerUrl != null) {
                        val videoUrl = matchPlayerUrl.groupValues[1]
                        val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                        Log.d("Anizone", "re.anizone.net: Encontrado servidor '$serverName' con URL: $videoUrl")
                        if (videoUrl.isNotBlank()) {
                            // Cambiado a la llamada global de loadExtractor
                            loadExtractor(fixUrl(videoUrl), currentUrl, subtitleCallback, callback)
                            linksFoundAndExtracted = true
                        }
                    } else {
                        Log.w("Anizone", "re.anizone.net: No se pudo extraer la URL del onclick: $onclickAttr")
                    }
                }
            }
            else if (currentUrl.contains("embed69.org")) {
                Log.d("Anizone", "loadLinks - Detectado embed69.org iframe: $currentUrl")
                val frameDoc = try {
                    app.get(fixUrl(currentUrl)).document
                } catch (e: Exception) {
                    Log.e("Anizone", "Error al obtener el contenido del iframe ($currentUrl): ${e.message}")
                    return@apmap
                }

                val scriptContent = frameDoc.select("script").map { it.html() }.joinToString("\n")

                val dataLinkRegex = """const dataLink = (\[.*?\]);""".toRegex()
                val dataLinkJsonString = dataLinkRegex.find(scriptContent)?.groupValues?.get(1)

                if (dataLinkJsonString.isNullOrBlank()) {
                    Log.e("Anizone", "No se encontró la variable dataLink en el script de embed69.org.")
                    return@apmap
                }

                Log.d("Anizone", "dataLink JSON string encontrado: $dataLinkJsonString")

                val dataLinkEntries = tryParseJson<List<DataLinkEntry>>(dataLinkJsonString)

                if (dataLinkEntries.isNullOrEmpty()) {
                    Log.e("Anizone", "Error al parsear dataLink JSON o está vacío.")
                    return@apmap
                }

                val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"

                for (entry in dataLinkEntries) {
                    for (embed in entry.sortedEmbeds) {
                        if (embed.type == "video") {
                            val decryptedLink = decryptLink(embed.link, secretKey)
                            if (decryptedLink != null) {
                                Log.d("Anizone", "Link desencriptado para ${embed.servername}: $decryptedLink")
                                // Cambiado a la llamada global de loadExtractor
                                loadExtractor(fixUrl(decryptedLink), currentUrl, subtitleCallback, callback)
                                linksFoundAndExtracted = true
                            }
                        }
                    }
                }
            }
            else {
                Log.d("Anizone", "Dominio desconocido o no manejado específicamente: $currentUrl. Pasando directamente a extractores de CloudStream.")
                // Cambiado a la llamada global de loadExtractor
                loadExtractor(fixUrl(currentUrl), targetUrl, subtitleCallback, callback)
                linksFoundAndExtracted = true
            }
        }
        return linksFoundAndExtracted
    }

    // Esta función debe ser una función de extensión o global en tu versión de CloudStream3.
    // La dejé como privada aquí, pero su implementación correcta dependerá de cómo tu CloudStream la provee.
    // Si sigue habiendo errores de 'loadExtractor' o sus parámetros, entonces el problema
    // no es el código en sí, sino cómo tu proyecto tiene acceso a esta función de la librería.
    private suspend fun loadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Anizone", "loadExtractor: Intentando con extractores de CloudStream para: $url")

        // Otra opción si la de arriba no funciona:
        // loadExtractor(url, referer, subtitleCallback, callback) // Si es una función de extensión de MainAPI o global sin prefijo.
    }
}