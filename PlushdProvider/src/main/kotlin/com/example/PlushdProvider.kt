package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile // Importación correcta para SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

// @CloudstreamProvider // Descomentar esta línea cuando el plugin esté listo para ser usado
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
    // Eliminadas las propiedades usesLoad, usesLoadLinks, usesSearch como acordamos.


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

        // --- Ajustes de selectores basados en el HTML proporcionado ---
        // Título: Priorizamos h1.slugh1 para películas, luego h2 dentro de home_slider_content.
        val title = document.selectFirst("h1.slugh1, div.home_slider_content h2")?.text()?.trim()

        // Póster: Si la imagen está en un background-image de div.bg, extraemos de ahí.
        // Si no, buscamos un img con data-src o src.
        val poster = document.selectFirst("div.bg")?.attr("style")?.let { style ->
            Regex("""url\("?([^")]*)"?\)""").find(style)?.groupValues?.get(1)
        } ?: document.selectFirst("div.image-single img, div.poster-single img, img.single-poster")?.attr("data-src")?.trim()
        ?: document.selectFirst("div.image-single img, div.poster-single img, img.single-poster")?.attr("src")?.trim()

        // Año: Lo buscamos en el enlace dentro de div.genres.rating
        val year = document.selectFirst("div.genres.rating a[href*=\"/year/\"]")?.text()?.trim()?.toIntOrNull()

        // Plot/Descripción: div.description p o p.scroll
        val plot = document.selectFirst("div.description p, p.scroll")?.text()?.trim()

        // Géneros: Correcto con div.genres a
        val genres = document.select("div.genres a")?.mapNotNull { it.text()?.trim() }

        // Rating: Extraemos el texto después de "Rating:" en div.genres.rating
        val ratingText = document.selectFirst("div.genres.rating span:contains(Rating)")?.text()?.trim()
        val rating = ratingText?.let {
            // Extraer solo el número después de "Rating:"
            val value = Regex("""Rating:\s*([\d.]+)""").find(it)?.groupValues?.get(1)
            value?.toFloatOrNull()?.times(100)?.toInt()
        }

        // Runtime: Extraemos el texto después de "Duración:" en div.genres.rating
        val runtimeText = document.selectFirst("div.genres.rating span:contains(Duración)")?.text()?.trim()
        val runtime = runtimeText?.let {
            // Extraer solo los números y luego parsear a minutos.
            val hours = Regex("""(\d+)\s*hora(?:s)?""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = Regex("""(\d+)\s*minuto(?:s)?""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            (hours * 60) + minutes
        }

        // Determinar el tipo (Movie o TvSeries)
        val type = if (url.contains("/serie/") || document.select("div.season.main").isNotEmpty()) TvType.TvSeries else TvType.Movie

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
                this.tags = genres ?: arrayListOf() // Asegúrate de que no sea null
                this.rating = rating
                this.duration = runtime
            }
        } else {
            val episodes = ArrayList<Episode>()
            // Selector para div.season.main que contiene las temporadas
            val seasonElements = document.select("div.season.main")

            if (seasonElements.isNotEmpty()) {
                seasonElements.forEach { seasonDiv ->
                    // CORRECCIÓN: Confirmado h3#seasonTitleChange
                    val seasonNumber = seasonDiv.selectFirst("h3#seasonTitleChange")?.text()?.replace("Temporada ", "")?.trim()?.toIntOrNull() ?: 1
                    Log.d(name, "load: Procesando Temporada: $seasonNumber")

                    seasonDiv.select("div#episodelist.articlesList article.item a.itemA").forEach { epElement ->
                        val episodeLink = epElement.attr("href")?.trim()
                        val epTitle = epElement.selectFirst("h2")?.text()?.trim()
                        val epNumber = Regex("""E(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                        if (episodeLink != null && episodeLink.isNotBlank() && epNumber != null) {
                            val episodeDataJson = EpisodeLoadData(epTitle ?: "Episodio $epNumber", fixUrl(episodeLink), seasonNumber, epNumber).toJson()
                            Log.d(name, "load: Episodio encontrado: Título: $epTitle, Número: $epNumber, Enlace: $episodeLink")
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
                            Log.w(name, "load: Episodio incompleto o nulo encontrado. Título: $epTitle, Número: $epNumber, Enlace: $episodeLink")
                        }
                    }
                }
            } else {
                Log.w(name, "load: No se encontraron elementos de temporada 'div.season.main' en la página de la serie: $url. Buscando episodios planos.")
                document.select("div#episodelist.articlesList article.item a.itemA").forEach { epElement ->
                    val episodeLink = epElement.attr("href")?.trim()
                    val epTitle = epElement.selectFirst("h2")?.text()?.trim()
                    val epNumber = Regex("""E(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                    if (episodeLink != null && episodeLink.isNotBlank() && epNumber != null) {
                        val episodeDataJson = EpisodeLoadData(epTitle ?: "Episodio $epNumber", fixUrl(episodeLink), 1, epNumber).toJson()
                        Log.d(name, "load: Episodio (sin temporada explícita) encontrado: Título: $epTitle, Número: $epNumber, Enlace: $episodeLink")
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
            Log.d(name, "load: Se encontraron ${episodes.size} episodios en total.")

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.sortedWith(compareBy({it.season}, {it.episode}))
            ) {
                this.posterUrl = poster ?: ""
                this.backgroundPosterUrl = poster ?: "" // Usar el mismo póster para el fondo si no hay uno específico
                this.year = year
                this.plot = plot ?: ""
                this.tags = genres ?: arrayListOf() // Asegúrate de que no sea null
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

        // Selector para el iframe principal del reproductor
        val iframeSrc = document.selectFirst("div#play iframe")?.attr("src")?.trim()
        if (iframeSrc != null && iframeSrc.startsWith("http")) {
            Log.d(name, "loadLinks: Encontrado posible iframe principal: $iframeSrc. Intentando cargar con extractores genéricos.")
            val extractorResult = safeApiCall { loadExtractor(iframeSrc, targetUrl, subtitleCallback, callback) }
            // Corrección: El resultado de safeApiCall es Resource.Success o Resource.Failure.
            // Para verificar si se encontraron enlaces, revisamos si el resultado es Success y su valor es true.
            if (extractorResult is com.lagradost.cloudstream3.mvvm.Resource.Success && extractorResult.value == true) {
                linksFound = true
            } else {
                Log.w(name, "loadLinks: loadExtractor no encontró enlaces o falló para el iframe principal: $iframeSrc")
            }
        } else {
            Log.i(name, "loadLinks: No se encontró iframe principal válido o no es HTTP. iframeSrc: $iframeSrc")
        }

        // Búsqueda de iframes en las opciones de servidor (si existen, p. ej. debajo del reproductor)
        document.select("ul.subselect li[data-server]").forEach { serverOption ->
            val dataServerId = serverOption.attr("data-server")?.trim()
            val serverName = serverOption.selectFirst("span")?.text()?.trim()

            if (dataServerId != null && dataServerId.isNotBlank()) {
                Log.d(name, "loadLinks: Intentando loadExtractor con data-server ID: '$dataServerId' ($serverName)")
                val extractorResult = safeApiCall { loadExtractor(dataServerId, targetUrl, subtitleCallback, callback) }
                if (extractorResult is com.lagradost.cloudstream3.mvvm.Resource.Success && extractorResult.value == true) {
                    linksFound = true
                } else {
                    Log.w(name, "loadLinks: loadExtractor no encontró enlaces o falló para data-server ID: '$dataServerId'")
                }
            } else {
                Log.i(name, "loadLinks: Opción de servidor sin data-server ID válido. ID: $dataServerId, Nombre: $serverName")
            }
        }

        // Búsqueda de URLs M3U8 y VTT directamente en el script o HTML
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