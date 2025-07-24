package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.* // Asegúrate de que ExtractorLinkType y INFER_TYPE estén importados
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
                // El error "Val cannot be reassigned" en esta línea DEBERÍA haber desaparecido con el Clean/Rebuild.
                // Si persiste, es muy extraño y podría ser un problema del IDE o la configuración del proyecto.
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

    // --- Data Classes para la API de AnimeOnsen ---
    data class AnimeOnsenContent(
        val id: String,
        val type: String, // "anime", "movie"
        val titles: Map<String, String?>?, // *** ¡NUEVO INTENTO! El mapa EN SÍ puede ser nulo, y sus valores también. ***
        val poster: String?,
        val banner: String?,
        val description: String?,
        val genres: List<String>?,
        val episodes: List<AnimeOnsenEpisode>?, // Para series
        val releaseYear: Int?
    ) {
        val preferredTitle: String
            // Ajustar para manejar el caso donde 'titles' es completamente nulo
            get() = titles?.get("en") ?: titles?.get("ja") ?: titles?.values?.firstOrNull() ?: "Título Desconocido"
    }

    data class AnimeOnsenEpisode(
        val id: String,
        val number: Int,
        val title: String?,
        val thumbnail: String?,
        val streams: List<AnimeOnsenStream>? // Detalles de los enlaces del video
    )

    data class AnimeOnsenStream(
        val id: String,
        val type: String, // e.g., "mp4", "m3u8"
        val url: String,
        val resolution: String?, // e.g., "1080p"
        val isDHLS: Boolean? // Si es DASH/HLS
    )

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

        val recentAnimeUrl = "$apiOrigin/api/v2/anime?sort=recently_updated&page=$page"
        val popularAnimeUrl = "$apiOrigin/api/v2/anime?sort=popularity&page=$page"

        val recentAnimeJson = safeAppGet(recentAnimeUrl)
        if (recentAnimeJson != null) {
            val recentAnimeResponse = tryParseJson<List<AnimeOnsenContent>>(recentAnimeJson)
            if (recentAnimeResponse != null) {
                val homeItems = recentAnimeResponse.mapNotNull { content ->
                    newAnimeSearchResponse(
                        content.preferredTitle,
                        "$mainUrl/anime/${content.id}"
                    ) {
                        this.type = if (content.type == "movie") TvType.Movie else TvType.Anime
                        this.posterUrl = content.poster
                    }
                }
                items.add(HomePageList("Animes Recientes", homeItems))
            } else {
                Log.e("AnimeOnsen", "Error al parsear JSON de animes recientes: $recentAnimeJson")
            }
        } else {
            Log.e("AnimeOnsen", "No se pudo obtener animes recientes de la API.")
        }

        val popularAnimeJson = safeAppGet(popularAnimeUrl)
        if (popularAnimeJson != null) {
            val popularAnimeResponse = tryParseJson<List<AnimeOnsenContent>>(popularAnimeJson)
            if (popularAnimeResponse != null) {
                val homeItems = popularAnimeResponse.mapNotNull { content ->
                    newAnimeSearchResponse(
                        content.preferredTitle,
                        "$mainUrl/anime/${content.id}"
                    ) {
                        this.type = if (content.type == "movie") TvType.Movie else TvType.Anime
                        this.posterUrl = content.poster
                    }
                }
                items.add(HomePageList("Animes Populares", homeItems))
            } else {
                Log.e("AnimeOnsen", "Error al parsear JSON de animes populares: $popularAnimeJson")
            }
        } else {
            Log.e("AnimeOnsen", "No se pudo obtener animes populares de la API.")
        }

        Log.d("AnimeOnsen", "DEBUG: getMainPage finalizado. ${items.size} listas añadidas.")
        return newHomePageResponse(items, false)
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

        val searchUrl = "$searchOrigin/api/v2/search?query=$query"
        val headers = mapOf("Authorization" to "Bearer $searchToken")

        val searchJson = safeAppGet(searchUrl, headers = headers)
        if (searchJson == null) {
            Log.e("AnimeOnsen", "search - No se pudo obtener JSON para la búsqueda: $searchUrl")
            return emptyList()
        }

        val searchResults = tryParseJson<List<AnimeOnsenContent>>(searchJson)
        if (searchResults.isNullOrEmpty()) {
            Log.d("AnimeOnsen", "No se encontraron resultados de búsqueda o el parseo falló.")
            return emptyList()
        }

        return searchResults.mapNotNull { content ->
            newAnimeSearchResponse(
                content.preferredTitle,
                "$mainUrl/anime/${content.id}"
            ) {
                this.type = if (content.type == "movie") TvType.Movie else TvType.Anime
                this.posterUrl = content.poster
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
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

        val apiUrl = "$apiOrigin/api/v2/anime/$animeId"
        val headers = searchToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

        val animeJson = safeAppGet(apiUrl, headers = headers)
        if (animeJson == null) {
            Log.e("AnimeOnsen", "load - No se pudo obtener JSON del anime de la API para ID: $animeId")
            return null
        }

        val animeContent = tryParseJson<AnimeOnsenContent>(animeJson)
        if (animeContent == null) {
            Log.e("AnimeOnsen", "load - Error al parsear JSON del anime para ID: $animeId: $animeJson")
            return null
        }

        val title = animeContent.preferredTitle
        val poster = animeContent.poster
        val description = animeContent.description
        val tags = animeContent.genres
        val tvType = if (animeContent.type == "movie") TvType.Movie else TvType.Anime

        val episodes = if (tvType == TvType.Anime) {
            animeContent.episodes?.mapNotNull { ep ->
                newEpisode(
                    EpisodeLoadData(ep.title ?: "Episodio ${ep.number}", "$mainUrl/anime/$animeId/episode/${ep.number}").toJson()
                ) {
                    this.name = ep.title ?: "Episodio ${ep.number}"
                    this.season = 1
                    this.episode = ep.number
                    this.posterUrl = ep.thumbnail
                }
            } ?: listOf()
        } else listOf()

        val recommendations = listOf<SearchResponse>()

        return when (tvType) {
            TvType.Anime, TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = url,
                    type = tvType,
                    episodes = episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = animeContent.banner ?: poster
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                    this.year = animeContent.releaseYear
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(
                    name = title,
                    url = url,
                    type = tvType,
                    dataUrl = EpisodeLoadData(title, "$mainUrl/anime/$animeId/episode/1").toJson()
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = animeContent.banner ?: poster
                    this.plot = description
                    this.tags = tags
                    this.recommendations = recommendations
                    this.year = animeContent.releaseYear
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
        val episodeUrl = parsedEpisodeData?.url ?: data

        val urlParts = episodeUrl.split("/")
        val animeId = urlParts.getOrNull(urlParts.indexOf("anime") + 1)
        val episodeNumber = urlParts.getOrNull(urlParts.indexOf("episode") + 1)?.toIntOrNull()

        if (animeId.isNullOrBlank() || episodeNumber == null) {
            Log.e("AnimeOnsen", "loadLinks - No se pudo extraer el ID del anime o el número de episodio de la URL: $episodeUrl")
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

        val episodeStreamsUrl = "$apiOrigin/api/v2/anime/$animeId/episode/$episodeNumber"
        val headers = searchToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

        val episodeJson = safeAppGet(episodeStreamsUrl, headers = headers)
        if (episodeJson == null) {
            Log.e("AnimeOnsen", "loadLinks - No se pudo obtener JSON de streams del episodio: $episodeStreamsUrl")
            return false
        }

        val episodeData = tryParseJson<AnimeOnsenEpisode>(episodeJson)
        if (episodeData == null || episodeData.streams.isNullOrEmpty()) {
            Log.e("AnimeOnsen", "loadLinks - Error al parsear JSON de streams o no se encontraron streams para el episodio.")
            return false
        }

        var foundLinks = false
        for (stream in episodeData.streams) {
            Log.d("AnimeOnsen", "Procesando stream: ${stream.url} (Tipo: ${stream.type}, Resolución: ${stream.resolution})")
            if (stream.url.isNotBlank()) {
                if (stream.type == "m3u8" || stream.isDHLS == true) {
                    callback(
                        newExtractorLink(
                            source = "AnimeOnsen",
                            name = "AnimeOnsen ${stream.resolution ?: "HD"}",
                            url = stream.url,
                            type = ExtractorLinkType.M3U8 // M3U8 es una constante de ExtractorLinkType
                        ) {
                            quality = getQualityFromName(stream.resolution ?: "")
                            referer = mainUrl
                        }
                    )
                    foundLinks = true
                } else {
                    if (loadExtractor(stream.url, mainUrl, subtitleCallback, callback)) {
                        foundLinks = true
                    } else {
                        callback(
                            newExtractorLink(
                                source = "AnimeOnsen",
                                name = "AnimeOnsen ${stream.resolution ?: "Direct"}",
                                url = stream.url,
                                type = INFER_TYPE // *** CAMBIO: Usar INFER_TYPE para tipos que no son M3U8 ***
                            ) {
                                quality = getQualityFromName(stream.resolution ?: "")
                                referer = mainUrl
                            }
                        )
                        foundLinks = true
                    }
                }
            }
        }

        if (!foundLinks) {
            Log.e("AnimeOnsen", "loadLinks - No se encontraron enlaces de video válidos después de procesar los streams de la API.")
        }
        return foundLinks
    }
}