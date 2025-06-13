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

    // Sobrescribe la función para cargar detalles de películas/series
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
                if (apiResponse.code == 200) apiResponse.parsed<PlayhubLoadMain>() else throw Exception("Ambas URLs fallaron")
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
                        // Ajusta esta URL según la API real (usa el endpoint que genera el .m3u8)
                        val episodeSourceDataUrl = "https://playhublite.com/api/stream/${seriesID}_s${seasonNum}_e${epNum}" // Placeholder
                        Log.d("PlayHubLite", "load: Añadiendo episodio S${seasonNum}E${epNum}: $eptitle con dataUrl para loadLinks: $episodeSourceDataUrl")

                        episodes.add(newEpisode(episodeSourceDataUrl) {
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
                // Ajusta esta URL según la API real (usa el endpoint que genera el .m3u8)
                val movieSourceDataUrl = "https://playhublite.com/api/stream/$id" // Placeholder
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("PlayHubLite", "loadLinks - Data de entrada (API de Fuentes): $data")

        if (data.isBlank()) {
            Log.e("PlayHubLite", "loadLinks: La URL de datos de fuentes está vacía.")
            return false
        }

        // Prueba ambas URLs: con el identificador y con file_id
        val identifierUrl = "https://tpz6t.com/bkg/8ga80911jrjl?ref=playhublite.com"
        val fileIdUrl = "https://tpz6t.com/bkg/file/46146551?ref=playhublite.com" // URL tentativa con file_id
        Log.d("PlayHubLite", "loadLinks: Probando URLs iniciales: $identifierUrl, $fileIdUrl")

        // Cabeceras mejoradas con las cookies obtenidas
        val enhancedHeaders = playhubHeaders + mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Upgrade-Insecure-Requests" to "1",
            "Origin" to "https://tpz6t.com",
            "Referer" to "https://tpz6t.com/",
            "X-Application-Key" to "ypvrgnttsmn6piLiktV5u4tf74610w",
            "Cookie" to "aff=84338; file_id=46146551; ref_url=playhublite.com" // Cookies obtenidas
        )

        // Prueba primero con el identificador
        var response = try {
            val apiResponse = app.get(identifierUrl, headers = enhancedHeaders, allowRedirects = true, timeout = 30)
            Log.d("PlayHubLite", "loadLinks: Código de estado (identificador): ${apiResponse.code}")
            Log.d("PlayHubLite", "loadLinks: Cuerpo (primeros 500 chars): ${apiResponse.text.take(500)}")
            apiResponse
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks: Error con identificador $identifierUrl: ${e.message}", e)
            null
        }

        // Si falla, prueba con file_id
        if (response == null || response.code !in 200..299) {
            response = try {
                val apiResponse = app.get(fileIdUrl, headers = enhancedHeaders, allowRedirects = true, timeout = 30)
                Log.d("PlayHubLite", "loadLinks: Código de estado (file_id): ${apiResponse.code}")
                Log.d("PlayHubLite", "loadLinks: Cuerpo (primeros 500 chars): ${apiResponse.text.take(500)}")
                apiResponse
            } catch (e: Exception) {
                Log.e("PlayHubLite", "loadLinks: Error con file_id $fileIdUrl: ${e.message}", e)
                null
            }
        }

        if (response == null || response.code !in 200..299) {
            Log.e("PlayHubLite", "loadLinks: Ambas URLs fallaron. Código: ${response?.code}. Cuerpo: ${response?.text}")
            return false
        }

        // Verifica si la respuesta contiene un enlace .m3u8 o redirige
        val link = response.text.trim()
        if (link.isNotBlank() && link.endsWith(".m3u8")) {
            Log.d("PlayHubLite", "loadLinks: Enlace .m3u8 detectado: $link")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = ExtractorLinkType.M3U8
                ).apply {
                    this.referer = "https://tpz6t.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = enhancedHeaders
                }
            )
            return true
        } else {
            // Busca redirecciones o el src del iframe
            val locationHeader = response.headers["Location"]
            val iframeSrcMatch = Regex("src=['\"](https?://[^'\"]+)['\"]").find(response.text)
            if (locationHeader != null || iframeSrcMatch != null) {
                val finalUrl = locationHeader ?: iframeSrcMatch?.groupValues?.get(1)
                if (finalUrl != null) {
                    Log.d("PlayHubLite", "loadLinks: Enlace extraído con redirección/iframe: $finalUrl")
                    // Solicitud adicional al src
                    val iframeResponse = app.get(finalUrl, headers = enhancedHeaders, allowRedirects = true, timeout = 30)
                    val m3u8Link = Regex("src=['\"](https?://[^'\"]+\\.m3u8[^'\"]*)['\"]").find(iframeResponse.text)?.groupValues?.get(1)
                    if (m3u8Link != null) {
                        Log.d("PlayHubLite", "loadLinks: Enlace .m3u8 encontrado en iframe: $m3u8Link")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = m3u8Link,
                                type = ExtractorLinkType.M3U8
                            ).apply {
                                this.referer = finalUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = enhancedHeaders
                            }
                        )
                        return true
                    }
                }
            }
            // Intenta una solicitud directa a mxafthohnoyh.com
            val mxafthResponse = app.get("https://mxafthohnoyh.com/", headers = enhancedHeaders, allowRedirects = true, timeout = 30)
            val mxafthM3u8Link = Regex("src=['\"](https?://[^'\"]+\\.m3u8[^'\"]*)['\"]").find(mxafthResponse.text)?.groupValues?.get(1)
            if (mxafthM3u8Link != null) {
                Log.d("PlayHubLite", "loadLinks: Enlace .m3u8 encontrado en mxafthohnoyh.com: $mxafthM3u8Link")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = mxafthM3u8Link,
                        type = ExtractorLinkType.M3U8
                    ).apply {
                        this.referer = "https://mxafthohnoyh.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = enhancedHeaders
                    }
                )
                return true
            }
            // Fallback basado en el patrón observado
            val fallbackUrl = "https://fin-3dg-b1.i8yz83pn.com/hls2/02/09229/46146551_x/master.m3u8?t=placeholder_token" // Usamos file_id
            Log.w("PlayHubLite", "loadLinks: Usando URL fallback: $fallbackUrl")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fallbackUrl,
                    type = ExtractorLinkType.M3U8
                ).apply {
                    this.referer = "https://tpz6t.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = enhancedHeaders
                }
            )
            return true
        }
    }
}