package com.example // <-- Confirma que este es tu package real

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.FormBody
import kotlinx.coroutines.delay
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper


// Puedes definir esta clase en algún lugar accesible de tu plugin
// Representa la estructura de un episodio en la respuesta JSON
// (Ya la tenías, la he incluido para el contexto completo)
data class KatanimeEpisode(
    @JsonProperty("chapter") val chapter: String,
    @JsonProperty("link") val link: String // El enlace al capítulo individual
)

// Y la respuesta completa de la API de episodios
// (Ya la tenías, la he incluido para el contexto completo)
data class EpisodeResponse( // Cambiado a EpisodeResponse para evitar conflicto con la tuya
    val ep: EpisodesWrapper?, // Make nullable just in case
    val valoration: String?,
    val last: LastEpisode?
)

data class EpisodesWrapper(
    val current_page: Int,
    val data: List<EpisodeItem>,
    val first_page_url: String?,
    val from: Int,
    val last_page: Int,
    val links: List<LinkItem>?,
    val next_page_url: String?,
    val path: String,
    val per_page: Int,
    val prev_page_url: String?,
    val to: Int,
    val total: Int
)

data class EpisodeItem(
    val id: Int?,
    val title: String?,
    val numero: String?,
    val thumb: String?,
    val created_at: String?,
    val url: String
)

data class LastEpisode(
    val numero: String?
)

data class LinkItem(
    val url: String?,
    val label: String,
    val active: Boolean
)

// Clase auxiliar para pasar datos entre load y loadLinks (si la usas)
data class EpisodeLoadData(
    val title: String,
    val url: String, // Esta URL SIEMPRE debe ser la URL del CAPÍTULO para loadLinks
    val poster: String?
)


class KatanimeProvider : MainAPI() {
    override var name = "Katanime"
    override var mainUrl = "https://katanime.net"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // Cliente para Cloudflare, asegúrate de que esté correctamente inicializado y utilizado.
    private val cfKiller = CloudflareKiller()

    // ObjectMapper para JSON
    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)


    // --- parseAnimeItem: Refinado para consistencia en URLs de ANIME ---
    private fun parseAnimeItem(element: Element, isSlider: Boolean = false, isRecentEpisodeCard: Boolean = false): SearchResponse? {
        var link: String? = null
        var title: String? = null
        var posterUrl: String? = null

        if (isSlider) {
            val sliderLinkElement = element.selectFirst("a.viewBtn")
            val sliderTitleElement = element.selectFirst(".slider_info h1")
            val sliderPosterImgElement = element.selectFirst(".sliderimg img")

            if (sliderLinkElement != null && sliderTitleElement != null && sliderPosterImgElement != null) {
                link = sliderLinkElement.attr("href")?.let { fixUrl(it) }
                title = sliderTitleElement.text()?.trim()
                posterUrl = sliderPosterImgElement.attr("data-src")?.let { fixUrl(it) }
                    ?: sliderPosterImgElement.attr("src")?.let { fixUrl(it) }

                if (title.isNullOrBlank() || link.isNullOrBlank() || posterUrl.isNullOrBlank()) {
                    Log.w("Katanime", "parseAnimeItem - Datos incompletos (slider), saltando: Title='$title', Link='$link', Poster='$posterUrl'")
                    return null
                }
                Log.d("Katanime", "parseAnimeItem - Parseado (slider): Title='$title', Link='$link', Poster='$posterUrl'")
            } else {
                Log.w("Katanime", "parseAnimeItem - Elemento slider no coincide con estructura esperada. HTML: ${element.html().take(200)}")
                return null
            }
        } else if (isRecentEpisodeCard) {
            // Para las cards de "Capítulos Recientes", el primer <a> con estas clases es el link al ANIME.
            val animeLinkElement = element.selectFirst("div._2NNxg a._1A2Dc._2uHIS")
            val posterImgElement = element.selectFirst("img")

            if (animeLinkElement != null && posterImgElement != null) {
                link = animeLinkElement.attr("href")?.let { fixUrl(it) }
                title = animeLinkElement.text()?.trim()
                posterUrl = posterImgElement.attr("data-src")?.let { fixUrl(it) }
                    ?: posterImgElement.attr("src")?.let { fixUrl(it) }

                if (title.isNullOrBlank() || link.isNullOrBlank() || posterUrl.isNullOrBlank()) {
                    Log.w("Katanime", "parseAnimeItem - Datos incompletos (capítulo reciente), saltando: Title='$title', Link='$link', Poster='$posterUrl'")
                    return null
                }
                // ¡Confirmado! Este link ya debe ser al ANIME, no al capítulo.
                Log.d("Katanime", "parseAnimeItem - Parseado (capítulo reciente, link a anime): Title='$title', Link='$link', Poster='$posterUrl'")
            } else {
                Log.w("Katanime", "parseAnimeItem - Elemento de capítulo reciente no coincide con estructura esperada. HTML: ${element.html().take(200)}")
                return null
            }

        } else {
            // Lógica para resultados de búsqueda y animes populares
            // El selector `a._1A2Dc._38LRT` parece ser el enlace directo del ANIME.
            val linkElement = element.selectFirst("a._1A2Dc._38LRT")
            val titleElement = element.selectFirst("div._2NNxg a._1A2Dc._2uHIS") // Título dentro del mismo contenedor
            val posterImgElement = element.selectFirst("img")

            if (linkElement != null && titleElement != null && posterImgElement != null) {
                link = linkElement.attr("href")?.let { fixUrl(it) }
                title = titleElement.text()?.trim()
                posterUrl = posterImgElement.attr("src")?.let { fixUrl(it) }
                    ?: posterImgElement.attr("data-src")?.let { fixUrl(it) }

                if (title.isNullOrBlank() || link.isNullOrBlank() || posterUrl.isNullOrBlank()) {
                    Log.w("Katanime", "parseAnimeItem - Datos incompletos (búsqueda/popular), saltando: Title='$title', Link='$link', Poster='$posterUrl'")
                    return null
                }
                Log.d("Katanime", "parseAnimeItem - Parseado (búsqueda/popular): Title='$title', Link='$link', Poster='$posterUrl'")
            } else {
                Log.w("Katanime", "parseAnimeItem - Elemento de búsqueda/popular no coincide con estructura esperada. HTML: ${element.html().take(200)}")
                return null
            }
        }

        return newTvSeriesSearchResponse(
            name = title,
            url = link
        ) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val doc = try {
            app.get(mainUrl, interceptor = cfKiller).document
        } catch (e: Exception) {
            Log.e("Katanime", "getMainPage - Error al obtener la página principal: ${e.message}")
            return null
        }

        // --- 1. Carrusel Principal (Slider) ---
        val sliderElements = doc.select("ul#mainslider li.slider-item")
        val sliderItems = sliderElements.mapNotNull { parseAnimeItem(it, isSlider = true) }
        if (sliderItems.isNotEmpty()) {
            items.add(HomePageList("Animes Destacados", sliderItems))
            Log.d("Katanime", "getMainPage - Se añadió la sección 'Animes Destacados' con ${sliderItems.size} elementos.")
        } else {
            Log.w("Katanime", "getMainPage - 'Animes Destacados' no encontró elementos con los selectores internos (ul#mainslider li.slider-item).")
        }


        // --- 2. Capítulos Recientes (Animes Recientes) ---
        val recentChaptersTitle = doc.selectFirst("h3.carousel.t:contains(Capítulos recientes)")
        if (recentChaptersTitle != null) {
            val contentLeftDiv = recentChaptersTitle.nextElementSibling()
            val recentAnimeElements = contentLeftDiv?.select("div._135yj._2FQAt.chap._2mJki")

            val recentItems = recentAnimeElements?.mapNotNull { chapterElement ->
                // Usamos parseAnimeItem con la bandera isRecentEpisodeCard
                parseAnimeItem(chapterElement, isRecentEpisodeCard = true)
            } ?: emptyList()

            if (recentItems.isNotEmpty()) {
                items.add(HomePageList("Capítulos Recientes", recentItems))
                Log.d("Katanime", "getMainPage - Se añadió la sección 'Capítulos Recientes' con ${recentItems.size} elementos.")
            } else {
                Log.w("Katanime", "getMainPage - 'Capítulos Recientes' no encontró elementos válidos o está vacía después de parsear.")
            }
        } else {
            Log.w("Katanime", "getMainPage - No se encontró la sección 'Capítulos Recientes'.")
        }

        // --- Animes Populares (widget de la derecha) ---
        // Revisar este selector si sigue fallando
        val popularWidget = doc.selectFirst("div#widget.dark h3:contains(Animes populares)")
        if (popularWidget != null) {
            val popularAnimeElements = popularWidget.parent()?.select("div._135yj._2FQAt.full._2mJki")
            val popularItems = popularAnimeElements?.mapNotNull { parseAnimeItem(it) } ?: emptyList()
            if (popularItems.isNotEmpty()) {
                items.add(HomePageList("Animes Populares", popularItems))
                Log.d("Katanime", "getMainPage - Se añadió la sección 'Animes Populares' con ${popularItems.size} elementos.")
            } else {
                Log.w("Katanime", "getMainPage - 'Animes Populares' no encontró elementos válidos o está vacía.")
            }
        } else {
            Log.d("Katanime", "getMainPage - No se encontró la sección 'Animes Populares' en la página principal. HTML relevante: ${doc.selectFirst("div#widget.dark")?.html()?.take(500)}")
        }

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        Log.d("Katanime", "search - Buscando en URL: $url")

        val response = try {
            app.get(url, interceptor = cfKiller).document
        } catch (e: Exception) {
            Log.e("Katanime", "search - Error al obtener la página de búsqueda: ${e.message}")
            return emptyList()
        }

        val searchResults = response.select("div._135yj._2FQAt.full._2mJki")

        Log.d("Katanime", "search - Número de resultados encontrados con selector 'div._135yj._2FQAt.full._2mJki': ${searchResults.size}")

        val animeList = searchResults.mapNotNull { parseAnimeItem(it) }

        Log.d("Katanime", "search - Total de animes en la lista después de parsear: ${animeList.size}")
        return animeList
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Katanime", "load - URL de entrada: $url")
        val cleanUrl = fixUrl(url)

        // *** CAMBIO CRÍTICO: Redirigir de URL de capítulo a URL de anime si es necesario ***
        var currentAnimeUrl = cleanUrl
        if (cleanUrl.contains("/capitulo/")) {
            Log.d("Katanime", "load - La URL de entrada es un capítulo. Intentando obtener la URL del anime...")
            // Ir a la página del capítulo para obtener el enlace a la página del anime
            val chapterDoc = try {
                app.get(cleanUrl, interceptor = cfKiller).document
            } catch (e: Exception) {
                Log.e("Katanime", "load - Error al obtener la página del capítulo para redirigir a anime URL: ${e.message}")
                return null
            }
            // Asegúrate de que este selector (h1.comics-title a) es correcto para Katanime.net
            val animeLinkElement = chapterDoc.selectFirst("h1.comics-title a")
            if (animeLinkElement != null) {
                currentAnimeUrl = animeLinkElement.attr("href")?.let { fixUrl(it) } ?: currentAnimeUrl
                Log.d("Katanime", "load - Redirigido de URL de capítulo a URL de anime: $currentAnimeUrl")
            } else {
                Log.e("Katanime", "load - No se pudo encontrar el enlace al anime desde la URL del capítulo: $cleanUrl (Selector 'h1.comics-title a' falló)")
                return null
            }
        }

        // Obtener el documento de la página del anime (ya asegurada que es la URL del anime)
        val doc = app.get(currentAnimeUrl, interceptor = cfKiller).document

        // Extraer información básica del anime
        val title = doc.selectFirst("h1.comics-title.ajp")?.text()?.trim() ?: ""

        val posterElement = doc.selectFirst("div#animeinfo img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrl(it) }
            ?: posterElement?.attr("data-src")?.let { fixUrl(it) }
            ?: ""

        if (posterUrl.isBlank()) {
            Log.w("Katanime", "load - No se pudo extraer el poster principal del anime en: $currentAnimeUrl. (Verificar selector 'div#animeinfo img').")
        }

        val description = doc.selectFirst("div#sinopsis p")?.text()?.trim() ?: ""
        val tags = doc.select("div.anime-genres a").map { it.text().trim() }

        Log.d("Katanime", "load - Title: $title")
        Log.d("Katanime", "load - Poster URL: $posterUrl")
        Log.d("Katanime", "load - Description: $description")
        Log.d("Katanime", "load - Tags: $tags")

        // Obtener la data-url para la API de episodios (normalmente desde div#c_list)
        val episodesDataUrl = doc.selectFirst("div#c_list")?.attr("data-url")?.let { fixUrl(it) }
        Log.d("Katanime", "load - Extracted episodesDataUrl: $episodesDataUrl")

        if (episodesDataUrl.isNullOrBlank()) {
            Log.e("Katanime", "load - ERROR: No se pudo encontrar la data-url para los episodios en $currentAnimeUrl.")
            return null
        }

        // Extraer el token CSRF del input oculto
        val csrfToken = doc.selectFirst("input[name=\"_token\"]")?.attr("value")
        if (csrfToken.isNullOrBlank()) {
            Log.e("Katanime", "load - CSRF Token no encontrado o vacío en la página de anime: $currentAnimeUrl.")
            return null
        }
        Log.d("Katanime", "load - CSRF Token extraído: $csrfToken")

        // Llamar a getEpisodes para obtener la lista de episodios
        val episodesList = getEpisodes(episodesDataUrl, csrfToken, currentAnimeUrl) // currentAnimeUrl como Referer

        // Construir la respuesta LoadResponse de Cloudstream
        return newTvSeriesLoadResponse(
            name = title,
            url = currentAnimeUrl,
            type = TvType.Anime,
            episodes = episodesList
        ) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.year = null
            this.rating = null
            this.duration = null
            this.recommendations = null
            this.actors = null
            this.trailers = mutableListOf()
            this.comingSoon = false
            this.syncData = mutableMapOf()
            this.posterHeaders = null
            this.contentRating = null
        }
    }

    // --- getEpisodes: Implementación con manejo de token y paginación ---
    private suspend fun getEpisodes(episodesApiUrl: String, csrfToken: String, refererUrl: String): List<Episode> {
        if (csrfToken.isBlank() || episodesApiUrl.isBlank()) {
            Log.e("Katanime", "getEpisodes - csrfToken o episodesApiUrl es nulo/vacío al inicio. No se pueden obtener episodios.")
            return emptyList()
        }

        val episodes = ArrayList<Episode>()
        var currentPage = 1
        var hasMorePages = true

        while (hasMorePages) {
            Log.d("Katanime", "getEpisodes - Intentando obtener página $currentPage de episodios de: $episodesApiUrl")

            // Construir el cuerpo del formulario (payload)
            val requestBody = FormBody.Builder()
                .add("_token", csrfToken)
                .add("pagina", currentPage.toString())
                .build()

            // Cabeceras de la solicitud POST
            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to refererUrl, // ¡Referer es CRUCIAL y debe ser la URL del ANIME!
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
                "Origin" to mainUrl,
                "X-CSRF-TOKEN" to csrfToken // <-- ¡Añadido explícitamente a las cabeceras!
            )

            val response = try {
                app.post(episodesApiUrl, requestBody = requestBody, headers = headers, interceptor = cfKiller)
            } catch (e: Exception) {
                Log.e("Katanime", "getEpisodes - Error en solicitud POST a $episodesApiUrl (Página $currentPage): ${e.message}")
                hasMorePages = false // Detener el bucle en caso de error
                break
            }

            val jsonString = response.body.string()
            Log.d("Katanime", "getEpisodes - Respuesta JSON (primeros 500 chars) para página $currentPage: ${jsonString.take(500)}")

            // Intentar parsear el JSON
            val episodeData = try {
                mapper.readValue(jsonString, EpisodeResponse::class.java)
            } catch (e: Exception) {
                Log.e("Katanime", "getEpisodes - Error al parsear JSON de episodios para página $currentPage: ${e.message}")
                null
            }

            if (episodeData != null && episodeData.ep?.data?.isNotEmpty() == true) {
                for (epItem in episodeData.ep.data) {
                    val episodeNum = epItem.numero?.toIntOrNull()
                    episodes.add(
                        newEpisode(
                            // Se serializa EpisodeLoadData para pasar a loadLinks
                            EpisodeLoadData(
                                title = "Episodio ${epItem.numero ?: "N/A"}",
                                url = fixUrl(epItem.url), // Esta URL es la URL del CAPÍTULO individual
                                poster = epItem.thumb?.let { fixUrl(it) }
                            ).toJson()
                        ) {
                            this.name = "Episodio ${epItem.numero ?: "N/A"}"
                            this.episode = episodeNum
                            this.posterUrl = epItem.thumb?.let { fixUrl(it) }
                        }
                    )
                }
                // Comprobar si hay más páginas
                hasMorePages = episodeData.ep.next_page_url != null
                if (hasMorePages) {
                    currentPage++
                    delay(100) // Pequeño delay para no abrumar al servidor en paginación rápida
                }
            } else {
                Log.d("Katanime", "getEpisodes - No se encontraron datos de episodios o JSON nulo/vacío para la página $currentPage. Finalizando.")
                hasMorePages = false
            }
        }
        Log.d("Katanime", "getEpisodes - Total de episodios finales encontrados: ${episodes.size}")
        return episodes.reversed() // Katanime suele devolverlos de más reciente a más antiguo
    }

    // --- loadLinks: Correctamente espera URL de capítulo ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Katanime", "loadLinks - Data de entrada: $data")

        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        // La URL que llega aquí desde `newEpisode` es la URL del CAPÍTULO.
        // Esto es correcto para `loadLinks`.
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data) // Fallback por si 'data' no es JSON

        if (targetUrl.isBlank()) {
            Log.e("Katanime", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl, interceptor = cfKiller).document
        } catch (e: Exception) {
            Log.e("Katanime", "loadLinks - Error al obtener la página del capítulo para loadLinks: ${e.message}")
            return false
        }


        val playerDivs = doc.select("div.player-data") // Busca los elementos que contienen la URL del reproductor
        var foundLinks = false

        if (playerDivs.isEmpty()) {
            Log.w("Katanime", "loadLinks - No se encontraron elementos 'div.player-data' en la página: $targetUrl")
            // Intenta buscar si hay un iframe directamente en el cuerpo o un script que cargue el video.
            // Esto es un fallback, la estructura principal es 'player-data'.
            val directIframe = doc.selectFirst("iframe[src]")
            if (directIframe != null) {
                val iframeSrc = directIframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    Log.d("Katanime", "loadLinks - Encontrado iframe directo en el documento: $iframeSrc")
                    try {
                        if (loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)) {
                            foundLinks = true
                        }
                    } catch (e: Exception) {
                        Log.e("Katanime", "Error al cargar extractor para iframe directo $iframeSrc: ${e.message}")
                    }
                }
            }
        }

        for (playerDiv in playerDivs) {
            val serverName = playerDiv.selectFirst("a")?.text()?.trim() ?: "Desconocido"
            val playerUrl = playerDiv.attr("data-player")

            if (playerUrl.isNotBlank()) {
                Log.d("Katanime", "loadLinks - Encontrado player '$serverName' con URL: $playerUrl")
                val fixedPlayerUrl = fixUrl(playerUrl)

                try {
                    val playerDoc = app.get(fixedPlayerUrl, interceptor = cfKiller).document
                    // Busca el iframe dentro del documento del reproductor
                    val finalIframeSrc = playerDoc.selectFirst("iframe")?.attr("src")

                    if (!finalIframeSrc.isNullOrBlank()) {
                        Log.d("Katanime", "loadLinks - Encontrado iframe final para '$serverName' en URL de reproductor: $finalIframeSrc")

                        try {
                            // Cargar el extractor con la URL del iframe final
                            if (loadExtractor(fixUrl(finalIframeSrc), fixedPlayerUrl, subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        } catch (e: Exception) {
                            Log.e("Katanime", "loadLinks - Error al cargar extractor para $finalIframeSrc: ${e.message}")
                        }

                    } else {
                        Log.w("Katanime", "loadLinks - No se encontró iframe dentro de $fixedPlayerUrl para $serverName. HTML del reproductor: ${playerDoc.html().take(500)}")
                    }
                } catch (e: Exception) {
                    Log.e("Katanime", "loadLinks - Error al obtener contenido del reproductor $fixedPlayerUrl ($serverName): ${e.message}")
                }
            } else {
                Log.w("Katanime", "loadLinks - Elemento player-data sin 'data-player' válido para server '$serverName'.")
            }
        }

        return foundLinks
    }

    // Función auxiliar para arreglar URLs relativas
    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url" else url
    }
}