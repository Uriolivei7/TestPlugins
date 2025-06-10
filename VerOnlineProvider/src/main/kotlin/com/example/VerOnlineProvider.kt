package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor // Importar loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

// AÑADIR/VERIFICAR ESTA IMPORTACIÓN EXPLÍCITA
import com.lagradost.cloudstream3.SeasonData // Asegúrate de que esta línea esté presente

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

        val mainContentContainer = mainPageDoc.selectFirst("div#dle-content")

        if (mainContentContainer == null) {
            log("Error: No se encontró el contenedor principal '#dle-content'.")
            return null
        }

        val contentElements = mainContentContainer.select("div.short")

        val entries = contentElements.mapNotNull { element ->
            val aElement = element.selectFirst("a.short_img_box.with_mask, a.short_img, a[href].main-link")

            var currentLink: String? = null
            var currentImg: String? = null
            var currentTitle: String? = null

            if (aElement != null) {
                currentLink = aElement.attr("href")
                val imgElement = aElement.selectFirst("img")
                currentImg = imgElement?.attr("data-src")
                    ?: imgElement?.attr("src")
                            ?: element.selectFirst("div.short_img_box img")?.attr("src")

                currentTitle = element.selectFirst("div.short_title a")?.text()?.trim()
                    ?: element.selectFirst("h3.short_title a")?.text()?.trim()
                            ?: element.selectFirst("div.title a")?.text()?.trim()
            } else {
                log("Error: No se encontró el enlace principal en el elemento 'div.short'.")
                return@mapNotNull null
            }

            if (currentTitle != null && currentLink != null && currentImg != null) {
                val fixedLink = fixUrl(currentLink)
                val fixedImg = fixUrl(currentImg)

                if (fixedLink.isNullOrBlank() || fixedImg.isNullOrBlank()) {
                    log("Advertencia: Link o imagen fija nulos/vacíos para ítem: $currentTitle")
                    return@mapNotNull null
                }

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
                log("Ítem incompleto: Título='${currentTitle}', Link='${currentLink}', Img='${currentImg}'")
                null
            }
        }

        if (entries.isNotEmpty()) {
            items.add(HomePageList("Series y Películas Online", entries))
        } else {
            log("Advertencia: No se encontraron series o películas válidas para agregar a la página principal.")
        }

        val hasNextPage = mainPageDoc.selectFirst("a.next-page, a.button.next") != null

        return HomePageResponse(items.toList(), hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = ArrayList<SearchResponse>()

        val getSearchUrl = "$mainUrl/series-online?search=$query"
        try {
            val getResponse = app.get(
                url = getSearchUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,application/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "es-ES,es;q=0.9",
                    "Connection" to "keep-alive"
                )
            )
            val searchDoc = Jsoup.parse(getResponse.text)

            val searchItems = searchDoc.select("div.short")

            for (item in searchItems) {
                val aElement = item.selectFirst("a.short_img_box.with_mask, a.short_img, a[href].main-link")
                val link = aElement?.attr("href") ?: ""
                val title = item.selectFirst("div.short_title a")?.text()?.trim()
                    ?: item.selectFirst("h3.short_title a")?.text()?.trim()
                    ?: item.selectFirst("div.title a")?.text()?.trim()
                    ?: ""
                val poster = aElement?.selectFirst("img")?.attr("data-src")
                    ?: aElement?.selectFirst("img")?.attr("src")
                    ?: item.selectFirst("div.short_img_box img")?.attr("src")
                    ?: ""

                if (title.isNotBlank() && link.isNotBlank() && (link.contains("/series-online/") || link.contains("/peliculas-online/"))) {
                    val fixedLink = fixUrl(link)
                    val fixedPoster = fixUrl(poster)
                    if (fixedLink.isNullOrBlank()) {
                        log("Advertencia: Link fijo vacío para ítem en búsqueda GET: $title")
                        continue
                    }

                    val type = if (fixedLink.contains("/series-online/")) TvType.TvSeries else TvType.Movie

                    if (type == TvType.TvSeries) {
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
                        searchResults.add(
                            MovieSearchResponse(
                                name = title,
                                url = fixedLink,
                                posterUrl = fixedPoster,
                                type = TvType.Movie,
                                apiName = this.name
                            )
                        )
                    }
                } else {
                    log("Filtrado elemento de búsqueda GET irrelevante (sin link de serie/película): Título='$title', Link='$link'")
                }
            }

            if (searchResults.isNotEmpty()) {
                return searchResults
            }
        } catch (e: Exception) {
            log("Error en la búsqueda GET para '$query': ${e.message}")
        }

        val mainPageResponse = app.get(mainUrl)
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)
        val csrfToken = mainPageDoc.select("meta[name=csrf-token]").attr("content")

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
                )
            )

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

                    if (title.isNotBlank() && link.isNotBlank() && (link.contains("/series-online/") || link.contains("/peliculas-online/"))) {
                        val fixedLink = fixUrl(link)
                        val fixedPoster = fixUrl(poster)
                        if (fixedLink.isNullOrBlank()) {
                            log("Advertencia: Link fijo vacío para ítem en búsqueda (JSON): $title")
                            continue
                        }

                        val type = if (fixedLink.contains("/series-online/")) TvType.TvSeries else TvType.Movie

                        if (type == TvType.TvSeries) {
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
                            searchResults.add(
                                MovieSearchResponse(
                                    name = title,
                                    url = fixedLink,
                                    posterUrl = fixedPoster,
                                    type = TvType.Movie,
                                    apiName = this.name
                                )
                            )
                        }
                    } else {
                        log("Filtrado elemento de búsqueda (JSON) irrelevante: Título='$title', Link='$link'")
                    }
                }
            } else {
                val doc = Jsoup.parse(res.text)

                val items = doc.select("div.short")

                for (item in items) {
                    val aElement = item.selectFirst("a.short_img_box.with_mask, a.short_img, a[href].main-link")
                    val link = aElement?.attr("href") ?: ""
                    val title = item.selectFirst("div.short_title a")?.text()?.trim()
                        ?: item.selectFirst("h3.short_title a")?.text()?.trim()
                        ?: item.selectFirst("div.title a")?.text()?.trim()
                        ?: ""
                    val poster = aElement?.selectFirst("img")?.attr("data-src")
                        ?: aElement?.selectFirst("img")?.attr("src")
                        ?: item.selectFirst("div.short_img_box img")?.attr("src")
                        ?: ""

                    if (title.isNotBlank() && link.isNotBlank() && (link.contains("/series-online/") || link.contains("/peliculas-online/"))) {
                        val fixedLink = fixUrl(link)
                        val fixedPoster = fixUrl(poster)
                        if (fixedLink.isNullOrBlank()) {
                            log("Advertencia: Link fijo vacío para ítem en búsqueda (HTML/Fallback): $title")
                            continue
                        }

                        val type = if (fixedLink.contains("/series-online/")) TvType.TvSeries else TvType.Movie

                        if (type == TvType.TvSeries) {
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
                            searchResults.add(
                                MovieSearchResponse(
                                    name = title,
                                    url = fixedLink,
                                    posterUrl = fixedPoster,
                                    type = TvType.Movie,
                                    apiName = this.name
                                )
                            )
                        }
                    } else {
                        log("Filtrado elemento de búsqueda (HTML/Fallback) irrelevante: Título='$title', Link='$link'")
                    }
                }
            }

            return searchResults
        } catch (e: Exception) {
            log("Error en la búsqueda POST para '$query': ${e.message}")
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

        val seasonTabs = doc.select("div.seasonstab a.th-hover")

        if (seasonTabs.isNotEmpty()) {
            log("Procesando ${seasonTabs.size} pestañas de temporada.")
            seasonTabs.apmap { seasonLinkElement ->
                val seasonTitleElement = seasonLinkElement.selectFirst("div.th-title1")
                val seasonName = seasonTitleElement?.text()?.trim() ?: "Temporada Desconocida"
                val seasonNumber = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull()
                    ?: 1

                val seasonUrl = fixUrl(seasonLinkElement.attr("href")) ?: ""

                if (seasonUrl.isBlank()) {
                    log("Advertencia: URL de temporada vacía para '$seasonName'. Saltando.")
                    return@apmap
                }

                log("Procesando temporada: $seasonName (Número: $seasonNumber), URL: $seasonUrl")

                val episodesInCurrentSeason = ArrayList<Episode>()
                val currentSeasonDoc = try {
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

                val episodeLinks = currentSeasonDoc.select("div[align=\"center\"] > a")

                log("Encontrados ${episodeLinks.size} enlaces de episodios para $seasonName.")

                episodeLinks.forEach { episodeLink ->
                    val epTitle = episodeLink.text().trim()
                    val epUrl = fixUrl(episodeLink.attr("href")) ?: ""

                    if (epUrl.isBlank()) {
                        log("Advertencia: URL de episodio vacía para elemento: ${episodeLink.html().take(100)}. Saltando.")
                        return@forEach
                    }

                    val episodeNumber = Regex("""-(\d+)(?:\.html)?$""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""Episodio\s*(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: 1

                    val safePoster = fixUrl(poster) ?: ""

                    val newEpisode = Episode(
                        data = EpisodeLoadData(epTitle, epUrl).toJson(),
                        name = epTitle,
                        season = seasonNumber,
                        episode = episodeNumber,
                        posterUrl = safePoster
                    )
                    episodesInCurrentSeason.add(newEpisode)
                    allEpisodes.add(newEpisode) // Añadir a la lista plana de todos los episodios
                }
                // CORRECCIÓN CLAVE AQUÍ: SeasonData solo toma season y name.
                seasonDataList.add(
                    SeasonData(
                        season = seasonNumber,
                        name = seasonName
                    )
                )
            }

            val recommendations = doc.select("div.item").mapNotNull { recElement ->
                val recTitle = recElement.selectFirst("h3 a")?.text()
                val recLink = recElement.selectFirst("a")?.attr("href")
                val recPoster = recElement.selectFirst("img")?.attr("data-src")
                    ?: recElement.selectFirst("img")?.attr("src")

                if (recTitle != null && recLink != null && recPoster != null) {
                    val fixedRecLink = fixUrl(recLink)
                    val fixedRecPoster = fixUrl(recPoster)

                    if (fixedRecLink.isNullOrBlank()) {
                        log("Advertencia: Link fijo vacío para recomendación: $recTitle")
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
                    null
                }
            }

            return TvSeriesLoadResponse(
                name = title,
                url = cleanUrl,
                apiName = this.name,
                type = TvType.TvSeries,
                episodes = allEpisodes, // Correcto
                posterUrl = fixUrl(poster),
                year = year,
                plot = plot, // Correcto: String?
                backgroundPosterUrl = fixUrl(poster),
                tags = tags,
                recommendations = recommendations,
                seasonNames = seasonDataList // Correcto: List<SeasonData>?
            )

        } else {
            log("No se encontraron pestañas de temporada. Asumiendo película o serie de una sola página.")
            val episodeLinks = doc.select("div[align=\"center\"] > a")

            if (episodeLinks.isNotEmpty()) {
                log("Encontrados ${episodeLinks.size} enlaces de episodios en la página principal.")
                val episodesForSingleSeason = ArrayList<Episode>()
                episodeLinks.forEach { episodeLink ->
                    val epTitle = episodeLink.text().trim()
                    val epUrl = fixUrl(episodeLink.attr("href")) ?: ""

                    if (epUrl.isBlank()) {
                        log("Advertencia: URL de episodio vacía para elemento: ${episodeLink.html().take(100)}. Saltando.")
                        return@forEach
                    }

                    val episodeNumber = Regex("""-(\d+)(?:\.html)?$""").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""Episodio\s*(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
                // CORRECCIÓN CLAVE AQUÍ: SeasonData solo toma season y name.
                seasonDataList.add(SeasonData(season = 1, name = "Temporada 1"))

                val recommendations = doc.select("div.item").mapNotNull { recElement ->
                    val recTitle = recElement.selectFirst("h3 a")?.text()
                    val recLink = recElement.selectFirst("a")?.attr("href")
                    val recPoster = recElement.selectFirst("img")?.attr("data-src")
                        ?: recElement.selectFirst("img")?.attr("src")

                    if (recTitle != null && recLink != null && recPoster != null) {
                        val fixedRecLink = fixUrl(recLink)
                        val fixedRecPoster = fixUrl(recPoster)

                        if (fixedRecLink.isNullOrBlank()) {
                            log("Advertencia: Link fijo vacío para recomendación: $recTitle")
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
                        null
                    }
                }
                // Si encontramos episodios aquí, significa que es una serie.
                return TvSeriesLoadResponse(
                    name = title,
                    url = cleanUrl,
                    apiName = this.name,
                    type = TvType.TvSeries,
                    episodes = allEpisodes, // Correcto
                    posterUrl = fixUrl(poster),
                    year = year,
                    plot = plot, // Correcto: String?
                    backgroundPosterUrl = fixUrl(poster),
                    tags = tags,
                    recommendations = recommendations,
                    seasonNames = seasonDataList // Correcto: List<SeasonData>?
                )
            } else {
                log("No se encontraron episodios. Asumiendo película.")
                val recommendations = doc.select("div.item").mapNotNull { recElement ->
                    val recTitle = recElement.selectFirst("h3 a")?.text()
                    val recLink = recElement.selectFirst("a")?.attr("href")
                    val recPoster = recElement.selectFirst("img")?.attr("data-src")
                        ?: recElement.selectFirst("img")?.attr("src")

                    if (recTitle != null && recLink != null && recPoster != null) {
                        val fixedRecLink = fixUrl(recLink)
                        val fixedRecPoster = fixUrl(recPoster)

                        if (fixedRecLink.isNullOrBlank()) {
                            log("Advertencia: Link fijo vacío para recomendación: $recTitle")
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
                        null
                    }
                }
                // Si no se encuentran episodios, se asume que es una película.
                return MovieLoadResponse(
                    name = title,
                    url = cleanUrl,
                    apiName = this.name,
                    type = TvType.Movie,
                    posterUrl = fixUrl(poster),
                    backgroundPosterUrl = fixUrl(poster),
                    plot = plot, // Correcto: String?
                    year = year,
                    tags = tags,
                    recommendations = recommendations,
                    dataUrl = cleanUrl
                )
            }
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
            log("URL de episodio/película: $targetUrl")
        } else {
            targetUrl = fixUrl(data) ?: data
            log("URL de episodio/película: $targetUrl")
        }

        if (targetUrl.isBlank()) {
            log("Error: La URL objetivo está en blanco.")
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
            log("Error al obtener el documento para URL: $targetUrl - ${e.message}")
            return false
        }

        val streamerElements = doc.select("li.streamer")

        var foundLinks = false
        if (streamerElements.isNotEmpty()) {
            log("Encontrados ${streamerElements.size} elementos de streamer.")
            streamerElements.apmap { streamerElement ->
                val encodedUrl = streamerElement.attr("data-url") ?: ""
                val serverName = streamerElement.selectFirst("span")?.text()?.replace("OPCI??N ", "Opción ")?.trim()
                    ?: "Servidor Desconocido"

                if (encodedUrl.isNotBlank()) {
                    val base64Part = encodedUrl.substringAfterLast("/")

                    try {
                        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                        val decodedUrl = String(decodedBytes, UTF_8)
                        log("URL decodificada para $serverName: $decodedUrl")

                        loadExtractor(decodedUrl, referer = targetUrl, subtitleCallback = subtitleCallback) { link ->
                            callback(link)
                        }
                        foundLinks = true

                    } catch (e: IllegalArgumentException) {
                        log("Error al decodificar Base64 de $encodedUrl: ${e.message}")
                    } catch (e: Exception) {
                        log("Error general al procesar link de $serverName ($encodedUrl): ${e.message}")
                    }
                } else {
                    log("URL codificada vacía para el elemento streamer.")
                }
            }
        } else {
            log("No se encontraron elementos 'li.streamer'. Buscando alternativas.")

            val iframeSrc = doc.selectFirst("div[id*=\"player_response\"] iframe.metaframe, div.video-player iframe, iframe[src*='stream']")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                log("Encontrado iframe directo: $iframeSrc. Intentando loadExtractor.")
                loadExtractor(iframeSrc, referer = targetUrl, subtitleCallback = subtitleCallback) { link ->
                    callback(link)
                }
                foundLinks = true
            }

            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            val directRegex = """(?:file|src|url):\s*['"](https?:\/\/[^'"]+?\.(?:m3u8|mp4|avi|mkv|mov|mpd|webm))['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                log("Encontrados ${directMatches.size} enlaces directos en scripts. Intentando loadExtractor.")
                directMatches.apmap { directUrl ->
                    loadExtractor(directUrl, referer = targetUrl, subtitleCallback = subtitleCallback) { link ->
                        callback(link)
                    }
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
            "$mainUrl/$url"
        } else {
            url
        }
    }
}