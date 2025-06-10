package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.MainPageRequest // <-- IMPORTACIÓN CORRECTA: MainPageRequest, no HomePageRequest

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
    // hasLatest eliminado porque no existe en la MainAPI que proporcionaste
    // override val hasLatest = true

    private fun log(message: String) {
        Log.d(name, message)
    }

    // Firma de getMainPage ajustada a MainPageRequest según la definición de tu MainAPI
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val seriesPageUrl = "$mainUrl/series-online"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,application/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "es-ES,es;q=0.9",
            "Connection" to "keep-alive"
        )

        val mainPageResponse = try {
            app.get(seriesPageUrl, headers = headers)
        } catch (e: Exception) {
            log("getMainPage - ERROR al obtener la página de series: ${e.message} - ${e.stackTraceToString()}")
            return null
        }
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)

        log("getMainPage - HTML completo recibido (primeros 2000 chars): ${mainPageDoc.html().take(2000)}")

        val mainContentContainer = mainPageDoc.selectFirst("div#dle-content")

        if (mainContentContainer == null) {
            log("getMainPage - ERROR: No se encontró el contenedor principal '#dle-content'.")
            return null
        }

        log("getMainPage - Contenedor '#dle-content' encontrado. Procesando elementos de serie...")

        val seriesElements = mainContentContainer.select("div.short")

        log("getMainPage - Encontrados ${seriesElements.size} elementos 'div.short' dentro de '#dle-content'.")

        val series = seriesElements.mapNotNull { element ->
            log("getMainPage - Procesando elemento 'div.short': ${element.html().take(300)}")

            val aElement = element.selectFirst("a.short_img_box.with_mask, a#short_img")

            var currentLink: String? = null
            var currentImg: String? = null
            var currentTitle: String? = null

            if (aElement != null) {
                currentLink = aElement.attr("href")
                val imgElement = aElement.selectFirst("img")
                currentImg = imgElement?.attr("data-src") ?: imgElement?.attr("src")
                val titleElement = element.selectFirst("div.short_title a")
                currentTitle = titleElement?.text()?.trim()
            } else {
                log("getMainPage - ERROR: No se encontró el enlace principal (a.short_img_box.with_mask o a#short_img) en el elemento 'div.short'.")
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

        if (series.isNotEmpty()) {
            items.add(HomePageList("Series y Películas Online", series))
            log("getMainPage - Se agregaron ${series.size} ítems para 'Series y Películas Online'.")
        } else {
            log("getMainPage - ADVERTENCIA: No se encontraron series o películas válidas para agregar a la página principal.")
        }

        return HomePageResponse(items.toList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = ArrayList<SearchResponse>()
        val mainPageResponse = app.get(mainUrl)
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
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Accept-Language" to "es-ES,es;q=0.9",
                    "Connection" to "keep-alive"
                )
            )
            val doc = Jsoup.parse(res.text)

            log("search - HTML de búsqueda recibido (primeros 1000 chars): ${doc.html().take(1000)}")

            val items = doc.select("a")
            for (item in items) {
                val link = item.attr("href") ?: ""
                val title = item.text() ?: ""

                if (title.isNotBlank() && link.isNotBlank() && (link.contains("/series-online/") || link.contains("/peliculas-online/"))) {
                    val fixedLink = fixUrl(link)
                    if (fixedLink.isNullOrBlank()) {
                        log("search - ADVERTENCIA: Link fijo vacío para ítem en búsqueda: $title")
                        continue
                    }

                    val type = if (fixedLink.contains("/series-online/")) TvType.TvSeries else TvType.Movie

                    if (type == TvType.TvSeries) {
                        searchResults.add(
                            TvSeriesSearchResponse(
                                name = title,
                                url = fixedLink,
                                posterUrl = "",
                                type = TvType.TvSeries,
                                apiName = this.name
                            )
                        )
                    } else {
                        searchResults.add(
                            MovieSearchResponse(
                                name = title,
                                url = fixedLink,
                                posterUrl = "",
                                type = TvType.Movie,
                                apiName = this.name
                            )
                        )
                    }
                } else {
                    log("search - Filtrado elemento de búsqueda irrelevante (sin link de serie/película): Título='$title', Link='$link'")
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
                "Referer" to mainUrl
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

                val episodeLinksContainer = seasonTitleElement.nextElementSibling()

                if (episodeLinksContainer != null && episodeLinksContainer.tagName() == "div" && episodeLinksContainer.select("a").isNotEmpty()) {
                    val episodeLinks = episodeLinksContainer.select("a")
                    log("load - Encontrados ${episodeLinks.size} enlaces de episodios para la temporada: $seasonName")

                    for (episodeLink in episodeLinks) {
                        val epUrl = fixUrl(episodeLink.attr("href"))
                        if (epUrl.isNullOrBlank()) {
                            log("load - ADVERTENCIA: URL de episodio vacía o nula para elemento: ${episodeLink.html().take(100)}")
                            continue
                        }

                        val episodeNumber = Regex("""episodio-(\d+)\.html""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: episodeLink.selectFirst("span.title")?.text()?.substringAfter("Episodio ")?.toIntOrNull()
                            ?: episodeLink.text()?.substringAfter("Episodio ")?.toIntOrNull()

                        val epTitle = episodeLink.selectFirst("span.title")?.text()?.trim()
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
                    log("load - ADVERTENCIA: Contenedor de enlaces de episodio no encontrado o incorrecto para la temporada: $seasonName")
                }
            }
        } else {
            log("load - No se encontraron títulos de temporada 'h2.saisontitle'. Asumiendo que es una película o una serie con un único listado de reproductores.")
            val episodeElements = doc.select("ul.listing.items.full li")

            if (episodeElements.isNotEmpty()) {
                log("load - Se encontraron ${episodeElements.size} elementos de episodio en 'ul.listing.items.full li' (fallback).")
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

                    val epTitleText = episodeElement.selectFirst("div.name")?.text()?.trim() ?: ""
                    val ssEpiText = episodeElement.selectFirst("div.ss-epi")?.text()

                    val seasonNumber = ssEpiText?.substringAfter("Temporada ")?.substringBefore(" ")?.toIntOrNull()
                    val episodeNumber = ssEpiText?.substringAfter("Episodio ")?.toIntOrNull()

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
                log("load - No se encontraron episodios en 'ul.listing.items.full li' ni 'h2.saisontitle'. Puede ser una película o un problema de selector.")
            }
        }

        log("load - Total de episodios encontrados (aplanados): ${allEpisodes.size}")

        val recommendations = doc.select("div.item").mapNotNull { recElement ->
            val recTitle = recElement.selectFirst("h3 a")?.text()
            val recLink = recElement.selectFirst("a")?.attr("href")
            val recPoster = recElement.selectFirst("img")?.attr("src")

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
                episodes = allEpisodes.sortedBy { it.episode },
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
                "Referer" to targetUrl
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
                    val base64Part = encodedUrl.substringAfter("/streamer/")

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
                                quality = 0,
                                headers = emptyMap(),
                                extractorData = null,
                                type = linkType
                            )
                        )
                        foundLinks = true

                    } catch (e: IllegalArgumentException) {
                        log("loadLinks - Error al decodificar Base64 de $encodedUrl: ${e.message}")
                    } catch (e: Exception) {
                        log("loadLinks - Error general al procesar link de $serverName ($encodedUrl): ${e.message} - ${e.stackTraceToString()}")
                    }
                } else {
                    log("loadLinks - URL codificada vacía para el elemento streamer: ${streamerElement.html().take(100)}")
                }
            }
        } else {
            log("loadLinks - No se encontraron elementos 'li.streamer'. Buscando alternativas (iframe directo/scripts).")

            val iframeSrc = doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
                ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

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
            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
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
        } else {
            url
        }
    }
}