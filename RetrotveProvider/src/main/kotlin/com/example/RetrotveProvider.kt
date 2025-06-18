package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
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
            try {
                val response = app.get(url, referer = referer, headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")).document
                val script = response.select("script").firstOrNull { it.html().contains("sources") }?.html() ?: ""
                println("RetroTVE: Filemoon script content: $script")
                val match = Regex("sources:\\s*\\[\\s*\\{[^}]*\"file\":\"([^\"]+\\.mp4)\"").find(script)
                val videoUrl = match?.groupValues?.getOrNull(1)?.replace("\\", "") ?: ""
                println("RetroTVE: Filemoon extracted URL: $videoUrl")
                if (response.select("body").text().contains("maintenance", ignoreCase = true)) {
                    println("RetroTVE: Filemoon server under maintenance, skipping")
                    return emptyList()
                }
                return if (videoUrl.isNotBlank()) {
                    listOf(ExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        referer = referer ?: "",
                        quality = Qualities.Unknown,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")
                    ))
                } else {
                    println("RetroTVE: No video URL found in Filemoon script, falling back to embed")
                    listOf(ExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        referer = referer ?: "",
                        quality = Qualities.Unknown,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")
                    ))
                }
            } catch (e: Exception) {
                println("RetroTVE: Error en FilemoonExtractor para $url: ${e.message}")
                return emptyList()
            }
        }
    }

    // Extractor para YourUpload
    class YourUploadExtractor : ExtractorApi {
        override val name: String = "YourUpload"
        override val mainUrl: String = "https://www.yourupload.com"

        override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
            try {
                val response = app.get(url, referer = referer, headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")).document
                val script = response.select("script").firstOrNull { it.html().contains("jwplayerOptions") }?.html() ?: ""
                println("RetroTVE: YourUpload script content: $script")
                val match = Regex("aboutlink\\s*:\\s*\"([^\"]+)\"").find(script)
                val watchUrl = match?.groupValues?.getOrNull(1) ?: ""
                println("RetroTVE: YourUpload extracted watch URL: $watchUrl")

                if (watchUrl.isNotBlank()) {
                    val watchResponse = app.get(watchUrl, referer = url, headers = mapOf("Referer" to url, "User-Agent" to "Mozilla/5.0")).document
                    val videoMatch = Regex("<source\\s+src=\"([^\"]+\\.mp4)\"|<video\\s+src=\"([^\"]+\\.mp4)\"").find(watchResponse.html())
                    val videoUrl = videoMatch?.groupValues?.getOrNull(1) ?: videoMatch?.groupValues?.getOrNull(2) ?: ""
                    println("RetroTVE: YourUpload extracted video URL: $videoUrl")

                    if (videoUrl.isNotBlank()) {
                        return listOf(ExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            referer = watchUrl,
                            quality = Qualities.Unknown,
                            type = ExtractorLinkType.VIDEO,
                            headers = mapOf("Referer" to watchUrl, "User-Agent" to "Mozilla/5.0")
                        ))
                    } else {
                        println("RetroTVE: No static video URL found. Dynamic loading via JavaScript may be required.")
                    }
                }

                if (response.select("body").text().contains("maintenance", ignoreCase = true)) {
                    println("RetroTVE: YourUpload server under maintenance, skipping")
                    return emptyList()
                }
                println("RetroTVE: No video URL found in YourUpload script, falling back to embed")
                return listOf(ExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    referer = referer ?: "",
                    quality = Qualities.Unknown,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")
                ))
            } catch (e: Exception) {
                println("RetroTVE: Error en YourUploadExtractor para $url: ${e.message}")
                return emptyList()
            }
        }
    }

    // Extractor para Mega.nz
    class MegaExtractor : ExtractorApi {
        override val name: String = "Mega"
        override val mainUrl: String = "https://mega.nz"

        override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
            try {
                val response = app.get(url, referer = referer, headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")).document
                val script = response.select("script").firstOrNull { it.html().contains("source") }?.html() ?: ""
                println("RetroTVE: Mega script content: $script")
                val match = Regex("source\\s*=\\s*\"([^\"]+\\.mp4)\"").find(script)
                val videoUrl = match?.groupValues?.getOrNull(1) ?: ""
                println("RetroTVE: Mega extracted URL: $videoUrl")
                if (response.select("body").text().contains("maintenance", ignoreCase = true)) {
                    println("RetroTVE: Mega server under maintenance, skipping")
                    return emptyList()
                }
                return if (videoUrl.isNotBlank()) {
                    listOf(ExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        referer = referer ?: "",
                        quality = Qualities.Unknown,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")
                    ))
                } else {
                    println("RetroTVE: No video URL found in Mega script, falling back to embed")
                    listOf(ExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        referer = referer ?: "",
                        quality = Qualities.Unknown,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")
                    ))
                }
            } catch (e: Exception) {
                println("RetroTVE: Error en MegaExtractor para $url: ${e.message}")
                return emptyList()
            }
        }
    }

    // Extractor para VK
    class VkExtractor : ExtractorApi {
        override val name: String = "VK"
        override val mainUrl: String = "https://vk.com"

        override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
            try {
                return listOf(ExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    referer = referer ?: "",
                    quality = Qualities.Unknown,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("Referer" to (referer ?: ""), "User-Agent" to "Mozilla/5.0")
                ))
            } catch (e: Exception) {
                println("RetroTVE: Error en VkExtractor para $url: ${e.message}")
                return emptyList()
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

        val hasNext = page < 5 // Ajusta según la lógica del sitio
        return HomePageResponse(items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${encode(query)}").document
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
        } else {
            val episodes = ArrayList<Episode>()

            doc.select("div.Wdgt.AAbox").forEach { seasonBlock ->
                val seasonNum = seasonBlock.selectFirst("div.Title.AA-Season")?.attr("data-tab")?.toIntOrNull()
                    ?: seasonBlock.selectFirst("div.Title.AA-Season span")?.text()?.replace("Temporada", "")?.trim()?.toIntOrNull()
                    ?: 0

                val currentSeason = seasonNum

                seasonBlock.select("table tbody tr").forEach { epRow ->
                    val epLinkElement = epRow.selectFirst("td:nth-child(2) a[href]") ?: epRow.selectFirst("td a[href]") ?: return@forEach
                    val epTitleElement = epRow.selectFirst("td:nth-child(3) a") ?: epRow.selectFirst("td:nth-child(3)")
                    val epNumElement = epRow.selectFirst("td:nth-child(1) span.Num") ?: epRow.selectFirst("td:nth-child(1)")

                    val epHref = epLinkElement.attr("href") ?: ""
                    val epTitle = epTitleElement?.text()?.trim() ?: "Episodio desconocido"
                    val epNum = epNumElement?.text()?.toIntOrNull() ?: 0

                    if (epHref.isBlank()) {
                        println("RetroTVE: Faltan datos para un episodio en fila (Skipped): Season $currentSeason, Link: $epHref, Title: $epTitle")
                        return@forEach
                    }

                    episodes.add(
                        Episode(
                            fixUrl(epHref),
                            epTitle,
                            currentSeason,
                            epNum,
                        )
                    )
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
        if (baseIframe == null) {
            println("RetroTVE DEBUG: No se encontró iframe con 'trembed'")
            return false
        }

        val baseSrc = baseIframe.attr("src")?.let { decodeHtml(it) } ?: run {
            println("RetroTVE: No se pudo obtener src del iframe base")
            return false
        }

        println("RetroTVE: Iframe base encontrado: $baseSrc")
        val trembedOptions = listOf(1, 0, 2)
        for (trembed in trembedOptions) {
            val fullTrembedUrl = baseSrc.replace(Regex("trembed=\\d"), "trembed=$trembed").let { fixUrl(it) }
            println("RetroTVE: Probando URL de trembed: $fullTrembedUrl")

            try {
                val embedDoc = app.get(fullTrembedUrl, headers = headers).document
                val videoIframe = embedDoc.selectFirst("div.Video iframe[src]")
                val videoSrc = videoIframe?.attr("src")?.let { decodeHtml(it) }

                if (videoSrc.isNullOrBlank()) {
                    println("RetroTVE: No se encontró iframe de video en $fullTrembedUrl")
                    continue
                }

                println("RetroTVE: Encontrado iframe de video en div.Video: $videoSrc")
                if (videoSrc.contains("vk.com")) {
                    println("RetroTVE: Saltando enlace de vk.com no soportado temporalmente")
                    continue
                }

                val extractorResult = loadExtractor(videoSrc, fullTrembedUrl, subtitleCallback) { link ->
                    println("RetroTVE: Extractor found link: ${link.url}")
                    callback(link)
                }
                println("RetroTVE: Resultado de loadExtractor para $videoSrc: $extractorResult")
                if (extractorResult) {
                    foundLinks = true
                    return true
                } else if (!extractorResult) {
                    println("RetroTVE: No links extracted by loadExtractor for $videoSrc, checking for errors")
                    if (embedDoc.select("body").text().contains("maintenance", ignoreCase = true) ||
                        embedDoc.select("body").text().contains("error", ignoreCase = true)) {
                        println("RetroTVE: Server issue detected for $videoSrc (maintenance or error), skipping")
                        continue
                    }
                }
            } catch (e: Exception) {
                println("RetroTVE: Error al acceder a $fullTrembedUrl: ${e.message}, StackTrace: ${e.stackTraceToString()}")
            }
        }

        if (!foundLinks) {
            println("RetroTVE: No se encontraron enlaces de reproducción para $data")
        }
        return foundLinks
    }
}