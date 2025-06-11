package com.example.plushdprovider

// IMPORTANTE: Se eliminaron todas las importaciones de 'log'
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class PlushdProvider : MainAPI() {
    override var mainUrl = "https://ww3.pelisplus.to"
    override var name = "Plushd"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon // Agregado Cartoon, ya que los animes/doramas pueden ser series animadas.
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/").document
        val homeItems = ArrayList<HomePageList>()

        // Home Slider (Películas destacadas del carrusel superior)
        val sliderItems = document.select("div.home__slider_index .swiper-slide article a.itemA")
        if (sliderItems.isNotEmpty()) {
            homeItems.add(HomePageList(
                "Destacadas",
                sliderItems.mapNotNull {
                    val title = it.selectFirst("div.home__slider_content h2")?.text() ?: ""
                    val href = fixUrl(it.attr("href"))
                    val poster = it.selectFirst("div.bg")?.attr("style")?.substringAfter("url(\"")?.substringBefore("\")")
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                    }
                }
            ))
        }

        // Función auxiliar para extraer elementos de las secciones de carrusel (Episodios, Películas, Series, Doramas, Animes)
        fun extractCarouselItems(elements: Elements, type: TvType): List<SearchResponse> {
            return elements.mapNotNull {
                val titleSpan = it.selectFirst("div.title_over span")
                val title = titleSpan?.text()?.substringBeforeLast("(")?.trim() ?: ""
                val year = Regex("\\((\\d{4})\\)").find(titleSpan?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
                val href = fixUrl(it.attr("href"))
                val poster = it.selectFirst("img.lazyload")?.attr("data-src")

                if (title.isNotEmpty() && href.isNotEmpty()) {
                    when (type) {
                        TvType.Movie -> newMovieSearchResponse(title, href, type) {
                            this.posterUrl = poster
                            this.year = year
                        }
                        TvType.TvSeries -> newTvSeriesSearchResponse(title, href, type) {
                            this.posterUrl = poster
                            this.year = year
                        }
                        TvType.Anime -> newAnimeSearchResponse(title, href, type) {
                            this.posterUrl = poster
                            this.year = year
                        }
                        else -> null
                    }
                } else null
            }
        }

        // Episodios en Estreno
        val episodes = document.select("h2.h1.main:contains(Episodios En Estreno) + .swiper.mySwiperItems.main .swiper-slide article.item a.itemA")
        if (episodes.isNotEmpty()) {
            homeItems.add(HomePageList("Episodios en Estreno", extractCarouselItems(episodes, TvType.TvSeries)))
        }


        // Últimas Películas Agregadas
        val movies = document.select("h2.h1.main:contains(Ultimas Pelicúlas Agregadas) + .swiper.mySwiperItems.main .swiper-slide article.item a.itemA")
        if (movies.isNotEmpty()) {
            homeItems.add(HomePageList("Últimas Películas Agregadas", extractCarouselItems(movies, TvType.Movie)))
        }

        // Últimas Series Agregadas
        val series = document.select("h2.h1.main:contains(Ultimas Series Agregadas) + .swiper.mySwiperItems.main .swiper-slide article.item a.itemA")
        if (series.isNotEmpty()) {
            homeItems.add(HomePageList("Últimas Series Agregadas", extractCarouselItems(series, TvType.TvSeries)))
        }

        // Últimos Doramas Agregados
        val doramas = document.select("h2.h1.main:contains(Ultimos Doramas Agregadas) + .swiper.mySwiperItems.main .swiper-slide article.item a.itemA")
        if (doramas.isNotEmpty()) {
            homeItems.add(HomePageList("Últimos Doramas Agregados", extractCarouselItems(doramas, TvType.TvSeries)))
        }

        // Últimos Animes Agregados
        val animes = document.select("h2.h1.main:contains(Ultimos Animes Agregados) + .swiper.mySwiperItems.main .swiper-slide article.item a.itemA")
        if (animes.isNotEmpty()) {
            homeItems.add(HomePageList("Últimos Animes Agregados", extractCarouselItems(animes, TvType.Anime)))
        }


        return HomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("div.Posters .Posters-item a.Posters-link").mapNotNull {
            val title = it.attr("data-title") ?: ""
            val href = fixUrl(it.attr("href"))
            val poster = it.selectFirst("img.Posters-img")?.attr("src")

            if (title.isNotEmpty() && href.isNotEmpty()) {
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
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Título: Puede estar en h1.Title o h2 (para algunos items de la página principal que puedan tener URL de detalle)
        val title = document.selectFirst("h1.Title")?.text() ?: document.selectFirst("h2")?.text() ?: ""

        // Póster: Prioridad 1: img.Poster, Prioridad 2: div.bg style, Prioridad 3: img.lazyload data-src
        val poster = document.selectFirst("img.Poster")?.attr("src")
            ?: document.selectFirst("div.bg")?.attr("style")?.substringAfter("url(\"")?.substringBefore("\")")
            ?: document.selectFirst("img.lazyload")?.attr("data-src")

        val description = document.selectFirst("div.Description p")?.text()
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val apiName = this.name

        // Detectar tipo de contenido basándose en la URL
        val type = when {
            url.contains("/pelicula/") -> TvType.Movie
            url.contains("/serie/") || url.contains("/doramas/") -> TvType.TvSeries
            url.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie // Default o caso inesperado
        }

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
        } else { // TvSeries, Anime, Cartoon
            val episodes = document.select("ul.ListEpisodios a").mapNotNull {
                val epTitle = it.text()
                val epUrl = fixUrl(it.attr("href"))
                // La web no da número de temporada o episodio directamente en el enlace del listado,
                // se podría parsear del href si tiene un formato consistente.
                // Ejemplo de URL: https://ww3.pelisplus.to/serie/duster/season/1/episode/4
                val seasonRegex = Regex("/season/(\\d+)/episode/(\\d+)")
                val match = seasonRegex.find(epUrl)
                val season = match?.groupValues?.get(1)?.toIntOrNull()
                val episode = match?.groupValues?.get(2)?.toIntOrNull()
                Episode(epUrl, epTitle, season = season, episode = episode)
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

        // Intenta extraer enlaces de `go_to_playerVast`
        document.select("li[onclick^=go_to_playerVast]").forEach { element ->
            val onclick = element.attr("onclick")
            val regex = Regex("go_to_playerVast\\('([^']+)'\\)")
            val match = regex.find(onclick)
            val embedUrl = match?.groupValues?.get(1)

            if (!embedUrl.isNullOrEmpty()) {
                val serverName = element.selectFirst("span")?.text() ?: "Unknown"
                // Línea de log eliminada aquí
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        // Intenta extraer enlaces de scripts que definen `var video = []`
        document.select("script").forEach { script ->
            val scriptText = script.html()
            val regex = Regex("""video\[\d+\] = '([^']+)';""")
            regex.findAll(scriptText).forEach { match ->
                val embedUrl = match.groupValues[1]
                if (!embedUrl.isNullOrEmpty()) {
                    // Línea de log eliminada aquí
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}