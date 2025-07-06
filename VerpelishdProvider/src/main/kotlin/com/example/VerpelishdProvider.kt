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
import kotlin.text.RegexOption // <-- Importación necesaria para RegexOption si DOT_MATCHES_ALL se usa
import okhttp3.MultipartBody
import okhttp3.RequestBody // Probablemente también necesites esta para el RequestBody, aunque puede que ya esté implícitamente
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.Serializable // <--- CAMBIO AQUÍ
import kotlinx.serialization.SerialName   // <--- CAMBIO AQUÍ
import okhttp3.FormBody
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.SubtitleFile

data class LinkEntry(
    val url: String? = null, // 'url' es el campo real aquí
    val name: String? = null, // 'name' es el nombre del servidor
    val lang: String? = null, // 'lang' es el idioma
    val type: String // 'type' es "embed"
)

private const val PLUSSTREAM_DECRYPT_KEY = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"
// Asegúrate de que esta constante esté accesible, quizás junto a PLUSSTREAM_DECRYPT_KEY
private const val VERPELISHD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

@Serializable // Asegúrate de tener la anotación de serialización (Kotlinx Serialization)
data class EpisodeLoadData(
    val title: String? = null,
    val url: String, // Esta es la URL del episodio en verpelishd.me
    val season: Int? = null,
    val episode: Int? = null
)

// Necesitas una clase para parsear la respuesta JSON de la primera llamada AJAX (corvus_get_servers)
@Serializable
data class ServerEntry(
    val url: String?, // La URL a la página intermedia (ej. plustream.xyz/f/...)
    val name: String?,
    val lang: String?,
    val type: String?
)
@Serializable
data class InnerEmbed(
    val servername: String?,
    val link: String?, // ¡Este es el enlace cifrado!
    val type: String?
)

// Clase para el 'file_id' en el JSON de dataLink (si hay múltiples idiomas/calidades)
@Serializable
data class DataLinkFile(
    @SerialName("file_id") val fileId: Int,
    @SerialName("video_language") val videoLanguage: String?,
    @SerialName("sortedEmbeds") val sortedEmbeds: List<InnerEmbed>
)

data class Player(
    @SerialName("url") val url: String?,
    @SerialName("type") val type: String?,
    @SerialName("name") val name: String?
)


private fun decryptLink(encryptedBase64: String, key: String): String {
    try {
        val cipherBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)

        if (cipherBytes.size < 16 || !cipherBytes.sliceArray(0..7).contentEquals("Salted__".toByteArray(Charsets.UTF_8))) {
            Log.e("VerpelisHD", "decryptLink - Prefijo 'Salted__' no encontrado o datos demasiado cortos. Es posible que el link no esté cifrado de esta forma o esté corrupto.")
            return "" // O lanza una excepción, o devuelve el original
        }

        val salt = cipherBytes.sliceArray(8..15) // Los 8 bytes después de "Salted__"
        val encryptedData = cipherBytes.sliceArray(16 until cipherBytes.size)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val iv = ByteArray(16) { 0 } // Intenta con IV de ceros (o puedes intentar un IV basado en el salt si el JS lo indica)
        val ivSpec = IvParameterSpec(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding") // O "AES/CBC/PKCS5Padding" si no funciona PKCS7Padding
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        val decryptedBytes = cipher.doFinal(encryptedData) // Usamos encryptedData, no cipherBytes completo
        return String(decryptedBytes, Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e("VerpelisHD", "Error al descifrar link (PlusStream): $e")
        Log.e("VerpelisHD", "Datos cifrados Base64 de entrada: $encryptedBase64")
        return ""
    }
}

class VerpelishdProvider : MainAPI() {
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
    //private val PLUSSTREAM_DECRYPT_KEY = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"
    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        additionalHeaders: Map<String, String> = mapOf()
    ): String? {
        for (i in 0 until retries) {
            try {
                Log.d("VerpelisHD", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val combinedHeaders = mapOf(
                    "User-Agent" to DEFAULT_WEB_USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "X-Requested-With" to "XMLHttpRequest"
                ) + additionalHeaders
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = combinedHeaders)
                if (res.isSuccessful) {
                    Log.d("VerpelisHD", "safeAppGet - Petición exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("VerpelisHD", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("VerpelisHD", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("VerpelisHD", "safeAppGet - Reintentando en ${delayMs / 1000.0} segundos...")
                delay(delayMs)
            }
        }
        Log.e("VerpelisHD", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val homeHtml = safeAppGet(mainUrl)
        if (homeHtml == null) {
            Log.e("VerpelisHD", "getMainPage - No se pudo obtener HTML de la página principal: $mainUrl")
            return null
        }
        val doc = Jsoup.parse(homeHtml)
        val featuredSection = doc.selectFirst("div.section--featured")
        if (featuredSection != null) {
            val featuredItems = featuredSection.select("article.ipst").mapNotNull {
                val title = it.selectFirst("h2.ipst__title")?.text()
                val link = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("img")?.attr("src")
                if (title != null && link != null) {
                    val type = if (link.contains("/serie/")) TvType.TvSeries else TvType.Movie
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = type
                        posterUrl = img
                    }
                } else null
            }
            if (featuredItems.isNotEmpty()) {
                items.add(HomePageList("Destacados", featuredItems))
            }
        } else {
            Log.w("VerpelisHD", "getMainPage - Sección 'Destacados' no encontrada.")
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
                        newAnimeSearchResponse(
                            title,
                            fixUrl(seriesUrl)
                        ) {
                            this.type = TvType.TvSeries
                            posterUrl = img
                        }
                    } else {
                        Log.w("VerpelisHD", "getMainPage - No se pudo extraer la URL de la serie de: $episodeLink")
                        null
                    }
                } else null
            }
            if (latestEpisodesItems.isNotEmpty()) {
                items.add(HomePageList("Últimos episodios", latestEpisodesItems))
            }
        } else {
            Log.w("VerpelisHD", "getMainPage - Sección 'Últimos episodios' no encontrada.")
        }
        val recentMoviesSection = doc.selectFirst("div#tab-peliculas")
        if (recentMoviesSection != null) {
            val recentMoviesItems = recentMoviesSection.select("article.ipst").mapNotNull {
                val title = it.selectFirst("h3.ipst__title a")?.text()
                val link = it.selectFirst("figure.ipst__image a")?.attr("href")
                val img = it.selectFirst("figure.ipst__image a img")?.attr("src")

                if (title != null && link != null) {
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = TvType.Movie
                        posterUrl = img
                    }
                } else null
            }
            if (recentMoviesItems.isNotEmpty()) {
                items.add(HomePageList("Películas Recientemente Agregadas", recentMoviesItems))
            }
        } else {
            Log.w("VerpelisHD", "getMainPage - Sección 'Películas Recientemente Agregadas' no encontrada.")
        }

        val recentSeriesSection = doc.selectFirst("div#tab-series")
        if (recentSeriesSection != null) {
            val recentSeriesItems = recentSeriesSection.select("article.ipst").mapNotNull {
                val title = it.selectFirst("h3.ipst__title a")?.text()
                val link = it.selectFirst("figure.ipst__image a")?.attr("href")
                val img = it.selectFirst("figure.ipst__image a img")?.attr("src")
                if (title != null && link != null) {
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = TvType.TvSeries
                        posterUrl = img
                    }
                } else null
            }
            if (recentSeriesItems.isNotEmpty()) {
                items.add(HomePageList("Series Recientemente Agregadas", recentSeriesItems))
            }
        } else {
            Log.w("VerpelisHD", "getMainPage - Sección 'Series Recientemente Agregadas' no encontrada.")
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
                        newAnimeSearchResponse(
                            title,
                            fixUrl(link)
                        ) {
                            this.type = type
                            posterUrl = img
                        }
                    } else null
                } else null
            }
            if (popularNowItems.isNotEmpty()) {
                items.add(HomePageList("Popular ahora", popularNowItems))
            }
        } else {
            Log.w("VerpelisHD", "getMainPage - Sección 'Popular ahora' no encontrada.")
        }
        return newHomePageResponse(items, false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$searchBaseUrl/?s=$query"
        val html = safeAppGet(url)
        if (html == null) {
            Log.e("VerpelisHD", "search - No se pudo obtener HTML para la búsqueda: $url")
            return emptyList()
        }
        val doc = Jsoup.parse(html)
        return doc.select("div.items article.ipst").mapNotNull {
            val title = it.selectFirst("h3.ipst__title a")?.text()
            val link = it.selectFirst("figure.ipst__image a")?.attr("href")
            val img = it.selectFirst("figure.ipst__image a img")?.attr("src")

            if (title != null && link != null) {
                val type = if (link.contains("/serie/")) TvType.TvSeries else TvType.Movie
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = type
                    posterUrl = img
                }
            } else null
        }
    }
    @Serializable
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
        Log.d("VerpelisHD", "load - URL de entrada: $url")
        var cleanUrl = url
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanUrl)
        if (parsedEpisodeData != null) {
            cleanUrl = parsedEpisodeData.url
            Log.d("VerpelisHD", "load - URL limpia de EpisodeLoadData: $cleanUrl")
        } else {
            val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
            if (urlJsonMatch != null) {
                cleanUrl = urlJsonMatch.groupValues[1]
                Log.d("VerpelisHD", "load - URL limpia por JSON Regex: $cleanUrl")
            } else {
                if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    cleanUrl = "https://" + cleanUrl.removePrefix("//")
                    Log.d("VerpelisHD", "load - URL limpiada con HTTPS: $cleanUrl")
                }
                Log.d("VerpelisHD", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
            }
        }
        if (cleanUrl.isBlank()) {
            Log.e("VerpelisHD", "load - ERROR: URL limpia está en blanco.")
            return null
        }
        val html = safeAppGet(cleanUrl)
        if (html == null) {
            Log.e("VerpelisHD", "load - No se pudo obtener HTML para la URL principal: $cleanUrl")
            return null
        }
        val doc = Jsoup.parse(html)
        val tvType = if (cleanUrl.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("article.hero h2")?.text() ?: doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.hero__poster img")?.attr("src") ?: doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val backgroundPoster = doc.selectFirst("figure.hero__backdrop img")?.attr("src") ?: poster
        val description = doc.selectFirst("p.hero__overview")?.text() ?: doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.hero__genres ul li a").map { it.text() }
        val seriesPageUrl = cleanUrl
        val episodes = if (tvType == TvType.TvSeries) {
            val episodeList = ArrayList<Episode>()
            val epsDiv = doc.selectFirst("div.eps[data-tmdb-id]")
            if (epsDiv == null) {
                Log.e("VerpelisHD", "load - No se encontró el div.eps para la serie. No se pueden cargar episodios.")
                return null // O manejar de otra forma si no hay div.eps
            }
            val seriesId = epsDiv.attr("data-tmdb-id")
            val ajaxUrlBase = epsDiv.attr("data-ajaxurl")
            val nonce = epsDiv.attr("data-nonce")
            val defaultResults = epsDiv.attr("data-results") ?: "1000"
            if (seriesId.isNullOrBlank() || ajaxUrlBase.isNullOrBlank() || nonce.isNullOrBlank()) {
                Log.e("VerpelisHD", "load - No se pudieron obtener seriesId, ajaxUrlBase o nonce para cargar episodios.")
                return null
            }
            Log.d("VerpelisHD", "load - seriesId: $seriesId, ajaxUrlBase: $ajaxUrlBase, nonce: $nonce, defaultResults: $defaultResults")
            val initialEpisodes = epsDiv.select("li.lep").mapNotNull { li ->
                val epurl = fixUrl(li.selectFirst("a")?.attr("href") ?: "")
                val epTitle = li.selectFirst("h3.lep__title")?.text() ?: ""
                val realSeasonNumber = li.attr("data-season").toIntOrNull()
                val realEpNumber = li.attr("data-episode").toIntOrNull()
                val realimg = li.selectFirst("img")?.attr("src")
                if (epurl.isNotBlank() && realSeasonNumber != null && realEpNumber != null) {
                    newEpisode(
                        EpisodeLoadData(epTitle, epurl, realSeasonNumber, realEpNumber).toJson()
                    ) {
                        name = "$epTitle"
                        season = realSeasonNumber
                        episode = realEpNumber
                        posterUrl = realimg
                    }
                } else {
                    Log.w("VerpelisHD", "load - Datos incompletos para episodio HTML: $epurl, S$realSeasonNumber E$realEpNumber")
                    null
                }
            }
            episodeList.addAll(initialEpisodes)
            Log.d("VerpelisHD", "load - Episodios iniciales del HTML: ${initialEpisodes.size}")
            val seasonsButtons = doc.select("details.eps-ssns div button")
            val allSeasons = if (seasonsButtons.isNotEmpty()) {
                seasonsButtons.mapNotNull { it.attr("data-season").toIntOrNull() }.sorted()
            } else {
                val initialSeasonFromDiv = epsDiv.attr("data-season-number")?.toIntOrNull()
                if (initialSeasonFromDiv != null) {
                    Log.d("VerpelisHD", "load - No se encontraron botones de temporadas, asumiendo temporada ${initialSeasonFromDiv} de data-season-number.")
                    listOf(initialSeasonFromDiv)
                } else {
                    Log.d("VerpelisHD", "load - No se encontraron botones de temporadas ni data-season-number, asumiendo temporada 1.")
                    listOf(1)
                }
            }
            Log.d("VerpelisHD", "load - Temporadas a procesar: $allSeasons")
            val loadMoreButtonDisabled = epsDiv.selectFirst("#load-eps")?.hasAttr("disabled") == true
            Log.d("VerpelisHD", "load - Botón 'Cargar más' deshabilitado: $loadMoreButtonDisabled")
            for (seasonNumber in allSeasons) {
                var currentOffset = if (seasonNumber == allSeasons.firstOrNull()) episodeList.size else 0 // Empezar desde el tamaño actual de la lista para la primera temporada
                var hasMore = !loadMoreButtonDisabled // Si el botón está deshabilitado, asumimos que no hay más por AJAX.
                if (loadMoreButtonDisabled && currentOffset > 0) {
                    Log.d("VerpelisHD", "load - No se intentará cargar más episodios por AJAX para Temporada $seasonNumber porque el botón 'Cargar más' está deshabilitado y ya tenemos episodios iniciales.")
                    hasMore = false // Asegurarse de que el bucle no se ejecute
                }
                while (hasMore) {
                    val ajaxUrl = "${searchBaseUrl}/wp-admin/admin-ajax.php"
                    val formData = mapOf(
                        "action" to "corvus_get_episodes",
                        "post_id" to seriesId,
                        "season" to seasonNumber.toString(),
                        "nonce" to nonce,
                        "results" to defaultResults,
                        "offset" to currentOffset.toString(),
                        "order" to "DESC"
                    )
                    Log.d("VerpelisHD", "load - Pidiendo AJAX para Temporada $seasonNumber, Offset $currentOffset con URL: $ajaxUrl y formData: $formData")
                    val requestBodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    for ((key, value) in formData) {
                        requestBodyBuilder.addFormDataPart(key, value)
                    }
                    val requestBody = requestBodyBuilder.build()
                    val ajaxResponse = app.post(
                        ajaxUrl,
                        requestBody = requestBody,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to seriesPageUrl
                        )
                    )
                    if (!ajaxResponse.isSuccessful) {
                        Log.e("VerpelisHD", "load - Error al obtener respuesta AJAX para temporada $seasonNumber (URL: ${ajaxResponse.url}): Código ${ajaxResponse.code}. Respuesta RAW: ${ajaxResponse.text}")
                        break
                    }
                    val responseJsonText = ajaxResponse.text
                    Log.d("VerpelisHD", "load - Respuesta AJAX RAW para temporada $seasonNumber, offset $currentOffset: $responseJsonText")
                    val episodeApiResponse = tryParseJson<EpisodeApiResponse>(responseJsonText)
                    var newEpisodesThisIteration: List<Episode> = emptyList()
                    if (episodeApiResponse?.success == true) {
                        newEpisodesThisIteration = episodeApiResponse.data.results.mapNotNull { result ->
                            val epurl = fixUrl(result.permalink)
                            val epTitle = result.name
                            val realEpNumber = result.episode_number
                            val realSeasonNumber = result.season_number
                            val realimg = result.episode_image
                            if (epurl.isNotBlank()) {
                                newEpisode(
                                    EpisodeLoadData(result.title, epurl, realSeasonNumber, realEpNumber).toJson()
                                ) {
                                    name = "$epTitle"
                                    season = realSeasonNumber
                                    episode = realEpNumber
                                    posterUrl = realimg
                                    this.description = result.overview
                                }
                            } else {
                                Log.w("VerpelisHD", "load - URL de episodio vacía para S${result.season_number}E${result.episode_number} de ${result.title}")
                                null
                            }
                        }
                        episodeList.addAll(newEpisodesThisIteration)
                        if (episodeApiResponse.data.hasMore) {
                            currentOffset += newEpisodesThisIteration.size
                            Log.d("VerpelisHD", "load - Más episodios disponibles para Temporada $seasonNumber. Nuevo offset: $currentOffset")
                        } else {
                            hasMore = false
                            Log.d("VerpelisHD", "load - No hay más episodios para Temporada $seasonNumber (según respuesta AJAX).")
                        }
                    } else {
                        Log.e("VerpelisHD", "load - Error o éxito falso en la respuesta AJAX para temporada $seasonNumber, offset $currentOffset. JSON inválido o 'success' es false. Respuesta: $responseJsonText")
                        hasMore = false // Detener el bucle si hay error o success:false
                    }
                    if (newEpisodesThisIteration.isEmpty() && currentOffset > 0 && hasMore) {
                        Log.w("VerpelisHD", "load - No se obtuvieron nuevos episodios pero 'hasMore' es true. Deteniendo bucle para evitar infinito.")
                        hasMore = false
                    }
                }
            }
            Log.d("VerpelisHD", "load - Total de episodios encontrados: ${episodeList.size}")
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
                    this.backgroundPosterUrl = backgroundPoster
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
                    this.backgroundPosterUrl = backgroundPoster
                    this.plot = description
                    this.tags = tags
                }
            }
            else -> null
        }
    }
    data class PlusStreamEmbed( // Cambiado de SortedEmbed a PlusStreamEmbed para claridad
        @JsonProperty("servername") val servername: String,
        @JsonProperty("link") val link: String,
        @JsonProperty("type") val type: String
    )
    data class PlusStreamDataLinkEntry( // Cambiado de DataLinkEntry a PlusStreamDataLinkEntry
        @JsonProperty("file_id") val file_id: String, // Asumo que es String por el ejemplo anterior, si es Int cámbialo
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
    private suspend fun appPost(
        url: String,
        data: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): String? {
        return try {
            app.post( // 'app' debe ser una propiedad de esta clase (o accesible globalmente)
                url,
                headers = headers,
                data = data,
                referer = referer
            ).text
        } catch (e: Exception) {
            Log.e("VerpelisHD", "Error en appPost para URL: $url - ${e.message}", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String, // 'data' es un String de entrada
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("VerpelisHD", "loadLinks - Data de entrada: $data")

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data) // Aquí se usa
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url // Acceso a parsedEpisodeData.url
            Log.d("VerpelisHD", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
            val match = regexExtractUrl.find(data)
            targetUrl = fixUrl(match?.groupValues?.get(1) ?: data)
            Log.d("VerpelisHD", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("VerpelisHD", "loadLinks - ERROR: URL objetivo está en blanco.")
            return false
        }

        // --- CORRECCIÓN 1: safeAppGet ya devuelve String? directamente ---
        val initialHtmlString = safeAppGet(targetUrl) // ¡Directamente el String? HTML!

        if (initialHtmlString.isNullOrBlank()) {
            Log.e("VerpelisHD", "loadLinks - No se pudo obtener HTML (o estaba vacío) para: $targetUrl")
            return false
        }
        // Ahora initialHtmlString es un String no nulo, perfecto para Jsoup.parse()
        val initialHtml: Document = Jsoup.parse(initialHtmlString)

        // --- LÓGICA DE DETECCIÓN DE IFRAME EXTERNO ---
        val mainIframeSrc = initialHtml.selectFirst("div.player iframe")?.attr("src")
            ?: initialHtml.selectFirst("#player iframe")?.attr("src")

        if (!mainIframeSrc.isNullOrBlank()) {
            Log.d("VerpelisHD", "loadLinks - Se encontró un iframe externo principal: $mainIframeSrc. Intentando cargar.")
            val extractedFromIframe = loadExtractor(mainIframeSrc, targetUrl, subtitleCallback, callback)
            if (extractedFromIframe) {
                Log.d("VerpelisHD", "loadLinks - Enlaces extraídos exitosamente del iframe externo.")
                return true
            } else {
                Log.w("VerpelisHD", "loadLinks - No se pudieron extraer enlaces del iframe externo: $mainIframeSrc. Intentando lógica interna de VerpelisHD.")
            }
        }

        // --- LÓGICA EXISTENTE PARA LA API INTERNA DE VERPELISHD (corvus_get_servers) ---
        val playerElement = initialHtml.selectFirst("div[data-id][data-nonce]")
            ?: initialHtml.selectFirst("#to--expand")
            ?: initialHtml.selectFirst(".fke-player")

        val postId = playerElement?.attr("data-id") ?: ""
        val nonce = playerElement?.attr("data-nonce") ?: ""

        if (postId.isBlank() || nonce.isBlank()) {
            Log.w("VerpelisHD", "loadLinks - No se pudieron encontrar 'data-id' o 'data-nonce' en el HTML inicial para el reproductor. Esto puede ser normal si el iframe externo funcionó.")
            Log.w("VerpelisHD", "loadLinks - No se encontraron los datos necesarios para la API interna. Fallo de extracción.")
            return false
        }

        val scriptContentInitial = initialHtml.select("script").map { it.html() }.joinToString("\n")
        val corvutilsAjaxUrlRegex = Regex("""corvutils\.ajaxurl\s*:\s*['"]([^'"]+)['"]""")
        val corvutilsAjaxUrlMatch = corvutilsAjaxUrlRegex.find(scriptContentInitial)
        val baseUrlCorvutils = corvutilsAjaxUrlMatch?.groupValues?.get(1)?.trimEnd('/')
        val ajaxUrl = if (!baseUrlCorvutils.isNullOrBlank()) {
            "$baseUrlCorvutils/admin-ajax.php"
        } else {
            "https://verpelishd.me/wp-admin/admin-ajax.php"
        }

        Log.d("VerpelisHD", "loadLinks - post_id: $postId, nonce: $nonce, ajaxUrl: $ajaxUrl. Intentando API interna.")

        val postData = mapOf(
            "action" to "corvus_get_servers",
            "nonce" to nonce,
            "post_id" to postId
        )

        val postHeaders = mapOf(
            "User-Agent" to VERPELISHD_USER_AGENT,
            "Accept" to "*/*",
            // "Accept-Encoding" to "gzip, deflate, br, zstd", // Mantener comentado o eliminar si no quieres compresión automática
            "Accept-Language" to "es-ES,es;q=0.7",
            "Origin" to "https://verpelishd.me",
            "Priority" to "u=1, i",
            "Referer" to targetUrl,
            "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Brave\";v=\"138\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
            "sec-gpc" to "1",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val jsonResponseRaw = appPost(
            url = ajaxUrl,
            data = postData,
            headers = postHeaders,
            referer = targetUrl
        )

        if (jsonResponseRaw.isNullOrBlank()) {
            Log.e("VerpelisHD", "loadLinks - Falló la petición AJAX para obtener los servidores o la respuesta estaba vacía.")
            return false
        }

        Log.d("VerpelisHD", "loadLinks - Respuesta JSON de servidores obtenida (raw): $jsonResponseRaw")

// *** ¡CAMBIO CLAVE AQUÍ! ***
// Parsear directamente como una lista de PlayerOption
        val playersList = tryParseJson<List<PlayerOption>>(jsonResponseRaw) // <--- CAMBIO DE ServersResponse A List<PlayerOption>

        var foundLinks = false

// Ahora, la comprobación se hace directamente sobre playersList
        if (!playersList.isNullOrEmpty()) { // playersList ya es List<PlayerOption>?, así que no necesitamos serversResponse.players
            Log.d("VerpelisHD", "loadLinks - Servidores encontrados desde la API interna. Procesando ${playersList.size} reproductores.")

            // Si 'apmap' da el error de "nullable receiver", usa 'playersList.let { nonNullableList -> ... }'
            // Como playersList ya se está comprobando con !isNullOrEmpty(), Kotlin debería hacer el smart cast.
            // Si no lo hace, entonces sí, usa el 'let' como opción B que te di antes.
            playersList.apmap { player -> // 'player' es de tipo PlayerOption
                val rawLink = player.url
                // Mantenemos la condición de tipo "iframe", "direct", "embed"
                if (!rawLink.isNullOrBlank() && (player.type == "iframe" || player.type == "direct" || player.type == "embed")) {
                    Log.d("VerpelisHD", "loadLinks - Enlace de servidor interno (PLUSTREAM/otro tipo compatible): $rawLink (Tipo: ${player.type}, Nombre: ${player.name ?: "N/A"})")
                    val extracted = loadExtractor(rawLink, targetUrl, subtitleCallback, callback)
                    if (extracted) {
                        foundLinks = true
                    } else {
                        Log.w("VerpelisHD", "loadLinks - loadExtractor de CloudStream falló al procesar el enlace: $rawLink")
                    }
                } else {
                    Log.w("VerpelisHD", "loadLinks - Enlace de servidor interno descartado (nulo/vacío o tipo no reconocido): $rawLink (Tipo: ${player.type})")
                }
            }

            if (foundLinks) return true
        } else {
            // Este log ahora es más preciso
            Log.w("VerpelisHD", "loadLinks - La API interna devolvió una lista de reproductores vacía o no se pudo parsear a una lista de PlayerOption. Respuesta: ${jsonResponseRaw}")
        }

        Log.w("VerpelisHD", "loadLinks - No se encontraron enlaces de video funcionales para: $targetUrl")
        return false
    }

    @Serializable
    data class ServersResponse(
        val success: Boolean,
        val players: List<PlayerOption>
    )

    @Serializable
    data class PlayerOption( // ¡El nombre correcto es PlayerOption!
        val name: String?,
        val url: String?,
        val type: String? // "iframe", "direct", o "embed" (según los logs)
    )
}
//Yeji