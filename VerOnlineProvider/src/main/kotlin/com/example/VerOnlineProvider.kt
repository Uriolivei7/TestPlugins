package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.MainPageRequest

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

class VerOnlineProvider : MainAPI() {
    override var mainUrl = "https://www.verseriesonline.net"
    override var name = "VerOnline"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private fun log(message: String) {
        Log.d(name, message)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        // Usamos la URL principal para la página de inicio, ya que desde ahí se navega.
        // Si el sitio tiene una página de "series" o "peliculas" específica para la home, la ajustamos.
        // Por tu código, "series-online" parece ser la base para la home.
        val homePageUrl = if (page == 1) "$mainUrl/series-online" else "$mainUrl/series-online/page/$page"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,application/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "es-ES,es;q=0.9",
            "Connection" to "keep-alive"
        )

        val mainPageResponse = try {
            app.get(homePageUrl, headers = headers)
        } catch (e: Exception) {
            log("getMainPage - ERROR al obtener la página principal ($homePageUrl): ${e.message} - ${e.stackTraceToString()}")
            return null
        }
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)

        log("getMainPage - HTML completo recibido (primeros 2000 chars): ${mainPageDoc.html().take(2000)}")

        // Selectores basados en tu código original, si la estructura no ha cambiado
        val mainContentContainer = mainPageDoc.selectFirst("div#dle-content")

        if (mainContentContainer == null) {
            log("getMainPage - ERROR: No se encontró el contenedor principal '#dle-content'.")
            return null
        }

        log("getMainPage - Contenedor '#dle-content' encontrado. Procesando elementos de serie/película...")

        val contentElements = mainContentContainer.select("div.short")

        log("getMainPage - Encontrados ${contentElements.size} elementos 'div.short' dentro de '#dle-content'.")

        val entries = contentElements.mapNotNull { element ->
            log("getMainPage - Procesando elemento 'div.short': ${element.html().take(300)}")

            // Ajuste del selector para ser más flexible si hay variantes
            val aElement = element.selectFirst("a.short_img_box.with_mask, a.short_img, a[href].main-link")

            var currentLink: String? = null
            var currentImg: String? = null
            var currentTitle: String? = null

            if (aElement != null) {
                currentLink = aElement.attr("href")
                // Intentar múltiples atributos para la imagen
                val imgElement = aElement.selectFirst("img")
                currentImg = imgElement?.attr("data-src")
                    ?: imgElement?.attr("src")
                            ?: element.selectFirst("div.short_img_box img")?.attr("src") // Posiblemente la imagen esté fuera del <a>

                // Ajuste para el título, el h3 dentro de .short puede ser una opción también
                currentTitle = element.selectFirst("div.short_title a")?.text()?.trim()
                    ?: element.selectFirst("h3.short_title a")?.text()?.trim()
                            ?: element.selectFirst("div.title a")?.text()?.trim() // Otro posible selector de título
            } else {
                log("getMainPage - ERROR: No se encontró el enlace principal (a.short_img_box.with_mask, a.short_img o a[href].main-link) en el elemento 'div.short'.")
                return@mapNotNull null
            }

            if (currentTitle != null && currentLink != null && currentImg != null) {
                val fixedLink = fixUrl(currentLink)
                val fixedImg = fixUrl(currentImg)

                if (fixedLink == null || fixedImg == null) {
                    log("getMainPage - ADVERTENCIA: Link o imagen fija nulos para ítem: $currentTitle")
                    return@mapNotNull null
                }

                log("getMainPage - Ítem extraído: Título='$currentTitle', Link Fijo='$fixedLink', Img Fija='$fixedImg'")

                val type = if (fixedLink.contains("/series-online/", ignoreCase = true)) TvType.TvSeries else TvType.Movie

                if (type == TvType.TvSeries) {
                    TvSeriesSearchResponse(
                        name = currentTitle,
                        url = fixedLink,
                        posterUrl = fixedImg,
                        type = TvType.TvSeries,
                        apiName = this.name
                    )
                } else {
                    MovieSearchResponse(
                        name = currentTitle,
                        url = fixedLink,
                        posterUrl = fixedImg,
                        type = TvType.Movie,
                        apiName = this.name
                    )
                }
            } else {
                log("getMainPage - Ítem incompleto: Título='${currentTitle}', Link='${currentLink}', Img='${currentImg}' para elemento: ${element.html().take(300)}")
                null
            }
        }

        if (entries.isNotEmpty()) {
            items.add(HomePageList("Series y Películas Online", entries))
            log("getMainPage - Se agregaron ${entries.size} ítems para 'Series y Películas Online'.")
        } else {
            log("getMainPage - ADVERTENCIA: No se encontraron series o películas válidas para agregar a la página principal.")
        }

        // Determinar si hay más páginas. Si el sitio usa paginación, necesitarías un selector para el botón "Siguiente" o enlaces de página.
        // Si no hay botón "Siguiente" o "más", asumimos que no hay más páginas para una carga simple de la home.
        val hasNextPage = mainPageDoc.selectFirst("a.next-page, a.button.next") != null // Placeholder, ajusta el selector

        return HomePageResponse(items.toList(), hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = ArrayList<SearchResponse>()
        val mainPageResponse = app.get(mainUrl) // Obtener el token CSRF de la página principal
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)
        val csrfToken = mainPageDoc.select("meta[name=csrf-token]").attr("content")

        if (csrfToken.isBlank()) {
            log("search - ERROR: No se pudo obtener el token CSRF para la búsqueda.")
            return emptyList()
        }
        log("search - Token CSRF para búsqueda: $csrfToken")

        val searchUrl = "$mainUrl/livesearch"
        val postData = mapOf(
            "search" to query,
            "_token" to csrfToken
        )

        log("search - Intentando POST de búsqueda a URL: $searchUrl con datos: $postData")
        try {
            val res = app.post(
                url = searchUrl,
                data = postData,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                    "Accept" to "application/json, text/javascript, */*; q=0.01", // Es probable que la respuesta sea JSON o JavaScript
                    "Accept-Language" to "es-ES,es;q=0.9",
                    "Connection" to "keep-alive"
                )
            )

            log("search - Estado de la respuesta de búsqueda: ${res.code}")
            log("search - Contenido de la respuesta de búsqueda (primeros 1000 chars): ${res.text.take(1000)}")


            // --- INICIO: CAMBIO POTENCIAL PARA RESPUESTAS JSON DE LA BÚSQUEDA ---
            // Si la respuesta es JSON, necesitarás una clase de datos que represente la estructura del JSON.
            // Por ejemplo, si el JSON es algo como: {"results": [{"title": "...", "link": "...", "img": "..."}]}
            data class SearchResultJson(
                val title: String?,
                val link: String?,
                val img: String? = null // Puede que no siempre venga imagen
            )
            data class SearchResponseJson(
                val results: List<SearchResultJson>?
            )

            val searchResponseJson = tryParseJson<SearchResponseJson>(res.text)

            if (searchResponseJson != null && searchResponseJson.results != null) {
                log("search - La respuesta de búsqueda es JSON. Procesando resultados JSON.")
                for (itemJson in searchResponseJson.results) {
                    val link = itemJson.link ?: ""
                    val title = itemJson.title ?: ""
                    val poster = itemJson.img ?: "" // Usar el poster si viene en el JSON

                    if (title.isNotBlank() && link.isNotBlank() && (link.contains("/series-online/") || link.contains("/peliculas-online/"))) {
                        val fixedLink = fixUrl(link)
                        val fixedPoster = fixUrl(poster)
                        if (fixedLink.isNullOrBlank()) {
                            log("search - ADVERTENCIA: Link fijo vacío para ítem en búsqueda (JSON): $title")
                            continue
                        }

                        val type = if (fixedLink.contains("/series-online/")) TvType.TvSeries else TvType.Movie

                        if (type == TvType.TvSeries) {
                            searchResults.add(
                                TvSeriesSearchResponse(
                                    name = title,
                                    url = fixedLink,
                                    posterUrl = fixedPoster, // Usar poster extraído del JSON
                                    type = TvType.TvSeries,
                                    apiName = this.name
                                )
                            )
                        } else {
                            searchResults.add(
                                MovieSearchResponse(
                                    name = title,
                                    url = fixedLink,
                                    posterUrl = fixedPoster, // Usar poster extraído del JSON
                                    type = TvType.Movie,
                                    apiName = this.name
                                )
                            )
                        }
                    } else {
                        log("search - Filtrado elemento de búsqueda (JSON) irrelevante (sin link de serie/película): Título='$title', Link='$link'")
                    }
                }
            } else {
                // --- FIN: CAMBIO POTENCIAL PARA RESPUESTAS JSON DE LA BÚSQUEDA ---
                // Si la respuesta no es JSON o el parseo falló, intenta parsear como HTML (tu código original)
                log("search - La respuesta de búsqueda NO es JSON o falló el parseo. Intentando procesar como HTML.")
                val doc = Jsoup.parse(res.text)

                // Selectores más específicos para los resultados de búsqueda si están anidados
                // Por ejemplo, si los resultados están en un div con clase "search-results" y cada item es un "div.result-item" con un "a" dentro
                val items = doc.select("a.search-item-link, div.search-result a, a[href*='/series-online/'], a[href*='/peliculas-online/']") // Ajusta aquí

                for (item in items) {
                    val link = item.attr("href") ?: ""
                    val title = item.text() ?: ""
                    val poster = item.selectFirst("img")?.attr("src") ?: "" // Intentar extraer poster si está en el HTML

                    if (title.isNotBlank() && link.isNotBlank() && (link.contains("/series-online/") || link.contains("/peliculas-online/"))) {
                        val fixedLink = fixUrl(link)
                        val fixedPoster = fixUrl(poster)
                        if (fixedLink.isNullOrBlank()) {
                            log("search - ADVERTENCIA: Link fijo vacío para ítem en búsqueda (HTML): $title")
                            continue
                        }

                        val type = if (fixedLink.contains("/series-online/")) TvType.TvSeries else TvType.Movie

                        if (type == TvType.TvSeries) {
                            searchResults.add(
                                TvSeriesSearchResponse(
                                    name = title,
                                    url = fixedLink,
                                    posterUrl = fixedPoster, // Usar poster extraído del HTML
                                    type = TvType.TvSeries,
                                    apiName = this.name
                                )
                            )
                        } else {
                            searchResults.add(
                                MovieSearchResponse(
                                    name = title,
                                    url = fixedLink,
                                    posterUrl = fixedPoster, // Usar poster extraído del HTML
                                    type = TvType.Movie,
                                    apiName = this.name
                                )
                            )
                        }
                    } else {
                        log("search - Filtrado elemento de búsqueda (HTML) irrelevante (sin link de serie/película): Título='$title', Link='$link'")
                    }
                }
            }

            log("search - Encontrados ${searchResults.size} resultados válidos para '$query'")
            return searchResults
        } catch (e: Exception) {
            log("search - Error en la búsqueda para '$query' en URL $searchUrl: ${e.message} - ${e.stackTraceToString()}")
            return emptyList()
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        log("load - URL de entrada: $url")

        var cleanUrl = url
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(url)
        if (parsedEpisodeData != null) {
            cleanUrl = parsedEpisodeData.url
            log("load - URL limpia por JSON de EpisodeLoadData: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                log("load - URL limpiada con HTTPS: $cleanUrl")
            }
            log("load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            log("load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,application/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "es-ES,es;q=0.9",
                "Connection" to "keep-alive",
                "Referer" to mainUrl // Referer debería ser la URL de la página de donde vinimos, no la mainUrl
            )).document
        } catch (e: Exception) {
            log("load - ERROR al obtener el documento para URL: $cleanUrl - ${e.message} - ${e.stackTraceToString()}")
            return null
        }

        log("load - HTML recibido para la URL de la serie (primeros 2000 chars): ${doc.html().take(2000)}")

        val title = doc.selectFirst("h1.movs-title")?.text()
            ?: doc.selectFirst("h1.full_content-title")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""

        val poster = doc.selectFirst("div.film-poster img")?.attr("data-src")
            ?: doc.selectFirst("div.full_content-poster.img_box img")?.attr("data-src")
            ?: doc.selectFirst("div.film-poster img")?.attr("src")
            ?: doc.selectFirst("div.full_content-poster.img_box img")?.attr("src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""

        val plot = doc.selectFirst("div.description.full")?.text()
            ?: doc.selectFirst("div.full_content-info p")?.text()
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content") ?: ""

        val year = doc.selectFirst("div.info-text:contains(Año) span")?.text()?.toIntOrNull()
            ?: doc.selectFirst("span.year")?.text()?.toIntOrNull()

        val tags = doc.select("div.genres a").map { it.text() }

        val allEpisodes = ArrayList<Episode>()

        val seasonTitleElements = doc.select("h2.saisontitle")

        if (seasonTitleElements.isNotEmpty()) {
            log("load - Se encontraron ${seasonTitleElements.size} elementos de título de temporada 'h2.saisontitle'. Procesando series.")
            for (seasonTitleElement in seasonTitleElements) {
                val seasonName = seasonTitleElement.text().trim()
                val seasonNumber = Regex("""Temporada (\d+)""").find(seasonName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 1

                // Selector más robusto para el contenedor de episodios después del título de temporada
                val episodeLinksContainer = seasonTitleElement.nextElementSibling()

                if (episodeLinksContainer != null && (episodeLinksContainer.tagName() == "div" || episodeLinksContainer.tagName() == "ul")) {
                    val episodeLinks = episodeLinksContainer.select("a.episode-link, li a") // Asegúrate de que esto capture los enlaces correctos
                    log("load - Encontrados ${episodeLinks.size} enlaces de episodios para la temporada: $seasonName")

                    for (episodeLink in episodeLinks) {
                        val epUrl = fixUrl(episodeLink.attr("href"))
                        if (epUrl.isNullOrBlank()) {
                            log("load - ADVERTENCIA: URL de episodio vacía o nula para elemento: ${episodeLink.html().take(100)}")
                            continue
                        }

                        // Ajustar las expresiones regulares para extraer el número de episodio
                        // Pueden haber varios formatos, por ejemplo, /serie-x/temporada-1/episodio-10.html
                        val episodeNumber = Regex("""episodio-(\d+)\.html""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("""-(\d+)\.html$""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() // Buscar el último número antes de .html
                            ?: episodeLink.selectFirst("span.title")?.text()?.substringAfter("Episodio ")?.trim()?.toIntOrNull()
                            ?: episodeLink.text()?.substringAfter("Episodio ")?.trim()?.toIntOrNull()
                            ?: episodeLink.text()?.substringAfter("E")?.trim()?.toIntOrNull() // E10

                        val epTitle = episodeLink.selectFirst("span.title")?.text()?.trim()
                            ?: episodeLink.selectFirst("div.name")?.text()?.trim() // Otro posible selector de título
                            ?: "Episodio ${episodeNumber ?: "N/A"}"

                        val safePoster = fixUrl(poster) ?: ""

                        allEpisodes.add(
                            Episode(
                                data = EpisodeLoadData(epTitle, epUrl).toJson(),
                                name = epTitle,
                                season = seasonNumber,
                                episode = episodeNumber,
                                posterUrl = safePoster
                            )
                        )
                    }
                } else {
                    log("load - ADVERTENCIA: Contenedor de enlaces de episodio no encontrado o incorrecto para la temporada: $seasonName. HTML nextElementSibling: ${episodeLinksContainer?.html()?.take(200)}")
                }
            }
        } else {
            log("load - No se encontraron títulos de temporada 'h2.saisontitle'. Asumiendo que es una película o una serie con un único listado de reproductores o formato alternativo.")
            // Intenta el selector de lista de episodios general (si aplica a películas o series con un solo listado)
            val episodeElements = doc.select("ul.listing.items.full li, div.episodes-list a.episode-item") // Nuevos selectores potenciales
            if (episodeElements.isNotEmpty()) {
                log("load - Se encontraron ${episodeElements.size} elementos de episodio en 'ul.listing.items.full li' o 'div.episodes-list a.episode-item' (fallback).")
                episodeElements.mapNotNull { episodeElement ->
                    val epLink = episodeElement.selectFirst("a")?.attr("href") ?: ""
                    if (epLink.isNullOrBlank()) {
                        log("load - ADVERTENCIA: URL de episodio vacía o nula para elemento: ${episodeElement.html().take(100)}")
                        return@mapNotNull null
                    }
                    val fixedEpLink = fixUrl(epLink)
                    if (fixedEpLink.isNullOrBlank()) {
                        log("load - ADVERTENCIA: Link fijo vacío para episodio: ${epLink.take(100)}")
                        return@mapNotNull null
                    }

                    val epTitleText = episodeElement.selectFirst("div.name")?.text()?.trim()
                        ?: episodeElement.selectFirst("span.title")?.text()?.trim()
                        ?: episodeElement.text()?.trim() ?: ""

                    val ssEpiText = episodeElement.selectFirst("div.ss-epi")?.text()

                    // Ajustar la extracción de temporada y episodio para este formato
                    val seasonNumber = ssEpiText?.substringAfter("Temporada ")?.substringBefore(" ")?.toIntOrNull()
                        ?: Regex("""T(\d+)\s*E(\d+)""").find(epTitleText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: 1 // Asumir temporada 1 si no se encuentra

                    val episodeNumber = ssEpiText?.substringAfter("Episodio ")?.toIntOrNull()
                        ?: Regex("""T\d+\s*E(\d+)""").find(epTitleText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""(\d+)$""").find(epLink)?.groupValues?.getOrNull(1)?.toIntOrNull() // Último número en el link
                        ?: 1 // Asumir episodio 1 si no se encuentra

                    val safePoster = fixUrl(poster) ?: ""

                    Episode(
                        data = EpisodeLoadData(epTitleText, fixedEpLink).toJson(),
                        name = epTitleText,
                        season = seasonNumber,
                        episode = episodeNumber,
                        posterUrl = safePoster
                    )
                }.let {
                    allEpisodes.addAll(it)
                }
            } else {
                log("load - No se encontraron episodios en 'ul.listing.items.full li', 'div.episodes-list a.episode-item' ni 'h2.saisontitle'. Puede ser una película o un problema de selector.")
            }
        }

        log("load - Total de episodios encontrados (aplanados): ${allEpisodes.size}")

        val recommendations = doc.select("div.item").mapNotNull { recElement ->
            val recTitle = recElement.selectFirst("h3 a")?.text()
            val recLink = recElement.selectFirst("a")?.attr("href")
            val recPoster = recElement.selectFirst("img")?.attr("data-src") // Probar data-src para recomendaciones también
                ?: recElement.selectFirst("img")?.attr("src")

            if (recTitle != null && recLink != null && recPoster != null) {
                val fixedRecLink = fixUrl(recLink)
                val fixedRecPoster = fixUrl(recPoster)

                if (fixedRecLink.isNullOrBlank()) {
                    log("load - ADVERTENCIA: Link fijo vacío para recomendación: $recTitle")
                    return@mapNotNull null
                }

                val safeRecPosterUrl = fixedRecPoster ?: ""

                val recType = if (fixedRecLink.contains("/series-online/")) TvType.TvSeries else TvType.Movie

                if (recType == TvType.TvSeries) {
                    TvSeriesSearchResponse(
                        name = recTitle.trim(),
                        url = fixedRecLink,
                        posterUrl = safeRecPosterUrl,
                        type = recType,
                        apiName = this.name
                    )
                } else {
                    MovieSearchResponse(
                        name = recTitle.trim(),
                        url = fixedRecLink,
                        posterUrl = safeRecPosterUrl,
                        type = recType,
                        apiName = this.name
                    )
                }
            } else {
                log("load - Recomendación incompleta: Título=$recTitle, Link=$recLink, Poster=$recPoster para elemento: ${recElement.html().take(200)}")
                null
            }
        }

        val finalTvType = if (cleanUrl.contains("/series-online/")) TvType.TvSeries else TvType.Movie

        val safePoster = fixUrl(poster) ?: ""

        return if (finalTvType == TvType.TvSeries) {
            TvSeriesLoadResponse(
                name = title,
                url = cleanUrl,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = allEpisodes.sortedBy { it.episode }, // Asegúrate de que los episodios estén ordenados
                posterUrl = safePoster,
                backgroundPosterUrl = safePoster,
                plot = plot,
                year = year,
                tags = tags,
                recommendations = recommendations
            )
        } else {
            MovieLoadResponse(
                name = title,
                url = cleanUrl,
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = safePoster,
                backgroundPosterUrl = safePoster,
                plot = plot,
                year = year,
                tags = tags,
                recommendations = recommendations,
                dataUrl = cleanUrl
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("loadLinks - Data de entrada: $data")

        var targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            log("loadLinks - URL final del episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(data) ?: data
            log("loadLinks - URL final de película (directa o ya limpia y con fixUrl): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            log("loadLinks - ERROR: La URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,application/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "es-ES,es;q=0.9",
                "Connection" to "keep-alive",
                "Referer" to targetUrl // Referer correcto: la URL desde la que se accedió a esta página
            )).document
        } catch (e: Exception) {
            log("loadLinks - ERROR al obtener el documento para URL: $targetUrl - ${e.message} - ${e.stackTraceToString()}")
            return false
        }

        val streamerElements = doc.select("li.streamer")

        var foundLinks = false
        if (streamerElements.isNotEmpty()) {
            log("loadLinks - Se encontraron ${streamerElements.size} elementos 'li.streamer'. Procesando...")
            streamerElements.apmap { streamerElement ->
                val encodedUrl = streamerElement.attr("data-url") ?: ""
                val serverName = streamerElement.selectFirst("span")?.text()?.replace("OPCI??N ", "Opción ")?.trim()
                    ?: "Servidor Desconocido"

                if (encodedUrl.isNotBlank()) {
                    // La parte de la URL codificada base64 debería ser lo que está después de la última "/"
                    val base64Part = encodedUrl.substringAfterLast("/") // Cambio a substringAfterLast para mayor robustez

                    try {
                        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                        val decodedUrl = String(decodedBytes, UTF_8)
                        log("loadLinks - Decodificado URL para $serverName: $decodedUrl")

                        val linkType = when {
                            decodedUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                            decodedUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                            else -> ExtractorLinkType.VIDEO
                        }

                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = serverName,
                                url = fixUrl(decodedUrl) ?: decodedUrl,
                                referer = targetUrl,
                                quality = 0, // La calidad puede ser difícil de determinar sin más info
                                headers = emptyMap(),
                                extractorData = null,
                                type = linkType
                            )
                        )
                        foundLinks = true

                    } catch (e: IllegalArgumentException) {
                        log("loadLinks - Error al decodificar Base64 de $encodedUrl (parte: $base64Part): ${e.message}")
                    } catch (e: Exception) {
                        log("loadLinks - Error general al procesar link de $serverName ($encodedUrl): ${e.message} - ${e.stackTraceToString()}")
                    }
                } else {
                    log("loadLinks - URL codificada vacía para el elemento streamer: ${streamerElement.html().take(100)}")
                }
            }
        } else {
            log("loadLinks - No se encontraron elementos 'li.streamer'. Buscando alternativas (iframe directo/scripts).")

            // Selectores más flexibles para iframes si la estructura cambia
            val iframeSrc = doc.selectFirst("div[id*=\"player_response\"] iframe.metaframe, div.video-player iframe, iframe[src*='stream']")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                log("loadLinks - Encontrado iframe directo en la página: $iframeSrc. Intentando ExtractorLink.")
                val iframeLinkType = when {
                    iframeSrc.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                    iframeSrc.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "Directo Iframe",
                        url = fixUrl(iframeSrc) ?: iframeSrc,
                        referer = targetUrl,
                        quality = 0,
                        headers = emptyMap(),
                        extractorData = null,
                        type = iframeLinkType
                    )
                )
                foundLinks = true
            }

            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            // Regex más general para URLs en scripts, pero podría capturar falsos positivos
            val directRegex = """(?:file|src|url):\s*['"](https?:\/\/[^'"]+?\.(?:m3u8|mp4|avi|mkv|mov|mpd|webm))['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                log("loadLinks - Encontrados ${directMatches.size} enlaces directos en scripts. Intentando ExtractorLink.")
                directMatches.apmap { directUrl ->
                    val directLinkType = when {
                        directUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        directUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "Directo Script",
                            url = fixUrl(directUrl) ?: directUrl,
                            referer = targetUrl,
                            quality = 0,
                            headers = emptyMap(),
                            extractorData = null,
                            type = directLinkType
                        )
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }

    fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("/")) {
            mainUrl + url
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // Manejar URLs que pueden ser relativas pero no empiezan con /
            // Por ejemplo, "path/to/resource.jpg"
            "$mainUrl/$url"
        } else {
            url
        }
    }
}