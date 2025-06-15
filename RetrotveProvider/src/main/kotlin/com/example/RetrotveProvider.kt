package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser // Importar para decodificar HTML
import java.net.URLEncoder

// Definición manual de Qualities si no está disponible
object Qualities {
    const val Unknown = 0
}

// Definición manual de ExtractorApi si no se resuelve
interface ExtractorApi {
    val name: String
    val mainUrl: String
    suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>
}

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

    // Extractor para Filemoon
    class FilemoonExtractor : ExtractorApi {
        override val name: String = "Filemoon"
        override val mainUrl: String = "https://filemoon.to"

        override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
            val response = app.get(url, referer = referer, headers = mapOf("Referer" to (referer ?: ""))).document
            val videoUrl = response.select("video source").attr("src") ?: response.select("source").attr("src")
            return if (videoUrl.isNotBlank()) {
                listOf(ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = referer ?: "",
                    quality = Qualities.Unknown,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("Referer" to (referer ?: ""))
                ))
            } else {
                emptyList()
            }
        }
    }

    // Extractor para YourUpload
    class YourUploadExtractor : ExtractorApi {
        override val name: String = "YourUpload"
        override val mainUrl: String = "https://www.yourupload.com"

        override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
            val response = app.get(url, referer = referer, headers = mapOf("Referer" to (referer ?: ""))).document
            val videoUrl = response.select("source").attr("src") // Ajustar según el HTML real
            return if (videoUrl.isNotBlank()) {
                listOf(ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = referer ?: "",
                    quality = Qualities.Unknown,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("Referer" to (referer ?: ""))
                ))
            } else {
                emptyList()
            }
        }
    }

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

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to data
        )

        val doc = app.get(data, headers = headers).document
        var foundLinks = false

        println("RetroTVE DEBUG: Buscando iframes...")
        val baseIframe = doc.selectFirst("iframe[src*=trembed]")
        if (baseIframe != null) {
            val baseSrc = baseIframe.attr("src")?.let { decodeHtml(it) }
            if (!baseSrc.isNullOrBlank()) {
                println("RetroTVE: Iframe base encontrado: $baseSrc")

                // Probar las opciones de trembed en orden (1, 0, 2)
                val trembedOptions = listOf(1, 0, 2) // Priorizar trembed=1
                for (trembed in trembedOptions) {
                    // Generar URL correcta
                    val params = baseSrc.split("&").toMutableList()
                    val trembedParam = params.find { it.startsWith("trembed=") } ?: params[0]
                    params.remove(trembedParam)
                    params.add(0, "trembed=$trembed")
                    val fullTrembedUrl = "${mainUrl}/?${params.joinToString("&")}".let { fixUrl(it) }
                    println("RetroTVE: Probando URL de trembed: $fullTrembedUrl")

                    try {
                        val embedDoc = app.get(fullTrembedUrl, headers = headers).document
                        // Buscar iframe dentro de div.Video
                        val videoIframe = embedDoc.selectFirst("div.Video iframe[src]")
                        val videoSrc = videoIframe?.attr("src")?.let { decodeHtml(it) }

                        if (!videoSrc.isNullOrBlank()) {
                            println("RetroTVE: Encontrado iframe de video en div.Video: $videoSrc")
                            val extractorResult = loadExtractor(videoSrc, fullTrembedUrl, subtitleCallback, callback)
                            println("RetroTVE: Resultado de loadExtractor para $videoSrc: $extractorResult")
                            if (extractorResult) {
                                println("RetroTVE: Enlace encontrado, intentando reproducción con callback")
                                foundLinks = true
                                return true
                            }
                        } else {
                            println("RetroTVE: No se encontró iframe de video en $fullTrembedUrl")
                        }
                    } catch (e: Exception) {
                        println("RetroTVE: Error al acceder a $fullTrembedUrl: ${e.message}")
                    }
                }
            }
        } else {
            println("RetroTVE DEBUG: No se encontró iframe con 'trembed'")
        }

        if (!foundLinks) {
            println("RetroTVE: No se encontraron enlaces de reproducción para $data")
        }
        return foundLinks
    }
}