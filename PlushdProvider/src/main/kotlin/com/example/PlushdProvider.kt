package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor // Esto se sigue usando para otros extractores si los hubiera, pero no para master.m3u8
import com.google.gson.Gson
import com.google.gson.JsonObject

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

    private suspend fun customGet(url: String, headers: Map<String, String> = emptyMap()): Document {
        Log.d(name, "customGet: Descargando HTML de: $url")
        return withContext(Dispatchers.IO) {
            val response = app.get(url, headers = headers)
            if (!response.isSuccessful) {
                val errorMessage = "Error HTTP ${response.code}"
                Log.e(name, "customGet: Error al descargar HTML de $url: ${response.code} - $errorMessage")
                throw Exception("Failed to load URL: ${response.code} - $errorMessage")
            }
            Jsoup.parse(response.text)
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
                return try {
                    Gson().fromJson(jsonString, EpisodeLoadData::class.java)
                } catch (e: Exception) {
                    Log.e("EpisodeLoadData", "Error parsing EpisodeLoadData JSON: $jsonString. Error: ${e.localizedMessage}")
                    null
                }
            }
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d(name, "getMainPage: Iniciando carga de la página principal para la página $page")
        val items = ArrayList<HomePageList>()

        val urls = listOf(
            Pair("Últimas Películas Agregadas", "$mainUrl/peliculas${if (page > 1) "/$page" else ""}"),
            Pair("Series Recientes", "$mainUrl/series${if (page > 1) "/$page" else ""}"),
            Pair("Animes Recientes", "$mainUrl/animes${if (page > 1) "/$page" else ""}"),
            Pair("Doramas Recientes", "$mainUrl/doramas${if (page > 1) "/$page" else ""}")
        )

        val homePageLists = urls.customApmap { (listName, url) ->
            Log.d(name, "getMainPage: Procesando URL de lista: $url")
            val tvType = when (listName) {
                "Últimas Películas Agregadas" -> TvType.Movie
                "Series Recientes" -> TvType.TvSeries
                else -> TvType.Movie
            }
            val doc = customGet(url)
            Log.d(name, "getMainPage: Documento HTML descargado de $url. Tama??o: ${doc.html().length}")

            val homeItems = doc.select("div.articlesList > article.item").mapNotNull { element ->
                val linkElement = element.selectFirst("a.itemA")
                val titleFull = element.selectFirst("h2")?.text()?.trim()
                val posterUrl = element.selectFirst("div.item__image img")?.attr("data-src")?.trim()
                    ?: element.selectFirst("div.item__image img")?.attr("src")?.trim()

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
        Log.d(name, "search: Documento HTML de búsqueda descargado. Tama??o: ${document.html().length}")

        val elements = document.select("div.articlesList > article.item")
        Log.d(name, "search: Elementos de búsqueda encontrados: ${elements.size}")

        elements.forEach { element ->
            val linkElement = element.selectFirst("a.itemA")
            val titleFull = element.selectFirst("h2")?.text()?.trim()
            val posterUrl = element.selectFirst("div.item__image img")?.attr("data-src")?.trim()
                ?: element.selectFirst("div.item__image img")?.attr("src")?.trim()
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
        Log.d(name, "load: Documento HTML de carga de detalles descargado. Tama??o: ${document.html().length}")

        val title = document.selectFirst("h1.slugh1, div.home_slider_content h2")?.text()?.trim()

        val poster = document.selectFirst("div.bg")?.attr("style")?.let { style ->
            Regex("""url\("?([^")]*)"?\)""").find(style)?.groupValues?.get(1)
        } ?: document.selectFirst("div.image-single img, div.poster-single img, img.single-poster")?.attr("data-src")?.trim()
        ?: document.selectFirst("div.image-single img, div.poster-single img, img.single-poster")?.attr("src")?.trim()

        val year = document.selectFirst("div.genres.rating a[href*=\"/year/\"]")?.text()?.trim()?.toIntOrNull()

        val plot = document.selectFirst("div.description p, p.scroll")?.text()?.trim()

        val genres: List<String> = document.select("div.genres a").mapNotNull { it.text()?.trim() }.filter { it.isNotBlank() }

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

                            val urlRegex = Regex("""/season/(\d+)/episode/(\d+)""")
                            val urlMatch = urlRegex.find(episodeLink ?: "")
                            val seasonNumberFromUrl = urlMatch?.groupValues?.get(1)?.toIntOrNull()
                            val episodeNumberFromUrl = urlMatch?.groupValues?.get(2)?.toIntOrNull()

                            val titleRegex = Regex("""(?:S(\d+))?\s*(?:E(\d+))?""")
                            val titleMatch = titleRegex.find(epTitle ?: "")
                            val seasonNumberFromTitle = titleMatch?.groupValues?.get(1)?.toIntOrNull()
                            val episodeNumberFromTitle = titleMatch?.groupValues?.get(2)?.toIntOrNull()

                            val finalSeason = seasonNumberFromUrl ?: seasonNumberFromTitle ?: 1
                            val finalEpisode = episodeNumberFromUrl ?: episodeNumberFromTitle

                            if (episodeLink != null && episodeLink.isNotBlank() && finalEpisode != null) {
                                val episodeDataJson = EpisodeLoadData(epTitle ?: "Episodio $finalEpisode", fixUrl(episodeLink), finalSeason, finalEpisode).toJson()
                                Log.d(name, "load: Episodio (temporada $finalSeason) encontrado: Título: $epTitle, Número: $finalEpisode, Enlace: $episodeLink")
                                episodes.add(
                                    newEpisode(
                                        data = episodeDataJson
                                    ) {
                                        this.name = epTitle
                                        this.season = finalSeason
                                        this.episode = finalEpisode
                                    }
                                )
                            } else {
                                Log.w(name, "load: Episodio incompleto o nulo encontrado en temporada $finalSeason. Título: $epTitle, Número: $finalEpisode, Enlace: $episodeLink")
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

                            val urlRegex = Regex("""/season/(\d+)/episode/(\d+)""")
                            val urlMatch = urlRegex.find(episodeLink ?: "")
                            val seasonNumberFromUrl = urlMatch?.groupValues?.get(1)?.toIntOrNull()
                            val episodeNumberFromUrl = urlMatch?.groupValues?.get(2)?.toIntOrNull()

                            val titleRegex = Regex("""(?:S(\d+))?\s*(?:E(\d+))?""")
                            val titleMatch = titleRegex.find(epTitle ?: "")
                            val seasonNumberFromTitle = titleMatch?.groupValues?.get(1)?.toIntOrNull()
                            val episodeNumberFromTitle = titleMatch?.groupValues?.get(2)?.toIntOrNull()

                            val finalSeason = seasonNumberFromUrl ?: seasonNumberFromTitle ?: 1
                            val finalEpisode = episodeNumberFromUrl ?: episodeNumberFromTitle

                            if (episodeLink != null && episodeLink.isNotBlank() && finalEpisode != null) {
                                val episodeDataJson = EpisodeLoadData(epTitle ?: "Episodio $finalEpisode", fixUrl(episodeLink), finalSeason, finalEpisode).toJson()
                                Log.d(name, "load: Episodio (sin temporada explícita, asumido Temporada 1) encontrado: Título: $epTitle, Número: $finalEpisode, Enlace: $episodeLink")
                                episodes.add(
                                    newEpisode(
                                        data = episodeDataJson
                                    ) {
                                        this.name = epTitle
                                        this.season = finalSeason
                                        this.episode = finalEpisode
                                    }
                                )
                            } else {
                                Log.w(name, "load: Episodio (sin temporada explícita) incompleto o nulo encontrado. Título: $epTitle, Número: $finalEpisode, Enlace: $episodeLink")
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

    // Nueva función para extraer enlaces M3U8
    private suspend fun extractM3u8Links(
        m3u8Url: String,
        sourceReferer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "extractM3u8Links: Intentando extraer de M3U8: $m3u8Url")
        return withContext(Dispatchers.IO) {
            try {
                val m3u8Response = app.get(m3u8Url, headers = headers)
                if (!m3u8Response.isSuccessful) {
                    // *** CORRECCIÓN EN ESTA LÍNEA ***
                    Log.e(name, "extractM3u8Links: Error al descargar M3U8: ${m3u8Response.code} - Fallo en la respuesta HTTP")
                    return@withContext false
                }

                val m3u8Content = m3u8Response.text
                val lines = m3u8Content.split("\n")
                var foundLinks = false

                // Asegúrate de que baseUrl termine con '/' para la resolución de URLs relativas
                val baseUrl = m3u8Url.substringBeforeLast("/") + "/"

                // Regex para capturar resolución y URL de variantes de stream
                val streamInfRegex = Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+x\d+).*?\n(https?:\/\/[^\n]+|\/[^\n]+)""")
                // Regex para capturar solo la URL si no hay RESOLUTION explícita o si es un segmento (no un manifiesto principal)
                // Asegúrate de que las URLs relativas se capturen correctamente
                val urlRegex = Regex("""^(https?:\/\/[^\n]+|\/[^\n]+)$""")


                for (i in lines.indices) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        // Concatenamos la línea actual con la siguiente para que el regex pueda encontrar la URL
                        val fullLineForRegex = line + "\n" + lines.getOrElse(i + 1) { "" }
                        val match = streamInfRegex.find(fullLineForRegex)
                        if (match != null) {
                            val resolution = match.groupValues[1]
                            val streamUrl = match.groupValues[2]
                            // Convertir URL relativa a absoluta si es necesario
                            val absoluteStreamUrl = if (streamUrl.startsWith("/")) baseUrl + streamUrl.substring(1) else streamUrl

                            val quality = when {
                                resolution.contains("1080") -> Qualities.P1080
                                resolution.contains("720") -> Qualities.P720
                                resolution.contains("480") -> Qualities.P480
                                resolution.contains("360") -> Qualities.P360
                                else -> Qualities.Unknown
                            }
                            Log.d(name, "extractM3u8Links: Encontrado stream de HLS: $resolution - $absoluteStreamUrl")
                            callback(
                                ExtractorLink(
                                    this@PlushdProvider.name, // Usar 'this@PlushdProvider.name' para el nombre del proveedor
                                    "Pelisplus HLS (${quality.name.replace("P", "")})", // Nombre de la fuente más limpio
                                    absoluteStreamUrl,
                                    sourceReferer,
                                    quality.value,
                                    headers = headers,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                            foundLinks = true
                        }
                    } else if (line.endsWith(".m3u8") || line.endsWith(".mp4") || line.endsWith(".ts")) {
                        // Captura directas de m3u8, mp4 o ts si no están en EXT-X-STREAM-INF,
                        // útil si la URL es directamente un stream final.
                        val match = urlRegex.find(line)
                        if (match != null) {
                            val mediaUrl = match.groupValues[1]
                            val absoluteMediaUrl = if (mediaUrl.startsWith("/")) baseUrl + mediaUrl.substring(1) else mediaUrl
                            Log.d(name, "extractM3u8Links: Encontrado media directa en M3U8: $absoluteMediaUrl")
                            callback(
                                ExtractorLink(
                                    this@PlushdProvider.name,
                                    "Pelisplus Direct",
                                    absoluteMediaUrl,
                                    sourceReferer,
                                    Qualities.Unknown.value,
                                    headers = headers,
                                    type = if (absoluteMediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                )
                            )
                            foundLinks = true
                        }
                    }
                }
                return@withContext foundLinks
            } catch (e: Exception) {
                Log.e(name, "extractM3u8Links: Error al procesar M3U8 de $m3u8Url. Error: ${e.localizedMessage}")
                e.printStackTrace()
                return@withContext false
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

        val videoHeaders = mapOf(
            "Referer" to targetUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )


        val document = customGet(targetUrl, headers = videoHeaders)
        Log.d(name, "loadLinks: Documento HTML de enlaces descargado. Tama??o: ${document.html().length}")

        var linksFound = false

        val serverOptions = document.select("ul.subselect li[data-server]")
        Log.d(name, "loadLinks: Encontrados ${serverOptions.size} elementos de servidor.")

        for (serverOption in serverOptions) {
            val dataServerId = serverOption.attr("data-server")?.trim()
            val currentServerName = serverOption.selectFirst("span")?.text()?.trim()

            if (dataServerId != null && dataServerId.isNotBlank()) {
                try {
                    val decodedBytes = Base64.decode(dataServerId, Base64.DEFAULT)
                    val decodedServerId = String(decodedBytes)
                    Log.d(name, "loadLinks: Servidor: '$currentServerName', Data-server decodificado: '$decodedServerId'")

                    val videoUrlFromDataServer = "https://ww3.pelisplus.to/master.m3u8?t=$decodedServerId"
                    Log.d(name, "loadLinks: Intentando cargar video desde Data-server URL: $videoUrlFromDataServer")

                    // AQUÍ USAMOS extractM3u8Links EN LUGAR DE loadExtractor
                    val extracted = extractM3u8Links(videoUrlFromDataServer, targetUrl, videoHeaders, callback)
                    if (extracted) {
                        linksFound = true
                        Log.d(name, "loadLinks: extractM3u8Links tuvo éxito para data-server: $decodedServerId ($currentServerName) con URL: $videoUrlFromDataServer")
                    } else {
                        Log.w(name, "loadLinks: extractM3u8Links no encontró enlaces para el data-server: $decodedServerId ($currentServerName) con URL: $videoUrlFromDataServer.")
                    }
                } catch (e: Exception) {
                    Log.e(name, "loadLinks: Error al decodificar Base64 o procesar servidor: '$dataServerId' ($currentServerName). Error: ${e.localizedMessage}")
                    e.printStackTrace()
                }
            } else {
                Log.w(name, "loadLinks: Opción de servidor sin data-server ID válido. ID: $dataServerId, Nombre: $currentServerName")
            }
        }


        val jwPlayerSetupRegex = Regex("""jwplayer\(\s*['"](?:[^"']+)['"]\s*\)\.setup\(\s*(\{[\s\S]*?\}\s*)\);""")
        val scriptElements = document.select("script")

        for (script in scriptElements) {
            val scriptContent = script.html()
            Log.d(name, "loadLinks: Contenido de script encontrado (primeros ${scriptContent.length.coerceAtMost(1000)} chars): ${scriptContent.take(1000)}")

            val match = jwPlayerSetupRegex.find(scriptContent)

            if (match != null) {
                val setupConfigJsonString = match.groupValues[1]
                Log.d(name, "loadLinks: Encontrada configuración de JW Player: $setupConfigJsonString")

                val fileRegex = Regex("""file\s*:\s*['"](https?:\/\/[^"']+\.(?:m3u8|mp4)[^"']*)['"]""")
                val fileMatch = fileRegex.find(setupConfigJsonString)
                val mediaUrl = fileMatch?.groupValues?.get(1)

                if (mediaUrl != null) {
                    Log.d(name, "loadLinks: Encontrado Media URL (M3U8/MP4) en JW Player setup: $mediaUrl")

                    // Aquí, si el mediaUrl es un M3U8, también lo pasamos a extractM3u8Links.
                    // Si es un MP4 directo, lo enviamos directamente al callback.
                    if (mediaUrl.contains(".m3u8")) {
                        val extracted = extractM3u8Links(mediaUrl, targetUrl, videoHeaders, callback)
                        if (extracted) {
                            linksFound = true
                            Log.d(name, "loadLinks: extractM3u8Links tuvo éxito para JW Player M3U8: $mediaUrl")
                        } else {
                            Log.w(name, "loadLinks: extractM3u8Links no encontró enlaces para JW Player M3U8: $mediaUrl.")
                        }
                    } else {
                        // Si es un MP4 directo, lo agregamos como ExtractorLink
                        callback(
                            ExtractorLink(
                                this.name,
                                "Pelisplus (Directo)",
                                mediaUrl,
                                targetUrl,
                                Qualities.Unknown.value,
                                headers = videoHeaders,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        linksFound = true
                        Log.d(name, "loadLinks: Encontrado MP4 directo en JW Player setup: $mediaUrl")
                    }
                } else {
                    Log.w(name, "loadLinks: No se encontró URL de M3U8/MP4 en la configuración de JW Player 'file'.")
                }

                val tracksRegex = Regex("""tracks\s*:\s*(\[[\s\S]*?\])""")
                val tracksMatch = tracksRegex.find(setupConfigJsonString)
                val tracksJsonString = tracksMatch?.groupValues?.get(1)

                if (tracksJsonString != null) {
                    Log.d(name, "loadLinks: Encontrados subtítulos en JW Player setup: $tracksJsonString")
                    val vttFileRegex = Regex("""\{[^}]*?file\s*:\s*['"](https?:\/\/[^"']+\.vtt[^"']*)['"][^}]*?(?:,\s*label\s*:\s*['"]([^"']+)['"])?[^}]*?\}""")
                    vttFileRegex.findAll(tracksJsonString).forEach { vttMatch ->
                        val vttUrl = vttMatch.groupValues[1]
                        val vttLabel = vttMatch.groupValues.getOrNull(2) ?: "Unknown"
                        Log.d(name, "loadLinks: Subtítulo VTT encontrado: $vttLabel - $vttUrl")
                        subtitleCallback(SubtitleFile(vttLabel, vttUrl))
                        linksFound = true
                    }
                } else {
                    Log.i(name, "loadLinks: No se encontraron subtítulos en la configuración de JW Player.")
                }
            }
        }

        val playerTrDiv = document.selectFirst("div#player-tr[data-tr]")
        if (playerTrDiv != null) {
            val dataTrEncoded = playerTrDiv.attr("data-tr")?.trim()
            if (!dataTrEncoded.isNullOrBlank()) {
                try {
                    val decodedDataTr = String(Base64.decode(dataTrEncoded, Base64.DEFAULT))
                    Log.d(name, "loadLinks: Encontrado y decodificado data-tr del reproductor principal: '$decodedDataTr'")

                    val videoUrl = "https://ww3.pelisplus.to/master.m3u8?t=$decodedDataTr"
                    Log.d(name, "loadLinks: Intentando cargar video desde URL: $videoUrl")

                    // AQUÍ TAMBIÉN USAMOS extractM3u8Links EN LUGAR DE loadExtractor
                    val extracted = extractM3u8Links(videoUrl, targetUrl, videoHeaders, callback)
                    if (extracted) {
                        linksFound = true
                        Log.d(name, "loadLinks: extractM3u8Links tuvo éxito para la URL de video: $videoUrl")
                    } else {
                        Log.w(name, "loadLinks: extractM3u8Links no encontró enlaces para la URL de video: $videoUrl.")
                    }

                } catch (e: Exception) {
                    Log.e(name, "loadLinks: Error al procesar data-tr o cargar video. Error: ${e.localizedMessage}")
                    e.printStackTrace()
                }
            } else {
                Log.w(name, "loadLinks: Atributo 'data-tr' del reproductor principal está vacío.")
            }
        } else {
            Log.i(name, "loadLinks: No se encontró el div del reproductor principal con id 'player-tr' y atributo 'data-tr'.")
        }

        Log.d(name, "loadLinks: Proceso de carga de enlaces finalizado. Links encontrados: $linksFound")
        return linksFound
    }
}