package com.example // <-- Si tu package es 'com.example', cámbialo aquí.

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

class KatanimeProvider : MainAPI() {
    override var name = "Katanime"
    override var mainUrl = "https://katanime.net"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    // Función auxiliar para parsear un elemento de anime (usado en search y getMainPage)
    // Se ha agregado un parámetro 'isSlider' y 'isRecentEpisodeCard' para diferenciar el parseo
    // y usar el selector de póster más adecuado.
    private fun parseAnimeItem(element: Element, isSlider: Boolean = false, isRecentEpisodeCard: Boolean = false): SearchResponse? {
        var link: String? = null
        var title: String? = null
        var posterUrl: String? = null

        if (isSlider) {
            // Lógica para los elementos del slider (ul#mainslider li.slider-item)
            val sliderLinkElement = element.selectFirst("a.viewBtn")
            val sliderTitleElement = element.selectFirst(".slider_info h1")
            val sliderPosterImgElement = element.selectFirst(".sliderimg img") // La imagen del slider está aquí

            if (sliderLinkElement != null && sliderTitleElement != null && sliderPosterImgElement != null) {
                link = sliderLinkElement.attr("href")?.let { fixUrl(it) }
                title = sliderTitleElement.text()?.trim()
                // Para el slider, data-src suele ser el que carga primero, luego src. Ambos tienen la misma URL.
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
            // Lógica para las cards de "Capítulos Recientes" (div._135yj._2FQAt.chap._2mJki)
            // Aquí queremos el link y título del ANIME asociado al capítulo, no del capítulo en sí.
            val animeLinkElement = element.selectFirst("div._2NNxg a._1A2Dc._2uHIS")
            val posterImgElement = element.selectFirst("img") // La imagen del capítulo (que es el póster del anime)

            if (animeLinkElement != null && posterImgElement != null) {
                link = animeLinkElement.attr("href")?.let { fixUrl(it) }
                title = animeLinkElement.text()?.trim()
                // Para capítulos recientes, `data-src` es común, pero `src` también es un buen fallback.
                posterUrl = posterImgElement.attr("data-src")?.let { fixUrl(it) }
                    ?: posterImgElement.attr("src")?.let { fixUrl(it) } // Priorizar data-src si es una imagen lazy-loaded

                if (title.isNullOrBlank() || link.isNullOrBlank() || posterUrl.isNullOrBlank()) {
                    Log.w("Katanime", "parseAnimeItem - Datos incompletos (capítulo reciente), saltando: Title='$title', Link='$link', Poster='$posterUrl'")
                    return null
                }
                Log.d("Katanime", "parseAnimeItem - Parseado (capítulo reciente): Title='$title', Link='$link', Poster='$posterUrl'")
            } else {
                Log.w("Katanime", "parseAnimeItem - Elemento de capítulo reciente no coincide con estructura esperada. HTML: ${element.html().take(200)}")
                return null
            }

        } else {
            // Lógica para resultados de búsqueda y animes populares (div._135yj._2FQAt.full._2mJki)
            val linkElement = element.selectFirst("a._1A2Dc._38LRT")
            val titleElement = element.selectFirst("div._2NNxg a._1A2Dc._2uHIS")
            val posterImgElement = element.selectFirst("img") // La imagen del póster

            if (linkElement != null && titleElement != null && posterImgElement != null) {
                link = linkElement.attr("href")?.let { fixUrl(it) }
                title = titleElement.text()?.trim()
                // Para resultados de búsqueda/popular, tu HTML muestra que la URL está directamente en 'src'.
                posterUrl = posterImgElement.attr("src")?.let { fixUrl(it) }
                    ?: posterImgElement.attr("data-src")?.let { fixUrl(it) } // Fallback, aunque 'src' es más probable aquí

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
            Log.d("Katanime", "getMainPage - No se encontró la sección 'Animes Populares' en la página principal.")
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

        val animeList = searchResults.mapNotNull { parseAnimeItem(it) } // No flags needed, default path

        Log.d("Katanime", "search - Total de animes en la lista después de parsear: ${animeList.size}")
        return animeList
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String,
        val poster: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Katanime", "load - URL de entrada: $url")
        val cleanUrl = fixUrl(url)

        val doc = app.get(cleanUrl, interceptor = cfKiller).document

        val title = doc.selectFirst("h1.comics-title.ajp")?.text() ?: ""

        // --- INICIO DE CAMBIO DE PÓSTER EN LA FUNCIÓN LOAD (SELECTOR BASADO EN TUS LOGS ANTERIORES) ---
        // Se busca el div con ID "animeinfo", y dentro, la imagen.
        // Si este selector no funciona, tendrás que inspeccionar la página actual.
        val posterElement = doc.selectFirst("div#animeinfo img") // <-- Selector ajustado
        val posterUrl = posterElement?.attr("src")?.let { fixUrl(it) }
            ?: posterElement?.attr("data-src")?.let { fixUrl(it) }
            ?: "" // Si no se encuentra ninguna, dejarlo vacío, para que no sea null.

        if (posterUrl.isBlank()) {
            Log.w("Katanime", "load - No se pudo extraer el poster principal del anime en: $cleanUrl. Selector: div#animeinfo img (Verificar selector en HTML si esto falla).")
        }
        // --- FIN DE CAMBIO DE PÓSTER EN LA FUNCIÓN LOAD ---


        val description = doc.selectFirst("div#sinopsis p")?.text()?.trim() ?: ""
        val tags = doc.select("div.anime-genres a").map { it.text().trim() }

        Log.d("Katanime", "load - Title: $title")
        Log.d("Katanime", "load - Poster URL: $posterUrl") // Ahora debería mostrar el URL correcto o vacío
        Log.d("Katanime", "load - Description: $description")
        Log.d("Katanime", "load - Tags: $tags")

        val episodesDataUrl = doc.selectFirst("div#c_list")?.attr("data-url")?.let { fixUrl(it) }
        Log.d("Katanime", "load - Extracted episodesDataUrl: $episodesDataUrl")

        if (episodesDataUrl.isNullOrBlank()) {
            Log.e("Katanime", "load - ERROR: No se pudo encontrar la data-url para los episodios.")
            if ("/capitulo/" in cleanUrl) {
                Log.e("Katanime", "load - La URL es de un capítulo, no de un anime. Esto es un error. ${cleanUrl}")
                return null
            }
            return null
        }

        // --- INICIO DE CAMBIO PARA EXTRACCIÓN DE CSRF TOKEN ---
        // Extrae el token CSRF del input oculto, como se vio en el HTML y JS
        val csrfToken = doc.selectFirst("input[name=\"_token\"]")?.attr("value") ?: ""
        Log.d("Katanime", "load - CSRF Token extraído de input[name=_token]: $csrfToken")
        // --- FIN DE CAMBIO PARA EXTRACCIÓN DE CSRF TOKEN ---

        val episodesList = getEpisodes(episodesDataUrl, csrfToken, cleanUrl)

        return newTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
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

    private suspend fun getEpisodes(episodesApiUrl: String, csrfToken: String, refererUrl: String): List<Episode> {
        if (csrfToken.isBlank() || episodesApiUrl.isBlank()) {
            Log.e("Katanime", "getEpisodes - csrfToken o episodesApiUrl es nulo/vacío. No se pueden obtener episodios.")
            return emptyList()
        }

        val episodes = ArrayList<Episode>()
        var currentPage = 1
        var hasMorePages = true

        while (hasMorePages) {
            Log.d("Katanime", "getEpisodes - Intentando obtener página $currentPage de episodios de: $episodesApiUrl")

            // No se recarga la página principal aquí; se usa el csrfToken pasado desde 'load'
            val requestBody = FormBody.Builder()
                .add("_token", csrfToken) // Usa el token CSRF pasado
                .add("pagina", currentPage.toString())
                .build()

            // Asegúrate de que el "Referer" y "Origin" son correctos para la solicitud POST
            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to refererUrl, // La URL del anime es el referer para la solicitud de episodios
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
                "Origin" to mainUrl, // La base del sitio es el origen
                "X-CSRF-TOKEN" to csrfToken // Sigue enviando el token en esta cabecera por si acaso
                // Cloudstream y OkHttp deberían manejar las cookies automáticamente si el dominio es el mismo.
            )

            val response = try {
                app.post(episodesApiUrl, requestBody = requestBody, headers = headers, interceptor = cfKiller)
            } catch (e: Exception) {
                Log.e("Katanime", "getEpisodes - Error en solicitud POST a $episodesApiUrl: ${e.message}")
                hasMorePages = false
                break
            }

            val jsonString = response.body.string()
            Log.d("Katanime", "getEpisodes - Respuesta JSON (primeros 500 chars): ${jsonString.take(500)}")

            val episodeData = tryParseJson<EpisodeResponse>(jsonString)

            if (episodeData != null && episodeData.ep.data.isNotEmpty()) {
                for (epItem in episodeData.ep.data) {
                    val episodeNum = epItem.numero?.toIntOrNull()
                    episodes.add(
                        newEpisode(
                            EpisodeLoadData(
                                title = "Episodio ${epItem.numero ?: "N/A"}",
                                url = fixUrl(epItem.url),
                                poster = epItem.thumb?.let { fixUrl(it) }
                            ).toJson()
                        ) {
                            this.name = "Episodio ${epItem.numero ?: "N/A"}"
                            this.episode = episodeNum
                            this.posterUrl = epItem.thumb?.let { fixUrl(it) }
                        }
                    )
                }
                hasMorePages = episodeData.ep.next_page_url != null
                if (hasMorePages) {
                    currentPage++
                }
            } else {
                Log.d("Katanime", "getEpisodes - No se encontraron datos de episodios o JSON nulo/vacío para la página $currentPage. Finalizando.")
                hasMorePages = false
            }
        }
        Log.d("Katanime", "getEpisodes - Total de episodios encontrados: ${episodes.size}")
        return episodes.reversed() // Katanime suele listar del más nuevo al más viejo, revertimos para Cloudstream.
    }

    data class EpisodeResponse(
        val ep: EpisodesWrapper,
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Katanime", "loadLinks - Data de entrada: $data")

        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data)

        if (targetUrl.isBlank()) {
            Log.e("Katanime", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl, interceptor = cfKiller).document

        val playerDivs = doc.select("div.player-data")
        var foundLinks = false

        for (playerDiv in playerDivs) {
            val serverName = playerDiv.selectFirst("a")?.text()?.trim() ?: "Desconocido"
            val playerUrl = playerDiv.attr("data-player")

            if (playerUrl.isNotBlank()) {
                Log.d("Katanime", "loadLinks - Encontrado player '$serverName' con URL: $playerUrl")
                val fixedPlayerUrl = fixUrl(playerUrl)

                try {
                    Log.d("Katanime", "loadLinks - yendo a la URL del reproductor: $fixedPlayerUrl")
                    val playerDoc = app.get(fixedPlayerUrl, interceptor = cfKiller).document
                    val finalIframeSrc = playerDoc.selectFirst("iframe")?.attr("src")

                    if (!finalIframeSrc.isNullOrBlank()) {
                        Log.d("Katanime", "loadLinks - Encontrado iframe final en $serverName: $finalIframeSrc")

                        try {
                            if (loadExtractor(fixUrl(finalIframeSrc), fixedPlayerUrl, subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        } catch (e: Exception) {
                            Log.e("Katanime", "Error al cargar extractor para $finalIframeSrc: ${e.message}")
                        }

                    } else {
                        Log.w("Katanime", "loadLinks - No se encontró iframe dentro de $fixedPlayerUrl para $serverName.")
                    }
                } catch (e: Exception) {
                    Log.e("Katanime", "loadLinks - Error al obtener contenido del reproductor $fixedPlayerUrl ($serverName): ${e.message}")
                }
            } else {
                Log.w("Katanime", "loadLinks - Elemento player-data sin 'data-player' válido.")
            }
        }

        return foundLinks
    }

    // Función auxiliar para arreglar URLs relativas
    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url" else url
    }
}