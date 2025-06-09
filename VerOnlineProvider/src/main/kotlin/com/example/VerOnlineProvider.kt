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

// ****** ESTAS LÍNEAS TE DAN ERROR, LAS COMENTO PERO SON NECESARIAS PARA LA MAYORÍA DE LAS VERSIONES DE CS3 ******
// import com.lagradost.cloudstream3.extractors.ExtractorLink // Para ExtractorLink
// import com.lagradost.cloudstream3.extractors.ExtractorLinkType // Para ExtractorLinkType y .STREAMING
// import com.lagradost.cloudstream3.utils.Qualities // Para Qualities
// **************************************************************************************************************

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

    // private val cfKiller = CloudflareKiller() // Descomentar si hay Cloudflare

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
                // SELECTORES HTML ACTUALIZADOS PARA MAIN PAGE (VERONLINE.CFD)
                val homeItems = doc.select("div.owl-item article.item").mapNotNull { articleElement ->
                    val title = articleElement.selectFirst("a div.data h3")?.text()
                    val link = articleElement.selectFirst("a")?.attr("href")
                    // Se prioriza data-src o data-srcset para lazyload, luego src
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
            // SELECTORES HTML ACTUALIZADOS PARA SEARCH (VERONLINE.CFD)
            return doc.select("div.result-item article.item").mapNotNull { articleElement ->
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
                        this.type = TvType.TvSeries // Asumimos TvSeries para búsqueda, se ajusta en load
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
        // SELECTORES HTML ACTUALIZADOS PARA LA PÁGINA DE DETALLES (LOAD)
        val title = doc.selectFirst("div.data h1")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""
        val description = doc.selectFirst("div.entry-content p")?.text() // Selector para la descripción principal
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content") ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() } // Selector para géneros/etiquetas

        val episodes = if (tvType == TvType.TvSeries || tvType == TvType.Anime || tvType == TvType.Cartoon) {
            var episodeListElements = doc.select("div#seasons ul.episodios li") // Selector para la lista de episodios

            if (episodeListElements.isEmpty()) {
                Log.d("VerOnline", "load - No se encontraron episodios con selector de temporada principal. Intentando selector alternativo de UL.")
                episodeListElements = doc.select("div.episodes ul.episodios li") // Otro selector común si el anterior falla
            }

            episodeListElements.mapNotNull { episodeElement ->
                val epurl = fixUrl(episodeElement.selectFirst("a")?.attr("href") ?: "")
                // Selectores de título de episodio más robustos
                val epTitleText = episodeElement.selectFirst("div.episodiotitle div.epst")?.text()
                    ?: episodeElement.selectFirst("div.episodiotitle a")?.text() // Podría ser el texto del enlace
                    ?: episodeElement.selectFirst("h3")?.text() // Otro posible selector
                    ?: Regex("""(?i)episodio\s*(\d+)""").find(epurl)?.groupValues?.get(1)?.let { "Episodio $it" } ?: "" // Fallback

                // Selectores de número de temporada y episodio más robustos
                val numerandoText = episodeElement.selectFirst("div.episodiotitle div.numerando")?.text()
                val seasonNumber = numerandoText?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                val episodeNumber = numerandoText?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()
                    ?: Regex("""(?i)episodio\s*(\d+)""").find(epurl)?.groupValues?.get(1)?.toIntOrNull() // Fallback

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
        } else listOf()

        return when (tvType) {
            TvType.TvSeries, TvType.TvSeries, TvType.Anime, TvType.Cartoon -> { // Mantengo TvSeries duplicado para compatibilidad si antes lo tenías así
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
        // ESTA LÍNEA DARÁ UN ERROR DE "Unresolved reference: ExtractorLink"
        // si no tienes la importación o una versión de la API compatible.
        // Pero es necesaria para la firma de `loadLinks` en MainAPI.
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
            Log.e("VerOnline", "loadLinks - ERROR al obtener el documento para URL: $targetUrl - ${e.message}", e)
            return false
        }

        // SELECTORES HTML ACTUALIZADOS PARA IFRAME SRC EN LOADLINKS
        val iframeSrc = doc.selectFirst("div[id*=\"player-ajax\"] iframe")?.attr("src") // Nuevo selector principal para el reproductor
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src") // Selector anterior
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src") // Selector anterior
            ?: doc.selectFirst("div#full-video iframe")?.attr("src") // Otro selector común
            ?: doc.selectFirst("div#player_div_1 iframe")?.attr("src") // Otro selector común
            ?: doc.selectFirst("iframe[src*='embed']")?.attr("src") // Genérico

        if (iframeSrc.isNullOrBlank()) {
            Log.d("VerOnline", "No se encontró iframe del reproductor con los selectores específicos en VerOnline. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            // Buscar URLs directas en scripts, como en SoloLatino
            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                Log.d("VerOnline", "Encontrados ${directMatches.size} enlaces directos en script de página principal.")
                directMatches.apmap { directUrl ->
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
                return loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
            }
        }
    }
}