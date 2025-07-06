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
import kotlin.text.RegexOption // <-- Importación necesaria para RegexOption

import com.fasterxml.jackson.annotation.JsonProperty

class VerpelisHDProvider : MainAPI() {
    override var mainUrl = "https://verpelishd.me/portal"
    private val searchBaseUrl = "https://verpelishd.me"
    override var name = "VerpelisHD"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    private val DEFAULT_WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val PLUSSTREAM_DECRYPT_KEY = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        additionalHeaders: Map<String, String> = mapOf()
    ): String? {
        for (i in 0 until retries) {
            try {
                val combinedHeaders = mapOf(
                    "User-Agent" to DEFAULT_WEB_USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "X-Requested-With" to "XMLHttpRequest"
                ) + additionalHeaders

                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = combinedHeaders)
                if (res.isSuccessful) {
                    return res.text
                }
            } catch (e: Exception) {
                Log.e("VerpelisHD", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                delay(delayMs)
            }
        }
        Log.e("VerpelisHD", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val homeHtml = safeAppGet(mainUrl)
        if (homeHtml == null) return null
        val doc = Jsoup.parse(homeHtml)

        val featuredSection = doc.selectFirst("div.section--featured")
        if (featuredSection != null) {
            val featuredItems = featuredSection.select("article.ipst").mapNotNull {
                val title = it.selectFirst("h2.ipst__title")?.text()
                val link = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("img")?.attr("src")

                if (title != null && link != null) {
                    val type = if (link.contains("/serie/")) TvType.TvSeries else TvType.Movie
                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.type = type
                        posterUrl = img
                    }
                } else null
            }
            if (featuredItems.isNotEmpty()) items.add(HomePageList("Destacados", featuredItems))
        }

        val latestEpisodesSection = doc.selectFirst("div.section--episode")
        if (latestEpisodesSection != null) {
            val latestEpisodesItems = latestEpisodesSection.select("article.ieps").mapNotNull {
                val title = it.selectFirst("h3.ieps__title a")?.text()
                val episodeLink = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("picture.ieps__image img")?.attr("src")

                if (title != null && episodeLink != null) {
                    val seriesSlugMatch = Regex("""\/episodios\/([^\/]+?)-s\d+x\d+\/""").find(episodeLink)
                    val seriesSlug = seriesSlugMatch?.groupValues?.get(1)

                    if (seriesSlug != null) {
                        val seriesUrl = "$searchBaseUrl/serie/${seriesSlug}/"
                        newAnimeSearchResponse(title, fixUrl(seriesUrl)) {
                            this.type = TvType.TvSeries
                            posterUrl = img
                        }
                    } else null
                } else null
            }
            if (latestEpisodesItems.isNotEmpty()) items.add(HomePageList("Últimos episodios", latestEpisodesItems))
        }

        val recentMoviesSection = doc.selectFirst("div#tab-peliculas")
        if (recentMoviesSection != null) {
            val recentMoviesItems = recentMoviesSection.select("article.ipst").mapNotNull {
                val title = it.selectFirst("h3.ipst__title a")?.text()
                val link = it.selectFirst("figure.ipst__image a")?.attr("href")
                val img = it.selectFirst("figure.ipst__image a img")?.attr("src")

                if (title != null && link != null) {
                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.type = TvType.Movie
                        posterUrl = img
                    }
                } else null
            }
            if (recentMoviesItems.isNotEmpty()) items.add(HomePageList("Películas Recientemente Agregadas", recentMoviesItems))
        }

        val recentSeriesSection = doc.selectFirst("div#tab-series")
        if (recentSeriesSection != null) {
            val recentSeriesItems = recentSeriesSection.select("article.ipst").mapNotNull {
                val title = it.selectFirst("h3.ipst__title a")?.text()
                val link = it.selectFirst("figure.ipst__image a")?.attr("href")
                val img = it.selectFirst("figure.ipst__image a img")?.attr("src")

                if (title != null && link != null) {
                    newAnimeSearchResponse(title, fixUrl(link)) {
                        this.type = TvType.TvSeries
                        posterUrl = img
                    }
                } else null
            }
            if (recentSeriesItems.isNotEmpty()) items.add(HomePageList("Series Recientemente Agregadas", recentSeriesItems))
        }

        val popularNowSection = doc.selectFirst("div.section--popular")
        if (popularNowSection != null) {
            val popularNowItems = popularNowSection.select("article.ppitem").mapNotNull {
                val title = it.selectFirst("h3.ppitem__title")?.text()
                val link = it.selectFirst("a.ppitem__link")?.attr("href")
                val img = it.selectFirst("picture.ppitem__image img")?.attr("src")
                val typeString = it.selectFirst("span.ppitem__type")?.text()

                if (title != null && link != null) {
                    val type = when {
                        typeString?.contains("Películas", ignoreCase = true) == true -> TvType.Movie
                        typeString?.contains("Series", ignoreCase = true) == true -> TvType.TvSeries
                        else -> null
                    }
                    if (type != null) {
                        newAnimeSearchResponse(title, fixUrl(link)) {
                            this.type = type
                            posterUrl = img
                        }
                    } else null
                } else null
            }
            if (popularNowItems.isNotEmpty()) items.add(HomePageList("Popular ahora", popularNowItems))
        }

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$searchBaseUrl/?s=$query"
        val html = safeAppGet(url)
        if (html == null) return emptyList()
        val doc = Jsoup.parse(html)
        return doc.select("div.items article.ipst").mapNotNull {
            val title = it.selectFirst("h3.ipst__title a")?.text()
            val link = it.selectFirst("figure.ipst__image a")?.attr("href")
            val img = it.selectFirst("figure.ipst__image a img")?.attr("src")

            if (title != null && link != null) {
                val type = if (link.contains("/serie/")) TvType.TvSeries else TvType.Movie
                newAnimeSearchResponse(title, fixUrl(link)) {
                    this.type = type
                    posterUrl = img
                }
            } else null
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String,
        val season: Int?,
        val episode: Int?
    )

    data class EpisodeApiResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("data") val data: EpisodeApiData
    )

    data class EpisodeApiData(
        @JsonProperty("results") val results: List<EpisodeApiResult>,
        @JsonProperty("hasMore") val hasMore: Boolean
    )

    data class EpisodeApiResult(
        @JsonProperty("permalink") val permalink: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("runtime") val runtime: String?,
        @JsonProperty("series_id") val series_id: String,
        @JsonProperty("episode_image") val episode_image: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        var cleanUrl = url
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanUrl)
        if (parsedEpisodeData != null) {
            cleanUrl = parsedEpisodeData.url
        } else {
            val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
            if (urlJsonMatch != null) {
                cleanUrl = urlJsonMatch.groupValues[1]
            } else {
                if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    cleanUrl = "https://" + cleanUrl.removePrefix("//")
                }
            }
        }

        if (cleanUrl.isBlank()) return null
        val html = safeAppGet(cleanUrl)
        if (html == null) return null
        val doc = Jsoup.parse(html)

        val tvType = if (cleanUrl.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("article.hero h2")?.text() ?: doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.hero__poster img")?.attr("src") ?: doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val description = doc.selectFirst("p.hero__overview")?.text() ?: doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.hero__genres ul li a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            val episodeList = ArrayList<Episode>()
            val seriesId = doc.selectFirst("div.eps[data-tmdb-id]")?.attr("data-tmdb-id")
            val ajaxUrlBase = doc.selectFirst("div.eps[data-ajaxurl]")?.attr("data-ajaxurl")
            val nonce = doc.selectFirst("div.eps[data-nonce]")?.attr("data-nonce")

            if (seriesId.isNullOrBlank() || ajaxUrlBase.isNullOrBlank() || nonce.isNullOrBlank()) return null

            val seasonsButtons = doc.select("details.eps-ssns div button")
            val allSeasons = if (seasonsButtons.isNotEmpty()) {
                seasonsButtons.mapNotNull { it.attr("data-season").toIntOrNull() }.sortedDescending()
            } else {
                listOf(1)
            }

            for (seasonNumber in allSeasons) {
                val ajaxUrl = "${searchBaseUrl}/wp-admin/admin-ajax.php"
                val formData = mapOf(
                    "action" to "corvus_get_episodes",
                    "post_id" to seriesId,
                    "season" to seasonNumber.toString(),
                    "nonce" to nonce,
                    "results" to "1000",
                    "offset" to "0",
                    "order" to "DESC"
                )

                val ajaxResponse = app.post(
                    ajaxUrl,
                    data = formData,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to cleanUrl
                    )
                )

                if (!ajaxResponse.isSuccessful) continue
                val responseJsonText = ajaxResponse.text
                val episodeApiResponse = tryParseJson<EpisodeApiResponse>(responseJsonText)

                if (episodeApiResponse?.success == true) {
                    val seasonEpisodes = episodeApiResponse.data.results.mapNotNull { result ->
                        val epurl = fixUrl(result.permalink)
                        if (epurl.isNotBlank()) {
                            newEpisode(
                                EpisodeLoadData(result.title, epurl, result.season_number, result.episode_number).toJson()
                            ) {
                                name = result.name
                                season = result.season_number
                                episode = result.episode_number
                                posterUrl = result.episode_image
                                this.description = result.overview
                            }
                        } else null
                    }
                    episodeList.addAll(seasonEpisodes)
                }
            }
            episodeList.sortedWith(compareBy({ it.season }, { it.episode }))
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = cleanUrl,
                    type = tvType,
                    episodes = episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(
                    name = title,
                    url = cleanUrl,
                    type = tvType,
                    dataUrl = cleanUrl
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }
            else -> null
        }
    }

    data class PlusStreamEmbed(
        @JsonProperty("servername") val servername: String,
        @JsonProperty("link") val link: String,
        @JsonProperty("type") val type: String
    )

    data class PlusStreamDataLinkEntry(
        @JsonProperty("file_id") val file_id: Int,
        @JsonProperty("video_language") val video_language: String,
        @JsonProperty("sortedEmbeds") val sortedEmbeds: List<PlusStreamEmbed>
    )

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
            Log.e("VerpelisHD", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
        } else {
            targetUrl = fixUrl(cleanedData)
        }

        if (targetUrl.isBlank()) return false
        val initialHtml = safeAppGet(targetUrl)
        if (initialHtml == null) return false
        val doc = Jsoup.parse(initialHtml)

        // Ajustar el selector del iframe principal para PlusStream
        val playerIframeSrc = doc.selectFirst("iframe[src*=\"plusstream.xyz\"]")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")


        if (playerIframeSrc.isNullOrBlank()) {
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()
            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            return false
        }

        val finalPlayerUrl = fixUrl(playerIframeSrc)
        val playerHtml = safeAppGet(finalPlayerUrl)
        if (playerHtml == null) return false
        val playerDoc = Jsoup.parse(playerHtml)

        val scriptContent = playerDoc.select("script").map { it.html() }.joinToString("\n")
        // Corrección: Usar DOT_MATCHES_ALL en lugar de DOT_ALL
        val dataLinkRegex = Regex("""const\s+dataLink\s*=\s*(\[.+?\]);""", RegexOption.DOT_MATCHES_ALL)
        val dataLinkMatchResult = dataLinkRegex.find(scriptContent) // Corrección: Renombrar para smart cast

        // Corrección: Acceder a groupValues de forma segura después de la verificación de nulidad
        if (dataLinkMatchResult == null || dataLinkMatchResult.groupValues.size < 2) {
            Log.e("VerpelisHD", "loadLinks - No se encontró la constante 'dataLink' o no tiene el formato esperado.")
            return false
        }

        val dataLinkJsonString = dataLinkMatchResult.groupValues[1] // Accede al primer grupo de captura
        val dataLinkEntries = tryParseJson<List<PlusStreamDataLinkEntry>>(dataLinkJsonString)

        if (dataLinkEntries.isNullOrEmpty()) {
            Log.e("VerpelisHD", "loadLinks - No se pudo parsear 'dataLink' o está vacío.")
            return false
        }

        val primaryEmbeds = dataLinkEntries.firstOrNull()?.sortedEmbeds
        if (primaryEmbeds.isNullOrEmpty()) {
            Log.w("VerpelisHD", "loadLinks - No se encontraron embeds en la entrada principal de dataLink.")
            return false
        }

        var foundLinks = false
        primaryEmbeds.apmap { embed ->
            if (embed.type == "video") {
                val decryptedLink = decryptLink(embed.link, PLUSSTREAM_DECRYPT_KEY)
                if (!decryptedLink.isNullOrBlank()) {
                    loadExtractor(decryptedLink, finalPlayerUrl, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}