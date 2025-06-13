package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log // Importamos esto para poder usar Log.d y Log.e

// Jackson imports para JSON (asegúrate de que estas sean 'compileOnly' en build.gradle)
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

class PlayhubProvider : MainAPI() {

    // MainUrl debe ser la base del sitio web, no de la API
    // Si la API es v3.playhublite.com, el sitio web quizás sea https://playhublite.com/ o similar
    // Si el sitio web principal también se movió a v3.playhublite.com, entonces cámbialo.
    // Mantenemos playhublite.com por ahora, asumiendo que es la URL principal donde se ven los contenidos.
    override var mainUrl = "https://playhublite.com" // O el nuevo dominio del sitio web, si ha cambiado
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

    // Instancia de Jackson ObjectMapper - ¡Se inicializa una sola vez!
    private val jacksonMapper = ObjectMapper().registerModule(KotlinModule())

    companion object  {
        // ¡ACTUALIZADA! La nueva API es http://v3.playhublite.com/api/
        private const val playhubAPI = "http://v3.playhublite.com/api/" // <--- ¡CAMBIO AQUÍ!
        private val playhubHeaders = mapOf(
            "Host" to "v3.playhublite.com", // <--- ¡CAMBIO AQUÍ! (Coincide con el nuevo dominio de la API)
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Authorization" to "Bearer null", // Mantener por ahora, pero atento a si se necesita un token real
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to "https://playhublite.com", // Mantener como el sitio web principal si no cambió
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Referer" to "https://playhublite.com/", // Mantener como el sitio web principal si no cambió
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "TE" to "trailers",
        )
    }

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        // La URL base para las imágenes de TMDB (poster_path, backdrop_path) es https://image.tmdb.org/t/p/w1280/
        // Si el link de la API ya viene con el dominio completo, lo usamos.
        // Si es solo la ruta, le anteponemos la URL de TMDB.
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w1280/$link" else link
    }

    // NUEVAS DATA CLASSES PARA LA PÁGINA PRINCIPAL (adaptadas al JSON que me has dado)
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

    data class PlayHubMovieData( // Esta clase contendrá los datos de cada película/serie
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null, // Para películas
        @JsonProperty("name") val name: String? = null,   // Para series
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null, // Para películas
        @JsonProperty("first_air_date") val firstAirDate: String? = null, // Para series
        @JsonProperty("last_air_date") val lastAirDate: String? = null // Para series
    )
    // Fin de las nuevas DATA CLASSES para la página principal

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        // Determinamos el tipo de contenido que queremos para esta HomePageList
        // El JSON que me diste es para "movies". Necesitas verificar la URL para "series" si es diferente.
        // Asumo que para la página principal, estás buscando la URL que te da el listado general de películas o series.
        // Si la página principal del sitio web tiene "listas" como "Películas Populares", "Series Nuevas", etc.,
        // entonces necesitarás hacer llamadas a la API para cada una de esas listas, o encontrar un endpoint que las combine.
        // Para empezar, usaremos el endpoint de películas.
        val apiCategoryUrl = "${playhubAPI}movies" // Puedes cambiar a "${playhubAPI}series" si el JSON es para series
        // O quizás un endpoint que te dé HOME categories como antes.

        Log.d("PlayHubLite", "getMainPage: Intentando obtener de la API: $apiCategoryUrl?page=$page")
        Log.d("PlayHubLite", "getMainPage: Cabeceras: $playhubHeaders")

        val response = try {
            val res = app.get("$apiCategoryUrl?page=$page", headers = playhubHeaders) // Incluimos el parámetro de página
            Log.d("PlayHubLite", "getMainPage: Código de estado de la respuesta cruda: ${res.code}")
            Log.d("PlayHubLite", "getMainPage: Cuerpo de la respuesta cruda (primeros 500 chars): ${res.text.take(500)}")

            // Intentamos analizar el JSON con la nueva data class
            res.parsed<PlayHubMainPageResponse>() // <--- ¡CAMBIO AQUÍ! Usamos la nueva data class
        } catch (e: Exception) {
            Log.e("PlayHubLite", "getMainPage: ERROR al analizar o obtener JSON de $apiCategoryUrl: ${e.message}", e)
            Log.e("PlayHubLite", "getMainPage: Por favor, verifica si la API de Playhub en $playhubAPI aún funciona y devuelve JSON.")
            throw ErrorLoadingException("No se pudo cargar la página principal: ${e.message}")
        }

        // Aquí es donde necesitamos adaptar la lógica. Ya no hay un campo "home" con "type" y "data".
        // Ahora tenemos una lista directa de "data" en la respuesta principal.
        val homeItems = response.data?.mapNotNull { info -> // Usamos mapNotNull para omitir elementos nulos
            val title = info.title ?: info.name ?: "" // Puede ser 'title' para películas o 'name' para series
            val id = info.id?.toString() ?: return@mapNotNull null // Aseguramos que ID no sea nulo

            val posterPath = info.posterPath
            val poster = getImageUrl(posterPath)

            // Asumimos que si tiene releaseDate es película, si tiene first_air_date es serie.
            // Ajusta esta lógica si sabes con certeza el tipo de contenido que viene de este endpoint.
            val tvType = if (!info.releaseDate.isNullOrEmpty()) TvType.Movie else TvType.TvSeries

            // Construimos la URL de datos de forma más fiable según el tipo
            val dataUrl = if (tvType == TvType.Movie) "$mainUrl/movies/$id" else "$mainUrl/series/$id"

            // Usamos el tipo de respuesta de búsqueda correcto
            newMovieSearchResponse(
                title,
                dataUrl,
                tvType // Pasamos el tipo determinado
            ) {
                this.posterUrl = poster
            }
        }

        // Agregamos una única HomePageList para la página principal, ya que el JSON es un listado plano
        // El "name" de esta lista puede ser "Películas Recientes" o "Contenido Principal".
        if (!homeItems.isNullOrEmpty()) {
            items.add(HomePageList("Contenido Principal", homeItems)) // Puedes cambiar "Contenido Principal" por algo más específico
        } else {
            Log.w("PlayHubLite", "getMainPage: No se encontraron elementos para la lista 'Contenido Principal'.")
        }


        if (items.size <= 0) {
            Log.e("PlayHubLite", "getMainPage: No se crearon HomePageLists a partir de los datos de la API.")
            throw ErrorLoadingException("No se encontraron datos de la API.")
        }

        // Si la API tiene paginación, la manejamos con HomePageResponse
        val hasNextPage = response.nextPageUrl != null
        return HomePageResponse(items, hasNextPage)
    }

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
        @JsonProperty("original_title" ) var originalTitle : String? = null,
        @JsonProperty("title"          ) var title         : String? = null,
        @JsonProperty("release_date"   ) var releaseDate   : String? = null,
        @JsonProperty("runtime"        ) var runtime       : String? = null,
    )
    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "${playhubAPI}search?q=$query" // Asegúrate de que esta URL de búsqueda también funcione con la nueva API
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
        @JsonProperty("seasons"          ) var seasons        : ArrayList<Seasons>?   = arrayListOf(),
        @JsonProperty("name"             ) var name           : String?               = null,
        @JsonProperty("original_name"    ) var originalName   : String?               = null,
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
    override suspend fun load(url: String): LoadResponse? {
        val type = if (url.contains("movie")) TvType.Movie else TvType.TvSeries
        val id = url.substringAfter("/movies/").substringAfter("/series/")
        // ¡ACTUALIZA estas URLs también para que usen playhubAPI y la nueva estructura!
        val uuu = if (type == TvType.Movie) "${playhubAPI}movies/$id" else "${playhubAPI}series/$id"
        val res = app.get(uuu, headers = playhubHeaders).parsed<PlayhubLoadMain>()
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
                    val epData = "${playhubAPI}xxx/$seriesID-$seasonNum-$epNum?s=web" // Verifica esta URL
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
                    //this.year = year
                    this.tags = tags
                    this.recommendations = recs
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, type, "${playhubAPI}xxx/$id?s=web"){ // Verifica esta URL
                    this.posterUrl = poster
                    this.plot = plot
                    this.backgroundPosterUrl = backposter
                    //this.year = year
                    this.tags = tags
                    this.recommendations = recs
                }
            }
            else -> null
        }
    }


    data class DataBase (

        @JsonProperty("data" ) var data : String? = null

    )

    data class ServersInfo (
        @JsonProperty("id"         ) var id        : Int?    = null,
        @JsonProperty("vid"        ) var vid       : String? = null,
        @JsonProperty("url"        ) var url       : String? = null,
        @JsonProperty("server"     ) var server    : String? = null,
        @JsonProperty("language"   ) var language  : String? = null,
        @JsonProperty("quality"    ) var quality   : String? = null,
        @JsonProperty("user_id"    ) var userId    : String? = null,
        @JsonProperty("status"     ) var status    : String? = null,
        @JsonProperty("created_at" ) var createdAt : String? = null,
        @JsonProperty("updated_at" ) var updatedAt : String? = null,
        @JsonProperty("type"       ) var type      : Int?    = null
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Asumo que 'data' ya viene de la URL de la API que termina en algo como 'xxx/$id?s=web'
        val rr = app.get(data).parsed<DataBase>()
        val datafix = rr.data?.replace("#", "A")?.replace("!", "B")?.replace("%", "N")?.replace("&", "i")?.replace("/", "l")?.replace("*", "L")?.replace("+", "s")?.replace("((", "j")?.replace("[]", "=")
        if (!datafix.isNullOrEmpty()) {
            val dadatec = base64Decode(datafix)
            val json = parseJson<ArrayList<ServersInfo>>(dadatec)
            json.map {
                val link = it.url?.replace(Regex("(https|http):.*\\/api\\/source\\/"),"https://embedsito.com/v/")
                    ?.replace(Regex("https://sbrity.com|https://sblanh.com"),"https://watchsb.com") ?: ""
                //println("TESTING $link")
                loadExtractor(link, subtitleCallback, callback)
            }
        }
        return true
    }
}