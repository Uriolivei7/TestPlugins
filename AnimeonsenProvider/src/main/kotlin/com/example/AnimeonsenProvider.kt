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
import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.collections.toList // Importación para .toList() si fuera necesario
import com.lagradost.nicehttp.NiceResponse

class AnimeonsenProvider : MainAPI() {
    override var mainUrl = "https://www.animeonsen.xyz"
    override var name = "AnimeOnsen"
    override val supportedTypes = setOf(
        TvType.Anime
    )
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private var apiOrigin: String = "https://api.animeonsen.xyz"
    private var searchOrigin: String = "https://search.animeonsen.xyz"
    private var searchToken: String? = null
    private var apiAuthToken: String? = null
    private val cfKiller = CloudflareKiller()

    private fun base64Decode(input: String): String {
        return try {
            String(Base64.decode(input, Base64.DEFAULT), UTF_8)
        } catch (e: Exception) {
            Log.e("AnimeOnsen", "Base64 Decoding Error: ${e.message}")
            ""
        }
    }

    private fun deObfuscateToken(obfuscatedToken: String): String {
        return obfuscatedToken.map { char ->
            (char.code - 1).toChar()
        }.joinToString("")
    }

    private suspend fun getApiAuthTokenFromCookie(): String? {
        val mainPageResponse = app.get(mainUrl, interceptor = cfKiller)

        val cookiesList: List<Pair<String, String>> = mainPageResponse.cookies
                as? List<Pair<String, String>> ?: run {
            Log.e("AnimeOnsen", "mainPageResponse.cookies no es una lista de Pair<String, String>")
            return null
        }

        var aoSessionCookie: String? = null

        aoSessionCookie = cookiesList.firstOrNull { it.first == "ao.session" }?.second

        if (aoSessionCookie.isNullOrBlank()) {
            Log.w("AnimeOnsen", "ao.session cookie not found.")
            return null
        }

        val decodedCookieValue = base64Decode(aoSessionCookie)
        if (decodedCookieValue.isBlank()) {
            Log.w("AnimeOnsen", "Failed to Base64 decode ao.session cookie.")
            return null
        }

        val finalToken = decodedCookieValue // O deObfuscateToken(decodedCookieValue)
        return finalToken
    }

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        headers: Map<String, String>? = null
    ): String? {
        if (apiAuthToken == null && url.startsWith(apiOrigin)) {
            apiAuthToken = getApiAuthTokenFromCookie()
            if (apiAuthToken == null) {
                Log.e("AnimeOnsen", "Failed to obtain API Auth Token for GET requests.")
            }
        }

        val requestHeaders = (headers?.toMutableMap() ?: mutableMapOf()).apply {
            if (url.startsWith(apiOrigin)) {
                apiAuthToken?.let { this["Authorization"] = "Bearer $it" }
                this["Origin"] = mainUrl
                this["Referer"] = "$mainUrl/"
                this["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
            }
            headers?.let { putAll(it) }
        }

        for (i in 0 until retries) {
            try {
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = requestHeaders)
                if (res.isSuccessful) return res.text

                if (res.code == 401 && url.startsWith(apiOrigin)) {
                    Log.d("AnimeOnsen", "Received 401 for GET, attempting token refresh.")
                    apiAuthToken = getApiAuthTokenFromCookie()
                    if (apiAuthToken != null) {
                        requestHeaders["Authorization"] = "Bearer $apiAuthToken"
                        val retryRes = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = requestHeaders)
                        if (retryRes.isSuccessful) return retryRes.text
                        Log.w("AnimeOnsen", "Retry after 401 failed for URL: $url with code ${retryRes.code}.")
                    } else {
                        Log.e("AnimeOnsen", "Failed to refresh token after 401.")
                        return null
                    }
                }
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "safeAppGet - Error on attempt ${i + 1}/$retries for URL: $url: ${e.message}")
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
        if (apiAuthToken == null && url.startsWith(apiOrigin)) {
            apiAuthToken = getApiAuthTokenFromCookie()
            if (apiAuthToken == null) {
                Log.e("AnimeOnsen", "Failed to obtain API Auth Token for POST requests.")
            }
        }

        val jsonBodyString = data?.toJson()
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonBodyString?.toRequestBody(mediaType)

        val postHeaders = (headers?.toMutableMap() ?: mutableMapOf()).apply {
            this["Content-Type"] = "application/json"
            this["Origin"] = mainUrl
            this["Referer"] = "$mainUrl/"
            this["User-Agent"] = "Mozilla/50 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

            if (url.startsWith(searchOrigin) && searchToken != null) {
                this["Authorization"] = "Bearer $searchToken"
            } else if (url.startsWith(apiOrigin) && apiAuthToken != null) {
                this["Authorization"] = "Bearer $apiAuthToken"
            }
            headers?.let { putAll(it) }
        }

        for (i in 0 until retries) {
            try {
                val res = app.post(url, requestBody = requestBody, interceptor = cfKiller, timeout = timeoutMs, headers = postHeaders)
                if (res.isSuccessful) return res.text

                if (res.code == 401 && url.startsWith(apiOrigin)) {
                    Log.d("AnimeOnsen", "Received 401 for POST, attempting token refresh.")
                    apiAuthToken = getApiAuthTokenFromCookie()
                    if (apiAuthToken != null) {
                        postHeaders["Authorization"] = "Bearer $apiAuthToken"
                        val retryRes = app.post(url, requestBody = requestBody, interceptor = cfKiller, timeout = timeoutMs, headers = postHeaders)
                        if (retryRes.isSuccessful) return retryRes.text
                        Log.w("AnimeOnsen", "Retry after 401 failed for URL: $url with code ${retryRes.code}.")
                    } else {
                        Log.e("AnimeOnsen", "Failed to refresh token after 401.")
                        return null
                    }
                }
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "safeAppPost - Error on attempt ${i + 1}/$retries for URL: $url: ${e.message}")
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
        val episode: List<Any>,
        val subtitles: Map<String, String>
    )

    data class VideoUris(
        val stream: String?,
        val subtitles: Map<String, String>?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (apiAuthToken == null) {
            apiAuthToken = getApiAuthTokenFromCookie()
            if (apiAuthToken == null) {
                Log.w("AnimeOnsen", "Could not get initial API Auth Token on getMainPage.")
            } else {
                Log.d("AnimeOnsen", "Initial API Auth Token from Cookie: ${apiAuthToken?.take(30)}...")
            }
        }

        val mainPageHtml = safeAppGet(mainUrl)
        if (mainPageHtml != null) {
            val doc = Jsoup.parse(mainPageHtml)
            searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
            apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
            searchOrigin = doc.selectFirst("meta[name=ao-search-origin]")?.attr("content") ?: searchOrigin
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
        if (searchToken == null || apiAuthToken == null) {
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                searchOrigin = doc.selectFirst("meta[name=ao-search-origin]")?.attr("content") ?: searchOrigin
                if (apiAuthToken == null) {
                    apiAuthToken = getApiAuthTokenFromCookie()
                }
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

        if (searchToken == null || apiAuthToken == null) {
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
                if (apiAuthToken == null) {
                    apiAuthToken = getApiAuthTokenFromCookie()
                }
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
            poster = animeDoc.selectFirst("img[alt^=Image of]")?.attr("src")
            banner = null
        } catch (e: Exception) {
            Log.e("AnimeOnsen", "Error scraping poster/banner from anime page: ${e.message}")
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

        val recommendations = listOf<SearchResponse>()

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
            Log.e("AnimeOnsen", "Error decrypting link: ${e.message}")
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

        if (apiAuthToken == null) {
            apiAuthToken = getApiAuthTokenFromCookie()
            if (apiAuthToken == null) {
                Log.e("AnimeOnsen", "loadLinks: Failed to obtain API Auth Token. Cannot load links.")
                return false
            }
        }

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

        val streamUrl = videoApiResponse.uri.stream
        if (streamUrl != null) {
            callback(
                newExtractorLink(
                    source = "AnimeOnsen",
                    name = "Play",
                    url = streamUrl,
                    type = ExtractorLinkType.DASH
                ) {
                    referer = mainUrl
                }
            )
        } else {
            Log.w("AnimeOnsen", "loadLinks: Stream URL not found in API response for $videoApiUrl")
        }

        videoApiResponse.uri.subtitles?.forEach { (langCode, subUrl) ->
            val langName = videoApiResponse.metadata.subtitles[langCode] ?: langCode
            subtitleCallback(
                SubtitleFile(
                    langName,
                    subUrl
                )
            )
        } ?: run {
            Log.i("AnimeOnsen", "loadLinks: No subtitles found in API response for $videoApiUrl")
        }

        return true
    }
}