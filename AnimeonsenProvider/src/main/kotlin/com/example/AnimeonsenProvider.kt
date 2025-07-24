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
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Date
import java.util.Calendar
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.fasterxml.jackson.annotation.JsonProperty // Necesario para @JsonProperty

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
    // ADVERTENCIA: Este token expira el 1 de agosto de 2025.
    private var apiAuthToken: String? = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImRlZmF1bHQifQ.eyJpc3MiOiJodHRwczovL2F1dGguYW5pbWVvbnNlbi54eXovIiwiYXVkIjoiaHR0cHM6Ly9hcGkuYW5pbWVvbnNlbi54eXoiLCJpYXQiOjE3NTMzOTU0ODcsImV4cCI6MTc1NDAwMDI4Nywic3ViIjoiMDZkMjJiOTYtNjNlNy00NmE5LTgwZmMtZGM0NDFkNDFjMDM4LmNsaWVudCIsImF6cCI6IjA2ZDIyYjk2LTYzZTctNDZhOS04MGZjLWRjNDQxZDRxYzAzOCIsImd0eSI6ImNsaWVudF9jcmVkZW50aWFscyJ9.xTqP444XaqD1Z_U_x4dCvgVHYWeGo5yt8y0uBXonRXu8Q5PtHjM0s6bVPtGWIwomVTtNfIexjviNflTqioMUju1GHEnFo7jUYNkvJbtyGt6PJxE2BNtxhF6v3UrVrc3XuuWtb2t8tz89TXY5oUhvLwjd6jkZ3p-PZ6OlDFJ8mCRkyJ7UF_yKK2F_3ppfDgil5dja9MuJupObJUGmLsUwVKhgtAM65EQTb6wf11ff0iCTYEkeYmY1tq-gcyfxQV-cQujoJZXt03sYkcYKogRwH9iwoGY6hcBSr1LUYcL2ORiak_2rNoPDAQadSPTTed6n4KbuncoQKPggEQn-KryN5A"
    private val cfKiller = CloudflareKiller()

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        headers: Map<String, String>? = null
    ): String? {
        val requestHeaders = (headers?.toMutableMap() ?: mutableMapOf()).apply {
            if (url.startsWith(apiOrigin)) { // Aplica a API y posiblemente subtítulos
                if (apiAuthToken != null) {
                    this["Authorization"] = "Bearer $apiAuthToken"
                }
                this["Origin"] = mainUrl
                this["Referer"] = "$mainUrl/"
                this["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
            }
            headers?.let { putAll(it) } // Añade cualquier header extra que se pase
        }

        for (i in 0 until retries) {
            try {
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = requestHeaders)
                if (res.isSuccessful) return res.text
                Log.w("AnimeOnsen", "safeAppGet - Request failed for URL: $url with code ${res.code}. HTTP Error.")
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "safeAppGet - Error on attempt ${i + 1}/$retries for URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        Log.e("AnimeOnsen", "safeAppGet - All attempts failed for URL: $url")
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

        val postHeaders = (headers?.toMutableMap() ?: mutableMapOf()).apply {
            this["Content-Type"] = "application/json"
            this["Origin"] = mainUrl
            this["Referer"] = "$mainUrl/"
            this["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

            if (url.startsWith(searchOrigin) && searchToken != null) {
                this["Authorization"] = "Bearer $searchToken"
            } else if (url.startsWith(apiOrigin) && apiAuthToken != null) {
                this["Authorization"] = "Bearer $apiAuthToken"
            }
            headers?.let { putAll(it) } // Añade cualquier header extra que se pase
        }

        for (i in 0 until retries) {
            try {
                val res = app.post(url, requestBody = requestBody, interceptor = cfKiller, timeout = timeoutMs, headers = postHeaders)
                if (res.isSuccessful) return res.text
                Log.w("AnimeOnsen", "safeAppPost - POST request failed for URL: $url with code ${res.code}. HTTP Error.")
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "safeAppPost - Error on attempt ${i + 1}/$retries for URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        Log.e("AnimeOnsen", "safeAppPost - All attempts failed for URL: $url")
        return null
    }

    data class AnimeOnsenMainPageResponse(
        val cursor: Cursor,
        val content: List<AnimeOnsenContentItemSimplified>
    )

    data class AnimeOnsenContentItemSimplified(
        val content_id: String,
        val content_title: String?,
        val content_title_en: String?,
        val total_episodes: Int?,
        val date_added: Long?
    ) {
        val preferredTitle: String
            get() = content_title_en ?: content_title ?: "Unknown Title"
    }

    data class AnimeOnsenSpotlightResponse(
        val content: List<AnimeOnsenSpotlightItem>
    )

    data class AnimeOnsenSpotlightItem(
        val content_id: String,
        val content_title: List<String>?,
        val content_description: String?
    ) {
        val preferredTitle: String
            get() = content_title?.getOrNull(1) ?: content_title?.getOrNull(0) ?: "Unknown Title"
    }

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
            get() = content_title_en ?: content_title ?: "Unknown Title"
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
        val date_added: Long?
    ) {
        val preferredTitle: String
            get() = content_title_en ?: content_title ?: "Unknown Title"
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
        val q: String
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
            get() = content_title_en ?: content_title ?: content_title_jp ?: "Unknown Title"
    }

    // --- Nuevas Data Classes para la respuesta de /v4/content/$animeId/video/$episodeNumber ---
    data class VideoApiResponse(
        val metadata: VideoMetadata,
        val uri: VideoUris
    )

    data class VideoMetadata(
        @JsonProperty("content_id") val contentId: String,
        @JsonProperty("content_title") val contentTitle: String,
        @JsonProperty("content_title_en") val contentTitleEn: String?,
        @JsonProperty("data_type") val dataType: String,
        @JsonProperty("is_movie") val isMovie: Boolean,
        @JsonProperty("subtitle_support") val subtitleSupport: Boolean,
        @JsonProperty("total_episodes") val totalEpisodes: Int,
        @JsonProperty("next_season") val nextSeason: String?,
        @JsonProperty("mal_id") val malId: Int,
        val episode: List<Any>, // Puede ser un Int o un mapa, se mantiene Any para flexibilidad
        val subtitles: Map<String, String> // Mapa de langCode a langName
    )

    data class VideoUris(
        val stream: String?,
        val subtitles: Map<String, String>? // Mapa de langCode a URL de subtítulo
    )
    // --- Fin Nuevas Data Classes ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val mainPageHtml = safeAppGet(mainUrl)
        if (mainPageHtml != null) {
            val doc = Jsoup.parse(mainPageHtml)
            searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
            apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
            searchOrigin = doc.selectFirst("meta[name=ao-search-origin]")?.attr("content") ?: searchOrigin

            Log.d("AnimeOnsen", "Search Token: $searchToken")
            Log.d("AnimeOnsen", "API Origin: $apiOrigin")
            Log.d("AnimeOnsen", "Search Origin: $searchOrigin")
            Log.d("AnimeOnsen", "API Auth Token: ${apiAuthToken?.take(30)}...")
        }

        val items = mutableListOf<HomePageList>()
        val allContentUrl = "$apiOrigin/v4/content/index?start=${(page - 1) * 30}&limit=30"
        val allContentJson = safeAppGet(allContentUrl)
        var hasNextPage = false

        if (allContentJson != null) {
            val mainPageResponse = tryParseJson<AnimeOnsenMainPageResponse>(allContentJson)
            if (mainPageResponse != null && mainPageResponse.content.isNotEmpty()) {
                val homeItems = mainPageResponse.content.mapNotNull { item ->
                    newAnimeSearchResponse(
                        item.preferredTitle,
                        "$mainUrl/anime/${item.content_id}"
                    ) {
                        this.type = TvType.Anime
                    }
                }
                items.add(HomePageList("All Anime", homeItems))
                hasNextPage = mainPageResponse.cursor.next?.getOrNull(0) == true
            }
        }
        return newHomePageResponse(
            list = items.toList(),
            hasNext = hasNextPage
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (searchToken == null) {
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                searchOrigin = doc.selectFirst("meta[name=ao-search-origin]")?.attr("content") ?: searchOrigin
            }
        }

        if (searchToken.isNullOrBlank()) {
            Log.e("AnimeOnsen", "Could not get search token. Search might fail.")
            return emptyList()
        }

        val searchUrl = "$searchOrigin/indexes/content/search"
        val requestBody = SearchRequestBody(q = query)

        val searchJson = safeAppPost(searchUrl, requestBody)
        if (searchJson == null) return emptyList()

        val searchResponse = tryParseJson<SearchResponseRoot>(searchJson)
        val searchHits = searchResponse?.hits

        if (searchHits.isNullOrEmpty()) return emptyList()

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
        val animeId = url.substringAfterLast("/anime/").substringBefore("?")
        if (animeId.isBlank()) return null

        // Refrescar tokens si es necesario
        if (searchToken == null || apiAuthToken == null) {
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
                // No se obtiene apiAuthToken de meta tags, así que se asume que siempre está hardcodeado o se obtiene de otra forma.
                // Si el apiAuthToken necesita refrescarse, la lógica debe implementarse aquí o en safeAppGet/Post.
            }
        }

        val extensiveApiUrl = "$apiOrigin/v4/content/$animeId/extensive"
        val extensiveJson = safeAppGet(extensiveApiUrl)
        if (extensiveJson == null) return null
        val extensiveContent = tryParseJson<AnimeOnsenExtensiveContent>(extensiveJson)
        if (extensiveContent == null) return null

        val episodesApiUrl = "$apiOrigin/v4/content/$animeId/episodes"
        val episodesJson = safeAppGet(episodesApiUrl)
        var parsedEpisodes: Map<String, EpisodeTitles>? = null
        if (episodesJson != null) {
            parsedEpisodes = tryParseJson<Map<String, EpisodeTitles>>(episodesJson)
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
            // Nuevo selector para el póster basado en el HTML proporcionado:
            poster = animeDoc.selectFirst("img[alt^=Image of]")?.attr("src")
            // No hay un selector claro para un banner distinto en el HTML proporcionado,
            // por lo que se usa el póster como fallback. Si existe un banner,
            // se necesitaría un selector específico aquí.
            banner = null // O podrías intentar buscar un banner con otro selector
            Log.d("AnimeOnsen", "Poster URL: $poster")
            Log.d("AnimeOnsen", "Banner URL: $banner")
        } catch (e: Exception) {
            Log.e("AnimeOnsen", "Error scraping poster/banner from anime page: ${e.message}", e)
        }

        val episodesList = if (tvType == TvType.Anime) {
            val totalEpisodes = extensiveContent.total_episodes ?: parsedEpisodes?.size ?: 0
            (1..totalEpisodes).mapNotNull { i ->
                val episodeTitles = parsedEpisodes?.get(i.toString())
                val episodeTitle = episodeTitles?.contentTitle_episode_en ?: episodeTitles?.contentTitle_episode_jp ?: "Episode $i"
                newEpisode(
                    EpisodeLoadData(episodeTitle, "$mainUrl/anime/$animeId/episode/$i", animeId, i).toJson()
                ) {
                    this.name = episodeTitle
                    this.season = 1
                    this.episode = i
                }
            }
        } else listOf()

        val recommendations = listOf<SearchResponse>() // Implementar si es necesario

        val year = extensiveContent.date_added?.let { timestampSeconds ->
            try {
                val date = Date(timestampSeconds * 1000L)
                val calendar = Calendar.getInstance()
                calendar.time = date
                calendar.get(Calendar.YEAR)
            } catch (e: Exception) {
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
                    this.year = year
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
                    this.year = year
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
            Log.e("AnimeOnsen", "Error decrypting link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val animeId = parsedEpisodeData?.contentId
        val episodeNumber = parsedEpisodeData?.episodeNumber

        if (animeId.isNullOrBlank() || episodeNumber == null) {
            Log.e("AnimeOnsen", "loadLinks: Missing animeId or episodeNumber from parsed data.")
            return false
        }

        // Refrescar tokens si es necesario (ya implementado en getMainPage y load, pero asegurar aquí si no se llama antes)
        if (searchToken == null || apiAuthToken == null) {
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
            }
        }

        // Nueva llamada para obtener la URL del stream y los subtítulos con el formato correcto
        val videoApiUrl = "$apiOrigin/v4/content/$animeId/video/$episodeNumber"
        val videoApiResponseJson = safeAppGet(videoApiUrl)

        if (videoApiResponseJson == null) {
            Log.e("AnimeOnsen", "loadLinks: Failed to get video API response for $videoApiUrl")
            return false
        }

        val videoApiResponse = tryParseJson<VideoApiResponse>(videoApiResponseJson)
        if (videoApiResponse == null) {
            Log.e("AnimeOnsen", "loadLinks: Failed to parse video API response for $videoApiUrl")
            return false
        }

        // Procesar link del stream
        val streamUrl = videoApiResponse.uri.stream
        if (streamUrl != null) {
            callback(
                newExtractorLink(
                    source = "AnimeOnsen",
                    name = "Play", // O podrías usar un nombre más descriptivo si la API lo proporciona
                    url = streamUrl,
                    type = ExtractorLinkType.DASH // Asumiendo que siempre es DASH si viene de cdn.animeonsen.xyz/video/mp4-dash/
                ) {
                    referer = mainUrl // Referer para el stream CDN
                }
            )
        } else {
            Log.w("AnimeOnsen", "loadLinks: Stream URL not found in API response for $videoApiUrl")
        }

        // Procesar links de subtítulos
        videoApiResponse.uri.subtitles?.forEach { (langCode, subUrl) ->
            val langName = videoApiResponse.metadata.subtitles[langCode] ?: langCode
            subtitleCallback(
                SubtitleFile(
                    langName,
                    subUrl
                )
            )
            Log.d("AnimeOnsen", "Loaded SubtitleFile: lang=$langName, url=$subUrl")
        } ?: run {
            Log.i("AnimeOnsen", "loadLinks: No subtitles found in API response for $videoApiUrl")
        }

        return true
    }
}