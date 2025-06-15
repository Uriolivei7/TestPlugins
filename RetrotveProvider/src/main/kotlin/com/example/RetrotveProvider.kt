package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser // Importar para decodificar HTML
import java.net.URLEncoder

class RetrotveProvider : MainAPI() {
    override var mainUrl = "https://retrotve.com"
    override var name = "RetroTVE"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Movie
    )

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    // Helper para decodificar entidades HTML
    private fun decodeHtml(text: String): String = Parser.unescapeEntities(text, false)

    private fun Document.toSearchResult(): List<SearchResponse> {
        return this.select("ul.MovieList li.TPostMv article.TPost").mapNotNull {
            val title = it.selectFirst("h3.Title")?.text()?.trim()
            val href = it.selectFirst("a[href]")?.attr("href")
            val img = it.selectFirst(".Image img")?.attr("src")
            val year = it.selectFirst("span.Year")?.text()?.toIntOrNull()

            if (title.isNullOrBlank() || href.isNullOrBlank() || img.isNullOrBlank()) {
                println("RetroTVE: Faltan datos para un SearchResult: $title, $href, $img")
                return@mapNotNull null
            }

            val type = if (href.contains("/serie/", true) || href.contains("/series/", true)) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

            val posterUrl = fixUrl(img)

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, fixUrl(href)) {
                    this.posterUrl = posterUrl
                    this.year = year
                }
            } else {
                newMovieSearchResponse(title, fixUrl(href)) {
                    this.posterUrl = posterUrl
                    this.year = year
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val soup = app.get(url).document

        val homeResults = soup.toSearchResult()
        if (homeResults.isNotEmpty()) {
            items.add(HomePageList("Últimas Series y Películas", homeResults))
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.toSearchResult()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.Title")?.text()?.trim()
        val description = doc.selectFirst(".Description p")?.text()?.trim()
        val poster = doc.selectFirst(".Image img")?.attr("src")
        val backposter = poster

        if (title.isNullOrBlank() || poster.isNullOrBlank()) {
            println("RetroTVE: Faltan datos esenciales para cargar la serie/película en $url - Título: $title, Póster: $poster")
            return null
        }

        val type = if (url.contains("/serie/", true) || url.contains("/series/", true)) TvType.TvSeries else TvType.Movie

        if (type == TvType.Movie) {
            val finalPosterUrl = fixUrl(poster)
            val finalBackgroundPosterUrl = fixUrlNull(backposter) ?: finalPosterUrl

            return newMovieLoadResponse(title, url, TvType.Movie, finalPosterUrl) {
                this.plot = description ?: ""
                this.posterUrl = finalPosterUrl
                this.backgroundPosterUrl = finalBackgroundPosterUrl
            }
        } else { // Es TvSeries
            val episodes = ArrayList<Episode>()

            doc.select("div.Wdgt.AAbox").forEach { seasonBlock ->
                val seasonNum = seasonBlock.selectFirst("div.Title.AA-Season")?.attr("data-tab")?.toIntOrNull()
                    ?: seasonBlock.selectFirst("div.Title.AA-Season span")?.text()?.replace("Temporada", "")?.trim()?.toIntOrNull()

                val currentSeason = seasonNum ?: 0

                seasonBlock.select("table tbody tr").forEach { epRow ->
                    val epLinkElement = epRow.selectFirst("td:nth-child(2) a[href]")
                    val epTitleElement = epRow.selectFirst("td:nth-child(3) a")
                    val epNumElement = epRow.selectFirst("td:nth-child(1) span.Num")

                    val epHref = epLinkElement?.attr("href")
                    val epTitle = epTitleElement?.text()?.trim()
                    val epNum = epNumElement?.text()?.toIntOrNull()

                    if (!epHref.isNullOrBlank() && !epTitle.isNullOrBlank()) {
                        episodes.add(
                            Episode(
                                fixUrl(epHref),
                                epTitle,
                                currentSeason,
                                epNum,
                            )
                        )
                    } else {
                        println("RetroTVE: Faltan datos para un episodio en fila (Skipped): Season $currentSeason, Link: $epHref, Title: $epTitle")
                    }
                }
            }

            episodes.sortBy { it.season }
            episodes.sortBy { it.episode }

            val finalPosterUrl = fixUrl(poster)
            val finalBackgroundPosterUrl = fixUrlNull(backposter) ?: finalPosterUrl

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = finalPosterUrl
                this.backgroundPosterUrl = finalBackgroundPosterUrl
                this.plot = description ?: ""
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("RetroTVE: Cargando enlaces para: $data")

        // CABECERAS ACTUALIZADAS CON BASE EN TU CAPTURA DE RED DEL NAVEGADOR
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "es-ES,es;q=0.9",
            "Cache-Control" to "max-age=0",
            "Priority" to "u=0, i",
            "Referer" to "https://retrotve.com/", // Sigue siendo la URL base
            "Sec-Ch-Ua" to "\"Google Chrome\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document", // Nueva
            "Sec-Fetch-Mode" to "navigate", // Nueva
            "Sec-Fetch-Site" to "same-origin", // Nueva
            "Sec-Fetch-User" to "?1", // Nueva
            "Upgrade-Insecure-Requests" to "1", // Nueva
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        )

        val doc = app.get(data, headers = headers).document

        var foundLinks = false

        println("RetroTVE DEBUG: Contenido completo de .TPlayerTb:")
        val tPlayerTbDivs = doc.select("div.TPlayerTb")
        if (tPlayerTbDivs.isNotEmpty()) {
            tPlayerTbDivs.forEach { div ->
                println(div.outerHtml())
            }
        } else {
            println("RetroTVE DEBUG: No se encontró div.TPlayerTb")
        }

        val playerEmbedIframes = doc.select("div.TPlayerTb iframe")

        println("RetroTVE: Número de iframes '.TPlayerTb iframe' encontrados: ${playerEmbedIframes.size}")

        val playerEmbedUrls = mutableListOf<String>()

        playerEmbedIframes.forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                val decodedSrc = decodeHtml(iframeSrc)
                playerEmbedUrls.add(decodedSrc)
                println("RetroTVE: Encontrado iframe de reproductor principal (crudo): $iframeSrc, Decodificado: $decodedSrc")
            }
        }

        // Ordenar las URLs para intentar trembed=1, luego trembed=2, y finalmente trembed=0
        val sortedPlayerEmbedUrls = playerEmbedUrls.sortedWith(compareBy { url ->
            when {
                url.contains("trembed=1") -> 0
                url.contains("trembed=2") -> 1
                url.contains("trembed=0") -> 2
                else -> 3
            }
        })

        if (sortedPlayerEmbedUrls.isEmpty()) {
            println("RetroTVE: No se encontraron iframes de reproductor principal para $data")
            return false
        }

        run outerLoop@{
            sortedPlayerEmbedUrls.forEach { fullTrembedUrl ->
                println("RetroTVE: Intentando resolver URL del reproductor intermedio: $fullTrembedUrl")

                val embedPageDoc = try {
                    // Mantener las cabeceras para la petición a trembed, ajustando el Referer
                    app.get(fullTrembedUrl, headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                        "Referer" to data, // Referer es la URL del episodio original
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8"
                    )).document
                } catch (e: Exception) {
                    println("RetroTVE: Error al obtener la página de trembed $fullTrembedUrl: ${e.message}")
                    return@forEach
                }

                // --- INICIO DE LÓGICA DE EXTRACCIÓN MEJORADA ---

                // 1. Intentar extraer directamente del iframe dentro de embedPageDoc si es un extractor conocido
                val videoEmbedIframe = embedPageDoc.selectFirst("div.Video iframe[src]")
                val videoSrc = videoEmbedIframe?.attr("src")

                if (!videoSrc.isNullOrBlank()) {
                    val decodedVideoSrc = decodeHtml(videoSrc)
                    println("RetroTVE: Encontrado iframe de video directo: $decodedVideoSrc")
                    // Intentar cargar el extractor para este video directo.
                    // El referer para este extractor debe ser la URL de la página trembed (fullTrembedUrl).
                    if (loadExtractor(decodedVideoSrc, fullTrembedUrl, subtitleCallback, callback)) {
                        foundLinks = true
                        return@outerLoop // Si encontramos enlaces y el extractor funciona, salimos.
                    } else {
                        println("RetroTVE: loadExtractor no pudo resolver el video de $decodedVideoSrc")
                    }
                }

                // 2. Si no se encuentra un iframe directo (o loadExtractor falla), intentar con patrones conocidos
                // (Mantengo la lógica de VK, Senvid, YourUpload por si son respaldos o aparecen en otros trembed)

                val vkIframe = embedPageDoc.selectFirst("iframe[src*=\"vk.com/video_ext.php\"]")
                val vkSrc = vkIframe?.attr("src")

                if (!vkSrc.isNullOrBlank()) {
                    val decodedVkSrc = decodeHtml(vkSrc)
                    println("RetroTVE: Encontrado URL de VK.com: $vkSrc, Decodificado: $decodedVkSrc")
                    if (loadExtractor(decodedVkSrc, fullTrembedUrl, subtitleCallback, callback)) {
                        foundLinks = true
                        return@outerLoop
                    } else {
                        println("RetroTVE: loadExtractor no pudo resolver el video de VK.com: $decodedVkSrc")
                    }
                }

                val senvidIframe = embedPageDoc.selectFirst("iframe[src*=\"senvid.net/embed-\"]")
                val senvidSrc = senvidIframe?.attr("src")
                if (!senvidSrc.isNullOrBlank()) {
                    val decodedSenvidSrc = decodeHtml(senvidSrc)
                    println("RetroTVE: Encontrado URL de SEnvid: $senvidSrc, Decodificado: $decodedSenvidSrc")
                    if (loadExtractor(decodedSenvidSrc, fullTrembedUrl, subtitleCallback, callback)) {
                        foundLinks = true
                        return@outerLoop
                    } else {
                        println("RetroTVE: loadExtractor no pudo resolver el video de SEnvid: $decodedSenvidSrc")
                    }
                }

                val yourUploadIframe = embedPageDoc.selectFirst("iframe[src*=\"yourupload.com/embed/\"]")
                val yourUploadSrc = yourUploadIframe?.attr("src")
                if (!yourUploadSrc.isNullOrBlank()) {
                    val decodedYourUploadSrc = decodeHtml(yourUploadSrc)
                    println("RetroTVE: Encontrado URL de YourUpload: $yourUploadSrc, Decodificado: $decodedYourUploadSrc")
                    if (loadExtractor(decodedYourUploadSrc, fullTrembedUrl, subtitleCallback, callback)) {
                        foundLinks = true
                        return@outerLoop
                    } else {
                        println("RetroTVE: loadExtractor no pudo resolver el video de YourUpload: $decodedYourUploadSrc")
                    }
                }

                // 3. Respaldo: Scripts con 'eval' o HLS directo (si aplica)
                val scriptContent = embedPageDoc.select("script:contains(eval)").text()
                if (scriptContent.isNotBlank()) {
                    println("RetroTVE: Script con 'eval' encontrado, se requiere análisis de JS para extraer URLs.")
                    val hlsMatches = Regex("""(http[s]?://[^"']*\.m3u8[^"']*)""").findAll(scriptContent).map { it.value }.toList()
                    if (hlsMatches.isNotEmpty()) {
                        hlsMatches.forEach { hlsUrl ->
                            val decodedHlsUrl = decodeHtml(hlsUrl)
                            println("RetroTVE: Encontrado HLS URL en script (respaldo): $hlsUrl, Decodificado: $decodedHlsUrl")
                            if (loadExtractor(decodedHlsUrl, fullTrembedUrl, subtitleCallback, callback)) {
                                foundLinks = true
                                return@outerLoop
                            }
                        }
                    } else {
                        println("RetroTVE: No HLS/MP4 directo en script con 'eval'. Intentando con loadExtractor la URL de embedPageDoc.")
                        val decodedFullTrembedUrl = decodeHtml(fullTrembedUrl)
                        if (loadExtractor(decodedFullTrembedUrl, data, subtitleCallback, callback)) {
                            foundLinks = true
                            return@outerLoop
                        }
                    }
                }
                // --- FIN DE LÓGICA DE EXTRACCIÓN MEJORADA ---
            }
        }

        if (!foundLinks) {
            println("RetroTVE: No se pudieron extraer enlaces de video de ningún iframe en $data")
        }
        return foundLinks
    }
}