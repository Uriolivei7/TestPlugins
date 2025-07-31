package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DoramasflixProvider : MainAPI() {

    override var mainUrl = "https://doramasflix.co"
    override var name = "Doramasflix"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val mainPageHtml = app.get(mainUrl).text
        val document = Jsoup.parse(mainPageHtml)

        val mainSections = document.select("section.Section--wraper")

        mainSections.forEach { section ->
            val title = section.selectFirst("h2.Section__title")?.text() ?: "Sin título"
            val mediaElements = section.select("div.TPost")

            val mediaList = mediaElements.mapNotNull { element ->
                val linkElement = element.selectFirst("a") ?: return@mapNotNull null
                val href = linkElement.attr("href")
                val posterElement = element.selectFirst("img") ?: return@mapNotNull null
                val posterUrl = posterElement.attr("data-src") ?: posterElement.attr("src")
                val mediaTitle = linkElement.selectFirst("h3.TPost__title")?.text() ?: posterElement.attr("alt")

                val mediaType = if (href.contains("/pelicula/")) TvType.Movie else TvType.AsianDrama

                if (mediaType == TvType.Movie) {
                    newMovieSearchResponse(mediaTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newTvSeriesSearchResponse(mediaTitle, fixUrl(href), TvType.AsianDrama) {
                        this.posterUrl = posterUrl
                    }
                }
            }

            if (mediaList.isNotEmpty()) {
                items.add(HomePageList(title, mediaList))
            }
        }

        if (items.isEmpty()) throw ErrorLoadingException("No se pudieron cargar elementos de la página principal.")
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val searchUrl = "$mainUrl/buscar/?s=$encodedQuery"
        val searchHtml = app.get(searchUrl).text
        val document = Jsoup.parse(searchHtml)

        val searchResults = document.select("div.TPost")

        return searchResults.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            val posterElement = element.selectFirst("img") ?: return@mapNotNull null
            val posterUrl = posterElement.attr("data-src") ?: posterElement.attr("src")
            val title = linkElement.selectFirst("h3.TPost__title")?.text() ?: posterElement.attr("alt")

            val mediaType = if (href.contains("/pelicula/")) TvType.Movie else TvType.AsianDrama

            if (mediaType == TvType.Movie) {
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val detailHtml = app.get(url).text
        val document = Jsoup.parse(detailHtml)

        val title = document.selectFirst("h1.Title--wraper__title")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Sin título"

        val posterUrl = document.selectFirst("div.Hero--media__poster img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("div.Description--wraper__text p")?.text()

        val genres = document.select("div.Hero--genres a").mapNotNull { it.text() }

        val type = if (url.contains("/pelicula/")) TvType.Movie else TvType.AsianDrama

        if (type == TvType.Movie) {
            val movieLinks = document.select("div.TPlayer--servers a.TPlayer--servers__item")
                .mapNotNull { it.attr("href") }

            return newMovieLoadResponse(title, url, type, movieLinks.joinToString(",")) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
            }
        } else {
            val seasons = document.select("div#seasons-list div.T-season--item")
            val episodes = seasons.flatMap { seasonElement ->
                val seasonNumber = seasonElement.attr("data-season").toIntOrNull()
                val episodeElements = seasonElement.select("ul.Eplist--list li a")
                episodeElements.mapNotNull { episodeElement ->
                    val episodeUrl = episodeElement.attr("href")
                    val episodeNumber = episodeElement.attr("data-number").toIntOrNull()
                    val episodeTitle = episodeElement.selectFirst("h3.Title")?.text()
                    newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("https://doramasflix.co")) {
            val episodeHtml = app.get(data).text
            val document = Jsoup.parse(episodeHtml)
            val embedLinks = document.select("div.TPlayer--servers a.TPlayer--servers__item")
                .mapNotNull { it.attr("href") }

            embedLinks.forEach { embedUrl ->
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        } else {
            val movieEmbedLinks = data.split(",").filter { it.isNotEmpty() }
            movieEmbedLinks.forEach { embedUrl ->
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}