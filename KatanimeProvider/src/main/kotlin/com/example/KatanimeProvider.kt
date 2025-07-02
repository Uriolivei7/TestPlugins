package com.example // Ajusta esto a tu paquete real de CloudStream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker // Importar el JsUnpacker de CloudStream

import com.example.extractors.CryptoAES
import com.example.extractors.PlaylistUtils
import com.example.extractors.UnpackerExtractor

// Importaciones de Jackson
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import okhttp3.Headers
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

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
        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH) }

        val mapper = ObjectMapper().apply {
            registerModule(KotlinModule())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        fun Any.toJsonJackson(): String = mapper.writeValueAsString(this)
        inline fun <reified T> String.tryParseJsonJackson(): T? = runCatching { mapper.readValue<T>(this) }.getOrNull()

        const val STATUS_ONGOING = 0
        const val STATUS_COMPLETED = 1
        const val STATUS_OTHER = 2
    }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L

    private suspend fun getDocument(url: String, customHeaders: Headers = Headers.Builder().build()): org.jsoup.nodes.Document {
        return app.baseClient.newCall(
            Request.Builder()
                .url(url)
                .headers(customHeaders)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Failed to fetch $url: ${response.code}")
            response.body?.string()?.let { Jsoup.parse(it, url) } ?: throw Exception("Empty body for $url")
        }
    }

    private fun Element.getImageUrl(): String? {
        return when {
            isValidUrl("data-src") -> attr("abs:data-src")
            isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
            isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
            isValidUrl("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("data:image/")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val popularDoc = getDocument("$mainUrl/populares")
        val items = ArrayList<HomePageList>()
        val popularAnime = popularDoc.select("#article-div .full > a").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val link = element.attr("abs:href")
            val img = element.selectFirst("img")?.getImageUrl()
            newAnimeSearchResponse(title, fixUrl(link)) { posterUrl = img; type = TvType.Anime }
        }
        items.add(HomePageList("Populares en Katanime", popularAnime))

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val latestDoc = getDocument("$mainUrl/animes?fecha=$currentYear&p=$page")
        val latestAnime = latestDoc.select("#article-div .full > a").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val link = element.attr("abs:href")
            val img = element.selectFirst("img")?.getImageUrl()
            newAnimeSearchResponse(title, fixUrl(link)) { posterUrl = img; type = TvType.Anime }
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
            val img = element.selectFirst("img")?.getImageUrl()
            if (title != null && link != null) {
                newAnimeSearchResponse(title, fixUrl(link)) { posterUrl = img; type = TvType.Anime }
            } else null
        }
    }

    data class KatanimeEpisodeData(val name: String, val url: String)

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocument(url)

        val title = doc.selectFirst(".comics-title")?.ownText() ?: ""
        val description = doc.selectFirst("#sinopsis p")?.ownText()
        val genre = doc.select(".anime-genres a").map { it.text() }
        val poster = doc.selectFirst(".anime-poster img")?.attr("src") ?: doc.selectFirst(".anime-poster img")?.getImageUrl()

        // Aunque se calcule, no se usará si la propiedad 'status' no está disponible en el DSL
        val statusInt = with(doc.select(".details-by #estado").text()) {
            when {
                contains("Finalizado", true) -> STATUS_COMPLETED
                contains("Emision", true) -> STATUS_ONGOING
                else -> STATUS_OTHER
            }
        }

        val episodes = doc.select("#c_list .cap_list").mapNotNull { element ->
            val epUrl = fixUrl(element.attr("abs:href"))
            val epTitle = element.selectFirst(".entry-title-h2")?.ownText() ?: ""
            val episodeNumber = Regex("""Capítulo\s+(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(KatanimeEpisodeData(epTitle, epUrl).toJsonJackson()) {
                name = epTitle
                episode = episodeNumber
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.plot = description
            this.tags = genre
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            // ELIMINADA: this.status = statusInt // Eliminar si no existe esta propiedad asignable
        }
    }

    data class CryptoDto(
        var ct: String? = null,
        var iv: String? = null,
        var s: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl: String = data.tryParseJsonJackson<KatanimeEpisodeData>()?.url ?: data
        if (targetUrl.isBlank()) return false

        val doc = getDocument(targetUrl)

        doc.select("[data-player]:not([data-player-name=\"Mega\"])").apmap { element ->
            runCatching {
                val dataPlayer = element.attr("data-player")
                val playerDocument = getDocument("$mainUrl/reproductor?url=$dataPlayer")

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
            }.onFailure { e -> Log.e("Katanime", "Error processing data-player: ${e.message}", e) }
        }
        return true
    }
}