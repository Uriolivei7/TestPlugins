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
        val doc = app.get(data).document

        val playerIframes = doc.select(".TPlayerCn iframe, .TPlayerTb iframe")

        if (playerIframes.isEmpty()) {
            println("RetroTVE: No se encontraron iframes de reproductor principal para $data")
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
                println("RetroTVE: Intentando resolver URL del reproductor intermedio: $fullIframeUrl")

                val embedPageDoc = try {
                    app.get(fullIframeUrl).document
                } catch (e: Exception) {
                    println("RetroTVE: Error al obtener la página de trembed $fullIframeUrl: ${e.message}")
                    continue
                }

                val finalPlayerIframe = embedPageDoc.selectFirst("iframe[src*=\"cubeembed.rpmvid.com\"]")
                val finalPlayerSrc = finalPlayerIframe?.attr("src")

                if (!finalPlayerSrc.isNullOrBlank()) {
                    println("RetroTVE: Encontrado URL de reproductor final: $finalPlayerSrc")
                    // Eliminado el argumento 'headers' ya que no está en la API stub
                    // El referer se pasa como segundo argumento aquí:
                    if (loadExtractor(finalPlayerSrc, "https://retrotve.com/", subtitleCallback, callback)) { // Línea ~197
                        foundLinks = true
                    } else {
                        println("RetroTVE: loadExtractor no pudo resolver el reproductor final: $finalPlayerSrc")
                    }
                } else {
                    println("RetroTVE: No se encontró iframe de reproductor final en $fullIframeUrl. Buscando scripts...")
                    val scriptContent = embedPageDoc.select("script:contains(eval)").text()
                    if (scriptContent.isNotBlank()) {
                        println("RetroTVE: Script con 'eval' encontrado, se requiere análisis de JS para extraer URLs.")
                        val hlsMatches = Regex("""(http[s]?://[^"']*\.m3u8[^"']*)""").findAll(scriptContent).map { it.value }.toList()
                        if (hlsMatches.isNotEmpty()) {
                            hlsMatches.forEach { hlsUrl ->
                                println("RetroTVE: Encontrado HLS URL en script: $hlsUrl")
                                // Eliminado el argumento 'headers' ya que no está en la API stub
                                // El referer se pasa como segundo argumento aquí:
                                if (loadExtractor(hlsUrl, fullIframeUrl, subtitleCallback, callback)) { // Línea ~218
                                    foundLinks = true
                                }
                            }
                        }
                    }
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