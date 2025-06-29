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

class KatanimeProvider : MainAPI() {
    override var name = "Katanime"
    override var mainUrl = "https://katanime.net"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

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
            // Lógica para las cards de "Capítulos Recientes"
            val animeLinkElement = element.selectFirst("div._2NNxg a._1A2Dc._2uHIS")
            val posterImgElement = element.selectFirst("img")

            if (animeLinkElement != null && posterImgElement != null) {
                // *** CAMBIO CRÍTICO AQUÍ: Extraer el URL del ANIME, no del capítulo ***
                // Asumo que el elemento `a._1A2Dc._2uHIS` en los capítulos recientes
                // te lleva a la página del ANIME. Si no es así, necesitarás ajustar el selector.
                link = animeLinkElement.attr("href")?.let { fixUrl(it) }
                // Asegurarse de que el enlace es del anime, no del capítulo
                if (link?.contains("/capitulo/") == true) {
                    // Si el link aún apunta a un capítulo, intenta extraer el enlace del anime del mismo elemento
                    // o busca un elemento padre que contenga el enlace al anime.
                    // Por ahora, asumimos que `animeLinkElement` ya apunta al ANIME.
                    // Si no, necesitaríamos una lógica más compleja para "subir" en el DOM
                    // o que el HTML del capítulo reciente contenga un enlace directo al anime.
                    // VAMOS A SIMPLIFICAR: el título del capítulo reciente es el nombre del anime,
                    // y su enlace es al capítulo. PERO, el enlace real al anime puede estar
                    // en un elemento padre o un elemento hermano.
                    // Para Katanime, es común que la URL en el card de "capítulo reciente" sea la URL del capítulo.
                    // Necesitamos ir a la página del CAPÍTULO, y desde ahí, obtener la URL del ANIME.
                    // ESTO ES UN ERROR DE DISEÑO SI LLAMAMOS A LOAD CON UNA URL DE CAPÍTULO.
                    // La mejor solución es que parseAnimeItem SIEMPRE devuelva la URL del ANIME.

                    // RECONSIDERACIÓN: Si el `animeLinkElement` (a._1A2Dc._2uHIS) ya apunta al anime, genial.
                    // Si apunta al capítulo, entonces el `SearchResponse` para "Capítulos Recientes"
                    // debería llevar al anime asociado.
                    // Basado en el log:
                    // Link='https://katanime.net/capitulo/lazarus-13/'
                    // Esto es un link de capítulo. Necesitamos cambiar cómo `getMainPage` o `parseAnimeItem`
                    // genera el `link` para las tarjetas de capítulos recientes.
                    //
                    // A CORTO PLAZO: Dejamos el link al capítulo, y en `load` validamos si es un capítulo,
                    // y si lo es, navegamos a la página del anime desde allí.
                    // Sin embargo, `load` debe recibir un URL de ANIME.

                    // SOLUCIÓN: Buscar el link al anime desde el card de capítulo.
                    // Katanime en las cards de capítulos recientes:
                    // <div class="_2NNxg">
                    //   <a href="https://katanime.net/anime/lazarus/" class="_1A2Dc _2uHIS">Lazarus</a>
                    //   <a href="https://katanime.net/capitulo/lazarus-13/" class="_1A2Dc">...</a>
                    // </div>
                    // ¡Eureka! El primer <a> con _1A2Dc _2uHIS YA ES EL LINK AL ANIME.

                    link = animeLinkElement.attr("href")?.let { fixUrl(it) }
                }

                title = animeLinkElement.text()?.trim()
                posterUrl = posterImgElement.attr("data-src")?.let { fixUrl(it) }
                    ?: posterImgElement.attr("src")?.let { fixUrl(it) }

                if (title.isNullOrBlank() || link.isNullOrBlank() || posterUrl.isNullOrBlank()) {
                    Log.w("Katanime", "parseAnimeItem - Datos incompletos (capítulo reciente), saltando: Title='$title', Link='$link', Poster='$posterUrl'")
                    return null
                }
                Log.d("Katanime", "parseAnimeItem - Parseado (capítulo reciente, link a anime): Title='$title', Link='$link', Poster='$posterUrl'")
            } else {
                Log.w("Katanime", "parseAnimeItem - Elemento de capítulo reciente no coincide con estructura esperada. HTML: ${element.html().take(200)}")
                return null
            }

        } else {
            // Lógica para resultados de búsqueda y animes populares
            val linkElement = element.selectFirst("a._1A2Dc._38LRT")
            val titleElement = element.selectFirst("div._2NNxg a._1A2Dc._2uHIS")
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

    data class EpisodeLoadData(
        val title: String,
        val url: String, // Esta URL SIEMPRE debe ser la URL del ANIME, no del capítulo
        val poster: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Katanime", "load - URL de entrada: $url")
        val cleanUrl = fixUrl(url)

        // Validar si la URL es de un capítulo, y si lo es, redirigir a la URL del anime
        var currentAnimeUrl = cleanUrl
        if (cleanUrl.contains("/capitulo/")) {
            // Ir a la página del capítulo para obtener el enlace a la página del anime
            val chapterDoc = try {
                app.get(cleanUrl, interceptor = cfKiller).document
            } catch (e: Exception) {
                Log.e("Katanime", "load - Error al obtener la página del capítulo para redirigir a anime URL: ${e.message}")
                return null
            }
            val animeLinkElement = chapterDoc.selectFirst("h1.comics-title a") // Asumo que el título del capítulo tiene un link al anime
            if (animeLinkElement != null) {
                currentAnimeUrl = animeLinkElement.attr("href")?.let { fixUrl(it) } ?: currentAnimeUrl
                Log.d("Katanime", "load - Redirigiendo de URL de capítulo a URL de anime: $currentAnimeUrl")
            } else {
                Log.e("Katanime", "load - No se pudo encontrar el enlace al anime desde la URL del capítulo: $cleanUrl")
                return null
            }
        }

        val doc = app.get(currentAnimeUrl, interceptor = cfKiller).document

        val title = doc.selectFirst("h1.comics-title.ajp")?.text() ?: ""

        val posterElement = doc.selectFirst("div#animeinfo img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrl(it) }
            ?: posterElement?.attr("data-src")?.let { fixUrl(it) }
            ?: ""

        if (posterUrl.isBlank()) {
            Log.w("Katanime", "load - No se pudo extraer el poster principal del anime en: $currentAnimeUrl. Selector: div#animeinfo img (Verificar selector en HTML si esto falla).")
        }

        val description = doc.selectFirst("div#sinopsis p")?.text()?.trim() ?: ""
        val tags = doc.select("div.anime-genres a").map { it.text().trim() }

        Log.d("Katanime", "load - Title: $title")
        Log.d("Katanime", "load - Poster URL: $posterUrl")
        Log.d("Katanime", "load - Description: $description")
        Log.d("Katanime", "load - Tags: $tags")

        val episodesDataUrl = doc.selectFirst("div#c_list")?.attr("data-url")?.let { fixUrl(it) }
        Log.d("Katanime", "load - Extracted episodesDataUrl: $episodesDataUrl")

        if (episodesDataUrl.isNullOrBlank()) {
            Log.e("Katanime", "load - ERROR: No se pudo encontrar la data-url para los episodios en $currentAnimeUrl.")
            return null
        }

        val csrfToken = doc.selectFirst("input[name=\"_token\"]")?.attr("value") ?: ""
        Log.d("Katanime", "load - CSRF Token extraído de input[name=_token]: $csrfToken")

        val episodesList = getEpisodes(episodesDataUrl, csrfToken, currentAnimeUrl) // Pass currentAnimeUrl as referer

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

            val requestBody = FormBody.Builder()
                .add("_token", csrfToken)
                .add("pagina", currentPage.toString())
                .build()

            // ******************************************************************
            // POSIBLE CAUSA DE CSRF TOKEN MISMATCH Y SOLUCIÓN (HEADER ADICIONAL)
            // Asegúrate de que las cookies se manejen correctamente por CloudflareKiller/OkHttp.
            // A veces, sitios Laravel también esperan el token en un header `X-CSRF-TOKEN`.
            // Añádelo explícitamente.
            // ******************************************************************
            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to refererUrl,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
                "Origin" to mainUrl,
                "X-CSRF-TOKEN" to csrfToken // <--- ¡Añadido explícitamente!
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

            if (episodeData != null && episodeData.ep?.data?.isNotEmpty() == true) { // Añadido safe call '?' para 'ep'
                for (epItem in episodeData.ep.data) {
                    val episodeNum = epItem.numero?.toIntOrNull()
                    episodes.add(
                        newEpisode(
                            EpisodeLoadData(
                                title = "Episodio ${epItem.numero ?: "N/A"}",
                                url = fixUrl(epItem.url), // Esta URL aquí es la URL del capítulo
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
        return episodes.reversed()
    }

    // Mantén las data classes como estaban
    data class EpisodeResponse(
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

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url" else url
    }
}