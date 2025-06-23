package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Log
import java.lang.Exception
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import android.util.Base64

// Extensión para crear slugs URL-friendly
private fun String.toUrlSlug(): String {
    return this
        .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
        .replace(" ", "-")
        .lowercase()
        .replace(Regex("-+"), "-")
        .trim('-')
}

// Clase principal del proveedor para Playhub
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
        // La API que proporciona los datos de películas/series y los enlaces de video
        private const val playhubAPI = "https://v3.playhublite.com/api/"

        // User-Agent de escritorio basado en la información que proporcionaste
        // Esto es crucial para simular un navegador real y evitar Cloudflare
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        // Cabeceras generales para las solicitudes a playhubAPI
        private val playhubHeaders = mapOf(
            "Host" to "v3.playhublite.com",
            "User-Agent" to DESKTOP_USER_AGENT, // Usar el User-Agent de escritorio
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-ES,es;q=0.6", // Basado en tu curl
            "Authorization" to "Bearer null", // Mantener por si acaso, aunque no parece usarse
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to "https://playhublite.com", // Origen del sitio principal
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Referer" to "https://playhublite.com/", // Referer del sitio principal
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "TE" to "trailers",
            // Client Hints (añadidos de tu curl)
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\""
        )
    }

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

    // --- DATA CLASSES para el JSON DECODIFICADO de la primera XHR ---
    data class ServersInfo(
        @JsonProperty("file_code") val file_code: String?,
        @JsonProperty("hash") val hash: String?,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("sources") val sources: List<Source>? = null,
        @JsonProperty("player_embed_url") val playerEmbedUrl: String? = null // Podría existir y ser muy útil!
    )

    data class Source(
        @JsonProperty("file_code") val file_code: String?,
        @JsonProperty("hash") val hash: String?,
        @JsonProperty("server") val server: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("quality") val quality: String?
    )

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("PlayHubLite", "loadLinks - Data de entrada (URL de la página del reproductor de Playhublite): $data")

        if (data.isBlank()) {
            Log.e("PlayHubLite", "loadLinks: La URL de la página del reproductor de Playhublite está vacía.")
            return false
        }

        // 1. Parsear la URL de entrada para obtener IDs de contenido (serie/película, temporada, episodio)
        val pathSegments = data.split("/")
        val typeSegment = pathSegments.getOrNull(3) // "movies" o "series"
        val contentIdPart = pathSegments.getOrNull(4) // e.g., "85077-4-1" or "12345-movie-slug"

        val isMovie = typeSegment == "movies"

        val playhubId: String?
        var seasonNum: String? = null
        var episodeNum: String? = null

        if (isMovie) {
            playhubId = contentIdPart?.split("-")?.firstOrNull()
        } else {
            val idParts = contentIdPart?.split("-")
            playhubId = idParts?.getOrNull(0)
            seasonNum = idParts?.getOrNull(1)
            episodeNum = idParts?.getOrNull(2)
        }

        if (playhubId.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: No se pudo extraer el ID de contenido de la URL: $data")
            return false
        }

        // --- PRIMERA LLAMADA XHR a v3.playhublite.com/api/videos/ (para obtener la cadena Base64) ---
        val firstXhrApiUrl: String = if (isMovie) {
            // Para películas, es solo el ID del contenido
            "${playhubAPI}videos/$playhubId?w=w"
        } else {
            // Para series, es ID-Temporada-Episodio
            "${playhubAPI}videos/$playhubId-$seasonNum-$episodeNum?w=w"
        }

        // Cabeceras específicas para la primera XHR a v3.playhublite.com (exactas a tu curl)
        val firstXhrHeaders = mapOf(
            "Host" to "v3.playhublite.com",
            "User-Agent" to DESKTOP_USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-ES,es;q=0.6",
            "Cache-Control" to "no-cache",
            "Origin" to mainUrl, // https://playhublite.com
            "Pragma" to "no-cache",
            "Priority" to "u=1, i",
            "Referer" to mainUrl + "/", // Referer del sitio principal (https://playhublite.com/)
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "Sec-GPC" to "1",
        )

        Log.d("PlayHubLite", "loadLinks: Realizando primera XHR (para cadena Base64) a: $firstXhrApiUrl")
        Log.d("PlayHubLite", "loadLinks: Cabeceras para primera XHR: $firstXhrHeaders")

        val base64ResponseString = try {
            val res = app.get(firstXhrApiUrl, headers = firstXhrHeaders)
            Log.d("PlayHubLite", "loadLinks: Código de estado de la primera XHR: ${res.code}")
            Log.d("PlayHubLite", "loadLinks: Cuerpo de la respuesta de la primera XHR (cadena Base64): ${res.text.take(500)}")

            if (res.code == 403) {
                Log.e("PlayHubLite", "loadLinks: La primera XHR fue bloqueada por Cloudflare (403 Forbidden). " +
                        "Esto indica que el sitio está detectando el bot. Intente ajustar las cabeceras o la URL.")
                return false
            }
            res.text
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks: ERROR en la primera XHR (obtener Base64): ${e.message}", e)
            return false
        }

        // --- DECODIFICAR LA CADENA BASE64 A JSON ---
        val decodedJsonString = try {
            val decodedBytes = Base64.decode(base64ResponseString, Base64.DEFAULT)
            String(decodedBytes, UTF_8)
        } catch (e: IllegalArgumentException) {
            Log.e("PlayHubLite", "loadLinks: ERROR al decodificar la cadena Base64: ${e.message}", e)
            return false
        }
        Log.d("PlayHubLite", "loadLinks: JSON decodificado de la Base64: ${decodedJsonString.take(500)}")

        val serversInfo = try {
            tryParseJson<ServersInfo>(decodedJsonString)
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks: ERROR al parsear el JSON decodificado a ServersInfo: ${e.message}", e)
            return false
        }

        val fileCode = serversInfo?.file_code
        val hash = serversInfo?.hash
        val playerEmbedUrl = serversInfo?.playerEmbedUrl // Si existe en el JSON

        if (fileCode.isNullOrBlank() || hash.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: No se pudo extraer file_code o hash del JSON decodificado. " +
                    "La estructura del JSON decodificado podría ser diferente a ServersInfo. JSON: ${decodedJsonString.take(200)}")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: File Code extraído: $fileCode, Hash extraído: $hash")


        // --- SEGUNDA LLAMADA XHR a dhcplay.com para el JSON con la URL del M3U8 ---
        // Usamos la URL del embed si está disponible, de lo contrario construimos con fileCode
        val dhcplayReferer = playerEmbedUrl ?: "https://dhcplay.com/e/$fileCode" // Preferir playerEmbedUrl si se proporciona

        val dhcplayApiUrl = "https://dhcplay.com/dl?op=view&file_code=$fileCode&hash=$hash&embed=1&referer=playhublite.com&adb=1"
        val dhcplayHeaders = mapOf(
            "Host" to "dhcplay.com",
            "User-Agent" to DESKTOP_USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "es-ES,es;q=0.6",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Priority" to "u=1, i",
            "Referer" to dhcplayReferer, // Usar la URL del embed de dhcplay.com como Referer
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin", // Esta solicitud es al mismo dominio dhcplay.com
            "Sec-Fetch-Storage-Access" to "active",
            "Sec-GPC" to "1",
            "X-Requested-With" to "XMLHttpRequest",
            // Las cookies 'tsn=2' se vieron en tu curl, podrías añadirlas si es necesario
            // "Cookie" to "tsn=2"
        )

        Log.d("PlayHubLite", "loadLinks: Realizando segunda XHR (M3U8 JSON) a: $dhcplayApiUrl")
        Log.d("PlayHubLite", "loadLinks: Cabeceras para segunda XHR: $dhcplayHeaders")

        val dhcplayResponse = try {
            val res = app.get(dhcplayApiUrl, headers = dhcplayHeaders)
            Log.d("PlayHubLite", "loadLinks: Código de estado de la segunda XHR: ${res.code}")
            res.text
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks: ERROR en la segunda XHR a dhcplay.com: ${e.message}", e)
            return false
        }
        Log.d("PlayHubLite", "loadLinks: Respuesta dhcplay.com (M3U8 JSON, primeros 500 chars): ${dhcplayResponse.take(500)}")


        // 4. Parsear la respuesta de dhcplay.com para la URL del M3U8
        // La respuesta de dhcplay.com debería ser JSON que contenga la URL del M3U8.
        val m3u8UrlRegex = Regex("\"file\":\"(https?://[^\"?]+\\.m3u8[^\"?]*)\"")
        val m3u8UrlMatch = m3u8UrlRegex.find(dhcplayResponse)
        val m3u8FullUrl = m3u8UrlMatch?.groupValues?.get(1)

        if (m3u8FullUrl.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: No se pudo extraer la URL M3U8 de la respuesta de dhcplay.com. " +
                    "Verifique el formato JSON/script esperado. Respuesta: ${dhcplayResponse.take(200)}")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: URL M3u8 extraída: $m3u8FullUrl")

        // Extraer CDN base (ej: https://ye0r0rrinu.cdn-centaurus.com) de la URL M3U8 para construir la URL final y subtítulos
        val cdnBaseUrlMatch = Regex("(https?://[^/]+)/hls2/").find(m3u8FullUrl)
        val cdnBaseUrl = cdnBaseUrlMatch?.groupValues?.get(1)

        // El ID aleatorio y el file_code ya están en la URL del master.m3u8,
        // pero podemos "limpiar" la URL de parámetros de seguimiento si se prefiere.
        // O simplemente usar m3u8FullUrl directamente.
        val finalM3u8Url = m3u8FullUrl // Por simplicidad, usamos la URL completa extraída

        Log.d("PlayHubLite", "loadLinks: URL final del M3U8 para callback: $finalM3u8Url")

        // Cabeceras para la solicitud del M3U8 (el stream real)
        val m3u8Headers = mapOf(
            "Referer" to "https://dhcplay.com/", // El Referer para el M3U8 debe ser la URL del reproductor (dhcplay.com)
            "Origin" to "https://dhcplay.com",
            "User-Agent" to DESKTOP_USER_AGENT, // Usar el mismo User-Agent
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
                // Configurar el Referer y las cabeceras para el extractor
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

            val vttPathSegmentMatch = Regex("/hls2/([^/]+/[^/]+)/").find(m3u8FullUrl)
            val vttPathSegment = vttPathSegmentMatch?.groupValues?.get(1) // Debería ser "01/09571"

            subtitleLanguages.forEach { (langCode, langName) ->
                val subtitleUrl = if (!vttPathSegment.isNullOrBlank()) {
                    "$cdnBaseUrl/vtt/$vttPathSegment/${fileCode}_${langCode}.vtt"
                } else {
                    "$cdnBaseUrl/vtt/${fileCode}_${langCode}.vtt"
                }

                Log.d("PlayHubLite", "loadLinks: Intentando añadir subtítulo: $subtitleUrl")
                // Crea el SubtitleFile con solo lang y url
                // La versión de Cloudstream que estás usando no permite adjuntar referer/headers
                // directamente a SubtitleFile. El reproductor podría intentar usar las cabeceras
                // del stream principal o el referer global.
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