package com.example

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.lagradost.cloudstream3.utils.fixUrl

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import com.example.extractors.CryptoAES
import com.example.extractors.PlaylistUtils
import com.example.extractors.UnpackerExtractor

val katanimeObjectMapper = ObjectMapper().apply {
    registerModule(KotlinModule())
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

fun Any.toJsonJackson(): String = katanimeObjectMapper.writeValueAsString(this)
inline fun <reified T> String.tryParseJsonJackson(): T? = runCatching { katanimeObjectMapper.readValue<T>(this) }.getOrNull()

fun Element.getKatanimeImageUrl(): String? {
    return when {
        hasAttr("data-src") && attr("data-src").isNotBlank() && !attr("data-src").contains("data:image/") -> attr("abs:data-src")
        hasAttr("data-lazy-src") && attr("data-lazy-src").isNotBlank() && !attr("data-lazy-src").contains("data:image/") -> attr("abs:data-lazy-src")
        hasAttr("srcset") && attr("srcset").isNotBlank() && !attr("srcset").contains("data:image/") -> attr("abs:srcset").substringBefore(" ")
        hasAttr("src") && attr("src").isNotBlank() && !attr("src").contains("data:image/") -> attr("abs:src")
        else -> null
    }
}

suspend fun <A, B> Iterable<A>.apmap(f: suspend (A) -> B): List<B> {
    return kotlinx.coroutines.coroutineScope {
        map { async { f(it) } }.awaitAll()
    }
}

object KatanimeFiltersData {
    val TYPES = arrayOf(
        Pair("Todos", ""), Pair("Anime", "anime"), Pair("Ova", "ova"), Pair("Película", "pelicula"),
        Pair("Especial", "especial"), Pair("Ona", "ona"), Pair("Musical", "musical")
    )
    val LANGUAGE = arrayOf(
        Pair("Todos", ""), Pair("Japones subtitulado", "Japones subtitulado"), Pair("Audio latino", "Audio latino")
    )
    val GENRES = arrayOf(
        Pair("Acción", "accion"), Pair("Aventura", "aventura"), Pair("Coches", "coches"), Pair("Comedia", "comedia"),
        Pair("Avant Garde", "avant-garde"), Pair("Demonios", "demonios"), Pair("Misterio", "misterio"), Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"), Pair("Fantasía", "fantasia"), Pair("Juego", "juego"), Pair("Hentai", "hentai"),
        Pair("Histórico", "historico"), Pair("Horror", "horror"), Pair("Infantil", "Infantil"), Pair("Magia", "magia"),
        Pair("Artes Marciales", "artes-marciales"), Pair("Mecha", "mecha"), Pair("Música", "musica"), Pair("Parodia", "parodia"),
        Pair("Samurái", "samurai"), Pair("Romance", "romance"), Pair("Escolar", "escolar"), Pair("Ciencia Ficción", "ciencia-ficcion"),
        Pair("Shoujo", "shoujo"), Pair("Yuri", "yuri"), Pair("Shônen", "shonen"), Pair("Yaoi", "yaoie"),
        Pair("Espacial", "espacial"), Pair("Deportes", "deportes"), Pair("Superpoderes", "superpoderes"), Pair("Vampiros", "vampiros"),
        Pair("Criuoi", "criuoi"), Pair("Yurii", "yurii"), Pair("Harem", "harem"), Pair("Recuentos de la vida", "recuentos-de-la-vida"),
        Pair("Sobrenatural", "sobrenatural"), Pair("Militar", "militar"), Pair("Policía", "policia"), Pair("Psicológico", "psicologico"),
        Pair("Suspenso", "suspenso"), Pair("Seinen", "seinen"), Pair("Josei", "josei"), Pair("Gore", "gore")
    )
    val YEARS = arrayOf(Pair("Todos", "")) + (1982..Calendar.getInstance().get(Calendar.YEAR)).map { Pair("$it", "$it") }.reversed().toTypedArray()
}

data class KatanimeEpisodesResponse(
    val ep: EpData,
    val last: LastData?
)

data class EpData(
    @JsonProperty("current_page") val currentPage: Int,
    val data: List<EpisodeJsonData>,
    @JsonProperty("first_page_url") val firstPageUrl: String?,
    val from: Int?,
    @JsonProperty("last_page") val lastPage: Int,
    @JsonProperty("last_page_url") val lastPageUrl: String?,
    val links: List<LinkData>?,
    @JsonProperty("next_page_url") val nextPageUrl: String?,
    val path: String,
    @JsonProperty("per_page") val perPage: Int,
    @JsonProperty("prev_page_url") val prevPageUrl: String?,
    val to: Int?,
    val total: Int
)

data class EpisodeJsonData(
    val numero: String,
    val thumb: String?,
    @JsonProperty("created_at") val createdAt: String,
    val url: String
)

data class LinkData(
    val url: String?,
    val label: String,
    val active: Boolean
)

data class LastData(
    val numero: String
)

class KatanimeProvider : MainAPI() {
    override var mainUrl = "https://katanime.net"
    override var name = "Katanime"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    companion object {
        const val DECRYPTION_PASSWORD = "hanabi"
        private val DATE_FORMATTER_GENERAL by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH) }
        private val DATE_FORMATTER_TOKEN_PLUS by lazy { SimpleDateFormat("yyyy-MM-dd+HH%3Amm%3Ass", Locale.ENGLISH) }

        val STATUS_ONGOING = ShowStatus.Ongoing
        val STATUS_COMPLETED = ShowStatus.Completed
    }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER_GENERAL.parse(trim())?.time }.getOrNull() ?: 0L

    private suspend fun getDocument(url: String, customHeaders: Headers = Headers.Builder().build()): org.jsoup.nodes.Document {
        // Crea un nuevo Headers.Builder basado en los customHeaders existentes
        val headersBuilder = customHeaders.newBuilder()
            // Añade o sobrescribe el User-Agent para todas las peticiones getDocument
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        return app.baseClient.newCall(
            Request.Builder()
                .url(url)
                .headers(headersBuilder.build()) // Usa los headers actualizados
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Fallo al obtener $url: ${response.code}")
            response.body?.string()?.let { Jsoup.parse(it, url) } ?: throw Exception("Cuerpo vacío para $url")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val popularDoc = getDocument("$mainUrl/populares")
        val items = ArrayList<HomePageList>()
        val popularAnime = popularDoc.select("#article-div .full > a").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt")
            val link = element.attr("abs:href")
            val img = element.selectFirst("img")?.getKatanimeImageUrl()
            if (title != null && link != null) {
                newAnimeSearchResponse(title, fixUrl(link)) { posterUrl = img; type = TvType.Anime }
            } else null
        }
        items.add(HomePageList("Populares en Katanime", popularAnime))

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val latestDoc = getDocument("$mainUrl/animes?fecha=$currentYear&p=$page")
        val latestAnime = latestDoc.select("#article-div .full > a").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt")
            val link = element.attr("abs:href")
            val img = element.selectFirst("img")?.getKatanimeImageUrl()
            if (title != null && link != null) {
                newAnimeSearchResponse(title, fixUrl(link)) { posterUrl = img; type = TvType.Anime }
            } else null
        }
        items.add(HomePageList("Últimas Actualizaciones", latestAnime))
        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        val doc = getDocument(url)
        return doc.select("#article-div .full > a").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt")
            val link = element.attr("abs:href")
            val img = element.selectFirst("img")?.getKatanimeImageUrl()
            if (title != null && link != null) {
                newAnimeSearchResponse(title, fixUrl(link)) { posterUrl = img; type = TvType.Anime }
            } else null
        }
    }

    data class KatanimeEpisodeData(val name: String, val url: String)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst(".comics-title")?.ownText() ?: ""
        val description = doc.selectFirst("#sinopsis p")?.ownText()
        val genre = doc.select(".anime-genres a").map { it.text() }
        val poster = doc.selectFirst(".anime-poster img")?.getKatanimeImageUrl()

        val statusVal: ShowStatus? = with(doc.select(".details-by #estado").text()) {
            when {
                contains("Finalizado", true) -> STATUS_COMPLETED
                contains("Emision", true) -> STATUS_ONGOING
                else -> null
            }
        }

        val episodesPostUrl = fixUrl(url + "eps") // Eliminado el slash inicial para evitar //eps

        val csrfToken = doc.selectFirst("input[name=\"_token\"]")?.attr("value")
        if (csrfToken.isNullOrBlank()) {
            Log.e("KatanimeProvider", "ERROR: No se encontró el token CSRF en la página principal.")
            return newTvSeriesLoadResponse(title, url, TvType.Anime, emptyList()) {
                this.plot = description
                this.tags = genre
                this.posterUrl = poster
                this.showStatus = statusVal
            }
        }

        val formBody = FormBody.Builder()
            .add("_token", csrfToken)
            .add("pagina", "1")
            .build()

        val postResponse = app.post(
            episodesPostUrl,
            requestBody = formBody,
            headers = mapOf(
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-CSRF-TOKEN" to csrfToken,
                "Origin" to mainUrl,
                "Accept-Language" to "es-ES,es;q=0.9",
                // ¡ATENCIÓN! Pega la cadena de cookies completa que me acabas de dar aquí:
                "Cookie" to "_gid=GA1.2.1691590932.1751478834; _ga_V358MX4G9Q=GS2.1.s1751478824\\\$o1\\\$g1\\\$t1751479676\\\$j58\\\$l0\\\$h0; _ga=GA1.2.755877887.1751478825; XSRF-TOKEN=eyJpdiI6IjJrcWp3SjVnR2p6VVE5WWdvRGdUNWc9PSIsInZhbHVlIjoiWkNZN2VUdkVuM2MzV0podHZHQ3Y4Mi81OE5KcUp1VjZRY3JiQTRmSk9oS2tQSDVkeVlndzd1RTlLZWwyOXd3MW12UHpwdE51aGw5WXM4Z2RuY0RTN28raXRUTlYxcTFTRmg3QnZGY2kxd0UzNGFiV2s0eEp2bkhCMENVVUVTOTAiLCJtYWMiOiIyMjc2OTJmMGRjMjE1ZDQyNDU0YTAzODg4Mjk3MGJiNTBkNmFlNWM1NzNlMmM4NzhhNjczYzg3YWJmMDNkNmI5IiwidGFnIjoiIn0%3D; katanime_session=eyJpdiI6IkVHcVNGeE4rc2g2SDFsUDdvMDZBeHc9PSIsInZhbHVlIjoiOGlOcitwN2ZweWphU0Ryd2FLNm1NSi8vWXhYdXJ2K3RUTDNyS0pMVGZURkZkU1Z4eC90RHJEMTZaM2FIYk8vYitFRTRMRXliOG4xNllrTDB6eTIzN0IzR1RDTDBpYmlmMkJ6TFZoYTA4YXQ0TzlwMnJpZ2lCUHpLSHdWK3k5RzIiLCJtYWMiOiIzNDcwZDEyMzBkODJjNDRlOWMyM2YxNWJjMWQ0ZjBmMThlODc3YWRkZjcwMWM3YjQ3YTEzOGY5YmNkN2ZjNDFhIiwidGFnIjoiIn0%3D; hWXtoLIHhiPkseOhViXPQeumteTRCp8Yh0siheYT=eyJpdiI6IkhiNDR5ZEdrZXdxOVhaVmhZNjBFdnc9PSIsInZhbHVlIjoiNmI3V0VmeWo5bmZEdnQvK2wvTU02MGl6MTkrZWcrYVJGRTlpVnE2YWlnQ2JVaUpiRkRoRVl1enV3N3lJaVFNL3ZSaFhkSWJCQ1ZNQWVjcEJaMVU0ZFhHU01SRkNydFdGR21YcUxoWUM0WXRrUjIwclk1ZzI0Q3NpQytlL3B2UEpTYXNkTW1TTllqeUNoWDhmUGJVd3oyd2h3VTc4VTh3ODd6azhDR2JiN3lPTm53aXZkNTZFZENldHcvc1F0WEc1OHA4Ujltb0doY0puTFA4ODVqSFZKeC9KQkRmaUxQTkw2cnUrNzAzS0JsWW95RHZFQmFqb0hzc0FHcjBBRHJMM292d1FCNXFCdHRsQ3N3OTZNbE9HY3lXNmxITGQzVXl0dnhzVkUrbXowMjkyN05QdHNMT3FWWkdGcW4zdVVxaDhyNFZpMGJuTnIzeXN1ZWo4YU8yOS9MNEJkTis2TkZjU1FoR1krN3pQL1hLb3V2M1luRUpBZHNxWS9QUm5TUFJLRWlyeDc5MjE1YzVhU3l1aUIzSjVYaXNTaGRocE9vNENPS3FCYjUwTVAxekUxYXZnRXl3TFA3bElSVUxhZlBuYiIsIm1hYyI6ImUwN2I1MmQ0NzAwMTllYTg5YTAzZjY4N2VjNjQ2MGE4YmY4OWU1MGM3MDM1YmI0NmI1NGE3YmNjYzVhNTBlYmMiLCJ0YWciOiIifQ%3D%3D"
            )
        )

        val episodesResponseData: KatanimeEpisodesResponse? = try {
            if (postResponse.isSuccessful) {
                val jsonString = postResponse.body?.string()
                jsonString?.tryParseJsonJackson<KatanimeEpisodesResponse>()
            } else {
                Log.e("KatanimeProvider", "Error al obtener episodios vía POST a /eps: ${postResponse.code} - Cuerpo: ${postResponse.body?.string()?.take(500)}...")
                null
            }
        } catch (e: Exception) {
            Log.e("KatanimeProvider", "Excepción al obtener episodios vía POST a /eps: ${e.message}", e)
            null
        }

        val episodes = episodesResponseData?.ep?.data?.mapNotNull { episodeData ->
            newEpisode(data = KatanimeEpisodeData(episodeData.numero, episodeData.url).toJsonJackson()) {
                name = "Capítulo ${episodeData.numero}"
                episode = episodeData.numero.toIntOrNull()
            }
        }?.reversed() ?: emptyList()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.plot = description
            this.tags = genre
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.showStatus = statusVal
        }
    }

    data class CryptoDto(
        @JsonProperty("ct") var ct: String? = null,
        @JsonProperty("iv") var iv: String? = null,
        @JsonProperty("s") var s: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl: String = data.tryParseJsonJackson<KatanimeEpisodeData>()?.url ?: data
        if (targetUrl.isBlank()) {
            Log.w("KatanimeProvider", "URL de destino en blanco.")
            return false
        }

        val initialEpisodeDoc = app.get(targetUrl).document
        val csrfToken = initialEpisodeDoc.selectFirst("input[name=\"_token\"]")?.attr("value")
        if (csrfToken.isNullOrBlank()) {
            Log.e("KatanimeProvider", "ERROR: No se encontró el token CSRF en la página del episodio: $targetUrl")
            return false
        }

        val fixedTokenPlusNumber = "90"
        val currentTimeFormatted = DATE_FORMATTER_TOKEN_PLUS.format(Calendar.getInstance().time)
        val tokenPlus = "${fixedTokenPlusNumber}_${currentTimeFormatted}"

        val postFormBody = FormBody.Builder()
            .add("_token", csrfToken)
            .add("token_plus", tokenPlus)
            .build()

        val postResponse = app.post(
            targetUrl,
            requestBody = postFormBody,
            headers = mapOf(
                "Referer" to targetUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*"
            )
        )

        if (!postResponse.isSuccessful) {
            Log.e("KatanimeProvider", "ERROR: Petición POST a $targetUrl falló con código ${postResponse.code}.")
            return false
        }

        val finalEpisodeDoc = app.get(targetUrl).document

        finalEpisodeDoc.select("[data-player]:not([data-player-name=\"Mega\"])").apmap { element ->
            runCatching {
                val dataPlayer = element.attr("data-player")
                val playerDocument = app.get("$mainUrl/reproductor?url=$dataPlayer").document

                val encryptedData = playerDocument
                    .selectFirst("script:containsData(var e =)")?.data()
                    ?.substringAfter("var e = '")?.substringBefore("';")
                    ?: return@apmap

                val json = encryptedData.tryParseJsonJackson<CryptoDto>() ?: return@apmap
                val decryptedLink = CryptoAES.decryptWithSalt(json.ct!!, json.s!!, DECRYPTION_PASSWORD)
                    .replace("\\/", "/").replace("\"", "")

                if (decryptedLink.contains("lulu.stream", ignoreCase = true)) {
                    val headers = Headers.Builder().add("Referer", "$mainUrl/").build()
                    val unpacker = UnpackerExtractor(app.baseClient, headers)
                    unpacker.videosFromUrl(decryptedLink, subtitleCallback, callback)
                } else {
                    loadExtractor(decryptedLink, targetUrl, subtitleCallback, callback)
                }
            }.onFailure { e -> Log.e("Katanime", "Error al procesar data-player: ${e.message}", e) }
        }
        return true
    }
}