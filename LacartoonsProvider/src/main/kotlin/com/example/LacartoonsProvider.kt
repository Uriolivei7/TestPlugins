package com.example// Asegúrate de que el nombre del paquete sea consistente

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Asegúrate de que NO haya importaciones problemáticas como:
// import com.lagradost.cloudstream3.utils.tryParseDuration
// import com.lagradost.cloudstream3.utils.parseDurationFromString

class LacartoonsProvider : MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LaCartoons"
    override var lang = "es"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homeItems = ArrayList<HomePageList>()

        val latestContent = document.select("div.conjuntos-series a")

        if (latestContent.isNotEmpty()) {
            homeItems.add(HomePageList(
                "Series Recientes",
                latestContent.mapNotNull { item ->
                    val href = item.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
                    val title = item.selectFirst("div.serie h3")?.text()?.trim() ?: ""
                    val poster = item.selectFirst("div.serie img")?.attr("src")?.let { fixUrl(it) }

                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    } else null
                }
            ))
        }

        return HomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl?Titulo=$query").document

        val searchResults = document.select("div.conjuntos-series a")

        return searchResults.mapNotNull { item ->
            val href = item.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val title = item.selectFirst("div.serie h3")?.text()?.trim() ?: ""
            val poster = item.selectFirst("div.serie img")?.attr("src")?.let { fixUrl(it) }

            if (title.isNotEmpty() && href.isNotEmpty()) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.film-name")?.text()?.trim()
            ?: throw ErrorLoadingException("No se pudo obtener el título de la serie.")

        val description = document.selectFirst("div.film-content p.desc")?.text()?.trim()

        val poster = document.selectFirst("div.film-poster img")?.attr("src")?.let { fixUrl(it) }

        val year: Int? = null
        val rating: Int? = null
        val tags: List<String> = emptyList()
        val duration: Int? = null

        val type = if (url.contains("/serie/")) TvType.TvSeries else TvType.TvSeries

        val episodes = ArrayList<Episode>()

        document.select("div.list-season").forEach { seasonDiv ->
            val seasonName = seasonDiv.selectFirst("h3")?.text()?.trim()
            val seasonNumber = seasonName?.replace("Temporada ", "")?.toIntOrNull() ?: 1

            seasonDiv.select("ul.list-episode li a").forEach { epLink ->
                val epUrl = fixUrl(epLink.attr("href"))
                val epTitle = epLink.text().trim()

                val episodeNumber = Regex("episodio\\s*(\\d+)").find(epTitle.lowercase())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("/(\\d+)$").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0

                episodes.add(
                    Episode(
                        data = epUrl,
                        name = epTitle,
                        season = seasonNumber,
                        episode = episodeNumber
                    )
                )
            }
        }

        // CORRECCIÓN PRINCIPAL para el error de Type Mismatch:
        // Usamos sortWith y un comparador lambda explícito para manejar los nulos.
        episodes.sortWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })


        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = type,
            episodes = episodes
        ) {
            this.apiName = this@LacartoonsProvider.name
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.showStatus = null
            this.rating = rating
            this.tags = tags
            this.duration = duration
            this.trailers = mutableListOf()
            this.recommendations = null
            this.actors = null
            this.comingSoon = false
            this.syncData = mutableMapOf()
            this.posterHeaders = null
            this.nextAiring = null
            this.seasonNames = null
            this.backgroundPosterUrl = null
            this.contentRating = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.startsWith("http")) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }

        document.select("script").forEach { script ->
            val scriptText = script.html()

            val playerUrlRegex = Regex("""player_url\s*=\s*['"](https?://[^'"]+)['"]""")
            playerUrlRegex.findAll(scriptText).forEach { match ->
                val embedUrl = match.groupValues[1]
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }

            val onclickRegex = Regex("go_to_playerVast\\('([^']+)'\\)")
            onclickRegex.findAll(scriptText).forEach { match ->
                val embedUrl = match.groupValues[1]
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }

            val videoArrayRegex = Regex("""video\[\d+\] = '([^']+)';""")
            videoArrayRegex.findAll(scriptText).forEach { match ->
                val embedUrl = match.groupValues[1] // Corregido: usar embedUrl
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}