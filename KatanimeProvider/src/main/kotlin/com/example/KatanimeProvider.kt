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
    private fun parseAnimeItem(element: Element): SearchResponse? {
        val link: String?
        val title: String?
        val posterUrl: String?

        // Intenta parsear como un elemento de búsqueda/recientes (div._135yj._2FQAt.full._2mJki o div._135yj._2FQAt.chap._2mJki)
        val linkElement = element.selectFirst("a._1A2Dc._38LRT") // Este es el <a> principal que contiene el link y la imagen
        val titleElement = element.selectFirst("div._2NNxg a._1A2Dc._2uHIS") // Título del anime en este tipo de cards
        val posterImgElement = element.selectFirst("img") // La imagen del póster

        if (linkElement != null && titleElement != null && posterImgElement != null) {
            link = linkElement.attr("href")?.let { fixUrl(it) }
            title = titleElement.text()?.trim()
            // CORRECCIÓN CLAVE para el poster: Probar data-src primero, luego src.
            posterUrl = posterImgElement.attr("data-src")?.let { fixUrl(it) }
                ?: posterImgElement.attr("src")?.let { fixUrl(it) }

            // Si alguno de los datos críticos (título, enlace, poster) es nulo, no añadimos este elemento
            if (title.isNullOrBlank() || link.isNullOrBlank() || posterUrl.isNullOrBlank()) {
                Log.w("Katanime", "parseAnimeItem - Datos incompletos (resultados/recientes), saltando: Title='$title', Link='$link', Poster='$posterUrl'")
                return null
            }
            Log.d("Katanime", "parseAnimeItem - Parseado (resultados/recientes): Title='$title', Link='$link', Poster='$posterUrl'")

        } else {
            // Intenta parsear como un elemento del slider (li.slider-item)
            val sliderLinkElement = element.selectFirst("a.viewBtn")
            val sliderTitleElement = element.selectFirst(".slider_info h1")
            val sliderPosterImgElement = element.selectFirst(".sliderimg img")

            if (sliderLinkElement != null && sliderTitleElement != null && sliderPosterImgElement != null) {
                link = sliderLinkElement.attr("href")?.let { fixUrl(it) }
                title = sliderTitleElement.text()?.trim()
                // CORRECCIÓN CLAVE para el poster: Probar data-src primero, luego src.
                posterUrl = sliderPosterImgElement.attr("data-src")?.let { fixUrl(it) }
                    ?: sliderPosterImgElement.attr("src")?.let { fixUrl(it) }

                if (title.isNullOrBlank() || link.isNullOrBlank() || posterUrl.isNullOrBlank()) {
                    Log.w("Katanime", "parseAnimeItem - Datos incompletos (slider), saltando: Title='$title', Link='$link', Poster='$posterUrl'")
                    return null
                }
                Log.d("Katanime", "parseAnimeItem - Parseado (slider): Title='$title', Link='$link', Poster='$posterUrl'")

            } else {
                Log.w("Katanime", "parseAnimeItem - Elemento no coincide con estructura conocida. HTML: ${element.html().take(200)}")
                return null // No se pudo parsear
            }
        }

        // CORRECCIÓN: Asignar posterUrl dentro del bloque lambda
        return newTvSeriesSearchResponse(
            name = title,
            url = link
        ) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            // this.apiName = name // 'apiName' no existe en SearchResponse, no debería estar aquí
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
        // Selector para los elementos del slider principal
        val sliderElements = doc.select("ul#mainslider li.slider-item")
        val sliderItems = sliderElements.mapNotNull { parseAnimeItem(it) }
        if (sliderItems.isNotEmpty()) {
            items.add(HomePageList("Animes Destacados", sliderItems))
            Log.d("Katanime", "getMainPage - Se añadió la sección 'Animes Destacados' con ${sliderItems.size} elementos.")
        } else {
            Log.w("Katanime", "getMainPage - 'Animes Destacados' no encontró elementos con los selectores internos (ul#mainslider li.slider-item).")
        }


        // --- 2. Capítulos Recientes (Animes Recientes) ---
        // Buscamos la sección con el h3 "Capítulos recientes" y luego sus elementos individuales
        val recentChaptersTitle = doc.selectFirst("h3.carousel.t:contains(Capítulos recientes)")
        if (recentChaptersTitle != null) {
            // Seleccionamos el div padre que contiene todos los capítulos recientes
            val contentLeftDiv = recentChaptersTitle.nextElementSibling()
            // El selector para los items individuales en "Capítulos Recientes" es el mismo que en la búsqueda
            val recentAnimeElements = contentLeftDiv?.select("div._135yj._2FQAt.chap._2mJki")

            val recentItems = recentAnimeElements?.mapNotNull { chapterElement ->
                // Para los capítulos recientes, necesitamos el link al ANIME, no al capítulo
                // El link al anime está en el <a> con clase _1A2Dc _2uHIS dentro de div._2NNxg
                val animeLinkElement = chapterElement.selectFirst("div._2NNxg a._1A2Dc._2uHIS")
                val animeLink = animeLinkElement?.attr("href")?.let { fixUrl(it) }
                val animeTitle = animeLinkElement?.text()?.trim()

                // El póster es el mismo que el del capítulo, tomado del img original
                val posterElement = chapterElement.selectFirst("img")
                val posterUrl = posterElement?.attr("data-src")?.let { fixUrl(it) }
                    ?: posterElement?.attr("src")?.let { fixUrl(it) } // Fallback para src

                if (animeTitle.isNullOrBlank() || animeLink.isNullOrBlank() || posterUrl.isNullOrBlank()) {
                    Log.w("Katanime", "getMainPage - Capítulos Recientes: Datos incompletos para capítulo, saltando. Title='$animeTitle', Link='$animeLink', Poster='$posterUrl'")
                    null
                } else {
                    newTvSeriesSearchResponse(
                        name = animeTitle,
                        url = animeLink
                    ) {
                        this.posterUrl = posterUrl
                        this.type = TvType.Anime
                    }
                }
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
            // El selector para los items individuales en "Animes populares" es el mismo que en la búsqueda
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

        // Selector específico para los contenedores de resultados de búsqueda
        val searchResults = response.select("div._135yj._2FQAt.full._2mJki")

        Log.d("Katanime", "search - Número de resultados encontrados con selector 'div._135yj._2FQAt.full._2mJki': ${searchResults.size}")

        val animeList = searchResults.mapNotNull { parseAnimeItem(it) }

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

        // Selectores de la página de carga de anime
        // CORRECCIÓN: Selectores actualizados según el HTML proporcionado
        val title = doc.selectFirst("h1.comics-title.ajp")?.text() ?: ""
        // CORRECCIÓN CLAVE para el poster principal: priorizar src, luego data-src
        val posterUrl = doc.selectFirst("div#animeinfo img")?.attr("src")?.let { fixUrl(it) }
            ?: doc.selectFirst("div#animeinfo img")?.attr("data-src")?.let { fixUrl(it) }

        val description = doc.selectFirst("div#sinopsis p")?.text()?.trim() ?: ""
        val tags = doc.select("div.anime-genres a").map { it.text().trim() }

        Log.d("Katanime", "load - Title: $title")
        Log.d("Katanime", "load - Poster URL: $posterUrl")
        Log.d("Katanime", "load - Description: $description")
        Log.d("Katanime", "load - Tags: $tags")

        // Extraer la data-url para la API de episodios del div#c_list
        val episodesDataUrl = doc.selectFirst("div#c_list")?.attr("data-url")?.let { fixUrl(it) }
        Log.d("Katanime", "load - Extracted episodesDataUrl: $episodesDataUrl")

        if (episodesDataUrl.isNullOrBlank()) {
            Log.e("Katanime", "load - ERROR: No se pudo encontrar la data-url para los episodios.")
            // Para URLs de capítulos individuales (como en "Capítulos Recientes"), no cargaremos LoadResponse
            if ("/capitulo/" in cleanUrl) {
                Log.e("Katanime", "load - La URL es de un capítulo, no de un anime. Esto es un error. ${cleanUrl}")
                return null // No se puede cargar una LoadResponse de un capítulo
            }
            return null
        }

        // Obtener el token CSRF del meta tag
        val csrfToken = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content") ?: ""
        Log.d("Katanime", "load - CSRF Token: $csrfToken")

        // Para el token X-XSRF-TOKEN en las cabeceras, Katanime.net usa una cookie llamada 'XSRF-TOKEN'
        // El CloudflareKiller y app.get/post suelen manejar las cookies automáticamente,
        // pero asegurar una petición GET previa al 'refererUrl' es crucial para que OkHttp capture las cookies.

        val episodesList = getEpisodes(episodesDataUrl, csrfToken, cleanUrl)

        // CORRECCIÓN: Asignar posterUrl, backgroundPosterUrl, etc. dentro del bloque lambda
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

    // CORRECCIÓN: Adaptar getEpisodes para usar la `episodesApiUrl` obtenida del `data-url` y el FormBody ajustado
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

            // El FormBody ahora solo necesita el token y la página
            val requestBody = FormBody.Builder()
                .add("_token", csrfToken)
                .add("pagina", currentPage.toString())
                .build()

            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to refererUrl, // URL del anime
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
                "Origin" to mainUrl,
                "X-CSRF-TOKEN" to csrfToken // A veces se espera el token en esta cabecera también
            )

            // Es crucial hacer una petición GET a la página del anime (refererUrl) antes del POST
            // para asegurar que las cookies de sesión (incluido el XSRF-TOKEN de la cookie) estén actualizadas y sean válidas.
            // Cloudstream y OkHttp gestionan las cookies. El 'interceptor = cfKiller' es fundamental.
            // No es necesario llamar app.get() explícitamente si Cloudstream ya lo gestiona con la sesión.
            // Si el error "CSRF token mismatch" persiste, podríamos intentar un segundo GET aquí,
            // o verificar si hay una cookie XSRF-TOKEN en el response del primer GET y añadirla manualmente a los headers del POST.
            // Por ahora, confiemos en que OkHttp y CloudflareKiller manejan las cookies de sesión.

            val response = try {
                app.post(episodesApiUrl, requestBody = requestBody, headers = headers, interceptor = cfKiller)
            } catch (e: Exception) {
                Log.e("Katanime", "getEpisodes - Error en solicitud POST a $episodesApiUrl: ${e.message}")
                hasMorePages = false
                break
            }

            val jsonString = response.body.string()
            Log.d("Katanime", "getEpisodes - Respuesta JSON (primeros 500 chars): ${jsonString.take(500)}")

            // Ajustar el parseo del JSON si la estructura del EpisodeItem o EpisodeResponse cambia
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
        val valoration: String?, // Añadido valoration según posible estructura de respuesta
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
        val id: Int?, // Añadido id si existe en la respuesta JSON
        val title: String?, // Añadido title si existe en la respuesta JSON (a veces numero es el único campo de nombre)
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
}