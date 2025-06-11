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
class LacartoonsProvider : MainAPI() { // <-- Abre la llave de la clase aquí

    override var name = "LaCartoons"
    override var mainUrl = "https://www.lacartoons.com"
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "es"

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) url else mainUrl + url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? { // <-- Esta función DEBE estar dentro
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

    override suspend fun search(query: String): List<SearchResponse> { // <-- Esta función DEBE estar dentro
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

    override suspend fun load(url: String): LoadResponse { // <-- Esta función DEBE estar dentro
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

    override suspend fun loadLinks( // <-- Esta función DEBE estar dentro
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc != null && (iframeSrc.startsWith("http://") || iframeSrc.startsWith("https://"))) {
                // Verifica si la URL del iframe pertenece al dominio del reproductor que hemos identificado
                if (iframeSrc.contains("cubeembed.rpmvid.com")) {
                    // Extrae el dominio base del iframe
                    val parsedUri = URI(iframeSrc)
                    val domain = parsedUri.scheme + "://" + parsedUri.host

                    // Construye la URL del master.m3u8 basándonos en las solicitudes de red
                    val masterM3u8Url = "$domain/master.m3u8"

                    // Añade el ExtractorLink para el stream HLS
                    callback.invoke(
                        ExtractorLink(
                            source = name, // Nombre de tu proveedor (LaCartoons)
                            name = "Stream Principal", // Un nombre descriptivo para el enlace
                            url = masterM3u8Url,
                            referer = iframeSrc, // El referer es crucial para que el servidor permita la reproducción
                            quality = 1080, // Puedes ajustar la calidad o dejarla genérica si master.m3u8 es una lista de variantes
                            type = ExtractorLinkType.M3U8 // Usamos el tipo correcto
                        )
                    )

                } else {
                    // Para otros iframes que no sean de cubeembed.rpmvid.com,
                    // intenta usar loadExtractor si Cloudstream tiene un extractor para ellos.
                    loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
} // <-- Cierra la llave de la clase aquí. ¡Asegúrate de que esta llave esté al final!