package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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
    override var mainUrl = "https://www.veronline.cfd" // URL base del sitio
    override var name = "VerOnline" // Nombre del proveedor
    override val supportedTypes = setOf(
        TvType.Movie,
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
            Pair("Últimas Series", "$mainUrl/series"),
            Pair("Últimas Películas", "$mainUrl/peliculas"),
            Pair("Estrenos de Series", "$mainUrl/generos/estrenos-series"),
            Pair("Estrenos de Películas", "$mainUrl/generos/estrenos-peliculas"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when {
                name.contains("Películas") -> TvType.Movie
                name.contains("Series") -> TvType.TvSeries
                else -> TvType.Others
            }
            try {
                val doc = app.get(url).document
                val homeItems = doc.select("div.items article.item").mapNotNull { articleElement ->
                    val title = articleElement.selectFirst("a div.data h3")?.text()
                    val link = articleElement.selectFirst("a")?.attr("href")
                    // Se prioriza data-src o data-srcset, luego src
                    val img = articleElement.selectFirst("div.poster img.lazyload")?.attr("data-src")
                        ?: articleElement.selectFirst("div.poster img.lazyload")?.attr("data-srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull()
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
            } catch (e: Exception) {
                Log.e("VerOnline", "Error al obtener la página principal para $url: ${e.message}", e)
                null
            }
        }.filterNotNull()

        items.addAll(homePageLists)

        return newHomePageResponse(items.toList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        try {
            val doc = app.get(url).document
            return doc.select("div.items article.item").mapNotNull { articleElement ->
                val title = articleElement.selectFirst("a div.data h3")?.text()
                val link = articleElement.selectFirst("a")?.attr("href")
                val img = articleElement.selectFirst("div.poster img.lazyload")?.attr("data-src")
                    ?: articleElement.selectFirst("div.poster img.lazyload")?.attr("data-srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull()
                    ?: articleElement.selectFirst("div.poster img")?.attr("src")

                if (title != null && link != null) {
                    newTvSeriesSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = TvType.TvSeries
                        this.posterUrl = img
                    }
                } else null
            }
        } catch (e: Exception) {
            Log.e("VerOnline", "Error en la búsqueda para '$query': ${e.message}", e)
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
            Log.e("VerOnline", "load - ERROR al obtener el documento para URL: $cleanUrl - ${e.message}", e)
            return null
        }

        val tvType = if (cleanUrl.contains("/peliculas/")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries || tvType == TvType.Anime || tvType == TvType.Cartoon) {
            var episodeListElements = doc.select("div#seasons div.se-c ul.episodios li")

            if (episodeListElements.isEmpty()) {
                Log.d("VerOnline", "load - No se encontraron episodios con selector de temporada. Intentando selector directo de UL.")
                episodeListElements = doc.select("ul.grid li") // Selector alternativo para algunos sitios
            }

            episodeListElements.mapNotNull { episodeElement ->
                val epurl = fixUrl(episodeElement.selectFirst("a")?.attr("href") ?: "")
                val epTitleText = episodeElement.selectFirst("div.episodiotitle div.epst")?.text()
                    ?: episodeElement.selectFirst("h3")?.text() // Otro posible selector de título
                    ?: Regex("""(?i)episodio\s*(\d+)""").find(epurl)?.groupValues?.get(1)?.let { "Episodio $it" } ?: "" // Fallback

                val seasonNumber = episodeElement.selectFirst("div.episodiotitle div.numerando")?.text()
                    ?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                val episodeNumber = episodeElement.selectFirst("div.episodiotitle div.numerando")?.text()
                    ?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()
                    ?: Regex("""(?i)episodio\s*(\d+)""").find(epurl)?.groupValues?.get(1)?.toIntOrNull() // Fallback

                val realimg = episodeElement.selectFirst("div.imagen img")?.attr("src")
                    ?: episodeElement.selectFirst("img")?.attr("src")

                if (epurl.isNotBlank() && epTitleText.isNotBlank()) {
                    newEpisode(
                        EpisodeLoadData(epTitleText, epurl).toJson()
                    ) {
                        this.name = epTitleText
                        // Mantengo estos ya que SoloLatino los usa y funciona
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = realimg
                    }
                } else {
                    Log.w("VerOnline", "load - Episodio incompleto encontrado: URL=$epurl, Título=$epTitleText")
                    null
                }
            }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries, TvType.Anime, TvType.Cartoon -> {
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
            Log.e("VerOnline", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    // === INICIO DE CAMBIOS PARA USAR LA LÓGICA DE SoloLatino en loadLinks ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit // CAMBIO CLAVE AQUÍ: ExtractorLink
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
            Log.e("VerOnline", "loadLinks - ERROR al obtener el documento para URL: $targetUrl - ${e.message}", e)
            return false
        }

        val iframeSrc = doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("VerOnline", "No se encontró iframe del reproductor con el selector específico en VerOnline. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            // Buscar URLs directas en scripts, como en SoloLatino
            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                Log.d("VerOnline", "Encontrados ${directMatches.size} enlaces directos en script de página principal.")
                directMatches.apmap { directUrl ->
                    // Delegar al extractor como en SoloLatino
                    Log.d("VerOnline", "Cargando extractor para URL directa de script: $directUrl")
                    loadExtractor(fixUrl(directUrl), targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("VerOnline", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("VerOnline", "Iframe encontrado: $iframeSrc")

        // --- LÓGICA PARA MANEJAR DOMINIOS DE IFRAMES (similar a SoloLatino) ---

        when {
            iframeSrc.contains("xupalace.org") -> {
                Log.d("VerOnline", "loadLinks - Detectado Xupalace.org iframe: $iframeSrc")
                val xupalaceDoc = try {
                    app.get(fixUrl(iframeSrc)).document
                } catch (e: Exception) {
                    Log.e("VerOnline", "Error al obtener el contenido del iframe de Xupalace ($iframeSrc): ${e.message}")
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
                        Log.d("VerOnline", "Cargando extractor para link de Xupalace: $playerUrl")
                        loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                    }
                    return true
                }
                return false
            }
            // Añadimos explícitamente re.veronline.cfd/embed.php aquí
            iframeSrc.contains("re.veronline.cfd/embed.php") || iframeSrc.contains("re.sololatino.net/embed.php") || iframeSrc.contains("re.anizone.net/embed.php") -> {
                Log.d("VerOnline", "loadLinks - Detectado re.veronline.cfd/embed.php o similar iframe: $iframeSrc")
                val embedDoc = try {
                    app.get(fixUrl(iframeSrc)).document
                } catch (e: Exception) {
                    Log.e("VerOnline", "Error al obtener el contenido del iframe de re.veronline.cfd ($iframeSrc): ${e.message}")
                    return false
                }

                val regexGoToPlayerUrl = Regex("""go_to_player\('([^']+)'\)""")
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
                        Log.d("VerOnline", "Cargando extractor para link de re.veronline.cfd: $playerUrl")
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
                    Log.e("VerOnline", "Error al obtener el contenido del iframe ($iframeSrc): ${e.message}")
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

                val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE" // Asegúrate de que esta clave es correcta para embed69.org en este sitio

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
                        Log.d("VerOnline", "Cargando extractor para link desencriptado (embed69.org): $playerUrl")
                        loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                    }
                    return true
                }
                return false
            }
            else -> {
                Log.w("VerOnline", "Tipo de iframe desconocido o no manejado: $iframeSrc. Intentando cargar extractor directamente.")
                // Aquí, intentar cargar el iframeSrc directamente con loadExtractor
                // si sospechamos que el iframeSrc es la URL directa de un video.
                // Esto es un último recurso.
                return loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
            }
        }
    }
    // === FIN DE CAMBIOS ===
}