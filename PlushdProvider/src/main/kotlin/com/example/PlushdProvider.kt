package com.example

import android.util.Log // Asegúrate de que esta línea esté presente
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
// COMENTADO: import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

// @CloudstreamProvider
class PlushdProvider : MainAPI() {
    override var name = "PlusHD"
    override var mainUrl = "https://ww3.pelisplus.to"
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val hasChromecastSupport = false
    override val hasDownloadSupport = false

    private fun fixUrl(url: String?): String {
        return if (url.isNullOrBlank()) "" else if (url.startsWith("/")) mainUrl + url else url
    }

    private suspend fun customGet(url: String): org.jsoup.nodes.Document {
        Log.d(name, "customGet: Descargando HTML de: $url") // LOG
        return withContext(Dispatchers.IO) {
            Jsoup.connect(url).get()
        }
    }

    suspend fun <A, B> List<A>.customApmap(f: suspend (A) -> B): List<B> =
        withContext(Dispatchers.IO) {
            val deferreds = this@customApmap.map { async { f(it) } }
            deferreds.awaitAll()
        }

    data class EpisodeLoadData(
        val name: String,
        val url: String
    ) {
        fun toJson(): String {
            val escapedName = name.replace("\"", "\\\"")
            val escapedUrl = url.replace("\"", "\\\"")
            return "{\"name\":\"$escapedName\",\"url\":\"$escapedUrl\"}"
        }

        companion object {
            fun fromJson(jsonString: String?): EpisodeLoadData? {
                if (jsonString.isNullOrBlank()) return null
                val regex = Regex("""\{"name":"(.*?)","url":"(.*?)"\}""")
                val match = regex.find(jsonString)
                return if (match != null) {
                    EpisodeLoadData(match.groupValues[1], match.groupValues[2])
                } else null
            }
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d(name, "getMainPage: Iniciando carga de la página principal para la página $page") // LOG
        val items = ArrayList<HomePageList>()

        val urls = listOf(
            Pair("Últimas Películas Agregadas", "$mainUrl/peliculas"),
            Pair("Series Recientes", "$mainUrl/series"),
        )

        val homePageLists = urls.customApmap { (name, url) ->
            Log.d(name, "getMainPage: Procesando URL de lista: $url") // LOG
            val tvType = when (name) {
                "Últimas Películas Agregadas" -> TvType.Movie
                "Series Recientes" -> TvType.TvSeries
                else -> TvType.Movie
            }
            val doc = customGet(url)
            Log.d(name, "getMainPage: Documento HTML descargado de $url. Tamaño: ${doc.html().length}") // LOG

            val homeItems = doc.select("div.movies-list-full > div.item-movie").mapNotNull { element ->
                val title = element.selectFirst("div.meta > h2 > a")?.text()?.trim()
                val link = element.selectFirst("div.meta > h2 > a")?.attr("href")
                val posterUrl = element.selectFirst("div.poster img")?.attr("src")?.trim()
                val year = element.selectFirst("div.meta > span.year")?.text()?.trim()?.toIntOrNull()

                if (title != null && link != null && posterUrl != null) {
                    Log.d(name, "getMainPage: Item encontrado: $title, Link: $link, Poster: $posterUrl") // LOG
                    newMovieSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = posterUrl ?: ""
                        this.year = year
                    }
                } else {
                    Log.w(name, "getMainPage: Item incompleto o nulo encontrado. Título: $title, Link: $link, Poster: $posterUrl") // LOG
                    null
                }
            }
            Log.d(name, "getMainPage: Se encontraron ${homeItems.size} ítems para la lista '$name'") // LOG
            HomePageList(name, homeItems)
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val url = "$mainUrl/search?q=${query.replace(" ", "+")}"
        Log.d(name, "search: Buscando en $url") // LOG

        val document = customGet(url)
        Log.d(name, "search: Documento HTML de búsqueda descargado. Tamaño: ${document.html().length}") // LOG

        document.select("div.item-search, div.movies-list-full > div.item-movie").forEach { element ->
            val titleElement = element.selectFirst("div.title a, div.meta > h2 > a")
            val title = titleElement?.text()?.trim()
            val posterUrl = element.selectFirst("div.poster img")?.attr("src")
            val link = titleElement?.attr("href")
            val year = element.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

            if (title != null && link != null && posterUrl != null) {
                val fixedLink = fixUrl(link)
                if (fixedLink.startsWith(mainUrl)) {
                    Log.d(name, "search: Encontrado resultado: $title ($fixedLink)") // LOG
                    newMovieSearchResponse(
                        title,
                        fixedLink
                    ) {
                        this.type = TvType.Movie
                        this.posterUrl = posterUrl ?: ""
                        this.year = year
                    }.also { searchResults.add(it) }
                } else {
                    Log.w(name, "search: Enlace de resultado no comienza con mainUrl: $fixedLink") // LOG
                }
            } else {
                Log.w(name, "search: Resultado de búsqueda incompleto o nulo. Título: $title, Link: $link, Poster: $posterUrl") // LOG
            }
        }
        Log.d(name, "search: Total de resultados de búsqueda encontrados: ${searchResults.size}") // LOG
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load: Cargando información detallada de: $url")
        val document = customGet(url)
        Log.d(name, "load: Documento HTML de carga de detalles descargado. Tamaño: ${document.html().length}") // LOG


        val title = document.selectFirst("div.heading > h1, h1.Title")?.text()?.trim()
        val poster = document.selectFirst("div.trailer > img, div.Image img")?.attr("src")?.trim()
        val year = document.selectFirst("div.trailer > span.year, span.Date")?.text()?.trim()?.toIntOrNull()
        val plot = document.selectFirst("div.description > p, div.Description > p")?.text()?.trim()
        val genres = document.select("div.genres > a, span.Generos a").mapNotNull { it.text()?.trim() }
        val tags = document.select("div.tags > a, div.Categori > a").mapNotNull { it.text()?.trim() }
        val rating = document.selectFirst("div.imdb-rating > span.rating, span.IMDB")?.text()?.trim()?.toFloatOrNull()?.times(1000)?.toInt()
        val runtime = document.selectFirst("div.meta-data > span:contains(min), span.Duration")?.text()?.replace(" min", "")?.trim()?.toIntOrNull()
        val trailerUrl = document.selectFirst("div.trailer iframe")?.attr("src")?.trim()

        val type = if (document.select("div.season.main").isNotEmpty()) TvType.TvSeries else TvType.Movie

        Log.d(name, "load: Título detectado: $title, Tipo: $type, Poster: $poster, Año: $year") // LOG

        if (title == null) {
            Log.e(name, "load: ERROR: Título no encontrado para URL: $url") // LOG
            return null
        }


        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = poster ?: ""
                this.year = year
                this.plot = plot ?: ""
                this.tags = tags
                // this.genres = genres
                this.rating = rating
                this.duration = runtime
                // this.trailer = trailerUrl
            }
        } else {
            val episodes = ArrayList<Episode>()
            document.select("div.season.main").forEach { seasonDiv ->
                val seasonNumber = seasonDiv.selectFirst("h3#seasonTitle")?.text()?.replace("Temporada ", "")?.trim()?.toIntOrNull() ?: 1
                Log.d(name, "load: Procesando Temporada: $seasonNumber") // LOG

                seasonDiv.select("div#episodelist.articlesList article.item a.itemA").forEach { epElement ->
                    val episodeLink = epElement.attr("href")?.trim()
                    val epTitle = epElement.selectFirst("h2")?.text()?.trim()
                    val epNumber = Regex("""E(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                    if (episodeLink != null && episodeLink.isNotBlank() && epNumber != null) { // Corrected line
                        val episodeDataJson = EpisodeLoadData(epTitle ?: "Episodio $epNumber", fixUrl(episodeLink)).toJson()
                        Log.d(name, "load: Episodio encontrado: Título: $epTitle, Número: $epNumber, Enlace: $episodeLink") // LOG
                        episodes.add(
                            newEpisode(
                                data = episodeDataJson
                            ) {
                                // this.name = epTitle
                                // this.season = seasonNumber
                                // this.episode = epNumber
                            }
                        )
                    } else {
                        Log.w(name, "load: Episodio incompleto o nulo encontrado. Título: $epTitle, Número: $epNumber, Enlace: $episodeLink") // LOG
                    }
                }
            }
            Log.d(name, "load: Se encontraron ${episodes.size} episodios en total.") // LOG

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.sortedWith(compareBy({it.season}, {it.episode}))
            ) {
                this.posterUrl = poster ?: ""
                this.backgroundPosterUrl = poster ?: ""
                this.year = year
                this.plot = plot ?: ""
                this.tags = tags
                // this.genres = genres
                this.rating = rating
                this.duration = runtime
                // this.trailer = trailerUrl
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks: Iniciando carga de enlaces para: $data") // LOG

        val targetUrl: String
        val parsedEpisodeData = EpisodeLoadData.fromJson(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d(name, "loadLinks: URL objetivo extraída del JSON de episodio: $targetUrl")
        } else {
            targetUrl = fixUrl(data)
            Log.d(name, "loadLinks: URL objetivo directa (probablemente película): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e(name, "loadLinks: ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val document = customGet(targetUrl)
        Log.d(name, "loadLinks: Documento HTML de enlaces descargado. Tamaño: ${document.html().length}") // LOG


        var linksFound = false

        val iframeSrc = document.selectFirst("div.player-frame iframe, iframe[src*='.m3u8']")?.attr("src")?.trim()
        if (iframeSrc != null && iframeSrc.startsWith("http")) {
            Log.d(name, "loadLinks: Encontrado posible iframe: $iframeSrc. Intentando cargar con extractores genéricos.") // LOG
            if (loadExtractor(iframeSrc, targetUrl, subtitleCallback, callback)) {
                linksFound = true
            } else {
                Log.w(name, "loadLinks: loadExtractor no encontró enlaces para el iframe: $iframeSrc") // LOG
            }
        } else {
            Log.i(name, "loadLinks: No se encontró iframe principal válido o no es HTTP. iframeSrc: $iframeSrc") // LOG
        }

        val scriptContent = document.select("script").map { it.html() }.joinToString("\n")
        val masterM3u8Regex = Regex("""(https?:\/\/[^\s"']+\.m3u8\?[^\s"']*)""")
        val vttRegex = Regex("""(https?:\/\/[^\s"']+\.vtt\?[^\s"']*)""")

        val masterM3u8Url = masterM3u8Regex.find(scriptContent)?.groupValues?.get(1) ?: masterM3u8Regex.find(document.html())?.groupValues?.get(1)
        if (masterM3u8Url != null) {
            Log.d(name, "loadLinks: Encontrado Master M3U8 URL en script/HTML: $masterM3u8Url") // LOG
            callback(
                ExtractorLink(
                    this.name,
                    "Pelisplus Player",
                    masterM3u8Url,
                    targetUrl,
                    Qualities.Unknown.value,
                    headers = mapOf("Referer" to targetUrl),
                    type = ExtractorLinkType.M3U8
                )
            )
            linksFound = true
        } else {
            Log.i(name, "loadLinks: No se encontró Master M3U8 URL en script/HTML.") // LOG
        }

        val vttUrl = vttRegex.find(scriptContent)?.groupValues?.get(1) ?: vttRegex.find(document.html())?.groupValues?.get(1)
        if (vttUrl != null) {
            Log.d(name, "loadLinks: Encontrado VTT URL en script/HTML: $vttUrl") // LOG
            // subtitleCallback(SubtitleFile("Español", vttUrl)) // Comentado previamente
            linksFound = true
        } else {
            Log.i(name, "loadLinks: No se encontró VTT URL en script/HTML.") // LOG
        }

        document.select("ul.subselect li[data-server]").forEach { serverOption ->
            val dataServerId = serverOption.attr("data-server")?.trim()
            val serverName = serverOption.selectFirst("span")?.text()?.trim()

            if (dataServerId != null && dataServerId.isNotBlank()) {
                Log.d(name, "loadLinks: Intentando loadExtractor con data-server ID: '$dataServerId' ($serverName)") // LOG
                if (loadExtractor(dataServerId, targetUrl, subtitleCallback, callback)) {
                    linksFound = true
                } else {
                    Log.w(name, "loadLinks: loadExtractor no encontró enlaces para data-server ID: '$dataServerId'") // LOG
                }
            } else {
                Log.i(name, "loadLinks: Opción de servidor sin data-server ID válido. ID: $dataServerId, Nombre: $serverName") // LOG
            }
        }
        Log.d(name, "loadLinks: Proceso de carga de enlaces finalizado. Links encontrados: $linksFound") // LOG

        return linksFound
    }
}