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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

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

        // Selectores actualizados basados en image_f95011.png
        val title = document.selectFirst("h2.subtitulo-serie-seccion")?.text()?.trim() ?: ""

        // Para el plot (sinopsis), busca la etiqueta "Reseña:" y luego toma el texto del siguiente elemento
        val plotElement = document.selectFirst("div.informacion-serie-seccion p:contains(Reseña:)")
        val plot = plotElement?.nextElementSibling()?.text()?.trim() ?: "" // Asume que la sinopsis es el siguiente hermano

        val poster = document.selectFirst("div.imagen-serie img")?.attr("src")?.let { fixUrl(it) }

        val episodes = ArrayList<Episode>()

        // Basado en image_f8fd99.png y el JavaScript, las temporadas usan un acordeón
        // y están cargadas en la misma página.
        val seasonHeaders = document.select("h4.accordion") // Selector para las cabeceras de temporada

        seasonHeaders.forEach { seasonHeader ->
            // Extrae el número de temporada del texto del encabezado (ej. "Temporada 1")
            val seasonName = seasonHeader.text()?.trim()
            val seasonNumber = seasonName?.substringAfter("Temporada ")?.toIntOrNull()

            // Encuentra la lista de episodios asociada a esta cabecera de temporada.
            // El JavaScript indica que 'nextElementSibling' es 'panel' que contiene el 'ul'.
            val episodeList = seasonHeader.nextElementSibling()?.select("ul.listas-de-episodion")?.first() // ¡CUIDADO con 'listas-de-episodion' por la 'n' extra!

            episodeList?.select("a")?.forEach { episodeElement ->
                // El título del episodio podría ser el texto directo del <a> o dentro de un <span>.
                val episodeTitle = episodeElement.text()?.trim() ?: ""

                val episodeUrl = episodeElement.attr("href")?.let { fixUrl(it) }

                if (episodeUrl != null && episodeTitle.isNotEmpty()) {
                    episodes.add(
                        Episode(
                            data = episodeUrl,
                            name = episodeTitle,
                            season = seasonNumber,
                            episode = episodes.size + 1 // Utiliza el índice si no se puede extraer un número específico
                        )
                    )
                }
            }
        }

        // Caso para series de una sola temporada o películas cargadas como series,
        // donde no hay un acordeón de temporadas y los episodios están directamente listados.
        if (episodes.isEmpty()) {
            val singleSeasonEpisodeElements = document.select("div.episodios-lista a") // Vuelve al selector que tenías
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

        // Intenta encontrar el iframe principal.
        // NO uses fixUrl para el src del iframe si ya es una URL absoluta (https://...).
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc != null && (iframeSrc.startsWith("http://") || iframeSrc.startsWith("https://"))) {
                // Aquí, loadExtractor intentará resolver la URL del iframe.
                // Si rpmvid.com no es soportado, no funcionará.
                loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
            }
        }

        // Si el iframe principal no funciona, la única otra opción viable (sin ejecutar JS)
        // sería buscar una URL de video directa en los scripts o tags <video>.
        // Los scripts que proporcionaste son principalmente para pop-ups, no para el video directo.
        // Si hay un script oculto con el video real, necesitarías encontrar su patrón.

        return true
    }
}