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

// NECESITARÁS ESTA IMPORTACIÓN PARA @JsonProperty SI NO LA TIENES YA
import com.fasterxml.jackson.annotation.JsonProperty

class VerpelishdProvider : MainAPI() {
    override var mainUrl = "https://verpelishd.me/portal"
    private val searchBaseUrl = "https://verpelishd.me" // Usaremos esto para construir la URL de la serie
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

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L,
        headers: Map<String, String> = mapOf() // Añadir headers para las peticiones AJAX
    ): String? {
        for (i in 0 until retries) {
            try {
                Log.d("VerpelisHD", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs, headers = headers) // Usar los headers
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

        // Sección 1: Destacados (Featured)
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

        // Sección 2: Últimos episodios (Latest Episodes)
        val latestEpisodesSection = doc.selectFirst("div.section--episode")
        if (latestEpisodesSection != null) {
            val latestEpisodesItems = latestEpisodesSection.select("article.ieps").mapNotNull {
                val title = it.selectFirst("h3.ieps__title a")?.text()
                val episodeLink = it.selectFirst("a")?.attr("href") // URL del episodio
                val img = it.selectFirst("picture.ieps__image img")?.attr("src")

                if (title != null && episodeLink != null) {
                    // NUEVA REGEX para extraer el slug de la serie de la URL del episodio
                    // Ejemplo: https://verpelishd.me/episodios/duster-s1x8/ -> duster
                    val seriesSlugMatch = Regex("""\/episodios\/([^\/]+?)-s\d+x\d+\/""").find(episodeLink)
                    val seriesSlug = seriesSlugMatch?.groupValues?.get(1)

                    if (seriesSlug != null) {
                        val seriesUrl = "$searchBaseUrl/serie/${seriesSlug}/" // Construimos la URL de la serie
                        newAnimeSearchResponse(
                            title,
                            fixUrl(seriesUrl) // AHORA LA URL ES LA DE LA SERIE COMPLETA
                        ) {
                            this.type = TvType.TvSeries
                            posterUrl = img
                        }
                    } else {
                        Log.w("VerpelisHD", "getMainPage - No se pudo extraer la URL de la serie de: $episodeLink")
                        null // Si no se puede extraer la URL de la serie, no agregamos este elemento
                    }
                } else null
            }
            if (latestEpisodesItems.isNotEmpty()) {
                items.add(HomePageList("Últimos episodios", latestEpisodesItems))
            }
        } else {
            Log.w("VerpelisHD", "getMainPage - Sección 'Últimos episodios' no encontrada.")
        }

        // Sección 3: Agregados recientemente - Películas
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

        // Sección 4: Agregados recientemente - Series
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

        // Sección 5: Popular ahora
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
        // Usamos los mismos selectores que las secciones de la página principal que contienen artículos de tipo 'ipst'
        return doc.select("div.items article.ipst").mapNotNull {
            val title = it.selectFirst("h3.ipst__title a")?.text()
            val link = it.selectFirst("figure.ipst__image a")?.attr("href")
            val img = it.selectFirst("figure.ipst__image a img")?.attr("src")

            if (title != null && link != null) {
                // Determinar el tipo basado en el enlace, asumiendo que el buscador puede devolver series o películas
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

    // Data class para serializar/deserializar la URL de episodio para loadLinks
    data class EpisodeLoadData(
        val title: String, // Título de la serie
        val url: String, // URL del episodio específico
        val season: Int?,
        val episode: Int?
    )

    // Data classes para parsear la respuesta JSON de la API de episodios
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
        @JsonProperty("title") val title: String, // Título de la serie
        @JsonProperty("name") val name: String, // Título del episodio
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
        // Intentar parsear si la URL viene de EpisodeLoadData (para cuando se hace clic en un episodio listado en LoadResponse)
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanUrl)
        if (parsedEpisodeData != null) {
            cleanUrl = parsedEpisodeData.url // Si es un JSON, obtenemos la URL del episodio de ahí
            Log.d("VerpelisHD", "load - URL limpia de EpisodeLoadData: $cleanUrl")
        } else {
            // Limpieza general de URL si no es un JSON de EpisodeLoadData (para películas o series clickeadas directamente desde el SearchResponse)
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

        // Extracción de detalles generales
        val tvType = if (cleanUrl.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("article.hero h2")?.text() ?: doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.hero__poster img")?.attr("src") ?: doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val backgroundPoster = doc.selectFirst("figure.hero__backdrop img")?.attr("src") ?: poster
        val description = doc.selectFirst("p.hero__overview")?.text() ?: doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.hero__genres ul li a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            val episodeList = ArrayList<Episode>()
            val seriesId = doc.selectFirst("div.eps[data-tmdb-id]")?.attr("data-tmdb-id")
            val ajaxUrlBase = doc.selectFirst("div.eps[data-ajaxurl]")?.attr("data-ajaxurl")
            val nonce = doc.selectFirst("div.eps[data-nonce]")?.attr("data-nonce")

            if (seriesId.isNullOrBlank() || ajaxUrlBase.isNullOrBlank() || nonce.isNullOrBlank()) {
                Log.e("VerpelisHD", "load - No se pudieron obtener seriesId, ajaxUrlBase o nonce para cargar episodios.")
                return null
            }

            // Obtener todas las temporadas disponibles del HTML
            val seasonsButtons = doc.select("details.eps-ssns div button")
            // Si no hay botones de temporadas, intentamos con la temporada 1 por defecto
            val allSeasons = if (seasonsButtons.isNotEmpty()) {
                seasonsButtons.mapNotNull { it.attr("data-season").toIntOrNull() }.sortedDescending()
            } else {
                Log.d("VerpelisHD", "load - No se encontraron botones de temporadas, asumiendo temporada 1.")
                listOf(1) // Asumimos al menos una temporada si no se especifican
            }


            for (seasonNumber in allSeasons) {
                // Construir la URL de la petición AJAX para cada temporada
                val ajaxUrl = "${searchBaseUrl}/wp-admin/admin-ajax.php" // Usamos searchBaseUrl que es "https://verpelishd.me"
                val formData = mapOf(
                    "action" to "seasons",
                    "id" to seriesId,
                    "season" to seasonNumber.toString(),
                    "nonce" to nonce,
                    "order" to "DESC"
                )

                Log.d("VerpelisHD", "load - Obteniendo episodios para Temporada $seasonNumber con AJAX: $ajaxUrl")
                val responseJsonText = app.post(ajaxUrl, data = formData).text

                // PARSEAR EL JSON A LA CLASE DE DATOS EpisodeApiResponse
                val episodeApiResponse = tryParseJson<EpisodeApiResponse>(responseJsonText)

                if (episodeApiResponse?.success == true) {
                    val seasonEpisodes = episodeApiResponse.data.results.mapNotNull { result ->
                        val epurl = fixUrl(result.permalink)
                        val epTitle = result.name // Nombre del episodio del JSON
                        val realEpNumber = result.episode_number
                        val realSeasonNumber = result.season_number
                        val realimg = result.episode_image

                        if (epurl.isNotBlank()) {
                            newEpisode(
                                // Usamos EpisodeLoadData para la URL del episodio real para loadLinks
                                // Aquí pasamos el 'title' del JSON que es el título de la SERIE
                                EpisodeLoadData(result.title, epurl, realSeasonNumber, realEpNumber).toJson()
                            ) {
                                name = "$epTitle" // El nombre del episodio es 'name' del JSON
                                season = realSeasonNumber
                                episode = realEpNumber
                                posterUrl = realimg
                                this.description = result.overview // Añadir la descripción del episodio
                            }
                        } else null
                    }
                    episodeList.addAll(seasonEpisodes)
                } else {
                    Log.e("VerpelisHD", "load - Error o éxito falso en la respuesta AJAX para temporada $seasonNumber: $responseJsonText")
                }
            }
            episodeList.sortedWith(compareBy({ it.season }, { it.episode })) // Ordenar por temporada y luego por episodio
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
                    dataUrl = cleanUrl // dataUrl es apropiado aquí para la MovieLoadResponse
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

    data class SortedEmbed(
        val servername: String,
        val link: String,
        val type: String
    )

    data class DataLinkEntry(
        val file_id: String,
        val video_language: String,
        val sortedEmbeds: List<SortedEmbed>
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
        Log.d("VerpelisHD", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        // La regex para extraer URL si viene como un string directo (para movies, o si EpisodeLoadData falla)
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("VerpelisHD", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("VerpelisHD", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        // Intentar parsear si la 'data' es un JSON de EpisodeLoadData (para cuando un episodio es clickeado)
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url // Si es un JSON, obtenemos la URL del episodio de ahí
            Log.d("VerpelisHD", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData) // Si no es JSON, usamos la URL tal cual (para películas, por ejemplo)
            Log.d("VerpelisHD", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("VerpelisHD", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val initialHtml = safeAppGet(targetUrl)
        if (initialHtml == null) {
            Log.e("VerpelisHD", "loadLinks - No se pudo obtener HTML para la URL principal del contenido: $targetUrl")
            return false
        }
        val doc = Jsoup.parse(initialHtml)

        // Se ajustan los selectores para encontrar los iframes en la página de detalles
        val playerIframeSrc = doc.selectFirst("iframe[src*=\"/reproductor/\"]")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")


        if (playerIframeSrc.isNullOrBlank()) {
            Log.d("VerpelisHD", "No se encontró iframe del reproductor principal. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    Log.d("VerpelisHD", "Encontrado enlace directo en script de página principal: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("VerpelisHD", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("VerpelisHD", "Iframe principal encontrado: $playerIframeSrc")

        val finalPlayerUrl = fixUrl(playerIframeSrc)
        Log.d("VerpelisHD", "URL del reproductor final: $finalPlayerUrl")

        val playerHtml = safeAppGet(finalPlayerUrl)
        if (playerHtml == null) {
            Log.e("VerpelisHD", "No se pudo obtener el HTML del reproductor desde: $finalPlayerUrl")
            return false
        }
        val playerDoc = Jsoup.parse(playerHtml)

        // Intenta encontrar enlaces de video directamente en el HTML del reproductor
        val scriptContent = playerDoc.select("script").map { it.html() }.joinToString("\n")

        // Regex para buscar URLs de video o URLs de reproductores conocidos
        val videoUrlRegex = """(https?:\/\/(?:www\.)?(?:fembed\.com|streamlare\.com|ok\.ru|mp4upload\.com|your_other_extractor\.com)[^\s"']+)""".toRegex()
        val videoUrls = videoUrlRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

        if (videoUrls.isNotEmpty()) {
            videoUrls.apmap { url ->
                Log.d("VerpelisHD", "Encontrado URL de video en el reproductor: $url")
                loadExtractor(url, targetUrl, subtitleCallback, callback)
            }
            return true
        }

        // Si no se encuentran URLs de video directas en el script del reproductor,
        // puedes buscar iframes anidados o llamadas a funciones JavaScript que generen enlaces.
        // Por ejemplo, si hay un iframe dentro de este reproductor, lo puedes seguir.
        val nestedIframe = playerDoc.selectFirst("iframe")?.attr("src")
        if (!nestedIframe.isNullOrBlank()) {
            Log.d("VerpelisHD", "Encontrado iframe anidado en el reproductor: $nestedIframe")
            // Recursivamente llamamos a loadExtractor con el nuevo iframe URL
            loadExtractor(fixUrl(nestedIframe), targetUrl, subtitleCallback, callback)
            return true
        }

        Log.w("VerpelisHD", "No se encontraron enlaces de video directos ni iframes anidados en el reproductor: $finalPlayerUrl")
        return false
    }
}