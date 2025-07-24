package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// REMOVED java.time IMPORTS:
// import java.time.Instant
// import java.time.ZoneId
// import java.time.LocalDate

// ADDED java.util IMPORTS FOR DATE HANDLING:
import java.util.Date
import java.util.Calendar


class AnimeonsenProvider : MainAPI() {
    override var mainUrl = "https://www.animeonsen.xyz"
    override var name = "AnimeOnsen"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie,
        TvType.TvSeries,
    )

    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private var apiOrigin: String = "https://api.animeonsen.xyz"
    private var searchOrigin: String = "https://search.animeonsen.xyz"
    private var searchToken: String? = null

    private val cfKiller = CloudflareKiller()

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        headers: Map<String, String>? = null
    ): String? {
        for (i in 0 until retries) {
            try {
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = headers ?: emptyMap())

                if (res.isSuccessful) {
                    Log.d("AnimeOnsen", "safeAppGet - Petición exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("AnimeOnsen", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("AnimeOnsen", "safeAppGet - Reintentando en ${delayMs / 1000.0} segundos...")
                delay(delayMs)
            }
        }
        Log.e("AnimeOnsen", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }

    private suspend fun safeAppPost(
        url: String,
        data: Any?,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        headers: Map<String, String>? = null
    ): String? {
        val jsonBodyString = data?.toJson()
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonBodyString?.toRequestBody(mediaType)

        val postHeaders = (headers ?: emptyMap()) + mapOf("Content-Type" to "application/json")

        for (i in 0 until retries) {
            try {
                val res = app.post(url, requestBody = requestBody, interceptor = cfKiller, timeout = timeoutMs, headers = postHeaders)

                if (res.isSuccessful) {
                    Log.d("AnimeOnsen", "safeAppPost - Petición POST exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("AnimeOnsen", "safeAppPost - Petición POST fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "safeAppPost - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("AnimeOnsen", "safeAppPost - Reintentando en ${delayMs / 1000.0} segundos...")
                delay(delayMs)
            }
        }
        Log.e("AnimeOnsen", "safeAppPost - Fallaron todos los intentos para URL: $url")
        return null
    }

    // --- Data Classes para la API de AnimeOnsen ---

    data class AnimeOnsenContentIndexResponse(
        val cursor: Cursor?,
        val content: List<AnimeOnsenContentItem>?
    )

    data class Cursor(
        val start: Int?,
        val limit: Int?,
        val next: List<Any>?
    )

    data class AnimeOnsenContentItem(
        val content_id: String,
        val content_title: String?,
        val content_title_en: String?,
        val total_episodes: Int?,
        val date_added: Long?
    ) {
        val preferredTitle: String
            get() = content_title_en ?: content_title ?: "Título Desconocido"
    }

    data class AnimeOnsenExtensiveContent(
        val content_id: String,
        val content_title: String?,
        val content_title_en: String?,
        val data_type: String?,
        val is_movie: Boolean?,
        val subtitle_support: Boolean?,
        val total_episodes: Int?,
        val previous_season: String?,
        val next_season: String?,
        val mal_id: Int?,
        val mal_data: MalData?,
        val available: Boolean?,
        val date_added: Long? // This is the Unix timestamp in SECONDS
    ) {
        val preferredTitle: String
            get() = content_title_en ?: content_title ?: "Título Desconocido"
    }

    data class MalData(
        val synopsis: String?,
        val mean_score: Double?,
        val genres: List<Genre>?,
        val status: String?,
        val broadcast: Broadcast?,
        val rating: String?,
        val studios: List<Studio>?,
    )

    data class Genre(
        val id: Int,
        val name: String
    )

    data class Broadcast(
        val day_of_the_week: String?,
        val start_time: String?
    )

    data class Studio(
        val id: Int,
        val name: String
    )

    data class EpisodeTitles(
        val contentTitle_episode_en: String?,
        val contentTitle_episode_jp: String?
    )

    data class SearchRequestBody(
        val query: String
    )

    data class SearchResponseRoot(
        val hits: List<SearchHit>?,
        val query: String?,
        val processingTimeMs: Long?,
        val limit: Int?,
        val offset: Int?,
        val estimatedTotalHits: Int?
    )

    data class SearchHit(
        val content_title: String?,
        val content_title_en: String?,
        val content_title_jp: String?,
        val content_id: String
    ) {
        val preferredTitle: String
            get() = content_title_en ?: content_title ?: content_title_jp ?: "Título Desconocido"
    }

    // --- Fin Data Classes ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("AnimeOnsen", "DEBUG: Iniciando getMainPage, página: $page, solicitud: ${request.name}")

        val mainPageHtml = safeAppGet(mainUrl)
        if (mainPageHtml != null) {
            val doc = Jsoup.parse(mainPageHtml)
            searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
            apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
            searchOrigin = doc.selectFirst("meta[name=ao-search-origin]")?.attr("content") ?: searchOrigin
            Log.d("AnimeOnsen", "Token de búsqueda obtenido: $searchToken")
            Log.d("AnimeOnsen", "API Origin: $apiOrigin")
            Log.d("AnimeOnsen", "Search Origin: $searchOrigin")
        } else {
            Log.e("AnimeOnsen", "No se pudo obtener el HTML de la página principal para extraer el token.")
        }

        val items = ArrayList<HomePageList>()

        val headersForApi = searchToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

        val contentIndexUrl = "$apiOrigin/v4/content/index?start=${(page - 1) * 30}&limit=30"
        val contentIndexJson = safeAppGet(contentIndexUrl, headers = headersForApi)

        if (contentIndexJson != null) {
            val contentIndexResponse = tryParseJson<AnimeOnsenContentIndexResponse>(contentIndexJson)
            if (contentIndexResponse != null && contentIndexResponse.content != null) {
                val homeItems = contentIndexResponse.content.mapNotNull { item ->
                    newAnimeSearchResponse(
                        item.preferredTitle,
                        "$mainUrl/anime/${item.content_id}"
                    ) {
                        this.type = TvType.Anime
                    }
                }
                items.add(HomePageList("Animes Recientes", homeItems))
            } else {
                Log.e("AnimeOnsen", "Error al parsear JSON de índice de contenido o contenido nulo: $contentIndexJson")
            }
        } else {
            Log.e("AnimeOnsen", "No se pudo obtener el índice de contenido de la API.")
        }

        Log.d("AnimeOnsen", "DEBUG: getMainPage finalizado. ${items.size} listas añadidas.")
        val hasNextPage = (contentIndexJson != null && tryParseJson<AnimeOnsenContentIndexResponse>(contentIndexJson)?.cursor?.next?.getOrNull(0) as? Boolean == true)
        return newHomePageResponse(items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("AnimeOnsen", "DEBUG: Iniciando search para query: $query")

        if (searchToken == null) {
            Log.w("AnimeOnsen", "Token de búsqueda no disponible. Intentando obtenerlo.")
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                searchOrigin = doc.selectFirst("meta[name=ao-search-origin]")?.attr("content") ?: searchOrigin
            }
        }

        if (searchToken.isNullOrBlank()) {
            Log.e("AnimeOnsen", "No se pudo obtener el token de búsqueda. La búsqueda podría fallar.")
            return emptyList()
        }

        val searchUrl = "$searchOrigin/indexes/content/search"
        val headers = searchToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
        val requestBody = SearchRequestBody(query)

        val searchJson = safeAppPost(searchUrl, requestBody, headers = headers)
        if (searchJson == null) {
            Log.e("AnimeOnsen", "search - No se pudo obtener JSON para la búsqueda POST: $searchUrl")
            return emptyList()
        }

        val searchResponse = tryParseJson<SearchResponseRoot>(searchJson)
        val searchHits = searchResponse?.hits

        if (searchHits.isNullOrEmpty()) {
            Log.d("AnimeOnsen", "No se encontraron resultados de búsqueda o el parseo falló.")
            return emptyList()
        }

        return searchHits.mapNotNull { hit ->
            newAnimeSearchResponse(
                hit.preferredTitle,
                "$mainUrl/anime/${hit.content_id}"
            ) {
                this.type = TvType.Anime
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String,
        val contentId: String,
        val episodeNumber: Int?
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("AnimeOnsen", "load - URL de entrada: $url")

        val animeId = url.substringAfterLast("/anime/").substringBefore("?")
        if (animeId.isBlank()) {
            Log.e("AnimeOnsen", "load - ERROR: No se pudo extraer el ID del anime de la URL: $url")
            return null
        }

        if (searchToken == null) {
            Log.w("AnimeOnsen", "Token de búsqueda no disponible. Intentando obtenerlo.")
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
            }
        }

        val headers = searchToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

        val extensiveApiUrl = "$apiOrigin/v4/content/$animeId/extensive"
        val extensiveJson = safeAppGet(extensiveApiUrl, headers = headers)
        if (extensiveJson == null) {
            Log.e("AnimeOnsen", "load - No se pudo obtener JSON extenso del anime de la API para ID: $animeId")
            return null
        }
        val extensiveContent = tryParseJson<AnimeOnsenExtensiveContent>(extensiveJson)
        if (extensiveContent == null) {
            Log.e("AnimeOnsen", "load - Error al parsear JSON extenso del anime para ID: $animeId: $extensiveJson")
            return null
        }

        val episodesApiUrl = "$apiOrigin/v4/content/$animeId/episodes"
        val episodesJson = safeAppGet(episodesApiUrl, headers = headers)
        var parsedEpisodes: Map<String, EpisodeTitles>? = null
        if (episodesJson != null) {
            parsedEpisodes = tryParseJson<Map<String, EpisodeTitles>>(episodesJson)
            if (parsedEpisodes == null) {
                Log.e("AnimeOnsen", "load - Error al parsear JSON de episodios (Map<String, EpisodeTitles>): $episodesJson")
            }
        } else {
            Log.w("AnimeOnsen", "load - No se pudo obtener JSON de episodios para ID: $animeId")
        }

        val title = extensiveContent.preferredTitle
        val description = extensiveContent.mal_data?.synopsis
        val tags = extensiveContent.mal_data?.genres?.map { it.name }
        val tvType = if (extensiveContent.is_movie == true) TvType.Movie else TvType.Anime

        var poster: String? = null
        var banner: String? = null
        try {
            val animePageHtml = app.get("$mainUrl/anime/$animeId", interceptor = cfKiller).text
            val animeDoc = Jsoup.parse(animePageHtml)
            poster = animeDoc.selectFirst("div.content__poster img")?.attr("src")
            banner = animeDoc.selectFirst("div.content__banner img")?.attr("src")
            if (poster.isNullOrBlank()) Log.w("AnimeOnsen", "No se encontró poster para $animeId")
            if (banner.isNullOrBlank()) Log.w("AnimeOnsen", "No se encontró banner para $animeId")
        } catch (e: Exception) {
            Log.e("AnimeOnsen", "Error al raspar el poster/banner de la página del anime: ${e.message}", e)
        }

        val episodesList = if (tvType == TvType.Anime) {
            val totalEpisodes = extensiveContent.total_episodes ?: parsedEpisodes?.size ?: 0
            (1..totalEpisodes).mapNotNull { i ->
                val episodeTitles = parsedEpisodes?.get(i.toString())
                val episodeTitle = episodeTitles?.contentTitle_episode_en ?: episodeTitles?.contentTitle_episode_jp ?: "Episodio $i"
                newEpisode(
                    EpisodeLoadData(episodeTitle, "$mainUrl/anime/$animeId/episode/$i", animeId, i).toJson()
                ) {
                    this.name = episodeTitle
                    this.season = 1
                    this.episode = i
                }
            }
        } else listOf()

        val recommendations = listOf<SearchResponse>()

        // CAMBIO AQUI: Uso de Calendar para obtener el año
        val year = extensiveContent.date_added?.let { timestampSeconds ->
            try {
                // Convertir timestamp de segundos a milisegundos para Date
                val date = Date(timestampSeconds * 1000L)
                val calendar = Calendar.getInstance()
                calendar.time = date
                calendar.get(Calendar.YEAR)
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "Error al parsear la fecha usando Calendar: ${e.message}", e)
                null
            }
        }

        return when (tvType) {
            TvType.Anime, TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = url,
                    type = tvType,
                    episodes = episodesList,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = banner ?: poster
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                    this.year = year // Usar el 'year' calculado
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(
                    name = title,
                    url = url,
                    type = tvType,
                    dataUrl = EpisodeLoadData(title, "$mainUrl/anime/$animeId/episode/1", animeId, 1).toJson()
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = banner ?: poster
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                    this.year = year // Usar el 'year' calculado
                }
            }
            else -> null
        }
    }

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
            Log.e("AnimeOnsen", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("AnimeOnsen", "loadLinks - Data de entrada: $data")

        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val animeId = parsedEpisodeData?.contentId
        val episodeNumber = parsedEpisodeData?.episodeNumber

        if (animeId.isNullOrBlank() || episodeNumber == null) {
            Log.e("AnimeOnsen", "loadLinks - No se pudo extraer el ID del anime o el número de episodio de la data: $data")
            return false
        }

        if (searchToken == null) {
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
            }
        }

        val mpdUrl = "https://cdn.animeonsen.xyz/video/mp4-dash/$animeId/$episodeNumber/manifest.mpd"
        Log.d("AnimeOnsen", "loadLinks - URL MPD construida: $mpdUrl")

        callback(
            newExtractorLink(
                source = "AnimeOnsen (DASH)",
                name = "Reproducir",
                url = mpdUrl,
                type = ExtractorLinkType.DASH
            ) {
                referer = mainUrl
            }
        )
        var foundLinks = true

        val headers = searchToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
        val subtitlesLanguagesUrl = "$apiOrigin/v4/subtitles/$animeId/languages"
        val subtitlesLanguagesJson = safeAppGet(subtitlesLanguagesUrl, headers = headers)
        if (subtitlesLanguagesJson != null) {
            val languagesMap = tryParseJson<Map<String, String>>(subtitlesLanguagesJson)
            languagesMap?.forEach { (langCode, langName) ->
                val subtitleUrl = "$apiOrigin/v4/subtitles/$animeId/$episodeNumber/$langCode.vtt"
                subtitleCallback(
                    SubtitleFile(
                        langName,
                        subtitleUrl
                    )
                )
                Log.d("AnimeOnsen", "Añadido subtítulo: $langName - $subtitleUrl")
            }
        } else {
            Log.w("AnimeOnsen", "No se pudieron obtener los idiomas de subtítulos para $animeId/$episodeNumber")
        }


        if (!foundLinks) {
            Log.e("AnimeOnsen", "loadLinks - No se encontraron enlaces de video válidos.")
        }
        return foundLinks
    }
}