package com.example // ¡MUY IMPORTANTE! Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// ¡CRÍTICO! Añadir esta anotación para que el plugin sea reconocido por CloudStream
class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino" // Nombre más amigable para el usuario
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
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Cartoons", "$mainUrl/genre_series/toons"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Cartoons" -> TvType.Cartoon
                else -> TvType.Others
            }
            val doc = app.get(url).document
            val homeItems = doc.select("div.items article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text()
                val link = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset") ?: it.selectFirst("div.poster img")?.attr("src")

                if (title != null && link != null) {
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link) // Pasa la URL directamente
                    ) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else null
            }
            HomePageList(name, homeItems)
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").mapNotNull {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset") ?: it.selectFirst("div.poster img")?.attr("src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link) // Pasa la URL directamente
                ) {
                    this.type = TvType.TvSeries // Asume TvType.TvSeries para búsquedas
                    this.posterUrl = img
                }
            } else null
        }
    }

    // Data class para pasar datos a newEpisode y loadLinks cuando es un episodio
    data class EpisodeLoadData(
        val title: String,
        val url: String // Usamos 'url' para mayor claridad
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }
        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { seasonElement ->
                seasonElement.select("ul.episodios li").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.episodiotitle div.epst")?.text() ?: ""

                    val seasonNumber = element.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                    val episodeNumber = element.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()

                    val realimg = element.selectFirst("div.imagen img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson() // Pasa EpisodeLoadData como JSON
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
                    url = url,
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
                    url = url,
                    type = tvType,
                    // dataUrl para películas: CloudStream puede envolver esta URL en JSON para loadLinks.
                    dataUrl = url
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SoloLatino", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        // PRIMERO: Intenta limpiar la cadena que viene con "url":"..." o cualquier otro formato.
        // Esta regex es muy permisiva y capturará la primera URL http/https encontrada.
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("SoloLatino", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("SoloLatino", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String

        // SEGUNDO: Intenta parsear la data limpia como JSON para episodios.
        // Si no es un JSON de episodio, asume que 'cleanedData' es una URL directa (para películas).
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url // Usa 'url' del EpisodeLoadData
            Log.d("SoloLatino", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            // Si no es JSON de episodio, 'cleanedData' YA DEBE SER una URL directa (para películas).
            targetUrl = cleanedData
            Log.d("SoloLatino", "loadLinks - URL final de película (directa o ya limpia): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("SoloLatino", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        // El resto del código de loadLinks ahora usará 'targetUrl'
        // para todas las peticiones HTTP y como 'referer' para extractores.

        // 1. Intentar obtener el iframe del reproductor
        // Aquí es donde el error original sucedía, ahora 'targetUrl' debería ser válida.
        val iframeSrc = app.get(targetUrl).document.selectFirst("iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("SoloLatino", "No se encontró iframe en la página principal del episodio/película. Intentando buscar en scripts.")
            val scriptContent = app.get(targetUrl).document.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?://[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback) // 'targetUrl' como referer
                }
                return true
            }
            Log.d("SoloLatino", "No se encontraron enlaces directos en scripts.")
            return false
        }

        Log.d("SoloLatino", "Iframe encontrado: $iframeSrc")

        // 2. Hacer una petición al src del iframe para obtener su contenido
        val frameDoc = try {
            app.get(fixUrl(iframeSrc)).document
        } catch (e: Exception) {
            Log.e("SoloLatino", "Error al obtener el contenido del iframe ($iframeSrc): ${e.message}")
            return false
        }

        val frameHtml = frameDoc.html()
        Log.d("SoloLatino", "HTML del iframe (fragmento): ${frameHtml.take(500)}...")

        // 3. Aplicar regex para encontrar la URL del reproductor dentro del contenido del iframe
        val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
        val playerLinks = regex.findAll(frameHtml).map {
            it.groupValues[2]
        }.toList()

        if (playerLinks.isEmpty()) {
            Log.d("SoloLatino", "No se encontraron enlaces go_to_player/go_to_playerVast en el iframe. Intentando buscar un reproductor directo...")
            val videoSrc = frameDoc.selectFirst("video source")?.attr("src") ?: frameDoc.selectFirst("video")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "Direct Play",
                        fixUrl(videoSrc),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = targetUrl // 'targetUrl' como referer
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            Log.d("SoloLatino", "No se encontró ningún enlace de video obvio en el iframe ni en scripts.")
            return false
        }

        Log.d("SoloLatino", "Enlaces de reproductor encontrados por regex: $playerLinks")

        // 4. Cargar los enlaces encontrados usando loadExtractor
        playerLinks.apmap { playerUrl ->
            Log.d("SoloLatino", "Cargando extractor para: $playerUrl")
            loadExtractor(fixUrl(playerUrl), targetUrl, subtitleCallback, callback) // 'targetUrl' como referer
        }
        return true
    }
}