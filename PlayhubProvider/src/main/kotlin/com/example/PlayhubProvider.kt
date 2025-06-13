package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.* // Import
import com.lagradost.cloudstream3.utils.* // ¡CRÍTICO! Importa todas las utilidades. Esto debería traer fixUrl, apmap, base64Decode, etc.
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson // Importación explícita para tryParseJson

import android.util.Log
import java.lang.Exception
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

// Clase principal del proveedor para Playhub
class PlayhubProvider : MainAPI() {

    // CAMBIO CRÍTICO: Usar 'override var' para las propiedades que son 'var' en MainAPI
    override var mainUrl = "https://playhublite.com"
    override var name = "Playhub"
    override var lang = "es" // Se usa 'var' porque es 'var' en MainAPI

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

    // Objeto compañero para constantes y propiedades estáticas
    companion object  {
        private const val playhubAPI = "http://v3.playhublite.com/api/"
        private val playhubHeaders = mapOf(
            "Host" to "v3.playhublite.com",
            "User-Agent" to USER_AGENT,
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

            val tvType = if (!info.releaseDate.isNullOrEmpty()) TvType.Movie else TvType.TvSeries

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

    // --- DATA CLASSES para Carga de Detalles (load) ---
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
                    val epData = "$mainUrl/series/$seriesID/season/$seasonNum/episode/$epNum"
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
                newMovieLoadResponse(title, url, type, "$mainUrl/movies/$id"){
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
    // --- FIN DATA CLASSES para Carga de Enlaces ---

    // Función de desencriptación (copy-paste de SoloLatinoProvider)
    private fun decryptLink(encryptedLinkBase64: String, secretKey: String): String? {
        try {
            val encryptedBytes = Base64.decode(encryptedLinkBase64, Base64.DEFAULT)

            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ivSpec = IvParameterSpec(ivBytes)

            val cipherTextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)

            val keySpec = SecretKeySpec(secretKey.toByteArray(UTF_8), "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decryptedBytes = cipher.doFinal(cipherTextBytes)

            return String(decryptedBytes, UTF_8)
        } catch (e: Exception) {
            Log.e("PlayHubLite", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    // Sobrescribe la función para cargar los enlaces de reproducción
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("PlayHubLite", "loadLinks - Data de entrada: $data")

        val rr = app.get(data, headers = playhubHeaders).parsed<DataBase>()
        Log.d("PlayHubLite", "loadLinks: Datos Base64 de la BD recibidos (primeros 200 chars): ${rr.data?.take(200)}")

        val datafix = rr.data?.replace("#", "A")?.replace("!", "B")?.replace("%", "N")?.replace("&", "i")?.replace("/", "l")?.replace("*", "L")?.replace("((", "j")?.replace("[]", "=")
        if (!datafix.isNullOrEmpty()) {
            val dadatec = base64Decode(datafix) // base64Decode debería resolverse con 'utils.*'
            Log.d("PlayHubLite", "loadLinks: JSON de servidores decodificado (primeros 500 chars): ${dadatec.take(500)}")

            val json = tryParseJson<ArrayList<ServersInfo>>(dadatec) // tryParseJson debería resolverse con 'utils.*'

            json?.apmap { serverInfo -> // apmap debería resolverse con 'utils.*'
                val link = serverInfo.url?.replace(Regex("(https|http):.*\\/api\\/source\\/"),"https://embedsito.com/v/")
                    ?.replace(Regex("https://sbrity.com|https://sblanh.com"),"https://watchsb.com") ?: ""
                Log.d("PlayHubLite", "loadLinks: Enlace final a pasar al extractor: $link")
                loadExtractor(link, subtitleCallback, callback) // loadExtractor debería resolverse con 'cloudstream3.*'
            }
        }
        return true
    }

    // ELIMINADAS: Estas funciones no existen en la MainAPI de esta versión de Cloudstream
    // override suspend fun getStatus(): String? { /* ... */ }
    // override val homepageList: List<HomePageList> = listOf(/* ... */)
}