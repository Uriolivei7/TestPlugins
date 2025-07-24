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

import java.util.Date
import java.util.Calendar
import kotlin.collections.List
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse


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
    private var apiAuthToken: String? = null // ¡Esta línea debe estar aquí!

    private val cfKiller = CloudflareKiller()

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        headers: Map<String, String>? = null
    ): String? {
        val requestHeaders = (headers?.toMutableMap() ?: mutableMapOf()).apply {
            // Solo añadir Authorization Bearer si apiAuthToken existe Y la URL no es la del índice principal
            // ya que esa URL funcionaba sin token en el navegador.
            if (apiAuthToken != null && url != "$apiOrigin/v4/content/index" && !url.startsWith("$apiOrigin/v4/content/index?")) {
                this["Authorization"] = "Bearer $apiAuthToken"
            }
        }

        for (i in 0 until retries) {
            try {
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = requestHeaders)
                if (res.isSuccessful) return res.text
                Log.w("AnimeOnsen", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
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

        val postHeaders = (headers?.toMutableMap() ?: mutableMapOf()).apply {
            this["Content-Type"] = "application/json"
            // Añadir encabezados estándar que el navegador envía y que podrían ser necesarios
            this["Origin"] = mainUrl
            this["Referer"] = mainUrl + "/" // Asegura que termine en barra
            this["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36" // Tu User-Agent original

            // Añadir Authorization para la búsqueda si el searchToken existe
            if (url.startsWith(searchOrigin) && searchToken != null) {
                this["Authorization"] = "Bearer $searchToken"
            }
            // Aunque apiAuthToken no se usa para getMainPage, si load/loadLinks lo necesitan
            // y se obtiene de algún lado, la lógica aquí se encargará de añadirlo.
            else if (url.startsWith(apiOrigin) && apiAuthToken != null) {
                this["Authorization"] = "Bearer $apiAuthToken"
            }
        }

        for (i in 0 until retries) {
            try {
                val res = app.post(url, requestBody = requestBody, interceptor = cfKiller, timeout = timeoutMs, headers = postHeaders)
                if (res.isSuccessful) return res.text
                Log.w("AnimeOnsen", "safeAppPost - Petición POST fallida para URL: $url con código ${res.code}. Error HTTP.")
            } catch (e: Exception) {
                Log.e("AnimeOnsen", "safeAppPost - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        Log.e("AnimeOnsen", "safeAppPost - Fallaron todos los intentos para URL: $url")
        return null
    }

    // Nuevas data classes para MainPage
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
            get() = content_title_en ?: content_title ?: "Título Desconocido"
    }

    // Tus otras data classes permanecen igual
    data class AnimeOnsenSpotlightResponse(
        val content: List<AnimeOnsenSpotlightItem>
    )

    data class AnimeOnsenSpotlightItem(
        val content_id: String,
        val content_title: List<String>?,
        val content_description: String?
    ) {
        val preferredTitle: String
            get() = content_title?.getOrNull(1) ?: content_title?.getOrNull(0) ?: "Título Desconocido"
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
        val date_added: Long?
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
            get() = content_title_en ?: content_title ?: content_title_jp ?: "Título Desconocido"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val mainPageHtml = safeAppGet(mainUrl)
        if (mainPageHtml != null) {
            val doc = Jsoup.parse(mainPageHtml)
            searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
            apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
            searchOrigin = doc.selectFirst("meta[name=ao-search-origin]")?.attr("content") ?: searchOrigin

            // No intentamos extraer apiAuthToken aquí para el endpoint de la página principal
            // ya que comprobamos que no lo requiere el navegador.
            apiAuthToken = null // Aseguramos que no se use un token obsoleto/incorrecto paragetMainPage

            Log.d("AnimeOnsen", "Token de búsqueda obtenido: $searchToken")
            Log.d("AnimeOnsen", "API Origin: $apiOrigin")
            Log.d("AnimeOnsen", "Search Origin: $searchOrigin")
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
                items.add(HomePageList("Todos los Animes", homeItems))

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
            Log.e("AnimeOnsen", "No se pudo obtener el token de búsqueda. La búsqueda podría fallar.")
            return emptyList()
        }

        val searchUrl = "$searchOrigin/indexes/content/search"
        // Los headers se configuran en safeAppPost
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

        // También necesitamos asegurar que apiAuthToken se obtenga si es necesario para load/loadLinks
        // Si estos endpoints requieren un token Bearer, deberías extraerlo en getMainPage
        // o antes de llamar a load. Por ahora, asumimos que no es necesario basado en los logs.
        if (searchToken == null) {
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
                // Si 'load' o 'loadLinks' necesitan apiAuthToken, deberías encontrar su origen aquí
                // apiAuthToken = doc.selectFirst("meta[name=ALGUN_TOKEN_PARA_LOAD]")?.attr("content")
            }
        }

        // Si apiAuthToken está nulo, los headers serán emptyMap()
        val headers = apiAuthToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

        val extensiveApiUrl = "$apiOrigin/v4/content/$animeId/extensive"
        // safeAppGet ya maneja si se envía o no Authorization basado en apiAuthToken
        val extensiveJson = safeAppGet(extensiveApiUrl, headers = headers)
        if (extensiveJson == null) return null
        val extensiveContent = tryParseJson<AnimeOnsenExtensiveContent>(extensiveJson)
        if (extensiveContent == null) return null

        val episodesApiUrl = "$apiOrigin/v4/content/$animeId/episodes"
        val episodesJson = safeAppGet(episodesApiUrl, headers = headers)
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
            poster = animeDoc.selectFirst("div.content__poster img")?.attr("src")
            banner = animeDoc.selectFirst("div.content__banner img")?.attr("src")
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
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val animeId = parsedEpisodeData?.contentId
        val episodeNumber = parsedEpisodeData?.episodeNumber

        if (animeId.isNullOrBlank() || episodeNumber == null) return false

        if (searchToken == null) {
            val mainPageHtml = safeAppGet(mainUrl)
            if (mainPageHtml != null) {
                val doc = Jsoup.parse(mainPageHtml)
                searchToken = doc.selectFirst("meta[name=ao-search-token]")?.attr("content")
                apiOrigin = doc.selectFirst("meta[name=ao-api-origin]")?.attr("content") ?: apiOrigin
            }
        }

        val mpdUrl = "https://cdn.animeonsen.xyz/video/mp4-dash/$animeId/$episodeNumber/manifest.mpd"
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

        val headers = apiAuthToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
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
            }
        }
        return true
    }
}