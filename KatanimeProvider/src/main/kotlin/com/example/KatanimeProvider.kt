package com.example // Ajusta esto a tu paquete real

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody // Importar FormBody
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
        hasAttr("data-src") && !attr("data-src").contains("data:image/") -> attr("abs:data-src")
        hasAttr("data-lazy-src") && !attr("data-lazy-src").contains("data:image/") -> attr("abs:data-lazy-src")
        hasAttr("srcset") && !attr("srcset").contains("data:image/") -> attr("abs:srcset").substringBefore(" ")
        hasAttr("src") && !attr("src").contains("data:image/") -> attr("abs:src")
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
        Log.d("KatanimeProvider", "Cargando URL principal de la serie: $url")
        val doc = app.get(url).document // Obtenemos el HTML inicial para la info de la serie y el token.

        Log.d("KatanimeProvider", "Documento HTML principal obtenido para $url.")

        val title = doc.selectFirst(".comics-title")?.ownText() ?: ""
        val description = doc.selectFirst("#sinopsis p")?.ownText()
        val genre = doc.select(".anime-genres a").map { it.text() }
        val poster = doc.selectFirst(".anime-poster img")?.attr("src") ?: doc.selectFirst(".anime-poster img")?.getKatanimeImageUrl()

        Log.d("KatanimeProvider", "Título extraído: $title")

        val statusInt = with(doc.select(".details-by #estado").text()) {
            when {
                contains("Finalizado", true) -> STATUS_COMPLETED
                contains("Emision", true) -> STATUS_ONGOING
                else -> STATUS_OTHER
            }
        }

        // --- INICIO DE LA LÓGICA PARA OBTENER EPISODIOS VÍA POST CON FORM-DATA ---
        val episodesPostUrl = url + "eps"
        Log.d("KatanimeProvider", "Intentando cargar episodios vía POST a: $episodesPostUrl")

        // 1. Extraer el _token de la página principal
        val csrfToken = doc.selectFirst("input[name=\"_token\"]")?.attr("value")
        if (csrfToken.isNullOrBlank()) {
            Log.e("KatanimeProvider", "ERROR: No se encontró el token CSRF en la página principal. Posiblemente cambió el selector o la web.")
            // En este caso, la página principal de la serie se cargará, pero sin episodios.
            return newTvSeriesLoadResponse(title, url, TvType.Anime, emptyList()) {
                this.plot = description
                this.tags = genre
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
            }
        }
        Log.d("KatanimeProvider", "Token CSRF encontrado: ${csrfToken.take(10)}...") // Log parcial del token

        // 2. Construir el FormBody con el token y el parámetro de página
        val formBody = FormBody.Builder()
            .add("_token", csrfToken)
            .add("pagina", "1") // Asumimos la primera página de episodios
            .build()
        Log.d("KatanimeProvider", "FormBody construido con _token y pagina=1.")

        val episodesDoc: org.jsoup.nodes.Document? = try {
            val response = app.post(
                episodesPostUrl,
                requestBody = formBody, // Añadimos el FormBody aquí
                headers = mapOf(
                    "Referer" to url,
                    "X-Requested-With" to "XMLHttpRequest", // **AGREGADO: Encabezado común para peticiones AJAX**
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" // **AGREGADO: User-Agent para simular navegador**
                )
            )
            if (response.isSuccessful) {
                Log.d("KatanimeProvider", "Petición POST de episodios exitosa. Código: ${response.code}")
                // FIX: Usamos ?.let para manejar el String? de forma segura
                response.body?.string()?.let { htmlContent ->
                    Jsoup.parse(htmlContent, episodesPostUrl)
                } ?: run {
                    Log.e("KatanimeProvider", "El cuerpo de la respuesta POST para episodios es nulo o vacío.")
                    null
                }
            } else {
                val errorBody = response.body?.string()
                Log.e("KatanimeProvider", "Error al obtener episodios vía POST: ${response.code} - Cuerpo de respuesta: ${errorBody?.take(500)}...") // Log parcial del cuerpo de error
                null
            }
        } catch (e: Exception) {
            Log.e("KatanimeProvider", "Excepción al obtener episodios vía POST: ${e.message}", e)
            null
        }

        if (episodesDoc == null) {
            Log.e("KatanimeProvider", "No se pudo obtener el documento de episodios de la petición POST. Se devolverán 0 episodios.")
            return newTvSeriesLoadResponse(title, url, TvType.Anime, emptyList()) {
                this.plot = description
                this.tags = genre
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
            }
        }

        // Aplicamos el selector al documento de la respuesta POST
        val episodesElements = episodesDoc.select("#c_list .cap_list")
        Log.d("KatanimeProvider", "Elementos de episodios encontrados por Jsoup en la respuesta POST: ${episodesElements.size}")

        val episodes = episodesElements.mapNotNull { element ->
            val epUrl = fixUrl(element.attr("abs:href"))
            val epTitle = element.selectFirst(".entry-title-h2")?.ownText() ?: ""
            val episodeNumber = Regex("""Capítulo\s+(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            Log.d("KatanimeProvider", "Episodio procesado: Título=$epTitle, URL=$epUrl, Número=$episodeNumber")
            newEpisode(data = KatanimeEpisodeData(epTitle, epUrl).toJsonJackson()) {
                name = epTitle
                episode = episodeNumber
            }
        }.reversed()

        Log.d("KatanimeProvider", "Total de episodios construidos: ${episodes.size}")

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.plot = description
            this.tags = genre
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
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
        Log.d("KatanimeProvider", "Cargando enlaces para: $targetUrl")
        if (targetUrl.isBlank()) {
            Log.w("KatanimeProvider", "URL de destino en blanco.")
            return false
        }

        val doc = app.get(targetUrl).document
        Log.d("KatanimeProvider", "Documento HTML obtenido para enlaces.")

        doc.select("[data-player]:not([data-player-name=\"Mega\"])").apmap { element ->
            runCatching {
                val dataPlayer = element.attr("data-player")
                Log.d("KatanimeProvider", "Encontrado data-player: $dataPlayer")
                val playerDocument = app.get("$mainUrl/reproductor?url=$dataPlayer").document

                val encryptedData = playerDocument
                    .selectFirst("script:containsData(var e =)")?.data()
                    ?.substringAfter("var e = '")?.substringBefore("';")
                    ?: return@apmap
                Log.d("KatanimeProvider", "Datos encriptados: ${encryptedData.take(50)}...")

                val json = encryptedData.tryParseJsonJackson<CryptoDto>() ?: return@apmap
                val decryptedLink = CryptoAES.decryptWithSalt(json.ct!!, json.s!!, DECRYPTION_PASSWORD)
                    .replace("\\/", "/").replace("\"", "")
                Log.d("KatanimeProvider", "Enlace desencriptado: $decryptedLink")

                if (decryptedLink.contains("lulu.stream", ignoreCase = true)) {
                    Log.d("KatanimeProvider", "Usando UnpackerExtractor para lulu.stream.")
                    val headers = Headers.Builder().add("Referer", "$mainUrl/").build()
                    val unpacker = UnpackerExtractor(app.baseClient, headers)
                    unpacker.videosFromUrl(decryptedLink, subtitleCallback, callback)
                } else {
                    Log.d("KatanimeProvider", "Usando loadExtractor para otros enlaces.")
                    loadExtractor(decryptedLink, targetUrl, subtitleCallback, callback)
                }
            }.onFailure { e -> Log.e("Katanime", "Error al procesar data-player: ${e.message}", e) }
        }
        return true
    }
}