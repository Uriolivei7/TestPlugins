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

        val playerIframes = mutableListOf<org.jsoup.nodes.Element>()

        // Depuración: Mostrar todos los iframes trembed encontrados
        doc.select(".TPlayerCn iframe, .TPlayerTb iframe").forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            if (!src.isNullOrBlank()) {
                println("RetroTVE: Encontrado iframe de reproductor principal [${index}]: $src")
            }
        }

        // 1. Intentar encontrar el iframe con trembed=1 primero
        val trembed1Iframe = doc.selectFirst("iframe[src*=\"trembed=1\"]") // Busca cualquier iframe que contenga "trembed=1" en su src
        if (trembed1Iframe != null) {
            playerIframes.add(trembed1Iframe)
            println("RetroTVE: Priorizando trembed=1 iframe: ${trembed1Iframe.attr("src")}")
        }

        // 2. Luego, añadir los demás iframes, asegurándose de no duplicar
        doc.select(".TPlayerCn iframe, .TPlayerTb iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            // Añadir solo si no es trembed=1 (ya lo añadimos) Y no es nulo/vacío
            if (!iframeSrc.isNullOrBlank() && !iframeSrc.contains("trembed=1")) {
                playerIframes.add(iframe)
            }
        }

        // Si trembed=1 no se encontró en el paso 1, y no hay otros iframes.
        if (playerIframes.isEmpty()) {
            println("RetroTVE: No se encontraron iframes de reproductor principal para $data")
            return false
        }

        var foundLinks = false
        // Usamos run outerLoop para poder "break" del bucle forEach con return@outerLoop
        run outerLoop@{
            playerIframes.forEach { iframe ->
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
                        return@forEach // Continúa al siguiente iframe en caso de error
                    }

                    // Buscar el iframe de VK.com
                    val vkIframe = embedPageDoc.selectFirst("iframe[src*=\"vk.com/video_ext.php\"]")
                    val vkSrc = vkIframe?.attr("src")

                    if (!vkSrc.isNullOrBlank()) {
                        println("RetroTVE: Encontrado URL de VK.com: $vkSrc")
                        if (loadExtractor(vkSrc, fullIframeUrl, subtitleCallback, callback)) {
                            foundLinks = true
                            return@outerLoop // Salir del bucle run outerLoop al encontrar enlaces
                        } else {
                            println("RetroTVE: loadExtractor no pudo resolver el video de VK.com: $vkSrc")
                        }
                    } else {
                        println("RetroTVE: No se encontró iframe de VK.com en $fullIframeUrl. Buscando scripts (respaldo)...")
                        // Aquí es donde necesitamos añadir la lógica para otros extractores si es necesario,
                        // o confiar en que loadExtractor los detecte a partir de la URL del script si no es HLS/MP4 directo.

                        val scriptContent = embedPageDoc.select("script:contains(eval)").text()
                        if (scriptContent.isNotBlank()) {
                            println("RetroTVE: Script con 'eval' encontrado, se requiere análisis de JS para extraer URLs.")
                            val hlsMatches = Regex("""(http[s]?://[^"']*\.m3u8[^"']*)""").findAll(scriptContent).map { it.value }.toList()
                            if (hlsMatches.isNotEmpty()) {
                                hlsMatches.forEach { hlsUrl ->
                                    println("RetroTVE: Encontrado HLS URL en script (respaldo): $hlsUrl")
                                    if (loadExtractor(hlsUrl, fullIframeUrl, subtitleCallback, callback)) {
                                        foundLinks = true
                                        return@outerLoop // Salir del bucle run outerLoop
                                    }
                                }
                            } else {
                                // Si no hay HLS/MP4 directo en scripts, pero hay un script con eval,
                                // podría ser una URL de extractor que loadExtractor puede manejar
                                // si se la pasamos directamente.
                                // Podríamos intentar pasar la URL del iframe trembed a loadExtractor
                                // si el script con eval es un obfuscador para un iframe de extractor.
                                println("RetroTVE: No HLS/MP4 directo en script con 'eval'. Intentando con loadExtractor la URL de embedPageDoc.")
                                if (loadExtractor(fullIframeUrl, data, subtitleCallback, callback)) { // Usamos 'data' como referer principal
                                    foundLinks = true
                                    return@outerLoop
                                }
                            }
                        }
                    }
                } else {
                    println("RetroTVE: iframe src estaba vacío o nulo para un iframe encontrado.")
                }
            }
        }


        if (!foundLinks) {
            println("RetroTVE: No se pudieron extraer enlaces de video de ningún iframe en $data")
        }
        return foundLinks
    }

}