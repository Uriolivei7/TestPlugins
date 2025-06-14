// Archivo: RetrotveProvider.kt

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder
import java.net.URI
import org.jsoup.nodes.Document
import android.util.Base64

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

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, fixUrl(href)) {
                    this.posterUrl = fixUrl(img)
                    this.year = year
                }
            } else {
                newMovieSearchResponse(title, fixUrl(href)) {
                    this.posterUrl = fixUrl(img)
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
        // backposter es String?, igual que poster
        val backposter = poster

        if (title.isNullOrBlank() || poster.isNullOrBlank()) {
            println("RetroTVE: Faltan datos esenciales para cargar la serie/película en $url - Título: $title, Póster: $poster")
            return null
        }

        val type = if (url.contains("/serie/", true) || url.contains("/series/", true)) TvType.TvSeries else TvType.Movie

        if (type == TvType.Movie) {
            // Línea 99 (aproximada).
            // fixUrl(poster) es seguro porque poster ya se ha validado como no nulo arriba.
            // Para backgroundPosterUrl, usaremos fixUrlNull para manejar el String?
            val safeBackgroundPosterUrl = fixUrlNull(backposter)

            return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(poster)) {
                this.plot = description ?: ""
                // No necesitamos '!!' en posterUrl si fixUrl(poster) ya es String
                this.posterUrl = fixUrl(poster)
                // Asignamos el resultado de fixUrlNull, que es String?
                this.backgroundPosterUrl = safeBackgroundPosterUrl
            }
        } else { // Es TvSeries
            val episodes = ArrayList<Episode>()
            doc.select(".Wdgt.AAbox table tbody tr").forEach { epRow ->
                val epLinkElement = epRow.selectFirst("td:nth-child(2) a[href]")
                val epTitleElement = epRow.selectFirst("td:nth-child(3) a")
                val epNumElement = epRow.selectFirst("td:nth-child(1) span.Num")

                val epHref = epLinkElement?.attr("href")
                val epTitle = epTitleElement?.text()?.trim()
                val epNum = epNumElement?.text()?.toIntOrNull()

                if (!epHref.isNullOrBlank() && !epTitle.isNullOrBlank()) {
                    val seasonNum = epHref.substringAfter("temporada-", "").substringBefore("/", "").toIntOrNull()

                    episodes.add(
                        Episode(
                            fixUrl(epHref),
                            epTitle,
                            seasonNum,
                            epNum,
                        )
                    )
                } else {
                    println("RetroTVE: Faltan datos para un episodio en fila: $epHref, $epTitle")
                }
            }

            episodes.sortBy { it.season }
            episodes.sortBy { it.episode }

            // Línea 133 (aproximada).
            // fixUrl(poster) es seguro porque poster ya se ha validado como no nulo arriba.
            // Para backgroundPosterUrl, usaremos fixUrlNull para manejar el String?
            val safeBackgroundPosterUrl = fixUrlNull(backposter)

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                // Asignamos el resultado de fixUrlNull, que es String?
                this.backgroundPosterUrl = safeBackgroundPosterUrl
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
        val doc = app.get(data).document

        val playerIframes = doc.select(".TPlayerCn iframe, .TPlayerTb iframe")

        if (playerIframes.isEmpty()) {
            println("RetroTVE: No se encontraron iframes de reproductor para $data")
            return false
        }

        var foundLinks = false
        for (iframe in playerIframes) {
            val iframeSrc = iframe.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                val fullIframeUrl = if (iframeSrc.startsWith("/")) {
                    mainUrl + iframeSrc
                } else {
                    iframeSrc
                }
                println("RetroTVE: Intentando cargar extractor para iframe: $fullIframeUrl")

                if (loadExtractor(fullIframeUrl, data, subtitleCallback, callback)) {
                    foundLinks = true
                } else {
                    println("RetroTVE: loadExtractor no pudo resolver: $fullIframeUrl")
                }
            } else {
                println("RetroTVE: iframe src estaba vacío o nulo para un iframe encontrado.")
            }
        }

        if (!foundLinks) {
            println("RetroTVE: No se pudieron extraer enlaces de video de ningún iframe en $data")
        }
        return foundLinks
    }
}