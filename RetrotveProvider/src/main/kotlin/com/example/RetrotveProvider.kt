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

            val posterUrl = fixUrl(img)

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, fixUrl(href)) {
                    this.posterUrl = posterUrl
                    this.year = year
                }
            } else {
                // CAMBIO AQUÍ: Usamos el constructor con lambda para newMovieSearchResponse
                newMovieSearchResponse(title, fixUrl(href)) {
                    this.posterUrl = posterUrl // Asignación dentro del lambda
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

        var foundLinks = false

        // Capturar todos los iframes de reproductor que tienen un src con "trembed="
        val allTrembedIframes = doc.select("div[class*=\"TPlayerTb\"] iframe[src*=\"trembed=\"]")
            .mapNotNull { it.attr("src") } // Obtener solo las URLs
            .distinct() // Eliminar URLs duplicadas
            .sortedWith(compareBy {
                // Ordenar para que trembed=1 sea primero, luego trembed=2, y el resto después.
                val trembedNum = Regex("""trembed=(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
                when (trembedNum) {
                    1 -> 0 // Máxima prioridad
                    2 -> 1 // Segunda prioridad
                    0 -> 99 // Última prioridad si no es 1 o 2
                    else -> trembedNum ?: 100 // Otras trembed en su orden numérico, o 100 si no se puede parsear
                }
            })

        if (allTrembedIframes.isEmpty()) {
            println("RetroTVE: No se encontraron iframes de reproductor principal con 'trembed=' en $data")
            return false
        }

        println("RetroTVE: Opciones de trembed encontradas y ordenadas: $allTrembedIframes")

        // Usamos run outerLoop para poder "break" del bucle forEach con return@outerLoop
        run outerLoop@{
            for (fullTrembedUrl in allTrembedIframes) { // Iterar sobre las URLs ya ordenadas
                println("RetroTVE: Intentando resolver URL del reproductor intermedio: $fullTrembedUrl")

                val embedPageDoc = try {
                    app.get(fullTrembedUrl).document
                } catch (e: Exception) {
                    println("RetroTVE: Error al obtener la página de trembed $fullTrembedUrl: ${e.message}")
                    continue // Continúa al siguiente iframe en caso de error
                }

                // Buscar el iframe de VK.com
                val vkIframe = embedPageDoc.selectFirst("iframe[src*=\"vk.com/video_ext.php\"]")
                val vkSrc = vkIframe?.attr("src")

                if (!vkSrc.isNullOrBlank()) {
                    println("RetroTVE: Encontrado URL de VK.com: $vkSrc")
                    if (loadExtractor(vkSrc, fullTrembedUrl, subtitleCallback, callback)) {
                        foundLinks = true
                        return@outerLoop // Salir del bucle run outerLoop al encontrar enlaces
                    } else {
                        println("RetroTVE: loadExtractor no pudo resolver el video de VK.com: $vkSrc")
                    }
                } else {
                    println("RetroTVE: No se encontró iframe de VK.com en $fullTrembedUrl. Buscando otros extractores o scripts...")

                    // Buscar SEnvid
                    val senvidIframe = embedPageDoc.selectFirst("iframe[src*=\"senvid.net/embed-\"], iframe[src*=\"senvid.com/embed-\"]")
                    val senvidSrc = senvidIframe?.attr("src")
                    if (!senvidSrc.isNullOrBlank()) {
                        println("RetroTVE: Encontrado URL de SEnvid: $senvidSrc")
                        if (loadExtractor(senvidSrc, fullTrembedUrl, subtitleCallback, callback)) {
                            foundLinks = true
                            return@outerLoop
                        } else {
                            println("RetroTVE: loadExtractor no pudo resolver el video de SEnvid: $senvidSrc")
                        }
                    }

                    // Buscar YourUpload
                    val yourUploadIframe = embedPageDoc.selectFirst("iframe[src*=\"yourupload.com/embed/\"]")
                    val yourUploadSrc = yourUploadIframe?.attr("src")
                    if (!yourUploadSrc.isNullOrBlank()) {
                        println("RetroTVE: Encontrado URL de YourUpload: $yourUploadSrc")
                        if (loadExtractor(yourUploadSrc, fullTrembedUrl, subtitleCallback, callback)) {
                            foundLinks = true
                            return@outerLoop
                        } else {
                            println("RetroTVE: loadExtractor no pudo resolver el video de YourUpload: $yourUploadSrc")
                        }
                    }

                    // Lógica de respaldo para scripts con HLS/MP4 o para pasar la URL trembed a loadExtractor si no se encontró un iframe de extractor conocido
                    val scriptContent = embedPageDoc.select("script:contains(eval)").text()
                    if (scriptContent.isNotBlank()) {
                        println("RetroTVE: Script con 'eval' encontrado, se requiere análisis de JS para extraer URLs.")
                        val hlsMatches = Regex("""(http[s]?://[^"']*\.m3u8[^"']*)""").findAll(scriptContent).map { it.value }.toList()
                        if (hlsMatches.isNotEmpty()) {
                            hlsMatches.forEach { hlsUrl ->
                                println("RetroTVE: Encontrado HLS URL en script (respaldo): $hlsUrl")
                                if (loadExtractor(hlsUrl, fullTrembedUrl, subtitleCallback, callback)) {
                                    foundLinks = true
                                    return@outerLoop
                                }
                            }
                        } else {
                            println("RetroTVE: No HLS/MP4 directo en script con 'eval'. Intentando con loadExtractor la URL de embedPageDoc.")
                            if (loadExtractor(fullTrembedUrl, data, subtitleCallback, callback)) {
                                foundLinks = true
                                return@outerLoop
                            }
                        }
                    }
                }
            }
        }

        if (!foundLinks) {
            println("RetroTVE: No se pudieron extraer enlaces de video de ningún iframe en $data")
        }
        return foundLinks
    }
}