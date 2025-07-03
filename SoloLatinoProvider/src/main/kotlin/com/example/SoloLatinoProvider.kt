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
import kotlinx.coroutines.delay

class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
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

    private val cfKiller = CloudflareKiller()

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L
    ): String? {
        for (i in 0 until retries) {
            try {
                Log.d("SoloLatino", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs)
                if (res.isSuccessful) {
                    Log.d("SoloLatino", "safeAppGet - Petición exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("SoloLatino", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("SoloLatino", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("SoloLatino", "safeAppGet - Reintentando en ${delayMs / 1000.0} segundos...")
                delay(delayMs)
            }
        }
        Log.e("SoloLatino", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Series", "$mainUrl/series"),
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Animes", "$mainUrl/animes")
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                else -> TvType.Others
            }
            val html = safeAppGet(url)
            if (html == null) {
                Log.e("SoloLatino", "getMainPage - No se pudo obtener HTML para $url")
                return@apmap null
            }
            val doc = Jsoup.parse(html)
            val homeItems = doc.select("div.items article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text()
                val link = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull() ?: it.selectFirst("div.poster img")?.attr("src")

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
        }.filterNotNull()

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val html = safeAppGet(url)
        if (html == null) {
            Log.e("SoloLatino", "search - No se pudo obtener HTML para la búsqueda: $url")
            return emptyList()
        }
        val doc = Jsoup.parse(html)
        return doc.select("div.items article.item").mapNotNull {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull() ?: it.selectFirst("div.poster img")?.attr("src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries
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
        Log.d("SoloLatino", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("SoloLatino", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("SoloLatino", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("SoloLatino", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("SoloLatino", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val html = safeAppGet(cleanUrl)
        if (html == null) {
            Log.e("SoloLatino", "load - No se pudo obtener HTML para la URL principal: $cleanUrl")
            return null
        }
        val doc = Jsoup.parse(html)

        val tvType = if (cleanUrl.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { seasonElement ->
                seasonElement.select("ul.episodios li").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.episodiotitle div.epst")?.text() ?: ""

                    val numerandoText = element.selectFirst("div.episodiotitle div.numerando")?.text()
                    val seasonNumber = numerandoText?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                    val episodeNumber = numerandoText?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()

                    val realimg = element.selectFirst("div.imagen img")?.attr("src")

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
            TvType.TvSeries -> {
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
            Log.e("SoloLatino", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SoloLatino", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("SoloLatino", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("SoloLatino", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("SoloLatino", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("SoloLatino", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("SoloLatino", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val initialHtml = safeAppGet(targetUrl)
        if (initialHtml == null) {
            Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML para la URL principal del contenido: $targetUrl")
            return false
        }
        val doc = Jsoup.parse(initialHtml)

        val initialIframeSrc = doc.selectFirst("iframe#iframePlayer")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

        if (initialIframeSrc.isNullOrBlank()) {
            Log.d("SoloLatino", "No se encontró iframe del reproductor principal con el selector específico en SoloLatino.net. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    Log.d("SoloLatino", "Encontrado enlace directo en script de página principal: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("SoloLatino", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("SoloLatino", "Iframe principal encontrado: $initialIframeSrc")

        var finalIframeSrc: String = initialIframeSrc

        if (initialIframeSrc.contains("ghbrisk.com")) {
            Log.d("SoloLatino", "loadLinks - Detectado ghbrisk.com iframe intermediario: $initialIframeSrc. Buscando iframe anidado.")
            val ghbriskHtml = safeAppGet(fixUrl(initialIframeSrc))
            if (ghbriskHtml == null) {
                Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML del iframe de ghbrisk.com: $initialIframeSrc")
                return false
            }
            val ghbriskDoc = Jsoup.parse(ghbriskHtml)

            val nestedIframeSrc = ghbriskDoc.selectFirst("iframe.metaframe.rptss")?.attr("src")
                ?: ghbriskDoc.selectFirst("iframe")?.attr("src")

            if (nestedIframeSrc.isNullOrBlank()) {
                Log.e("SoloLatino", "No se encontró un iframe anidado (posiblemente embed69.org) dentro de ghbrisk.com.")
                return false
            }
            Log.d("SoloLatino", "Iframe anidado encontrado en ghbrisk.com: $nestedIframeSrc")
            finalIframeSrc = nestedIframeSrc
        }
        else if (initialIframeSrc.contains("xupalace.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado Xupalace.org iframe intermediario/directo: $initialIframeSrc.")
            val xupalaceHtml = safeAppGet(fixUrl(initialIframeSrc))
            if (xupalaceHtml == null) {
                Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML del iframe de Xupalace: $initialIframeSrc")
                return false
            }
            val xupalaceDoc = Jsoup.parse(xupalaceHtml)

            val nestedIframeSrc = xupalaceDoc.selectFirst("iframe#IFR")?.attr("src")

            if (!nestedIframeSrc.isNullOrBlank()) {
                Log.d("SoloLatino", "Iframe anidado (playerwish.com) encontrado en Xupalace.org: $nestedIframeSrc")
                finalIframeSrc = nestedIframeSrc
            } else {
                Log.w("SoloLatino", "No se encontró un iframe anidado (playerwish.com) dentro de Xupalace.org. Intentando buscar enlaces directos 'go_to_playerVast'.")
                val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
                val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

                if (elementsWithOnclick.isEmpty()) {
                    Log.e("SoloLatino", "No se encontraron elementos con 'go_to_playerVast' ni iframe 'IFR' en xupalace.org.")
                    return false
                }

                val foundXupalaceLinks = mutableListOf<String>()
                for (element in elementsWithOnclick) {
                    val onclickAttr = element.attr("onclick")
                    val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)

                    if (matchPlayerUrl != null) {
                        val videoUrl = matchPlayerUrl.groupValues[1]
                        val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                        Log.d("SoloLatino", "Xupalace: Encontrado servidor '$serverName' con URL: $videoUrl")
                        if (videoUrl.isNotBlank()) {
                            foundXupalaceLinks.add(videoUrl)
                        }
                    } else {
                        Log.w("SoloLatino", "Xupalace: No se pudo extraer la URL del onclick: $onclickAttr")
                    }
                }

                if (foundXupalaceLinks.isNotEmpty()) {
                    foundXupalaceLinks.apmap { playerUrl ->
                        Log.d("SoloLatino", "Cargando extractor para link de Xupalace (go_to_playerVast): $playerUrl")

                        if (playerUrl.contains("player-cdn.com")) {
                            Log.d("SoloLatino", "Detectado player-cdn.com. Intentando obtener el HTML de la página de Abyss.to después de la redirección.")
                            val abyssHtml = safeAppGet(fixUrl(playerUrl))
                            if (abyssHtml != null) {
                                val abyssDoc = Jsoup.parse(abyssHtml)

                                val videoLink = abyssDoc.selectFirst("video[src$=.mp4]")?.attr("src")
                                    ?: abyssDoc.selectFirst("video source[src$=.mp4]")?.attr("src")
                                    ?: abyssDoc.selectFirst("video source[src$=.m3u8]")?.attr("src")
                                    ?: abyssDoc.selectFirst("video")?.attr("src")

                                if (!videoLink.isNullOrBlank()) {
                                    val cleanedVideoLink = videoLink.substringBeforeLast("#mp4", videoLink)
                                    Log.d("SoloLatino", "Encontrado link de video de Abyss.to en tag <video>: $cleanedVideoLink")
                                    loadExtractor(cleanedVideoLink, initialIframeSrc, subtitleCallback, callback)
                                } else {
                                    val scriptContent = abyssDoc.select("script").map { it.html() }.joinToString("\n")
                                    val storageRegex = Regex("""(https?://storage\.googleapis\.com/[^"']+\.(mp4|m3u8))""")
                                    val match = storageRegex.find(scriptContent)
                                    if (match != null) {
                                        val finalVideoUrl = match.groupValues[1]
                                        Log.d("SoloLatino", "Encontrado link de video de Abyss.to en script: $finalVideoUrl")
                                        loadExtractor(finalVideoUrl, initialIframeSrc, subtitleCallback, callback)
                                    } else {
                                        Log.e("SoloLatino", "No se encontró el link de storage.googleapis.com en el HTML o scripts de Abyss.to.")
                                    }
                                }
                            } else {
                                Log.e("SoloLatino", "No se pudo obtener el HTML de la página de Abyss.to desde $playerUrl.")
                            }
                        } else {
                            loadExtractor(fixUrl(playerUrl), initialIframeSrc, subtitleCallback, callback)
                        }
                    }
                    return true
                } else {
                    Log.d("SoloLatino", "No se encontraron enlaces de video de Xupalace.org (go_to_playerVast).")
                    return false
                }
            }
        }

        if (finalIframeSrc.contains("playerwish.com")) {
            Log.d("SoloLatino", "loadLinks - Detectado playerwish.com. Pasando al extractor general de CloudStream: $finalIframeSrc")
            loadExtractor(fixUrl(finalIframeSrc), finalIframeSrc, subtitleCallback, callback)
            return true
        }
        else if (finalIframeSrc.contains("re.sololatino.net/embed.php")) {
            Log.d("SoloLatino", "loadLinks - Detectado re.sololatino.net/embed.php iframe: $finalIframeSrc")
            val embedHtml = safeAppGet(fixUrl(finalIframeSrc))
            if (embedHtml == null) {
                Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML del iframe de re.sololatino.net: $finalIframeSrc")
                return false
            }
            val embedDoc = Jsoup.parse(embedHtml)
            val regexGoToPlayerUrl = Regex("""go_to_player\('([^']+)'\)""")
            val elementsWithOnclick = embedDoc.select("*[onclick*='go_to_player']")
            if (elementsWithOnclick.isEmpty()) {
                Log.w("SoloLatino", "No se encontraron elementos con 'go_to_player' en re.sololatino.net/embed.php con el selector general.")
                return false
            }
            val foundReSoloLatinoLinks = mutableListOf<String>()
            for (element in elementsWithOnclick) {
                val onclickAttr = element.attr("onclick")
                val matchPlayerUrl = regexGoToPlayerUrl.find(onclickAttr)
                if (matchPlayerUrl != null) {
                    val videoUrl = matchPlayerUrl.groupValues[1]
                    val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                    Log.d("SoloLatino", "re.sololatino.net: Encontrado servidor '$serverName' con URL: $videoUrl")
                    if (videoUrl.isNotBlank()) {
                        foundReSoloLatinoLinks.add(videoUrl)
                    }
                } else {
                    Log.w("SoloLatino", "re.sololatino.net: No se pudo extraer la URL del onclick: $onclickAttr")
                }
            }
            if (foundReSoloLatinoLinks.isNotEmpty()) {
                foundReSoloLatinoLinks.apmap { playerUrl ->
                    Log.d("SoloLatino", "Cargando extractor para link de re.sololatino.net: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), initialIframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SoloLatino", "No se encontraron enlaces de video de re.sololatino.net/embed.php.")
                return false
            }
        }
        else if (finalIframeSrc.contains("embed69.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado embed69.org iframe: $finalIframeSrc")

            val frameHtml = safeAppGet(fixUrl(finalIframeSrc))
            if (frameHtml == null) {
                Log.e("SoloLatino", "loadLinks - No se pudo obtener HTML del iframe de embed69.org: $finalIframeSrc")
                return false
            }
            val frameDoc = Jsoup.parse(frameHtml)

            val scriptContent = frameDoc.select("script").map { it.html() }.joinToString("\n")

            val dataLinkRegex = """const\s+dataLink\s*=\s*(\[.*?\]);""".toRegex()
            val dataLinkJsonString = dataLinkRegex.find(scriptContent)?.groupValues?.get(1)

            if (dataLinkJsonString.isNullOrBlank()) {
                Log.e("SoloLatino", "No se encontró la variable dataLink en el script de embed69.org con la regex. Contenido del script (primeras 500 chars): ${scriptContent.take(500)}...")
                return false
            }

            Log.d("SoloLatino", "dataLink JSON string encontrado: $dataLinkJsonString")

            val dataLinkEntries = tryParseJson<List<DataLinkEntry>>(dataLinkJsonString)

            if (dataLinkEntries.isNullOrEmpty()) {
                Log.e("SoloLatino", "Error al parsear dataLink JSON o está vacío.")
                return false
            }

            val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"

            val foundEmbed69Links = mutableListOf<String>()
            for (entry in dataLinkEntries) {
                for (embed in entry.sortedEmbeds) {
                    if (embed.type == "video") {
                        val decryptedLink = decryptLink(embed.link, secretKey)
                        if (decryptedLink != null) {
                            Log.d("SoloLatino", "Link desencriptado para ${embed.servername}: $decryptedLink")
                            foundEmbed69Links.add(decryptedLink)
                        } else {
                            Log.e("SoloLatino", "Falló la desencriptación para ${embed.servername} con enlace: ${embed.link}")
                        }
                    } else {
                        Log.d("SoloLatino", "Ignorando embed de tipo no video: ${embed.servername} (${embed.type})")
                    }
                }
            }

            if (foundEmbed69Links.isNotEmpty()) {
                foundEmbed69Links.apmap { playerUrl ->
                    Log.d("SoloLatino", "Cargando extractor para link desencriptado (embed69.org): $playerUrl")
                    loadExtractor(fixUrl(playerUrl), initialIframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SoloLatino", "No se encontraron enlaces de video desencriptados de embed69.org.")
                return false
            }
        }
        else if (finalIframeSrc.contains("fembed.com") || finalIframeSrc.contains("streamlare.com") || finalIframeSrc.contains("player.sololatino.net")) {
            Log.d("SoloLatino", "loadLinks - Detectado reproductor directo (Fembed/Streamlare/player.sololatino.net): $finalIframeSrc")
            loadExtractor(fixUrl(finalIframeSrc), targetUrl, subtitleCallback, callback)
            return true
        }
        else {
            Log.w("SoloLatino", "Tipo de iframe desconocido o no manejado: $finalIframeSrc")
            return false
        }
    }
}