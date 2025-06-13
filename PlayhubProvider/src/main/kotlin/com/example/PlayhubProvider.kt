package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils // Importación de AppUtils para parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log // Importación para usar Log.d y Log.e

// Clase principal del proveedor para Playhub
class PlayhubProvider : MainAPI() {

    // Configuración básica del proveedor
    override var mainUrl = "https://playhublite.com" // URL principal del sitio web
    override var name = "Playhub" // Nombre del proveedor
    override var lang = "es" // Idioma del contenido
    override val hasQuickSearch = false // No soporta búsqueda rápida
    override val hasMainPage = true // Tiene página principal
    override val hasChromecastSupport = true // Soporta Chromecast
    override val hasDownloadSupport = true // Soporta descargas
    override val supportedTypes = setOf( // Tipos de contenido soportados
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    // Objeto compañero para constantes y propiedades estáticas
    companion object  {
        private const val playhubAPI = "http://v3.playhublite.com/api/" // URL de la API de Playhub
        private val playhubHeaders = mapOf( // Cabeceras HTTP para las solicitudes a la API
            "Host" to "v3.playhublite.com",
            "User-Agent" to USER_AGENT, // USER_AGENT es una constante global de Cloudstream
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.5",
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
        )
    }

    // Función para obtener URL de imagen, adaptando si es un path de TMDB
    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w1280/$link" else link
    }

    // --- DATA CLASSES para la Página Principal (getMainPage) ---
    data class PlayHubMainPageResponse(
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("data") val data: ArrayList<PlayHubMovieData>? = arrayListOf(),
        @JsonProperty("first_page_url") val firstPageUrl: String? = null,
        @JsonProperty("from") val from: Int? = null,
        @JsonProperty("next_page_url") val nextPageUrl: String? = null,
        @JsonProperty("path") val path: String? = null,
        @JsonProperty("per_page") val perPage: Int? = null,
        @JsonProperty("prev_page_url") val prevPageUrl: String? = null
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
    // --- FIN DATA CLASSES para la Página Principal ---

    // Sobrescribe la función para obtener la página principal
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val apiCategoryUrl = "${playhubAPI}movies"

        Log.d("PlayHubLite", "getMainPage: Intentando obtener de la API: $apiCategoryUrl?page=$page")
        Log.d("PlayHubLite", "getMainPage: Cabeceras: $playhubHeaders")

        val response = try {
            val res = app.get("$apiCategoryUrl?page=$page", headers = playhubHeaders)
            Log.d("PlayHubLite", "getMainPage: Código de estado de la respuesta cruda: ${res.code}")
            Log.d("PlayHubLite", "getMainPage: Cuerpo de la respuesta cruda (primeros 500 chars): ${res.text.take(500)}")

            // Usa app.parsed<T>() para parsear el JSON, aprovechando el ObjectMapper interno de Cloudstream
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

            // Determina el tipo de TV (película o serie) basándose en la fecha de lanzamiento
            val tvType = if (!info.releaseDate.isNullOrEmpty()) TvType.Movie else TvType.TvSeries

            // Construye la URL de los datos para la carga detallada
            val dataUrl = if (tvType == TvType.Movie) "$mainUrl/movies/$id" else "$mainUrl/series/$id"

            // Crea un nuevo objeto SearchResponse para la película/serie
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

    // --- DATA CLASSES para Búsqueda (search) ---
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
    // --- FIN DATA CLASSES para Búsqueda ---

    // Sobrescribe la función de búsqueda
    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "${playhubAPI}search?q=$query"
        val search = ArrayList<SearchResponse>()
        val res = app.get(url, headers = playhubHeaders).parsed<PlayhubSearchMain>()

        // Mapea las películas encontradas
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

        // Mapea las series encontradas
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

    // --- DATA CLASSES para Carga de Detalles (load) ---
    data class PlayhubLoadMain (
        @JsonProperty("id"              ) var id              : Int?                       = null,
        @JsonProperty("original_title"  ) var originalTitle   : String?                    = null,
        @JsonProperty("title"           ) var title           : String?                    = null,
        @JsonProperty("backdrop_path"   ) var backdropPath    : String?                    = null,
        @JsonProperty("logo"            ) var logo            : String?                    = null,
        @JsonProperty("poster_path"     ) var posterPath      : String?                    = null,
        @JsonProperty("overview"        ) var overview        : String?                    = null, // Campo para la trama
        @JsonProperty("release_date"    ) var releaseDate     : String?                    = null,
        @JsonProperty("runtime"         ) var runtime         : String?                    = null,
        @JsonProperty("status"          ) var status          : String?                    = null,
        @JsonProperty("vote_average"    ) var voteAverage     : Double?                    = null,
        @JsonProperty("created_at"      ) var createdAt       : String?                    = null,
        @JsonProperty("updated_at"      ) var updatedAt       : String?                    = null,
        @JsonProperty("view_count"      ) var viewCount       : Int?                       = null,
        @JsonProperty("recommendations" ) var recommendations : ArrayList<PlayhubRecommendations>? = arrayListOf(),
        @JsonProperty("categories"      ) var categories      : ArrayList<Categories>?      = arrayListOf(),
        @JsonProperty("seasons"         ) var seasons        : ArrayList<Seasons>?   = arrayListOf(), // Para series
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
    )

    data class Categories (
        @JsonProperty("id"    ) var id    : Int?    = null,
        @JsonProperty("name"  ) var name  : String? = null,
    )

    data class Seasons (
        @JsonProperty("id"            ) var id           : Int? = null,
        @JsonProperty("serie_id"      ) var serieId      : Int? = null,
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
    // --- FIN DATA CLASSES para Carga de Detalles ---

    // Sobrescribe la función para cargar detalles de películas/series
    override suspend fun load(url: String): LoadResponse? {
        val type = if (url.contains("movie")) TvType.Movie else TvType.TvSeries
        val id = url.substringAfter("/movies/").substringAfter("/series/")
        val uuu = if (type == TvType.Movie) "${playhubAPI}movies/$id" else "${playhubAPI}series/$id"

        Log.d("PlayHubLite", "load: Solicitando detalles de la API: $uuu")
        val res = app.get(uuu, headers = playhubHeaders).parsed<PlayhubLoadMain>()
        Log.d("PlayHubLite", "load: Resumen (overview) recibido: ${res.overview}")

        val title = res.title ?: res.originalTitle ?: res.name ?: res.originalName ?: ""
        val plot = res.overview ?: ""
        val posterinfo = res.posterPath ?: ""
        val backposterinfo = res.backdropPath ?: ""
        val poster = getImageUrl(posterinfo)
        val backposter = getImageUrl(backposterinfo)
        val tags = res.categories?.mapNotNull { it.name }
        val episodes = ArrayList<Episode>()
        val recs = ArrayList<SearchResponse>()

        if (type == TvType.TvSeries) {
            res.seasons?.apmap { mainInfo ->
                val seasonurl = "${playhubAPI}seasons/${mainInfo.serieId}/${mainInfo.seasonNumber}"
                val seasonres = app.get(seasonurl, headers = playhubHeaders).parsed<SeasonsInfo>()
                val seriesID = mainInfo.serieId
                seasonres.episodes?.apmap { ep ->
                    val eptitle = ep.name
                    val epthumb = getImageUrl(ep.stillPath)
                    val epPlot = ep.overview
                    val seasonNum = ep.seasonNumber
                    val epNum = ep.episodeNumber
                    val airDate = ep.airDate
                    // CAMBIO AQUI: La URL de datos para el episodio ahora es la URL de la página web del episodio
                    val epData = "$mainUrl/series/$seriesID/season/$seasonNum/episode/$epNum" // Ajusta esta URL si el formato es diferente
                    episodes.add(
                        newEpisode(epData) {
                            this.name = eptitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = fixUrlNull(epthumb)
                            this.description = epPlot
                            addDate(airDate)
                        })
                }
            }
        }
        if (type == TvType.Movie)  {
            res.recommendations?.map {
                val rectitle = it.title ?: ""
                val recid = it.id
                val recposterinfo = it.posterPath
                val recposter = getImageUrl(recposterinfo)
                recs.add(
                    newMovieSearchResponse(rectitle, "$mainUrl/movies/$recid", type) {
                        this.posterUrl = recposter
                    })
            }
        }

        return when (type) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title,
                    url, type, episodes,){
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backposter
                    this.plot = plot
                    this.tags = tags
                    this.recommendations = recs
                }
            }
            TvType.Movie -> {
                // CAMBIO AQUI: La URL de datos para la película ahora es la URL de la página web de la película
                newMovieLoadResponse(title, url, type, "$mainUrl/movies/$id"){ // Ajusta esta URL si el formato es diferente
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

    // --- DATA CLASSES para Carga de Enlaces (loadLinks) ---
    data class DataBase (
        @JsonProperty("data" ) var data : String? = null // Contiene los datos Base64
    )

    data class ServersInfo (
        @JsonProperty("id"         ) var id        : Int?    = null,
        @JsonProperty("vid"        ) var vid       : String? = null,
        @JsonProperty("url"        ) var url       : String? = null, // URL del servidor/extractor
        @JsonProperty("server"     ) var server    : String? = null,
        @JsonProperty("language"   ) var language  : String? = null,
        @JsonProperty("quality"    ) var quality   : String? = null,
        @JsonProperty("user_id"    ) var userId    : String? = null,
        @JsonProperty("status"     ) var status    : String? = null,
        @JsonProperty("created_at" ) var createdAt : String? = null,
        @JsonProperty("updated_at" ) var updatedAt : String? = null,
        @JsonProperty("type"       ) var type      : Int?    = null
    )
    // --- FIN DATA CLASSES para Carga de Enlaces ---

    // Sobrescribe la función para cargar los enlaces de reproducción
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("PlayHubLite", "loadLinks: Solicitando enlaces desde URL (data): $data") // LOG: URL de la solicitud de enlaces
        // Añadí headers aquí por si la API de enlaces también los requiere
        val rr = app.get(data, headers = playhubHeaders).parsed<DataBase>()
        Log.d("PlayHubLite", "loadLinks: Datos Base64 de la BD recibidos (primeros 200 chars): ${rr.data?.take(200)}") // LOG: Datos Base64

        val datafix = rr.data?.replace("#", "A")?.replace("!", "B")?.replace("%", "N")?.replace("&", "i")?.replace("/", "l")?.replace("*", "L")?.replace("+", "s")?.replace("((", "j")?.replace("[]", "=")
        if (!datafix.isNullOrEmpty()) {
            val dadatec = base64Decode(datafix) // Decodifica el Base64
            Log.d("PlayHubLite", "loadLinks: JSON de servidores decodificado (primeros 500 chars): ${dadatec.take(500)}") // LOG: JSON decodificado

            // Parsea el JSON decodificado a una lista de ServersInfo
            val json = AppUtils.parseJson<ArrayList<ServersInfo>>(dadatec)

            // Itera sobre cada servidor usando apmap para manejar funciones suspendidas
            json.apmap { serverInfo ->
                // Transforma la URL del servidor para que sea compatible con los extractores
                val link = serverInfo.url?.replace(Regex("(https|http):.*\\/api\\/source\\/"),"https://embedsito.com/v/")
                    ?.replace(Regex("https://sbrity.com|https://sblanh.com"),"https://watchsb.com") ?: ""
                Log.d("PlayHubLite", "loadLinks: Enlace final a pasar al extractor: $link") // LOG: Enlace final
                // Carga el extractor para el enlace
                loadExtractor(link, subtitleCallback, callback)
            }
        }
        return true
    }
}