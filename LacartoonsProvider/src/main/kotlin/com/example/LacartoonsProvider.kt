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

            // Usamos el mismo selector que en la búsqueda para el ejemplo de la página principal.
            // Asegúrate que 'div.conjuntos-series a' y sus elementos internos existan en la PÁGINA PRINCIPAL
            // de LaCartoons.com para que esto funcione correctamente en Home.
            val latestAdded = document.select("div.conjuntos-series a") // Ajustado basado en image_03cc52.png

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
        // La sinopsis no es obvia en la imagen f95011.png. Si hay una sección de "Reseña:",
        // podrías buscar el párrafo dentro de ella, pero no hay un selector claro.
        // Asumiendo que el texto del plot está en algún <p> dentro de "informacion-serie-seccion".
        // NECESITARÁS VERIFICAR ESTO MANUALMENTE EN LA PÁGINA WEB.
        val plot = document.selectFirst("div.informacion-serie-seccion p:contains(Reseña:)")?.nextElementSibling()?.text()?.trim()
            ?: document.selectFirst("div.informacion-serie-seccion p:last-of-type")?.text()?.trim() // Último p en esa sección
            ?: "" // Si no se encuentra, dejar vacío

        val poster = document.selectFirst("div.imagen-serie img")?.attr("src")?.let { fixUrl(it) }

        val episodes = ArrayList<Episode>()

        // Basado en image_f8fd99.png y el JavaScript, las temporadas usan un acordeón
        // y están cargadas en la misma página.
        val seasonHeaders = document.select("h4.accordion") // Selectores para las cabeceras de temporada

        seasonHeaders.forEach { seasonHeader ->
            val seasonName = seasonHeader.text()?.trim()?.replace("Temporada ", "") ?: ""
            val seasonNumber = seasonName.toIntOrNull()

            // Encuentra la lista de episodios asociada a esta cabecera de temporada.
            // El JavaScript indica que 'nextElementSibling' es 'panel' que contiene el 'ul'.
            val episodeList = seasonHeader.nextElementSibling()?.select("ul.listas-de-episodion")?.first() // ¡Verifica 'listas-de-episodion' por la 'n' extra!

            episodeList?.select("a")?.forEach { episodeElement ->
                // Asumiendo que el título del episodio está dentro del <a> o un <span> hijo.
                // Usamos el selector anterior, si no funciona, necesitarás inspeccionar un enlace de episodio.
                val episodeTitle = episodeElement.text()?.trim() // A veces el texto del <a> es el título
                    ?: episodeElement.selectFirst("span.titulo-episodio")?.text()?.trim() // Si hay un span específico
                    ?: ""

                val episodeUrl = episodeElement.attr("href")?.let { fixUrl(it) }

                if (episodeUrl != null && episodeTitle.isNotEmpty()) {
                    episodes.add(
                        Episode(
                            data = episodeUrl,
                            name = episodeTitle,
                            season = seasonNumber,
                            // El número de episodio se puede extraer del texto si está presente (ej. "Episodio 12")
                            // o simplemente usar el índice, que es lo más fiable si no está claro.
                            episode = episodeElement.selectFirst("span.numero-episodio")?.text()?.toIntOrNull() ?: (episodes.size + 1)
                        )
                    )
                }
            }
        }

        // Si no hay cabeceras de temporada (o es una película que se carga como serie de un episodio)
        if (episodes.isEmpty()) {
            val episodeElements = document.select("div.episodios-lista a") // Selector antiguo, verifica si aplica a series sin acordeón
            episodeElements.forEachIndexed { index, episodeElement ->
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
            val iframeSrc = iframe.attr("src")?.let { fixUrl(it) }
            if (iframeSrc != null) {
                loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}