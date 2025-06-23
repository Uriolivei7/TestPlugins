package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
                        val episodeSourceDataUrl = "$mainUrl/series/$seriesID/season/$seasonNum/episode/$epNum" // Usar la URL de la página del episodio
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
                val movieSourceDataUrl = "$mainUrl/movies/$id" // Usar la URL de la página de la película
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
        @JsonProperty("server"    ) var server    : String? = null,
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

    // Función para desofuscar el P.A.C.K.E.D. JS
    private fun decodePackedJs(p: String, a: Int, c: Int, k: List<String>): String {
        var decodedP = p
        var currentC = c
        while (currentC-- > 0) {
            if (k.isNotEmpty() && currentC < k.size && k[currentC].isNotEmpty()) {
                // Escapamos los caracteres especiales para la regex
                val replacement = Regex.escapeReplacement(k[currentC])
                val regex = "\\b" + currentC.toString(a) + "\\b"
                decodedP = decodedP.replace(regex.toRegex(), replacement)
            }
        }
        return decodedP
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("PlayHubLite", "loadLinks - Data de entrada (URL de la página del reproductor): $data")

        if (data.isBlank()) {
            Log.e("PlayHubLite", "loadLinks: La URL de la página del reproductor está vacía.")
            return false
        }

        // 1. Obtener el HTML de la página del reproductor
        val playerPageResponse = app.get(data, headers = mapOf("Referer" to mainUrl)) // Añadir referer
        val playerPageHtml = playerPageResponse.text

        // Extraer el file_code de la URL de la página de datos
        // El file_code puede ser el ID numérico o el ID alfanumérico como '5tajj3vt6jno'
        val fileCodeMatch = Regex("/([^/]+)(?:/season/(\\d+)/episode/(\\d+))?$").find(data)
        val fileCode = fileCodeMatch?.groupValues?.get(1) // Captura el ID principal (ej: 47856551 o 5tajj3vt6jno)
        val seasonNum = fileCodeMatch?.groupValues?.get(2)
        val episodeNum = fileCodeMatch?.groupValues?.get(3)

        if (fileCode.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: No se pudo extraer el file_code de la URL: $data")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: File Code extraído: $fileCode")


        // 2. Extraer la clave JWPlayer
        val jwPlayerKeyMatch = Regex("jwplayer\\.key = \"([^\"]+)\";").find(playerPageHtml)
        val jwPlayerKey = jwPlayerKeyMatch?.groupValues?.get(1)
        if (jwPlayerKey.isNullOrBlank()) {
            Log.w("PlayHubLite", "loadLinks: No se pudo encontrar la clave de JWPlayer en el HTML. Esto puede no ser crítico.")
            // No es un error crítico si la clave JWPlayer no está directamente, la URL del stream es más importante.
        } else {
            Log.d("PlayHubLite", "loadLinks: JWPlayer Key encontrada: $jwPlayerKey")
        }


        // 3. Desofuscar el script `eval`
        val evalRegex = Regex("eval\\(function\\(p, a, c, k, e, d\\) \\{.*?return p \\}\\('([^']+)', (\\d+), (\\d+), \\[([^\\]]*?)\\](?:,.*?)*\\)\\);")
        val evalMatch = evalRegex.find(playerPageHtml)

        if (evalMatch == null) {
            Log.e("PlayHubLite", "loadLinks: No se encontró el script ofuscado `eval` en el HTML.")
            return false
        }

        val p = evalMatch.groupValues[1]
        val a = evalMatch.groupValues[2].toIntOrNull() ?: 0
        val c = evalMatch.groupValues[3].toIntOrNull() ?: 0
        val kString = evalMatch.groupValues[4].replace("'", "").split(",").map { it.trim() } // Clean up and split k values

        Log.d("PlayHubLite", "loadLinks: Desofuscando script con p: ${p.take(50)}, a: $a, c: $c, k-size: ${kString.size}")

        val deobfuscatedScript = decodePackedJs(p, a, c, kString)
        Log.d("PlayHubLite", "loadLinks: Script desofuscado (primeros 500 chars): ${deobfuscatedScript.take(500)}")

        // 4. Parsear el script desofuscado para obtener la URL del M3U8 y el token
        // "file":"https://ye0r0rrinu.cdn-centaurus.com/hls2/01/09571/5tajj3vt6jno_h/master.m3u8?t=7lrfQg7XLF8pWleX2zfYpx..."
        val m3u8UrlRegex = Regex("\"file\":\"(https://[^\"?]+\\.m3u8\\?t=[^\"]+)\"")
        val m3u8UrlMatch = m3u8UrlRegex.find(deobfuscatedScript)

        val m3u8FullUrl = m3u8UrlMatch?.groupValues?.get(1)

        if (m3u8FullUrl.isNullOrBlank()) {
            Log.e("PlayHubLite", "loadLinks: No se pudo extraer la URL completa del M3U8 y su token del script desofuscado.")
            return false
        }
        Log.d("PlayHubLite", "loadLinks: URL completa del M3u8 extraída: $m3u8FullUrl")

        // 5. Construir la URL final del M3U8 y añadir los ExtractorLinks
        // Se observa que el `file_code` en la URL del CDN tiene un `_h` al final (ej: 5tajj3vt6jno_h)
        // El `fileCode` que extraemos de la URL de `data` (ej: 47856551 o 5tajj3vt6jno) debe ser el mismo.
        // Si el `fileCode` en la URL del M3U8 es diferente, se reemplaza.
        // Asumimos que el fileCode en la URL del M3U8 se encuentra antes de "_h".
        val finalM3u8Url = m3u8FullUrl.replace(Regex("(/hls2/[^/]+/[^/]+/)([^/]+)_h(/master\\.m3u8)"), "$1${fileCode}_h$3")
        Log.d("PlayHubLite", "loadLinks: URL final del M3U8 para callback: $finalM3u8Url")

        // Headers para la solicitud del M3U8
        val m3u8Headers = mapOf(
            "Referer" to data, // El referer debe ser la URL de la página del reproductor
            "Origin" to mainUrl // El origin debe ser el dominio principal
        )

        // Añadir el ExtractorLink
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Playhub",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
                this.headers = m3u8Headers
            }
        )

        // 6. Extraer y añadir subtítulos
        // Las URLs de los subtítulos se ven como: https://ye0r0rrinu.cdn-centaurus.com/vtt/5tajj3vt6jno/5tajj3vt6jno_spa.vtt
        // Y provienen del mismo CDN que el M3U8.
        // La URL base del CDN la obtenemos de la m3u8FullUrl.
        val cdnBaseUrlMatch = Regex("(https://[^/]+)/hls2/").find(m3u8FullUrl)
        val cdnBaseUrl = cdnBaseUrlMatch?.groupValues?.get(1) // Esto debería capturar "https://ye0r0rrinu.cdn-centaurus.com"

        if (!cdnBaseUrl.isNullOrBlank()) {
            val subtitleLanguages = mapOf(
                "spa" to "Español",
                "eng" to "English",
                "ger" to "Deutsch",
                "jpn" to "日本語",
                "por" to "Português"
            )

            subtitleLanguages.forEach { (langCode, langName) ->
                val subtitleUrl = "$cdnBaseUrl/vtt/$fileCode/${fileCode}_${langCode}.vtt" // Correcto
                Log.d("PlayHubLite", "loadLinks: Intentando añadir subtítulo: $subtitleUrl")
                subtitleCallback.invoke(
                    SubtitleFile(
                        langName,
                        subtitleUrl
                        // El constructor de SubtitleFile en CloudStream3 a menudo solo toma lang y url.
                        // Si se necesitan más argumentos, revisa la definición de SubtitleFile en tu dependencia de CloudStream3.
                    )
                )
            }
        } else {
            Log.w("PlayHubLite", "loadLinks: No se pudo determinar la URL base del CDN para los subtítulos.")
        }

        return true
    }
}