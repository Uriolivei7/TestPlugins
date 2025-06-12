package com.example

import android.util.Log // Importa la clase Log para depuración

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

    // Constante para el TAG de los logs, facilita el filtrado
    private val TAG = "LaCartoonsProvider"

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) url else mainUrl + url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d(TAG, "getMainPage: Solicitando página principal para la página $page")
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
                Log.d(TAG, "getMainPage: Se encontraron ${parsedList.size} elementos en la página principal.")
                val homePageList = HomePageList("Últimos Agregados", parsedList, true)
                return HomePageResponse(arrayListOf(homePageList))
            } else {
                Log.w(TAG, "getMainPage: No se encontraron elementos en la página principal.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: Error al cargar la página principal", e)
            e.printStackTrace()
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: Buscando contenido para la consulta -> $query")
        val document = app.get("$mainUrl?Titulo=$query").document

        val searchResults = document.select("div.conjuntos-series a")

        return searchResults.mapNotNull { item ->
            val href = item.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val title = item.selectFirst("div.informacion-serie p.nombre-serie")?.text()?.trim() ?: ""
            val poster = item.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            if (title.isNotEmpty() && href.isNotEmpty()) {
                Log.d(TAG, "search: Encontrado resultado -> Título: $title, URL: $href")
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                Log.w(TAG, "search: Resultado de búsqueda inválido o incompleto.")
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load: Cargando contenido para la URL -> $url")
        val document = app.get(url).document

        val title = document.selectFirst("h2.subtitulo-serie-seccion")?.text()?.trim() ?: ""
        Log.d(TAG, "load: Título de la serie/película -> $title")

        val plotElement = document.selectFirst("div.informacion-serie-seccion p:contains(Reseña:)")
        val plot = plotElement?.nextElementSibling()?.text()?.trim() ?: ""
        Log.d(TAG, "load: Reseña/Plot -> $plot")

        val poster = document.selectFirst("div.imagen-serie img")?.attr("src")?.let { fixUrl(it) }
        Log.d(TAG, "load: URL del póster -> $poster")

        val episodes = ArrayList<Episode>()

        val seasonHeaders = document.select("h4.accordion")

        if (seasonHeaders.isNotEmpty()) {
            Log.d(TAG, "load: Se encontraron encabezados de temporada. Procesando por temporadas.")
            seasonHeaders.forEach { seasonHeader ->
                val seasonName = seasonHeader.text()?.trim()
                val seasonNumber = seasonName?.substringAfter("Temporada ")?.toIntOrNull()
                Log.d(TAG, "load: Procesando temporada: $seasonName (Número: $seasonNumber)")

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
                                episode = episodes.size + 1 // Ajustado para un conteo secuencial por temporada
                            )
                        )
                        Log.d(TAG, "load: Añadido episodio S${seasonNumber ?: "N/A"}E${episodes.size} - $episodeTitle -> $episodeUrl")
                    } else {
                        Log.w(TAG, "load: Episodio inválido o incompleto encontrado en temporada $seasonName.")
                    }
                }
            }
        } else {
            Log.d(TAG, "load: No se encontraron encabezados de temporada. Asumiendo episodios sin temporadas.")
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
                    Log.d(TAG, "load: Añadido episodio E${index + 1} - $episodeTitle -> $episodeUrl (Sin temporada)")
                } else {
                    Log.w(TAG, "load: Episodio inválido o incompleto encontrado en lista de episodios (sin temporada).")
                }
            }
        }

        Log.d(TAG, "load: Total de episodios encontrados: ${episodes.size}")
        if (episodes.isEmpty()) {
            Log.w(TAG, "load: ¡Advertencia! No se encontraron episodios para esta URL: $url")
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
        Log.d(TAG, "loadLinks: Iniciando extracción de enlaces para la URL del episodio -> $data")
        val document = app.get(data).document

        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc != null && (iframeSrc.startsWith("http://") || iframeSrc.startsWith("https://"))) {
                Log.d(TAG, "loadLinks: Detectado iframe válido -> $iframeSrc")
                // Delegamos la extracción del video a loadExtractor para CUALQUIER iframe.
                // Esto es crucial para URLs dinámicas generadas por JavaScript como las de cubeembed.rpmvid.com.
                try {
                    val success = loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                    Log.d(TAG, "loadLinks: loadExtractor para $iframeSrc completado con éxito -> $success")
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks: Error al ejecutar loadExtractor para $iframeSrc", e)
                }
            } else {
                Log.w(TAG, "loadLinks: Iframe con src inválido o nulo, ignorando -> ${iframe.attr("src")}")
            }
        }
        Log.d(TAG, "loadLinks: Finalizada la extracción de enlaces para la URL del episodio -> $data")
        return true
    }
}