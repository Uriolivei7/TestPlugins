package com.example // ¡IMPORTANTE! Asegúrate de que este paquete coincida con la ubicación real de tu archivo y con LacartoonsPlugin.kt

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
// import com.lagradost.cloudstream3.fixUrlNull // Comenta o elimina si sigue dando error de "Too many arguments"
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse // ¡NUEVA IMPORTACIÓN NECESARIA!
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
// Importa extensiones de URL si necesitas una solución más robusta para fixUrl
// import com.lagradost.cloudstream3.utils.toAbsoluteUrl // Si está disponible en tu versión de CS3

/**
 * Clase principal del proveedor LaCartoons para Cloudstream.
 * Implementa MainAPI para manejar búsquedas, carga de contenido y enlaces.
 */
class LaCartoonsProvider : MainAPI() {
    override var name = "LaCartoons"
    override var mainUrl = "https://www.lacartoons.com"
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "es"

    // Función para corregir URLs relativas a absolutas
    private fun fixUrl(url: String): String {
        // La solución más genérica si fixUrlNull sigue dando errores.
        // Asume que si no empieza con "http", es una URL relativa a mainUrl.
        return if (url.startsWith("http")) url else mainUrl + url
        // Alternativa si tu CS3 la soporta y 'fixUrlNull' falla:
        // return url.toAbsoluteUrl(mainUrl) ?: url // Necesitarías importar 'toAbsoluteUrl'
    }

    // Implementación de getMainPage corregida para devolver HomePageResponse?
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Por ahora, solo devuelve null para que compile, resolviendo el error de tipo de retorno.
        return null
    }

    // Función de búsqueda: Busca series/películas en el sitio web
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

    // Función para cargar la información detallada de una serie/película
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.titulo-principal-serie")?.text()?.trim() ?: ""
        val plot = document.selectFirst("div.descripcion-serie p")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.portada-serie img")?.attr("src")?.let { fixUrl(it) }

        val episodes = ArrayList<Episode>()

        val seasonElements = document.select("div.temporadas-nav a")
        if (seasonElements.isNotEmpty()) {
            seasonElements.amap { seasonLink ->
                val seasonUrl = seasonLink.attr("href")?.let { fixUrl(it) } ?: return@amap
                val seasonName = seasonLink.text()?.trim()
                val seasonNumber = seasonName?.substringAfter("Temporada ")?.toIntOrNull()

                val seasonDoc = app.get(seasonUrl).document

                val episodeElements = seasonDoc.select("div.episodios-lista a")
                episodeElements.forEachIndexed { index, episodeElement ->
                    val episodeTitle = episodeElement.selectFirst("span.titulo-episodio")?.text()?.trim()
                    val episodeUrl = episodeElement.attr("href")?.let { fixUrl(it) }

                    if (episodeUrl != null && episodeTitle != null) {
                        episodes.add(
                            Episode(
                                data = episodeUrl,
                                name = episodeTitle,
                                season = seasonNumber,
                                episode = index + 1
                            )
                        )
                    }
                }
            }
        } else {
            val episodeElements = document.select("div.episodios-lista a")
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

    // Función para obtener enlaces de streaming de un episodio
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