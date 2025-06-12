package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

// CAMBIO AQUÍ: Usar android.util.Base64 para compatibilidad con API más bajas
import android.util.Base64

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

    private suspend fun customGet(url: String): Document {
        Log.d(name, "customGet: Descargando HTML de: $url")
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
        val url: String,
        val season: Int? = null,
        val episode: Int? = null
    ) {
        fun toJson(): String {
            val escapedName = name.replace("\"", "\\\"")
            val escapedUrl = url.replace("\"", "\\\"")
            return "{\"name\":\"$escapedName\",\"url\":\"$escapedUrl\"," +
                    "\"season\":${season ?: "null"},\"episode\":${episode ?: "null"}}"
        }

        companion object {
            fun fromJson(jsonString: String?): EpisodeLoadData? {
                if (jsonString.isNullOrBlank()) return null
                val regex = Regex("""\{"name":"(.*?)","url":"(.*?)"(?:,"season":(\d+|null))?(?:,"episode":(\d+|null))?\}""")
                val match = regex.find(jsonString)
                return if (match != null) {
                    EpisodeLoadData(
                        match.groupValues[1],
                        match.groupValues[2],
                        match.groupValues.getOrNull(3)?.toIntOrNull(),
                        match.groupValues.getOrNull(4)?.toIntOrNull()
                    )
                } else null
            }
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d(name, "getMainPage: Iniciando carga de la página principal para la página $page")
        val items = ArrayList<HomePageList>()

        val urls = listOf(
            Pair("Últimas Películas Agregadas", "$mainUrl/peliculas${if (page > 1) "/$page" else ""}"),
            Pair("Series Recientes", "$mainUrl/series${if (page > 1) "/$page" else ""}")
        )

        val homePageLists = urls.customApmap { (listName, url) ->
            Log.d(name, "getMainPage: Procesando URL de lista: $url")
            val tvType = when (listName) {
                "Últimas Películas Agregadas" -> TvType.Movie
                "Series Recientes" -> TvType.TvSeries
                else -> TvType.Movie
            }
            val doc = customGet(url)
            Log.d(name, "getMainPage: Documento HTML descargado de $url. Tamaño: ${doc.html().length}")

            val homeItems = doc.select("div.articlesList > article.item").mapNotNull { element ->
                val linkElement = element.selectFirst("a.itemA")
                val titleFull = element.selectFirst("h2")?.text()?.trim()
                val posterUrl = element.selectFirst("div.item__image img")?.attr("data-src")?.trim()

                if (linkElement != null && titleFull != null && posterUrl != null) {
                    val link = linkElement.attr("href")
                    val yearRegex = Regex("""\((\d{4})\)""")
                    val year = yearRegex.find(titleFull)?.groupValues?.get(1)?.toIntOrNull()
                    val cleanTitle = titleFull.replace(yearRegex, "").trim()

                    Log.d(name, "getMainPage: Item encontrado: Título: $cleanTitle, Link: $link, Póster: $posterUrl, Año: $year, Tipo: $tvType")
                    newMovieSearchResponse(
                        cleanTitle,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = posterUrl
                        this.year = year
                    }
                } else {
                    Log.w(name, "getMainPage: Item incompleto o nulo encontrado. Título Completo: $titleFull, Enlace Elemento: $linkElement, Póster URL: $posterUrl")
                    null
                }
            }
            Log.d(name, "getMainPage: Se encontraron ${homeItems.size} ítems para la lista '$listName'")
            HomePageList(listName, homeItems)
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val url = "$mainUrl/search/${query.replace(" ", "%20")}"
        Log.d(name, "search: Buscando '$query' en $url")

        val document = customGet(url)
        Log.d(name, "search: Documento HTML de búsqueda descargado. Tamaño: ${document.html().length}")

        val elements = document.select("div.articlesList > article.item")
        Log.d(name, "search: Elementos de búsqueda encontrados: ${elements.size}")

        elements.forEach { element ->
            val linkElement = element.selectFirst("a.itemA")
            val titleFull = element.selectFirst("h2")?.text()?.trim()
            val posterUrl = element.selectFirst("div.item__image img")?.attr("data-src")?.trim()
            val typeString = element.selectFirst("span.typeItem")?.text()?.trim()

            if (linkElement != null && titleFull != null && posterUrl != null && typeString != null) {
                val link = linkElement.attr("href")

                val yearRegex = Regex("""\((\d{4})\)""")
                val year = yearRegex.find(titleFull)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = titleFull.replace(yearRegex, "").trim()

                val tvType = when (typeString) {
                    "Pelicúla" -> TvType.Movie
                    "Serie" -> TvType.TvSeries
                    else -> {
                        Log.w(name, "search: Tipo de contenido desconocido '$typeString' para $cleanTitle. Asumiendo como Película.")
                        TvType.Movie
                    }
                }

                val fixedLink = fixUrl(link)
                if (fixedLink.startsWith(mainUrl)) {
                    Log.d(name, "search: Resultado procesado: Título: $cleanTitle, Enlace: $fixedLink, Póster: $posterUrl, Año: $year, Tipo: $tvType")
                    searchResults.add(
                        newMovieSearchResponse(
                            cleanTitle,
                            fixedLink
                        ) {
                            this.type = tvType
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    )
                } else {
                    Log.w(name, "search: Enlace de resultado no válido (no comienza con mainUrl): $fixedLink")
                }
            } else {
                Log.w(name, "search: Resultado de búsqueda incompleto. Título: $titleFull, Enlace: ${linkElement?.attr("href")}, Póster: $posterUrl, Tipo: $typeString")
            }
        }
        Log.d(name, "search: Total de resultados de búsqueda encontrados: ${searchResults.size}")
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load: Cargando información detallada de: $url")
        val document = customGet(url)
        Log.d(name, "load: Documento HTML de carga de detalles descargado. Tamaño: ${document.html().length}")

        val title = document.selectFirst("h1.slugh1, div.home_slider_content h2")?.text()?.trim()

        val poster = document.selectFirst("div.bg")?.attr("style")?.let { style ->
            Regex("""url\("?([^")]*)"?\)""").find(style)?.groupValues?.get(1)
        } ?: document.selectFirst("div.image-single img, div.poster-single img, img.single-poster")?.attr("data-src")?.trim()
        ?: document.selectFirst("div.image-single img, div.poster-single img, img.single-poster")?.attr("src")?.trim()

        val year = document.selectFirst("div.genres.rating a[href*=\"/year/\"]")?.text()?.trim()?.toIntOrNull()

        val plot = document.selectFirst("div.description p, p.scroll")?.text()?.trim()

        val genres = document.select("div.genres a")?.mapNotNull { it.text()?.trim() }

        val ratingText = document.selectFirst("div.genres.rating span:contains(Rating)")?.text()?.trim()
        val rating = ratingText?.let {
            val value = Regex("""Rating:\s*([\d.]+)""").find(it)?.groupValues?.get(1)
            value?.toFloatOrNull()?.times(100)?.toInt()
        }

        val runtimeText = document.selectFirst("div.genres.rating span:contains(Duración)")?.text()?.trim()
        val runtime = runtimeText?.let {
            val hours = Regex("""(\d+)\s*hora(?:s)?""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = Regex("""(\d+)\s*minuto(?:s)?""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            (hours * 60) + minutes
        }

        // Determina el tipo basado en la URL o la presencia de elementos de serie
        val type = if (url.contains("/serie/") || document.select("div.season.main").isNotEmpty() || document.select("div#episodelist").isNotEmpty()) TvType.TvSeries else TvType.Movie

        Log.d(name, "load: Título detectado: $title, Tipo: $type, Póster: $poster, Año: $year, Plot: $plot, Géneros: $genres, Rating: $rating, Duración: $runtime")

        if (title == null) {
            Log.e(name, "load: ERROR: Título no encontrado para URL: $url")
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
                this.tags = genres
                this.rating = rating
                this.duration = runtime
            }
        } else {
            val episodes = ArrayList<Episode>()
            val seasonElements = document.select("div.season.main")
            val allEpisodeItems = document.select("div#episodelist article.item")

            if (allEpisodeItems.isNotEmpty()) {
                if (seasonElements.isNotEmpty()) {
                    seasonElements.forEach { seasonDiv ->
                        val seasonNumber = seasonDiv.selectFirst("h3#seasonTitleChange")?.text()?.replace("Temporada ", "")?.trim()?.toIntOrNull() ?: 1
                        Log.d(name, "load: Procesando Temporada: $seasonNumber")

                        seasonDiv.select("div#episodelist article.item a.itemA").forEach { epElement ->
                            val episodeLink = epElement.attr("href")?.trim()
                            val epTitle = epElement.selectFirst("h2")?.text()?.trim()
                            val epNumber = Regex("""E(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                            if (episodeLink != null && episodeLink.isNotBlank() && epNumber != null) {
                                val episodeDataJson = EpisodeLoadData(epTitle ?: "Episodio $epNumber", fixUrl(episodeLink), seasonNumber, epNumber).toJson()
                                Log.d(name, "load: Episodio (temporada $seasonNumber) encontrado: Título: $epTitle, Número: $epNumber, Enlace: $episodeLink")
                                episodes.add(
                                    newEpisode(
                                        data = episodeDataJson
                                    ) {
                                        this.name = epTitle
                                        this.season = seasonNumber
                                        this.episode = epNumber
                                    }
                                )
                            } else {
                                Log.w(name, "load: Episodio incompleto o nulo encontrado en temporada $seasonNumber. Título: $epTitle, Número: $epNumber, Enlace: $episodeLink")
                            }
                        }
                    }
                } else {
                    Log.d(name, "load: No se encontraron elementos de temporada 'div.season.main'. Buscando episodios planos en el contenedor global de episodios.")
                    allEpisodeItems.forEach { item ->
                        val epElement = item.selectFirst("a.itemA")
                        if (epElement != null) {
                            val episodeLink = epElement.attr("href")?.trim()
                            val epTitle = epElement.selectFirst("h2")?.text()?.trim()
                            val epNumber = Regex("""E(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                            if (episodeLink != null && episodeLink.isNotBlank() && epNumber != null) {
                                val episodeDataJson = EpisodeLoadData(epTitle ?: "Episodio $epNumber", fixUrl(episodeLink), 1, epNumber).toJson()
                                Log.d(name, "load: Episodio (sin temporada explícita, asumido Temporada 1) encontrado: Título: $epTitle, Número: $epNumber, Enlace: $episodeLink")
                                episodes.add(
                                    newEpisode(
                                        data = episodeDataJson
                                    ) {
                                        this.name = epTitle
                                        this.season = 1
                                        this.episode = epNumber
                                    }
                                )
                            } else {
                                Log.w(name, "load: Episodio (sin temporada explícita) incompleto o nulo encontrado. Título: $epTitle, Número: $epNumber, Enlace: $episodeLink")
                            }
                        }
                    }
                }
            } else {
                Log.w(name, "load: No se encontraron elementos de episodio 'div#episodelist article.item' en la página de la serie: $url")
            }
            Log.d(name, "load: Se encontraron ${episodes.size} episodios en total.")

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
                this.tags = genres
                this.rating = rating
                this.duration = runtime
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks: Iniciando carga de enlaces para: $data")

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
        Log.d(name, "loadLinks: Documento HTML de enlaces descargado. Tamaño: ${document.html().length}")

        var linksFound = false

        val iframeSrc = document.selectFirst("div#play iframe")?.attr("src")?.trim()
        if (iframeSrc != null && iframeSrc.startsWith("http")) {
            Log.d(name, "loadLinks: Encontrado posible iframe principal: $iframeSrc. Intentando cargar con extractores genéricos.")
            val extractorResult = safeApiCall { loadExtractor(iframeSrc, targetUrl, subtitleCallback, callback) }
            if (extractorResult is com.lagradost.cloudstream3.mvvm.Resource.Success && extractorResult.value == true) {
                linksFound = true
            } else {
                Log.w(name, "loadLinks: loadExtractor no encontró enlaces o falló para el iframe principal: $iframeSrc")
            }
        } else {
            Log.i(name, "loadLinks: No se encontró iframe principal válido o no es HTTP. iframeSrc: $iframeSrc")
        }

        document.select("ul.subselect li[data-server]").forEach { serverOption ->
            val dataServerId = serverOption.attr("data-server")?.trim()
            val serverName = serverOption.selectFirst("span")?.text()?.trim()

            if (dataServerId != null && dataServerId.isNotBlank()) {
                try {
                    // CAMBIO AQUÍ: Usar android.util.Base64 para la decodificación
                    val decodedServerUrl = String(Base64.decode(dataServerId, Base64.DEFAULT))
                    Log.d(name, "loadLinks: Intentando loadExtractor con URL decodificada: '$decodedServerUrl' ($serverName)")
                    val extractorResult = safeApiCall { loadExtractor(decodedServerUrl, targetUrl, subtitleCallback, callback) }
                    if (extractorResult is com.lagradost.cloudstream3.mvvm.Resource.Success && extractorResult.value == true) {
                        linksFound = true
                    } else {
                        Log.w(name, "loadLinks: loadExtractor no encontró enlaces o falló para URL decodificada: '$decodedServerUrl'")
                    }
                } catch (e: Exception) {
                    Log.e(name, "loadLinks: Error al decodificar Base64 para data-server ID: '$dataServerId'. Error: ${e.message}")
                }
            } else {
                Log.i(name, "loadLinks: Opción de servidor sin data-server ID válido. ID: $dataServerId, Nombre: $serverName")
            }
        }

        val scriptContent = document.select("script").map { it.html() }.joinToString("\n")
        val masterM3u8Regex = Regex("""(https?:\/\/[^\s"']+\.m3u8\?[^\s"']*)""")
        val vttRegex = Regex("""(https?:\/\/[^\s"']+\.vtt\?[^\s"']*)""")

        val masterM3u8Url = masterM3u8Regex.find(scriptContent)?.groupValues?.get(1)
        if (masterM3u8Url != null) {
            Log.d(name, "loadLinks: Encontrado Master M3U8 URL en script/HTML: $masterM3u8Url")
            callback(
                ExtractorLink(
                    this.name,
                    "Pelisplus M3U8",
                    masterM3u8Url,
                    targetUrl,
                    Qualities.Unknown.value,
                    headers = mapOf("Referer" to targetUrl),
                    type = ExtractorLinkType.M3U8
                )
            )
            linksFound = true
        } else {
            Log.i(name, "loadLinks: No se encontró Master M3U8 URL en script/HTML.")
        }

        val vttUrl = vttRegex.find(scriptContent)?.groupValues?.get(1)
        if (vttUrl != null) {
            Log.d(name, "loadLinks: Encontrado VTT URL en script/HTML: $vttUrl")
            subtitleCallback(SubtitleFile("Español", vttUrl))
            linksFound = true
        } else {
            Log.i(name, "loadLinks: No se encontró VTT URL en script/HTML.")
        }

        Log.d(name, "loadLinks: Proceso de carga de enlaces finalizado. Links encontrados: $linksFound")
        return linksFound
    }
}