package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Log
import java.lang.Exception
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import android.util.Base64 // Aunque no lo usaremos para decodificar, sí para codificar si fuera necesario.

private fun String.toUrlSlug(): String {
    return this
        .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
        .replace(" ", "-")
        .lowercase()
        .replace(Regex("-+"), "-")
        .trim('-')
}

class PlayhubProvider : MainAPI() {

    override var mainUrl = "https://playhublite.com"
    override var name = "Playhub"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    companion object  {
        private const val playhubAPI = "https://v3.playhublite.com/api/"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        private val playhubHeaders = mapOf(
            "Host" to "v3.playhublite.com",
            "User-Agent" to DESKTOP_USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-ES,es;q=0.6",
            "Authorization" to "Bearer null",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to "https://playhublite.com",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Referer" to "https://playhublite.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "TE" to "trailers",
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\""
        )
    }

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w1280/$link" else link
    }

    // --- Clases de datos para la API principal (sin cambios) ---
    data class PlayHubMainPageResponse(
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("data") val data: ArrayList<PlayHubMovieData>? = arrayListOf(),
        @JsonProperty("first_page_url") val firstPageUrl: String? = null,
        @JsonProperty("from") val from: Int? = null,
        @JsonProperty("next_page_url") val nextPageUrl: String? = null,
        @JsonProperty("path") val path: String? = null,
        @JsonProperty("per_page") val perPage: Int? = null,
        @JsonProperty("prev_page_url") val prevPage_url: String? = null
    )
    data class PlayHubMovieData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("last_air_date") val lastAirDate: String? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val apiCategoryUrl = "${playhubAPI}movies"
        Log.d("PlayHubLite", "getMainPage: Intentando obtener de la API: $apiCategoryUrl?page=$page")
        Log.d("PlayHubLite", "getMainPage: Cabeceras: $playhubHeaders")

        val response = try {
            val res = app.get("$apiCategoryUrl?page=$page", headers = playhubHeaders)
            Log.d("PlayHubLite", "getMainPage: Código de estado de la respuesta cruda: ${res.code}")
            Log.d("PlayHubLite", "getMainPage: Cuerpo de la respuesta cruda (primeros 500 chars): ${res.text.take(500)}")
            res.parsed<PlayHubMainPageResponse>()
        } catch (e: Exception) {
            Log.e("PlayHubLite", "getMainPage: ERROR al analizar o obtener JSON de $apiCategoryUrl: ${e.message}", e)
            Log.e("PlayHubLite", "getMainPage: Por favor, verifica si la API de Playhub en $playhubAPI aún funciona y devuelve JSON.")
            throw ErrorLoadingException("No se pudo cargar la página principal: ${e.message}")
        }

        val homeItems = response.data?.mapNotNull { info ->
            val title = info.title ?: info.name ?: ""
            val id = info.id?.toString() ?: return@mapNotNull null
            val posterPath = info.posterPath
            val poster = getImageUrl(posterPath)
            val tvType = if (!info.firstAirDate.isNullOrEmpty()) TvType.TvSeries else TvType.Movie
            val dataUrl = if (tvType == TvType.Movie) "$mainUrl/movies/$id" else "$mainUrl/series/$id"
            newMovieSearchResponse(
                title,
                dataUrl,
                tvType
            ) {
                this.posterUrl = poster
            }
        }

        if (!homeItems.isNullOrEmpty()) {
            items.add(HomePageList("Contenido Principal", homeItems))
        } else {
            Log.w("PlayHubLite", "getMainPage: No se encontraron elementos para la lista 'Contenido Principal'.")
        }

        if (items.size <= 0) {
            Log.e("PlayHubLite", "getMainPage: No se crearon HomePageLists a partir de los datos de la API.")
            throw ErrorLoadingException("No se encontraron datos de la API.")
        }

        val hasNextPage = response.nextPageUrl != null
        return HomePageResponse(items, hasNextPage)
    }

    // --- Clases de datos para Búsqueda y Carga (sin cambios) ---
    data class PlayhubSearchMain (
        @JsonProperty("movies" ) var movies : ArrayList<PlayhubSearchInfo>? = arrayListOf(),
        @JsonProperty("series" ) var series : ArrayList<PlayhubSearchInfo>? = arrayListOf()
    )
    data class PlayhubSearchInfo (
        @JsonProperty("id"               ) var id             : Int?    = null,
        @JsonProperty("name"             ) var name           : String? = null,
        @JsonProperty("original_name"    ) var originalName   : String? = null,
        @JsonProperty("poster_path"      ) var posterPath     : String? = null,
        @JsonProperty("backdrop_path"    ) var backdropPath   : String? = null,
        @JsonProperty("logo"             ) var logo           : String? = null,
        @JsonProperty("episode_run_time" ) var episodeRunTime : String? = null,
        @JsonProperty("first_air_date"   ) var firstAirDate   : String? = null,
        @JsonProperty("in_production"    ) var inProduction   : Int?    = null,
        @JsonProperty("last_air_date"    ) var lastAirDate    : String? = null,
        @JsonProperty("overview"         ) var overview       : String? = null,
        @JsonProperty("status"           ) var status         : String? = null,
        @JsonProperty("vote_average"     ) var voteAverage    : Double? = null,
        @JsonProperty("created_at"       ) var createdAt      : String? = null,
        @JsonProperty("updated_at"       ) var updatedAt      : String? = null,
        @JsonProperty("view_count"       ) var viewCount      : Int?    = null,
        @JsonProperty("original_title"   ) var originalTitle  : String? = null,
        @JsonProperty("title"            ) var title          : String? = null,
        @JsonProperty("release_date"     ) var releaseDate    : String? = null,
        @JsonProperty("runtime"          ) var runtime        : String? = null,
    )

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "${playhubAPI}search?q=$query"
        val search = ArrayList<SearchResponse>()
        val res = app.get(url, headers = playhubHeaders).parsed<PlayhubSearchMain>()

        res.movies?.map {
            val title = it.title ?: it.originalTitle ?: ""
            val posterinfo = it.posterPath ?: ""
            val poster = getImageUrl(posterinfo)
            val id = it.id
            val href = "$mainUrl/movies/$id"
            search.add(
                newMovieSearchResponse(title, href, TvType.Movie){
                    this.posterUrl = poster
                })
        }
        res.series?.map {
            val title = it.name ?: it.originalName ?: ""
            val posterinfo = it.posterPath ?: ""
            val poster = getImageUrl(posterinfo)
            val id = it.id
            val href = "$mainUrl/series/$id"
            search.add(
                newTvSeriesSearchResponse(title,href, TvType.TvSeries) {
                    this.posterUrl = poster
                })
        }
        return search
    }

    data class PlayhubLoadMain (
        @JsonProperty("id"              ) var id              : Int?                       = null,
        @JsonProperty("original_title"  ) var originalTitle   : String?                    = null,
        @JsonProperty("title"           ) var title           : String?                    = null,
        @JsonProperty("backdrop_path"   ) var backdropPath    : String?                    = null,
        @JsonProperty("logo"            ) var logo            : String?                    = null,
        @JsonProperty("poster_path"     ) var posterPath      : String?                    = null,
        @JsonProperty("overview"        ) var overview        : String?                    = null,
        @JsonProperty("release_date"    ) var releaseDate     : String?                    = null,
        @JsonProperty("runtime"         ) var runtime         : String?                    = null,
        @JsonProperty("status"          ) var status          : String?                    = null,
        @JsonProperty("vote_average"    ) var voteAverage     : Double?                    = null,
        @JsonProperty("created_at"      ) var createdAt       : String?                    = null,
        @JsonProperty("updated_at"      ) var updatedAt       : String?                    = null,
        @JsonProperty("view_count"      ) var viewCount       : Int?                       = null,
        @JsonProperty("recommendations" ) var recommendations : ArrayList<PlayhubRecommendations>? = arrayListOf(),
        @JsonProperty("categories"      ) var categories      : ArrayList<Categories>?      = arrayListOf(),
        @JsonProperty("seasons"         ) var seasons        : ArrayList<Seasons>?   = arrayListOf(),
        @JsonProperty("name"            ) var name           : String?               = null,
        @JsonProperty("original_name"   ) var originalName   : String?               = null,
        @JsonProperty("episode_run_time" ) var episodeRunTime : String?               = null,
        @JsonProperty("first_air_date"   ) var firstAirDate   : String?               = null,
        @JsonProperty("in_production"    ) var inProduction   : Int?                  = null,
        @JsonProperty("last_air_date"    ) var lastAirDate    : String?               = null,
    )
    data class PlayhubRecommendations (
        @JsonProperty("id"            ) var id           : Int?    = null,
        @JsonProperty("title"         ) var title        : String? = null,
        @JsonProperty("poster_path"   ) var posterPath   : String? = null,
        @JsonProperty("backdrop_path" ) var backdropPath : String? = null,
        @JsonProperty("name"          ) var name         : String? = null,
    )
    data class Categories (
        @JsonProperty("id"    ) var id    : Int?    = null,
        @JsonProperty("name"  ) var name  : String? = null,
    )
    data class Seasons (
        @JsonProperty("id"            ) var id           : Int? = null,
        @JsonProperty("serie_id"      ) var serieId       : Int? = null,
        @JsonProperty("season_number" ) var seasonNumber : Int? = null
    )
    data class SeasonsInfo (
        @JsonProperty("id"            ) var id           : Int?                = null,
        @JsonProperty("serie_id"      ) var serieId      : Int?                = null,
        @JsonProperty("season_number" ) var seasonNumber : Int?                = null,
        @JsonProperty("episodes"      ) var episodes     : ArrayList<EpisodesInfo>? = arrayListOf()
    )
    data class EpisodesInfo (
        @JsonProperty("id"             ) var id            : Int?    = null,
        @JsonProperty("serie_id"       ) var serieId       : String? = null,
        @JsonProperty("season_id"      ) var seasonId      : Int?    = null,
        @JsonProperty("episode_number" ) var episodeNumber : Int?    = null,
        @JsonProperty("season_number"  ) var seasonNumber  : Int?    = null,
        @JsonProperty("air_date"       ) var airDate       : String? = null,
        @JsonProperty("name"           ) var name          : String? = null,
        @JsonProperty("overview"       ) var overview      : String? = null,
        @JsonProperty("still_path"     ) var stillPath     : String? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val initialTypeFromUrl = if (url.contains("/movies/")) TvType.Movie else TvType.TvSeries
        val id = url.substringAfterLast("/")
        if (id.isBlank()) {
            Log.e("PlayHubLite", "load: ID no pudo ser extraído de la URL: $url")
            return null
        }

        var apiDetailUrl = if (initialTypeFromUrl == TvType.Movie) "${playhubAPI}movies/$id" else "${playhubAPI}series/$id"
        Log.d("PlayHubLite", "load: Solicitando detalles de la API para ID $id con URL inicial: $apiDetailUrl")

        val res = try {
            var apiResponse = app.get(apiDetailUrl, headers = playhubHeaders)
            if (apiResponse.code == 404) {
                val alternateType = if (initialTypeFromUrl == TvType.Movie) TvType.TvSeries else TvType.Movie
                apiDetailUrl = if (alternateType == TvType.Movie) "${playhubAPI}movies/$id" else "${playhubAPI}series/$id"
                apiResponse = app.get(apiDetailUrl, headers = playhubHeaders)
                if (apiResponse.code == 200) apiResponse.parsed<PlayhubLoadMain>() else throw Exception("Ambas URLs de la API fallaron o dieron 404.")
            } else {
                apiResponse.parsed<PlayhubLoadMain>()
            }
        } catch (e: Exception) {
            Log.e("PlayHubLite", "load: ERROR al obtener o parsear detalles: ${e.message}", e)
            return null
        }

        val title = res.title ?: res.originalTitle ?: res.name ?: res.originalName ?: ""
        val plot = res.overview ?: ""
        val poster = getImageUrl(res.posterPath)
        val backposter = getImageUrl(res.backdropPath)
        val tags = res.categories?.mapNotNull { it.name }
        val episodes = ArrayList<Episode>()
        val recs = ArrayList<SearchResponse>()

        val actualType = if (!res.firstAirDate.isNullOrEmpty()) TvType.TvSeries else TvType.Movie
        Log.d("PlayHubLite", "load: Tipo de contenido determinado por API: $actualType")

        if (actualType == TvType.TvSeries) {
            Log.d("PlayHubLite", "load: Procesando temporadas para series.")
            res.seasons?.apmap { mainInfo ->
                val seriesID = mainInfo.serieId
                val seasonNum = mainInfo.seasonNumber
                if (seriesID != null && seasonNum != null) {
                    val seasonApiUrl = "${playhubAPI}seasons/$seriesID/$seasonNum"
                    Log.d("PlayHubLite", "load: Solicitando detalles de temporada: $seasonApiUrl")

                    val seasonres = try {
                        val seasonApiResponse = app.get(seasonApiUrl, headers = playhubHeaders)
                        Log.d("PlayHubLite", "load: Código de estado de temporada $seasonNum: ${seasonApiResponse.code}")
                        seasonApiResponse.parsed<SeasonsInfo>()
                    } catch (e: Exception) {
                        Log.e("PlayHubLite", "load: ERROR al obtener o parsear temporada $seasonApiUrl: ${e.message}", e)
                        null
                    }

                    if (seasonres == null || seasonres.episodes.isNullOrEmpty()) {
                        Log.w("PlayHubLite", "load: No se encontraron episodios para la temporada $seasonNum.")
                        return@apmap
                    }

                    Log.d("PlayHubLite", "load: Temporada $seasonNum obtenida. Procesando ${seasonres.episodes?.size} episodios.")
                    seasonres.episodes?.apmap { ep ->
                        val eptitle = ep.name
                        val epthumb = getImageUrl(ep.stillPath)
                        val epPlot = ep.overview
                        val epNum = ep.episodeNumber
                        val airDate = ep.airDate
                        val seriesTitleSlug = res.name?.toUrlSlug()
                        val episodeSourceDataUrl = if (!seriesTitleSlug.isNullOrBlank() && seriesID != null && seasonNum != null && epNum != null) {
                            // Esta es la URL que loadLinks recibirá. Necesitamos el ID de la serie, temporada y episodio.
                            // Formato: mainUrl/series/SERIE_ID-SEASON_NUM-EPISODE_NUM/titulo-slug
                            "$mainUrl/series/$seriesID-$seasonNum-$epNum/$seriesTitleSlug"
                        } else {
                            "$mainUrl/series/${seriesID?:""}/season/${seasonNum?:""}/episode/${epNum?:""}"
                        }

                        Log.d("PlayHubLite", "load: Añadiendo episodio S${seasonNum}E${epNum}: $eptitle con dataUrl para loadLinks: $episodeSourceDataUrl")

                        episodes.add(newEpisode(episodeSourceDataUrl) {
                            this.name = eptitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = epthumb
                            this.description = epPlot
                            addDate(airDate)
                        })
                    }
                }
            }
        }

        res.recommendations?.map {
            val rectitle = it.title ?: it.name ?: ""
            val recid = it.id
            val recposter = getImageUrl(it.posterPath)
            val recType = if (it.name != null && it.title == null) TvType.TvSeries else TvType.Movie
            val recUrl = if (recType == TvType.Movie) "$mainUrl/movies/$recid" else "$mainUrl/series/$recid"
            recs.add(if (recType == TvType.Movie) newMovieSearchResponse(rectitle, recUrl, TvType.Movie) { this.posterUrl = recposter }
            else newTvSeriesSearchResponse(rectitle, recUrl, TvType.TvSeries) { this.posterUrl = recposter })
        }

        return when (actualType) {
            TvType.TvSeries -> newTvSeriesLoadResponse(title, url, actualType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backposter
                this.plot = plot
                this.tags = tags
                this.recommendations = recs
            }
            TvType.Movie -> {
                val movieTitleSlug = res.title?.toUrlSlug()
                val movieSourceDataUrl = if (!movieTitleSlug.isNullOrBlank() && id != null) {
                    "$mainUrl/movies/$id-$movieTitleSlug"
                } else {
                    "$mainUrl/movies/$id"
                }
                Log.d("PlayHubLite", "load: URL de datos de fuente para película: $movieSourceDataUrl")
                newMovieLoadResponse(title, url, actualType, movieSourceDataUrl) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.backgroundPosterUrl = backposter
                    this.tags = tags
                    this.recommendations = recs
                }
            }
            else -> null
        }
    }

    // --- Data class ServersInfo ya no es necesaria si el file_code y hash vienen del JS directo ---
    // data class ServersInfo(...)
    // data class Source(...)


    // Función auxiliar para desofuscar el JS y extraer los valores
    // Esta función intentará replicar el comportamiento de un "packer" JS.
    private fun unpackPlayhubScript(packedScript: String): String? {
        // La regex para extraer los parámetros de la función eval(function(p,a,c,k,e,d){...})('packedString',a,c,k,e,d);
        // Queremos 'packedString', a, c, k.
        // Regex para capturar los argumentos principales del packer JS
        val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'(?:,.+?)?\)\)""")
        val matchResult = packerRegex.find(packedScript)

        if (matchResult == null || matchResult.groupValues.size < 5) {
            Log.e("PlayHubLite", "unpackPlayhubScript: No se pudo extraer los parámetros del packer.")
            return null
        }

        val p = matchResult.groupValues[1] // La cadena packed (la que tiene los números ofuscados)
        val a = matchResult.groupValues[2].toIntOrNull() ?: return null // Base para conversión numérica
        val c = matchResult.groupValues[3].toIntOrNull() ?: return null // Contador
        val kRaw = matchResult.groupValues[4] // La cadena de palabras clave separadas por '|'

        val k = kRaw.split('|').toTypedArray() // Array de palabras clave

        Log.d("PlayHubLite", "unpackPlayhubScript: Extracted p length: ${p.length}, a: $a, c: $c, k length: ${k.size}")

        var unpackedP = p
        try {
            for (i in (c - 1) downTo 0) {
                // Convertir el índice 'i' a la base 'a' (como lo hace el ofuscador en JS)
                val encodedIndex = if (i < a) {
                    i.toString(a)
                } else {
                    // Esta parte es la función 'e' del ofuscador: (c<a?'':e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))
                    // Simplificando para nuestros índices numéricos, solo necesitamos el toString(a)
                    // Si el ofuscador usa `e` para convertir índices a strings y esos strings son las 'variables' a reemplazar,
                    // como '0', '1', '2', etc. o 'a', 'b', 'c', etc.
                    // Para este packer específico, parece que usa c.toString(a) directamente.
                    i.toString(a)
                }

                if (k.size > i && k[i].isNotEmpty()) { // Asegurarse de que el índice es válido y la palabra no está vacía
                    unpackedP = unpackedP.replace(Regex("\\b$encodedIndex\\b"), k[i])
                }
            }
        } catch (e: Exception) {
            Log.e("PlayHubLite", "unpackPlayhubScript: Error durante el desempaquetado: ${e.message}", e)
            return null
        }
        return unpackedP
    }


    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("PlayHubLite", "loadLinks - Data de entrada (URL de la página del reproductor de Playhublite): $data")

        if (data.isBlank()) {
            Log.e("PlayHubLite", "loadLinks: La URL de la página del reproductor de Playhublite está vacía.")
            return false
        }

        val pageHtml = try {
            app.get(data, headers = mapOf("User-Agent" to DESKTOP_USER_AGENT)).text
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks: ERROR al obtener el HTML de la página: ${data}. ${e.message}", e)
            return false
        }

        // 1. Extraer el script ofuscado del HTML
        // Buscamos la etiqueta <script> que contiene el patrón 'eval(function(p,a,c,k,e,d){'
        val scriptRegex = Regex("""<script>[\s\S]*?(eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'(?:,.+?)?\)\))\s*?</script>""")
        val scriptMatch = scriptRegex.find(pageHtml)

        val fullPackedScript = scriptMatch?.groupValues?.getOrNull(1)

        if (fullPackedScript.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: No se encontró el script ofuscado principal en el HTML. HTML size: ${pageHtml.length}")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: Script ofuscado encontrado. Longitud: ${fullPackedScript.length}")

        // 2. Desofuscar el script
        val unpackedScript = unpackPlayhubScript(fullPackedScript)
        if (unpackedScript.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: Falló el desempaquetado del script.")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: Script desempaquetado exitosamente. Primeros 500 chars: ${unpackedScript.take(500)}")


        // 3. Extraer file_code y hash del script desempaquetado
        // Buscamos el patrón de la URL dhcplay.com/dl?op=view...
        // Ejemplo de lo que buscamos: "https://dhcplay.com/dl?op=view&file_code=wz3y6l0d86u9&hash=47854908-6-184-1750729834-f830544cd81a06aeee53f67433526cf4&embed=1&referer=&adb=1"
        val dhcplayUrlRegex = Regex("""(https?://dhcplay\.com/dl\?op=view&file_code=([^&]+)&hash=([^&]+)(?:[^"']*))""")
        val dhcplayUrlMatch = dhcplayUrlRegex.find(unpackedScript)

        val fileCode = dhcplayUrlMatch?.groupValues?.getOrNull(2)
        val hash = dhcplayUrlMatch?.groupValues?.getOrNull(3)
        val fullVideoUrl = dhcplayUrlMatch?.groupValues?.getOrNull(1) // Captura la URL completa para el referer

        if (fileCode.isNullOrBlank() || hash.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: No se pudo extraer file_code o hash del script desempaquetado. Unpacked script sample: ${unpackedScript.take(500)}")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: File Code extraído: $fileCode, Hash extraído: $hash")

        // 4. Continuar con la obtención del M3U8 de dhcplay.com
        val dhcplayReferer = "https://dhcplay.com/e/$fileCode" // O, si fullVideoUrl es más específico: fullVideoUrl
        val dhcplayApiUrl = "https://dhcplay.com/dl?op=view&file_code=$fileCode&hash=$hash&embed=1&referer=playhublite.com&adb=1" // Usamos playhublite.com como referer aquí

        val dhcplayHeaders = mapOf(
            "Host" to "dhcplay.com",
            "User-Agent" to DESKTOP_USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "es-ES,es;q=0.6",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Priority" to "u=1, i",
            "Referer" to dhcplayReferer, // Referer clave para dhcplay.com
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Storage-Access" to "active",
            "Sec-GPC" to "1",
            "X-Requested-With" to "XMLHttpRequest",
        )

        Log.d("PlayHubLite", "loadLinks: Realizando XHR a dhcplay.com (M3U8 JSON) a: $dhcplayApiUrl")
        Log.d("PlayHubLite", "loadLinks: Cabeceras para dhcplay.com XHR: $dhcplayHeaders")

        val dhcplayResponse = try {
            val res = app.get(dhcplayApiUrl, headers = dhcplayHeaders)
            Log.d("PlayHubLite", "loadLinks: Código de estado de dhcplay.com XHR: ${res.code}")
            res.text
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks: ERROR en la solicitud a dhcplay.com: ${e.message}", e)
            return false
        }
        Log.d("PlayHubLite", "loadLinks: Respuesta dhcplay.com (M3U8 JSON/Script): ${dhcplayResponse.take(500)}")

        // Se mantiene la misma lógica para extraer la URL M3U8 del JSON de dhcplay.com
        val m3u8UrlRegex = Regex("\"file\":\"(https?://[^\"?]+\\.m3u8[^\"?]*)\"")
        val m3u8UrlMatch = m3u8UrlRegex.find(dhcplayResponse)
        val m3u8FullUrl = m3u8UrlMatch?.groupValues?.get(1)

        if (m3u8FullUrl.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: No se pudo extraer la URL M3U8 de la respuesta de dhcplay.com. Respuesta: ${dhcplayResponse.take(200)}")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: URL M3u8 extraída: $m3u8FullUrl")

        val cdnBaseUrlMatch = Regex("(https?://[^/]+)/hls2/").find(m3u8FullUrl)
        val cdnBaseUrl = cdnBaseUrlMatch?.groupValues?.get(1)
        val finalM3u8Url = m3u8FullUrl

        Log.d("PlayHubLite", "loadLinks: URL final del M3U8 para callback: $finalM3u8Url")

        val m3u8Headers = mapOf(
            "Referer" to "https://dhcplay.com/", // El Referer para el M3U8 debe ser la URL del reproductor (dhcplay.com)
            "Origin" to "https://dhcplay.com",
            "User-Agent" to DESKTOP_USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "es-ES,es;q=0.6",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-GPC" to "1",
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\""
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Playhub",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://dhcplay.com/"
                this.quality = Qualities.Unknown.value
                this.headers = m3u8Headers
            }
        )

        // Subtítulos
        if (cdnBaseUrl != null && !fileCode.isNullOrBlank()) {
            val subtitleLanguages = mapOf(
                "spa" to "Español",
                "eng" to "English",
                "ger" to "Deutsch",
                "jpn" to "日本語",
                "por" to "Português"
            )

            // Buscar la parte del path del VTT en la URL del M3U8
            // Ej: "https://.../hls2/01/09571/wz3y6l0d86u9_master.m3u8"
            // Queremos extraer "01/09571"
            val vttPathSegmentMatch = Regex("/hls2/([^/]+/[^/]+)/").find(m3u8FullUrl)
            val vttPathSegment = vttPathSegmentMatch?.groupValues?.get(1)

            subtitleLanguages.forEach { (langCode, langName) ->
                val subtitleUrl = if (!vttPathSegment.isNullOrBlank()) {
                    "$cdnBaseUrl/vtt/$vttPathSegment/${fileCode}_${langCode}.vtt"
                } else {
                    "$cdnBaseUrl/vtt/${fileCode}_${langCode}.vtt" // Fallback si no se encuentra el segmento de ruta
                }

                Log.d("PlayHubLite", "loadLinks: Intentando añadir subtítulo: $subtitleUrl")
                subtitleCallback.invoke(
                    SubtitleFile(
                        langName,
                        subtitleUrl
                    )
                )
            }
        } else {
            Log.w("PlayHubLite", "loadLinks: No se pudo determinar la URL base del CDN o fileCode para los subtítulos.")
        }
        return true
    }
}