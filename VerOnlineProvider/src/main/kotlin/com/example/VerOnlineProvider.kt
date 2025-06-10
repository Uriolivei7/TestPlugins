package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import com.lagradost.cloudstream3.SeasonData
import java.io.IOException
import kotlinx.coroutines.CancellationException

class VerOnlineProvider : MainAPI() {
    override var mainUrl = "https://www.verseriesonline.net"
    override var name = "VerOnline"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
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
            log("Error al obtener la página principal ($homePageUrl): ${e.message}")
            return null
        }
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)

        val mainContentContainer = mainPageDoc.selectFirst("div#dle-content") ?: run {
            log("Error: No se encontró el contenedor principal '#dle-content'.")
            return null
        }

        val contentElements = mainContentContainer.select("div.short")

        val entries = contentElements.mapNotNull { element ->
            val aElement = element.selectFirst("a.short_img_box.with_mask, a.short_img, a[href].main-link") ?: run {
                log("Error: No se encontró el enlace principal en el elemento 'div.short'.")
                return@mapNotNull null
            }

            var currentLink: String? = aElement.attr("href")
            var currentImg: String? = aElement.selectFirst("img")?.attr("data-src")
                ?: aElement.selectFirst("img")?.attr("src")
                ?: element.selectFirst("div.short_img_box img")?.attr("src")
            var currentTitle: String? = element.selectFirst("div.short_title a")?.text()?.trim()
                ?: element.selectFirst("h3.short_title a")?.text()?.trim()
                ?: element.selectFirst("div.title a")?.text()?.trim()

            if (currentTitle != null && currentLink != null && currentImg != null) {
                val fixedLink = fixUrl(currentLink)
                val fixedImg = fixUrl(currentImg)

                if (fixedLink.isNullOrBlank() || fixedImg.isNullOrBlank()) {
                    log("Advertencia: Link o imagen fija nulos/vacíos para ítem: $currentTitle")
                    return@mapNotNull null
                }

                TvSeriesSearchResponse(
                    name = currentTitle,
                    url = fixedLink,
                    posterUrl = fixedImg,
                    type = TvType.TvSeries,
                    apiName = this.name
                )
            } else {
                log("Ítem incompleto: Título='${currentTitle}', Link='${currentLink}', Img='${currentImg}'")
                null
            }
        }

        if (entries.isNotEmpty()) {
            items.add(HomePageList("Series Online", entries))
        } else {
            log("Advertencia: No se encontraron series válidas para agregar a la página principal.")
        }

        val hasNextPage = mainPageDoc.selectFirst("a.next-page, a.button.next") != null

        return HomePageResponse(items.toList(), hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = ArrayList<SearchResponse>()

        val mainPageResponse = try {
            app.get(mainUrl)
        } catch (e: Exception) {
            log("Error al obtener la página principal para CSRF: ${e.message}")
            return emptyList()
        }
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)
        val csrfToken = mainPageDoc.select("meta[name=csrf-token]").attr("content")
        log("CSRF Token para búsqueda: $csrfToken")

        if (csrfToken.isBlank()) {
            log("Error: No se pudo obtener el token CSRF para la búsqueda POST.")
            return emptyList()
        }

        val searchUrl = "$mainUrl/livesearch"
        val postData = mapOf(
            "search" to query,
            "_token" to csrfToken
        )

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
                ),
                timeout = 30
            )

            log("Respuesta de búsqueda POST: ${res.text.take(1000)}")

            data class SearchResultJson(
                val title: String?,
                val link: String?,
                val img: String? = null
            )
            data class SearchResponseJson(
                val results: List<SearchResultJson>?
            )

            val searchResponseJson = tryParseJson<SearchResponseJson>(res.text)

            if (searchResponseJson != null && searchResponseJson.results != null) {
                for (itemJson in searchResponseJson.results) {
                    val link = itemJson.link ?: ""
                    val title = itemJson.title ?: ""
                    val poster = itemJson.img ?: ""

                    if (title.isNotBlank() && link.isNotBlank() && link.contains("/series-online/")) {
                        val fixedLink = fixUrl(link)
                        val fixedPoster = fixUrl(poster)
                        if (fixedLink.isNullOrBlank()) {
                            log("Advertencia: Link fijo vacío para ítem en búsqueda (JSON): $title")
                            continue
                        }

                        searchResults.add(
                            TvSeriesSearchResponse(
                                name = title,
                                url = fixedLink,
                                posterUrl = fixedPoster,
                                type = TvType.TvSeries,
                                apiName = this.name
                            )
                        )
                    } else {
                        log("Filtrado elemento de búsqueda (JSON) irrelevante: Título='$title', Link='$link'")
                    }
                }
            } else {
                log("No se pudo parsear la respuesta JSON de búsqueda. Intentando parseo HTML.")
                val doc = Jsoup.parse(res.text)
                val items = doc.select("div.short") // Ajustar selector según el HTML de la respuesta

                for (item in items) {
                    val aElement = item.selectFirst("a.short_img_box.with_mask, a.short_img, a[href].main-link") ?: continue
                    val link = aElement.attr("href")
                    val title = item.selectFirst("div.short_title a")?.text()?.trim()
                        ?: item.selectFirst("h3.short_title a")?.text()?.trim()
                        ?: item.selectFirst("div.title a")?.text()?.trim()
                        ?: ""
                    val poster = aElement.selectFirst("img")?.attr("data-src")
                        ?: aElement.selectFirst("img")?.attr("src")
                        ?: item.selectFirst("div.short_img_box img")?.attr("src")
                        ?: ""

                    if (title.isNotBlank() && link.isNotBlank() && link.contains("/series-online/")) {
                        val fixedLink = fixUrl(link)
                        val fixedPoster = fixUrl(poster)
                        if (fixedLink.isNullOrBlank()) {
                            log("Advertencia: Link fijo vacío para ítem en búsqueda (HTML): $title")
                            continue
                        }

                        searchResults.add(
                            TvSeriesSearchResponse(
                                name = title,
                                url = fixedLink,
                                posterUrl = fixedPoster,
                                type = TvType.TvSeries,
                                apiName = this.name
                            )
                        )
                    } else {
                        log("Filtrado elemento de búsqueda (HTML) irrelevante: Título='$title', Link='$link'")
                    }
                }
            }

            return searchResults
        } catch (e: CancellationException) {
            log("Búsqueda cancelada para '$query': ${e.message}")
            return emptyList()
        } catch (e: IOException) {
            log("Error de red en la búsqueda POST para '$query': ${e.message}")
            return emptyList()
        } catch (e: Exception) {
            log("Error inesperado en la búsqueda POST para '$query': ${e.message}")
            return emptyList()
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        log("Cargando URL: $url")

        var cleanUrl = url
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(url)
        if (parsedEpisodeData != null) {
            cleanUrl = parsedEpisodeData.url
            log("URL limpia por JSON: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                log("URL ajustada con HTTPS: $cleanUrl")
            }
        }

        if (cleanUrl.isBlank()) {
            log("Error: URL limpia está en blanco.")
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
            log("Error al obtener el documento para URL: $cleanUrl - ${e.message}")
            return null
        }

        log("HTML de la página (primeros 1000 caracteres): ${doc.html().take(1000)}")

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
        val seasonDataList = ArrayList<SeasonData>()

        // Buscar enlaces de temporadas en la página
        log("Buscando enlaces de temporadas en la página principal.")
        val seasonLinks = doc.select("a[href*='/temporada-']")
        log("Encontrados ${seasonLinks.size} enlaces de temporadas: ${seasonLinks.joinToString { it.attr("href") }}")

        if (seasonLinks.isNotEmpty()) {
            seasonLinks.apmap { seasonLink ->
                val seasonUrl = fixUrl(seasonLink.attr("href")) ?: ""
                val seasonName = seasonLink.text().trim() ?: "Temporada Desconocida"
                val seasonNumber = Regex("""temporada-(\d+)""").find(seasonUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                if (seasonUrl.isNotBlank()) {
                    log("Procesando temporada: $seasonName (Número: $seasonNumber), URL: $seasonUrl")

                    val seasonDoc = try {
                        app.get(seasonUrl, headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,application/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                            "Accept-Language" to "es-ES,es;q=0.9",
                            "Connection" to "keep-alive",
                            "Referer" to cleanUrl
                        )).document
                    } catch (e: Exception) {
                        log("Error al obtener el documento para la URL de temporada ($seasonUrl): ${e.message}")
                        return@apmap
                    }

                    val episodeLinks = seasonDoc.select("a[href*='ver-episodio']")
                    log("Encontrados ${episodeLinks.size} enlaces de episodios para $seasonName: ${episodeLinks.joinToString { it.attr("href") }}")

                    episodeLinks.forEach { episodeLink ->
                        val epUrl = fixUrl(episodeLink.attr("href")) ?: ""
                        if (epUrl.isNotBlank()) {
                            val epTitle = episodeLink.selectFirst("span.name")?.text()?.trim() ?: episodeLink.text().trim() ?: "Episodio Desconocido"
                            val episodeNumber = Regex("""ver-episodio-(\d+)\.html""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: Regex("""Capítulo\s*(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: 1

                            val safePoster = fixUrl(poster) ?: ""

                            val newEpisode = Episode(
                                data = EpisodeLoadData(epTitle, epUrl).toJson(),
                                name = epTitle,
                                season = seasonNumber,
                                episode = episodeNumber,
                                posterUrl = safePoster
                            )
                            allEpisodes.add(newEpisode)
                        }
                    }
                    seasonDataList.add(SeasonData(season = seasonNumber, name = seasonName))
                }
            }
        } else {
            log("No se encontraron enlaces de temporadas. Buscando episodios directamente en la página principal.")
            val episodeLinks = doc.select("a[href*='ver-episodio']")
            log("Encontrados ${episodeLinks.size} enlaces de episodios en la página principal: ${episodeLinks.joinToString { it.attr("href") }}")

            if (episodeLinks.isNotEmpty()) {
                val episodesForSingleSeason = ArrayList<Episode>()
                episodeLinks.forEach { episodeLink ->
                    val epUrl = fixUrl(episodeLink.attr("href")) ?: ""
                    if (epUrl.isNotBlank()) {
                        val epTitle = episodeLink.selectFirst("span.name")?.text()?.trim() ?: episodeLink.text().trim() ?: "Episodio Desconocido"
                        val episodeNumber = Regex("""ver-episodio-(\d+)\.html""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("""Capítulo\s*(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: 1

                        val safePoster = fixUrl(poster) ?: ""

                        val newEpisode = Episode(
                            data = EpisodeLoadData(epTitle, epUrl).toJson(),
                            name = epTitle,
                            season = 1,
                            episode = episodeNumber,
                            posterUrl = safePoster
                        )
                        episodesForSingleSeason.add(newEpisode)
                        allEpisodes.add(newEpisode)
                    }
                }
                seasonDataList.add(SeasonData(season = 1, name = "Temporada 1"))
            }
        }

        if (allEpisodes.isNotEmpty()) {
            val recommendations = doc.select("div.item").mapNotNull { recElement ->
                val recTitle = recElement.selectFirst("h3 a")?.text()
                val recLink = recElement.selectFirst("a")?.attr("href")
                val recPoster = recElement.selectFirst("img")?.attr("data-src")
                    ?: recElement.selectFirst("img")?.attr("src")

                if (recTitle != null && recLink != null && recPoster != null && recLink.contains("/series-online/")) {
                    val fixedRecLink = fixUrl(recLink)
                    val fixedRecPoster = fixUrl(recPoster)

                    if (fixedRecLink.isNullOrBlank()) {
                        log("Advertencia: Link fijo vacío para recomendación: $recTitle")
                        return@mapNotNull null
                    }

                    TvSeriesSearchResponse(
                        name = recTitle.trim(),
                        url = fixedRecLink,
                        posterUrl = fixedRecPoster ?: "",
                        type = TvType.TvSeries,
                        apiName = this.name
                    )
                } else {
                    null
                }
            }

            return TvSeriesLoadResponse(
                name = title,
                url = cleanUrl,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = allEpisodes,
                posterUrl = fixUrl(poster),
                year = year,
                plot = plot,
                backgroundPosterUrl = fixUrl(poster),
                tags = tags,
                recommendations = recommendations,
                seasonNames = seasonDataList
            )
        } else {
            log("Error: No se encontraron episodios en la página ni en las temporadas.")
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("Cargando enlaces para: $data")

        var targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            log("URL de episodio: $targetUrl")
        } else {
            targetUrl = fixUrl(data) ?: data
            log("Datos no parseados, usando URL directa: $targetUrl")
        }

        if (targetUrl.isBlank()) {
            log("Error: La URL de destino está vacía.")
            return false
        }

        val doc = try {
            app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,application/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "es-ES,es;q=0.9",
                    "Connection" to "keep-alive",
                    "Referer" to mainUrl
                ),
                timeout = 30
            ).document
        } catch (e: Exception) {
            log("Error al cargar documento para URL: $targetUrl - ${e.message}")
            return false
        }

        log("HTML de la página (primeros 500 caracteres): ${doc.html().take(500)}")

        val csrfToken = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content") ?: ""
        if (csrfToken.isBlank()) {
            log("Error: No se pudo obtener el token CSRF.")
            return false
        }
        log("CSRF Token: $csrfToken")

        val playerLinks = doc.select("ul.player-list li div.lien.fx-row")
        log("Enlaces de reproductor encontrados: ${playerLinks.size}")

        var foundLinks = false
        playerLinks.apmap { linkElement ->
            val hash = linkElement.attr("data-hash") ?: ""
            val serverName = linkElement.selectFirst("span.serv")?.text()?.trim() ?: "Desconocido"
            val optionNumber = linkElement.selectFirst("span.pl-1")?.text()?.trim() ?: "Opción Desconocida"
            val langImg = linkElement.selectFirst("span.pl-4 img")?.attr("src") ?: ""
            val language = when {
                langImg.contains("esp.png") -> "Español"
                langImg.contains("lat.png") -> "Latino"
                else -> "Desconocido"
            }
            val quality = linkElement.selectFirst("span.pl-5")?.text()?.trim() ?: "HDTV"

            log("Procesando enlace: $optionNumber, Servidor: $serverName, Idioma: $language, Calidad: $quality, Hash: $hash")

            if (hash.isNotBlank()) {
                try {
                    val postResponse = app.post(
                        url = "$mainUrl/hashembedlink",
                        data = mapOf(
                            "hash" to hash,
                            "_token" to csrfToken
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to targetUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Accept-Language" to "es-ES,es;q=0.9",
                            "Connection" to "keep-alive"
                        ),
                        timeout = 15
                    )

                    log("Respuesta POST para hash $hash: ${postResponse.text.take(500)}")

                    data class EmbedLinkResponse(val link: String?)
                    val embedLink = tryParseJson<EmbedLinkResponse>(postResponse.text)?.link

                    if (!embedLink.isNullOrBlank()) {
                        log("URL de enlace obtenida: $embedLink")
                        loadExtractor(
                            url = embedLink,
                            referer = targetUrl,
                            subtitleCallback = subtitleCallback,
                            callback = { link ->
                                val resolvedQuality = when (quality.uppercase()) {
                                    "HDTV" -> Qualities.P720.value
                                    "HD" -> Qualities.P1080.value
                                    "SD" -> Qualities.P480.value
                                    else -> Qualities.Unknown.value
                                }
                                callback(
                                    ExtractorLink(
                                        source = link.source,
                                        name = "$serverName ($language - $optionNumber)",
                                        url = link.url,
                                        referer = link.referer,
                                        quality = resolvedQuality,
                                        headers = link.headers,
                                        extractorData = link.extractorData,
                                        type = link.type ?: ExtractorLinkType.VIDEO
                                    )
                                )
                                foundLinks = true
                            }
                        )
                    } else {
                        log("No se obtuvo enlace válido para hash: $hash")
                    }
                } catch (e: IOException) {
                    log("Error de red al procesar hash $hash para $serverName: ${e.message}")
                } catch (e: Exception) {
                    log("Error inesperado al procesar hash $hash para $serverName: ${e.message}")
                }
            } else {
                log("Hash vacío para enlace: $optionNumber")
            }
        }

        // Solución temporal mejorada
        if (!foundLinks) {
            log("Intentando encontrar enlaces directos en el HTML como solución temporal.")
            val videoLinks = doc.select("video source, iframe[src], a[href], script[src]").mapNotNull { element ->
                val link = element.attr("src") ?: element.attr("href")
                if (link.isNotBlank() && (link.contains(".mp4") || link.contains(".m3u8") || link.contains("video") || link.contains("stream"))) fixUrl(link) else null
            }

            videoLinks.forEach { link ->
                callback(
                    ExtractorLink(
                        source = "VerOnline",
                        name = "Direct Link",
                        url = link,
                        referer = targetUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                foundLinks = true
            }

            if (!foundLinks) {
                log("No se encontraron enlaces de streaming válidos ni con hash ni directos.")
            }
        }

        return foundLinks
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("/")) {
            mainUrl + url
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "$mainUrl/$url"
        } else {
            url
        }
    }
}