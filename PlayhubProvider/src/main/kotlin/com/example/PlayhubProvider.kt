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
            "Authorization" to "Bearer null", // Mantener como "Bearer null" a menos que sepas que se necesita un token real.
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

            // Lógica revisada para determinar TvType:
            // Si firstAirDate NO es nulo/vacío, es TvSeries.
            // Si releaseDate NO es nulo/vacío Y firstAirDate ES nulo/vacío, es Movie.
            // Esto es más robusto para diferenciar series de películas.
            val tvType = if (!info.firstAirDate.isNullOrEmpty()) TvType.TvSeries else TvType.Movie

            val dataUrl = if (tvType == TvType.Movie) "$mainUrl/movies/$id" else "$mainUrl/series/$id"

            newMovieSearchResponse(
                title,
                dataUrl,
                tvType // Usa el tvType determinado
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
        @JsonProperty("release_date"    ) var releaseDate     : String?                    = null, // Para películas
        @JsonProperty("runtime"         ) var runtime         : String?                    = null, // Para películas
        @JsonProperty("status"          ) var status          : String?                    = null,
        @JsonProperty("vote_average"    ) var voteAverage     : Double?                    = null,
        @JsonProperty("created_at"      ) var createdAt       : String?                    = null,
        @JsonProperty("updated_at"      ) var updatedAt       : String?                    = null,
        @JsonProperty("view_count"      ) var viewCount       : Int?                       = null,
        @JsonProperty("recommendations" ) var recommendations : ArrayList<PlayhubRecommendations>? = arrayListOf(),
        @JsonProperty("categories"      ) var categories      : ArrayList<Categories>?      = arrayListOf(),
        @JsonProperty("seasons"         ) var seasons        : ArrayList<Seasons>?   = arrayListOf(), // Para series
        @JsonProperty("name"            ) var name           : String?               = null, // Para series
        @JsonProperty("original_name"   ) var originalName   : String?               = null, // Para series
        @JsonProperty("episode_run_time" ) var episodeRunTime : String?               = null, // Para series
        @JsonProperty("first_air_date"   ) var firstAirDate   : String?               = null, // Para series
        @JsonProperty("in_production"    ) var inProduction   : Int?                  = null, // Para series
        @JsonProperty("last_air_date"    ) var lastAirDate    : String?               = null, // Para series
    )

    data class PlayhubRecommendations (
        @JsonProperty("id"            ) var id           : Int?    = null,
        @JsonProperty("title"         ) var title        : String? = null,
        @JsonProperty("poster_path"   ) var posterPath   : String? = null,
        @JsonProperty("backdrop_path" ) var backdropPath : String? = null,
        @JsonProperty("name"          ) var name         : String? = null, // Añadido para recomendaciones de series
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
        // Asegúrate de que 'id' se extraiga correctamente, sin importar si es movie o series
        val initialTypeFromUrl = if (url.contains("/movies/")) TvType.Movie else TvType.TvSeries
        val id = url.substringAfterLast("/") // Extrae el ID después de la última barra

        if (id.isBlank()) {
            Log.e("PlayHubLite", "load: ID no pudo ser extraído de la URL: $url")
            return null // No podemos continuar sin un ID válido
        }

        var apiDetailUrl = if (initialTypeFromUrl == TvType.Movie) "${playhubAPI}movies/$id" else "${playhubAPI}series/$id"
        Log.d("PlayHubLite", "load: Solicitando detalles de la API para ID $id con URL inicial: $apiDetailUrl")

        val res = try {
            var apiResponse = app.get(apiDetailUrl, headers = playhubHeaders)
            Log.d("PlayHubLite", "load: Código de estado de detalles (intento 1): ${apiResponse.code}")

            // Si la respuesta es 404 y el tipo asumido es incorrecto,
            // podríamos intentar el otro tipo. Esto es un parche si la API es inconsistente.
            if (apiResponse.code == 404) {
                Log.w("PlayHubLite", "load: Recibido 404 para ${initialTypeFromUrl} ID $id. Intentando el tipo opuesto.")
                val alternateType = if (initialTypeFromUrl == TvType.Movie) TvType.TvSeries else TvType.Movie
                apiDetailUrl = if (alternateType == TvType.Movie) "${playhubAPI}movies/$id" else "${playhubAPI}series/$id" // Actualiza la URL para el intento alternativo
                Log.d("PlayHubLite", "load: Intentando con la URL alternativa: $apiDetailUrl")
                apiResponse = app.get(apiDetailUrl, headers = playhubHeaders) // Vuelve a intentar la solicitud
                Log.d("PlayHubLite", "load: Código de estado de detalles (intento 2, ${alternateType}): ${apiResponse.code}")

                if (apiResponse.code == 200) {
                    Log.d("PlayHubLite", "load: La URL alternativa funcionó. Ajustando tipo a $alternateType.")
                    apiResponse.parsed<PlayhubLoadMain>() // Parsear la respuesta de la URL alternativa
                } else {
                    throw Exception("Ambas URLs de detalles fallaron. Último código: ${apiResponse.code}")
                }
            } else {
                apiResponse.parsed<PlayhubLoadMain>()
            }
        } catch (e: Exception) {
            Log.e("PlayHubLite", "load: ERROR al obtener o parsear detalles de $apiDetailUrl: ${e.message}", e)
            Log.e("PlayHubLite", "load: Cuerpo de respuesta (si disponible): ${e.printStackTrace()}")
            return null // Fallo al cargar los detalles
        }

        val title = res.title ?: res.originalTitle ?: res.name ?: res.originalName ?: ""
        val plot = res.overview ?: ""
        val poster = getImageUrl(res.posterPath)
        val backposter = getImageUrl(res.backdropPath)
        val tags = res.categories?.mapNotNull { it.name }
        val episodes = ArrayList<Episode>()
        val recs = ArrayList<SearchResponse>()

        Log.d("PlayHubLite", "load: Título: $title, Plot: ${plot.take(100)}...")

        // Determinación final del TvType basado en los datos obtenidos de la API
        val actualType = if (!res.firstAirDate.isNullOrEmpty()) { // Si tiene firstAirDate, es una serie
            TvType.TvSeries
        } else { // De lo contrario, es una película
            TvType.Movie
        }
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
                        Log.d("PlayHubLite", "load: Cuerpo de temporada $seasonNum (primeros 500 chars): ${seasonApiResponse.text.take(500)}")
                        seasonApiResponse.parsed<SeasonsInfo>()
                    } catch (e: Exception) {
                        Log.e("PlayHubLite", "load: ERROR al obtener o parsear temporada $seasonApiUrl: ${e.message}", e)
                        null
                    }

                    if (seasonres == null || seasonres.episodes.isNullOrEmpty()) {
                        Log.w("PlayHubLite", "load: No se encontraron episodios para la temporada $seasonNum en $seasonApiUrl.")
                        return@apmap // Continúa con la siguiente temporada si no hay episodios o la respuesta es nula
                    }

                    Log.d("PlayHubLite", "load: Temporada $seasonNum obtenida. Procesando ${seasonres.episodes?.size} episodios.")

                    seasonres.episodes?.apmap { ep ->
                        val eptitle = ep.name
                        val epthumb = getImageUrl(ep.stillPath)
                        val epPlot = ep.overview
                        val epNum = ep.episodeNumber
                        val airDate = ep.airDate

                        // IMPORTANTE: La URL de 'data' para loadLinks para un episodio debe ser la URL de la API que devuelve las FUENTES
                        // Si la API es http://v3.playhublite.com/api/sources/{serieId}_s{seasonNumber}_e{episodeNumber}
                        val episodeSourceDataUrl = "${playhubAPI}sources/${seriesID}_s${seasonNum}_e${epNum}"
                        Log.d("PlayHubLite", "load: Añadiendo episodio S${seasonNum}E${epNum}: $eptitle con dataUrl para loadLinks: $episodeSourceDataUrl")

                        episodes.add(
                            newEpisode(episodeSourceDataUrl) {
                                this.name = eptitle
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = fixUrlNull(epthumb)
                                this.description = epPlot
                                addDate(airDate)
                            })
                    }
                } else {
                    Log.w("PlayHubLite", "load: Serie ID ($seriesID) o número de temporada ($seasonNum) nulo en mainInfo para serie: $mainInfo")
                }
            }
        }

        // Procesamiento de recomendaciones (para películas y series)
        res.recommendations?.map {
            // Usa 'title' para películas y 'name' para series en recomendaciones
            val rectitle = it.title ?: it.name ?: ""
            val recid = it.id
            val recposter = getImageUrl(it.posterPath)

            // Intenta determinar el tipo de la recomendación de forma más robusta
            // Si la recomendación tiene 'name' pero no 'title', es probable que sea una serie.
            // Si tiene 'title' (o ambos), y es probable que sea una película por el contexto del API.
            val recType = if (it.name != null && it.title == null) TvType.TvSeries else TvType.Movie
            val recUrl = if (recType == TvType.Movie) "$mainUrl/movies/$recid" else "$mainUrl/series/$recid"

            recs.add(
                when(recType) {
                    TvType.Movie -> newMovieSearchResponse(rectitle, recUrl, TvType.Movie) { this.posterUrl = recposter }
                    TvType.TvSeries -> newTvSeriesSearchResponse(rectitle, recUrl, TvType.TvSeries) { this.posterUrl = recposter }
                    else -> throw IllegalStateException("Unsupported TvType for recommendation: $recType") // Esto no debería ocurrir
                }
            )
        }


        return when (actualType) { // Usar 'actualType' aquí
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, // url original de la página (e.g. playhublite.com/series/ID)
                    actualType, // Usa el actualType
                    episodes
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backposter
                    this.plot = plot
                    this.tags = tags
                    this.recommendations = recs
                }
            }
            TvType.Movie -> {
                // IMPORTANTE: La URL de 'data' para loadLinks para una película debe ser la URL de la API que devuelve las FUENTES
                // Si la API es http://v3.playhublite.com/api/sources/{movieId}
                val movieSourceDataUrl = "${playhubAPI}sources/${id}" // Usa el ID de la película
                Log.d("PlayHubLite", "load: URL de datos de fuente para película: $movieSourceDataUrl")

                newMovieLoadResponse(
                    title,
                    url, // url original de la página (e.g. playhublite.com/movies/ID)
                    actualType, // Usa el actualType
                    movieSourceDataUrl // Pasa la URL de la API de fuentes para la película
                ) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.backgroundPosterUrl = backposter
                    this.tags = tags
                    this.recommendations = recs
                }
            }
            else -> null // Devolver null si el tipo no es compatible
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
        data: String, // ¡Esta 'data' DEBE ser la URL de la API de fuentes!
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Logging inicial para depuración
        Log.d("PlayHubLite", "loadLinks - Data de entrada (API de Fuentes): $data")

        // 2. Validación de la URL de entrada
        if (data.isBlank()) {
            Log.e("PlayHubLite", "loadLinks: La URL de datos de fuentes está vacía. No se puede cargar enlaces.")
            return false
        }

        // 3. Solicitud a la API de fuentes para obtener los datos encriptados
        val rr = try {
            val apiResponse = app.get(data, headers = playhubHeaders)
            Log.d("PlayHubLite", "loadLinks: Código de estado de la API de fuentes: ${apiResponse.code}")
            Log.d("PlayHubLite", "loadLinks: Cuerpo de la API de fuentes (primeros 500 chars): ${apiResponse.text.take(500)}")

            // Verificar si la respuesta es exitosa. Si no es 200, podría ser un problema.
            if (apiResponse.code !in 200..299) {
                Log.e("PlayHubLite", "loadLinks: API de fuentes devolvió un código de estado ${apiResponse.code}. Cuerpo: ${apiResponse.text}")
                return false
            }
            apiResponse.parsed<DataBase>() // Intenta parsear la respuesta como DataBase
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks: Error al obtener o parsear BaseDatos de la API de fuentes $data: ${e.message}", e)
            Log.e("PlayHubLite", "loadLinks: Posiblemente la URL de fuentes ($data) es incorrecta o la API no responde.")
            return false
        }

        // 4. Obtener la cadena Base64 encriptada del campo 'data'
        val rawDataBase64 = rr.data
        if (rawDataBase64.isNullOrEmpty()) {
            Log.w("PlayHubLite", "loadLinks: Campo 'data' en BaseDatos vacío o nulo. No hay enlaces para procesar.")
            return false
        }

        Log.d("PlayHubLite", "loadLinks: Raw Base64 de la BD recibidos (primeros 200 chars): ${rawDataBase64.take(200)}")

        // 5. Aplicar las sustituciones y luego decodificar Base64
        // Estas sustituciones son CRÍTICAS y deben coincidir EXACTAMENTE con el esquema de ofuscación de Playhub.
        // Asegúrate de que `?.` no rompa la cadena si `rawDataBase64` no es nulo aquí, mejor usar `rawDataBase64.replace(...)`
        val datafix = rawDataBase64
            .replace("#", "A")
            .replace("!", "B")
            .replace("%", "N")
            .replace("&", "i")
            .replace("/", "l")
            .replace("*", "L")
            .replace("((", "j") // ¡Cuidado con dobles paréntesis, verifica si son literales!
            .replace("[]", "=") // ¡Cuidado con corchetes vacíos, verifica si son literales!

        if (datafix.isBlank()) { // Usar isBlank para verificar si está vacío o solo espacios en blanco
            Log.e("PlayHubLite", "loadLinks: datafix está vacío después de las sustituciones. Esto indica un problema con el formato original o las sustituciones.")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: datafix después de sustituciones (primeros 200 chars): ${datafix.take(200)}")

        val dadatec = try {
            // Usando Android Base64 directamente, ya que lo tienes importado y es confiable.
            String(Base64.decode(datafix, Base64.DEFAULT), UTF_8)
        } catch (e: IllegalArgumentException) {
            Log.e("PlayHubLite", "loadLinks: Error al decodificar Base64 para datafix: $datafix, error: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks: Error inesperado al decodificar Base64: ${e.message}", e)
            return false
        }

        if (dadatec.isBlank()) {
            Log.e("PlayHubLite", "loadLinks: Cadena decodificada (dadatec) está vacía. Esto es un problema de Base64 o datafix.")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: JSON de servidores decodificado (primeros 500 chars): ${dadatec.take(500)}")

        // 6. Parsear el JSON decodificado a una lista de ServersInfo
        val jsonServers = tryParseJson<ArrayList<ServersInfo>>(dadatec)
        if (jsonServers.isNullOrEmpty()) {
            Log.w("PlayHubLite", "loadLinks: tryParseJson devolvió una lista vacía o nula. El JSON decodificado no coincide con ServersInfo.")
            Log.w("PlayHubLite", "loadLinks: JSON decodificado que intentó parsear: $dadatec")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: Se encontraron ${jsonServers.size} servidores en el JSON.")

        // 7. Procesar cada servidor encontrado
        jsonServers.apmap { serverInfo ->
            val originalLink = serverInfo.url
            if (originalLink.isNullOrBlank()) {
                Log.w("PlayHubLite", "loadLinks: Enlace original de servidor vacío o nulo para serverInfo: $serverInfo")
                return@apmap // Salta a la siguiente iteración si el enlace es nulo o vacío
            }
            Log.d("PlayHubLite", "loadLinks: Procesando servidor: ${serverInfo.server}, Enlace original: $originalLink")

            var linkToExtract = originalLink // Usa el enlace original si no hay desencriptación AES

            // 9. Aplicar reemplazos específicos para transformar el enlace de la API en un enlace de reproductor
            // Estos reemplazos son específicos de cómo PlayhublLite maneja sus embeds o redirecciona los enlaces.
            linkToExtract = linkToExtract
                .replace(Regex("(https|http):.*\\/api\\/source\\/"), "https://embedsito.com/v/")
                .replace(Regex("https://sbrity.com|https://sblanh.com"), "https://watchsb.com")
            // Añade más reemplazos si son necesarios, por ejemplo, para otros dominios de embeds
            // .replace("otro_dominio_feo.com", "otro_dominio_limpio.com")

            Log.d("PlayHubLite", "loadLinks: Enlace final a pasar al extractor: $linkToExtract")

            // 10. Pasar el enlace final al extractor de Cloudstream3
            if (linkToExtract.isNotBlank()) {

                loadExtractor(linkToExtract, mainUrl, subtitleCallback, callback)
            } else {
                Log.w("PlayHubLite", "loadLinks: Enlace de extractor vacío después de transformaciones para serverInfo: $serverInfo")
            }
        }
        return true // Indica que la operación de carga de enlaces se ha completado (no necesariamente que se hayan encontrado enlaces válidos)
    }
}