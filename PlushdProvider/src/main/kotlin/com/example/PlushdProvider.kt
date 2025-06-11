package com.example.plushdprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*
import org.jsoup.nodes.Element

class PlushdProvider : MainAPI() {
    override var mainUrl = "https://ww3.pelisplus.to"
    override var name = "Plushd"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/").document
        val homeItems = ArrayList<HomePageList>()

        // Home Slider (Películas destacadas)
        val sliderItems = document.select("div.home__slider_index .swiper-slide article a.itemA")
        homeItems.add(HomePageList(
            "Destacadas",
            sliderItems.mapNotNull {
                val title = it.selectFirst("h2")?.text() ?: it.selectFirst("span")?.text() ?: ""
                val href = fixUrl(it.attr("href"))
                val poster = it.selectFirst("div.bg")?.attr("style")?.substringAfter("url(\"")?.substringBefore("\")")
                    ?: it.selectFirst("img.lazyload")?.attr("data-src")
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        ))

        // Episodios en estreno
        val episodes = document.select("div#home .mySwiperItems.main .swiper-slide article.item a.itemA")
        homeItems.add(HomePageList(
            "Episodios en Estreno",
            episodes.mapNotNull {
                val title = it.selectFirst("span")?.text() ?: ""
                val href = fixUrl(it.attr("href"))
                val poster = it.selectFirst("img.lazyload")?.attr("data-src")
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        ))

        // Últimas Películas
        val movies = document.select("h2:contains(Ultimas Pelicúlas Agregadas) + .swiper.mySwiperItems .swiper-slide article.item a.itemA")
        homeItems.add(HomePageList(
            "Últimas Películas",
            movies.mapNotNull {
                val title = it.selectFirst("span")?.text() ?: ""
                val href = fixUrl(it.attr("href"))
                val poster = it.selectFirst("img.lazyload")?.attr("data-src")
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        ))

        // Últimas Series
        val series = document.select("h2:contains(Ultimas Series Agregadas) + .swiper.mySwiperItems .swiper-slide article.item a.itemA")
        homeItems.add(HomePageList(
            "Últimas Series",
            series.mapNotNull {
                val title = it.selectFirst("span")?.text() ?: ""
                val href = fixUrl(it.attr("href"))
                val poster = it.selectFirst("img.lazyload")?.attr("data-src")
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        ))

        // Últimos Doramas
        val doramas = document.select("h2:contains(Ultimos Doramas Agregadas) + .swiper.mySwiperItems .swiper-slide article.item a.itemA")
        homeItems.add(HomePageList(
            "Últimos Doramas",
            doramas.mapNotNull {
                val title = it.selectFirst("span")?.text() ?: ""
                val href = fixUrl(it.attr("href"))
                val poster = it.selectFirst("img.lazyload")?.attr("data-src")
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        ))

        // Últimos Animes
        val animes = document.select("h2:contains(Ultimos Animes Agregados) + .swiper.mySwiperItems .swiper-slide article.item a.itemA")
        homeItems.add(HomePageList(
            "Últimos Animes",
            animes.mapNotNull {
                val title = it.selectFirst("span")?.text() ?: ""
                val href = fixUrl(it.attr("href"))
                val poster = it.selectFirst("img.lazyload")?.attr("data-src")
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        ))

        return HomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("article.item a.itemA").mapNotNull {
            val title = it.selectFirst("span")?.text() ?: it.selectFirst("h2")?.text() ?: ""
            val href = fixUrl(it.attr("href"))
            val poster = it.selectFirst("img.lazyload")?.attr("data-src")
            when {
                href.contains("/pelicula/") -> newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
                href.contains("/serie/") || href.contains("/doramas/") -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
                href.contains("/anime/") -> newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                }
                else -> null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h2")?.text() ?: document.selectFirst("span")?.text() ?: ""
        val poster = document.selectFirst("div.bg")?.attr("style")?.substringAfter("url(\"")?.substringBefore("\")")
            ?: document.selectFirst("img.lazyload")?.attr("data-src")
        val description = document.selectFirst(".description p")?.text()
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val type = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val apiName = this.name // Nombre del proveedor como apiName

        return if (type == TvType.Movie) {
            MovieLoadResponse(
                name = title,
                url = url,
                apiName = apiName,
                type = type,
                dataUrl = url,
                posterUrl = poster,
                year = year,
                plot = description,
                rating = null,
                tags = null,
                duration = null,
                trailers = mutableListOf(),
                recommendations = null,
                actors = null,
                comingSoon = false,
                syncData = mutableMapOf(),
                posterHeaders = null,
                backgroundPosterUrl = null,
                contentRating = null
            )
        } else {
            val episodes = document.select("div.episode-list a").mapNotNull {
                val epTitle = it.text()
                val epUrl = fixUrl(it.attr("href"))
                Episode(epUrl, epTitle, season = null, episode = null)
            }
            TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = apiName,
                type = type,
                episodes = episodes,
                posterUrl = poster,
                year = year,
                plot = description,
                showStatus = null,
                rating = null,
                tags = null,
                duration = null,
                trailers = mutableListOf(),
                recommendations = null,
                actors = null,
                comingSoon = false,
                syncData = mutableMapOf(),
                posterHeaders = null,
                nextAiring = null,
                seasonNames = null,
                backgroundPosterUrl = null,
                contentRating = null
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.WatchButton_button__ldxjm a").apmap { element ->
            val link = fixUrl(element.attr("href"))
            loadExtractor(link, data, subtitleCallback, callback)
        }
        return true
    }
}