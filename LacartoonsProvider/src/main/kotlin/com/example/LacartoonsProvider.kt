package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Clase principal del proveedor LaCartoons para Cloudstream.
 * Implementa MainAPI para manejar búsquedas, carga de contenido y enlaces.
 */
class LacartoonsProvider : MainAPI() {

    override var name = "LaCartoons"
    override var mainUrl = "https://www.lacartoons.com"
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "es"

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) url else mainUrl + url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        try {
            val document = app.get(mainUrl).document

            val latestAdded = document.select("div.conjuntos-series a")

            val parsedList = latestAdded.mapNotNull { item ->
                val href = item.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
                val title = item.selectFirst("div.informacion-serie p.nombre-serie")?.text()?.trim() ?: ""
                val poster = item.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

                if (title.isNotEmpty() && href.isNotEmpty()) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else null
            }

            if (parsedList.isNotEmpty()) {
                val homePageList = HomePageList("Últimos Agregados", parsedList, true)
                return HomePageResponse(arrayListOf(homePageList))
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl?Titulo=$query").document

        val searchResults = document.select("div.conjuntos-series a")

        return searchResults.mapNotNull { item ->
            val href = item.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val title = item.selectFirst("div.informacion-serie p.nombre-serie")?.text()?.trim() ?: ""
            val poster = item.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            if (title.isNotEmpty() && href.isNotEmpty()) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2.subtitulo-serie-seccion")?.text()?.trim() ?: ""

        val plotElement = document.selectFirst("div.informacion-serie-seccion p:contains(Reseña:)")
        val plot = plotElement?.nextElementSibling()?.text()?.trim() ?: ""

        val poster = document.selectFirst("div.imagen-serie img")?.attr("src")?.let { fixUrl(it) }

        val episodes = ArrayList<Episode>()

        val seasonHeaders = document.select("h4.accordion")

        seasonHeaders.forEach { seasonHeader ->
            val seasonName = seasonHeader.text()?.trim()
            val seasonNumber = seasonName?.substringAfter("Temporada ")?.toIntOrNull()

            val episodeList = seasonHeader.nextElementSibling()?.select("ul.listas-de-episodion")?.first()

            episodeList?.select("a")?.forEach { episodeElement ->
                val episodeTitle = episodeElement.text()?.trim() ?: ""
                val episodeUrl = episodeElement.attr("href")?.let { fixUrl(it) }

                if (episodeUrl != null && episodeTitle.isNotEmpty()) {
                    episodes.add(
                        Episode(
                            data = episodeUrl,
                            name = episodeTitle,
                            season = seasonNumber,
                            episode = episodes.size + 1
                        )
                    )
                }
            }
        }

        if (episodes.isEmpty()) {
            val singleSeasonEpisodeElements = document.select("div.episodios-lista a")
            singleSeasonEpisodeElements.forEachIndexed { index, episodeElement ->
                val episodeTitle = episodeElement.selectFirst("span.titulo-episodio")?.text()?.trim()
                val episodeUrl = episodeElement.attr("href")?.let { fixUrl(it) }

                if (episodeUrl != null && episodeTitle != null) {
                    episodes.add(
                        Episode(
                            data = episodeUrl,
                            name = episodeTitle,
                            episode = index + 1
                        )
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc != null && (iframeSrc.startsWith("http://") || iframeSrc.startsWith("https://"))) {
                if (iframeSrc.contains("cubeembed.rpmvid.com")) {
                    val parsedUri = URI(iframeSrc)
                    val domain = parsedUri.scheme + "://" + parsedUri.host
                    val masterM3u8Url = "$domain/master.m3u8"

                    // Define los encabezados HTTP, incluyendo el User-Agent
                    val headers = mapOf(
                        "Referer" to iframeSrc,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                    )

                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Stream Principal",
                            url = masterM3u8Url,
                            referer = iframeSrc, // Este referer es el que se pasa al constructor, pero el `headers` map es el que realmente se usa para la petición
                            quality = 1080,
                            headers = headers, // <-- ¡Pasa el mapa de encabezados aquí!
                            type = ExtractorLinkType.M3U8
                        )
                    )

                } else {
                    loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}