package com.example

import android.util.Log

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
import com.lagradost.cloudstream3.utils.loadExtractor // Sigue siendo necesario para otros extractores
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

// Importaciones ESPECÍFICAS para el Extractor CubeEmbedExtractorInternal
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.Qualities


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
            Log.e(name, "Error en getMainPage: ${e.message}", e)
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.get("$mainUrl?Titulo=$query").document

            val searchResults = document.select("div.conjuntos-series a")

            searchResults.mapNotNull { item ->
                val href = item.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
                val title = item.selectFirst("div.informacion-serie p.nombre-serie")?.text()?.trim() ?: ""
                val poster = item.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

                if (title.isNotEmpty() && href.isNotEmpty()) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else null
            }
        } catch (e: Exception) {
            Log.e(name, "Error en search para '$query': ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
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

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.plot = plot
                this.posterUrl = poster
            }
        } catch (e: Exception) {
            Log.e(name, "Error en load para '$url': ${e.message}", e)
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks: Iniciando extracción de enlaces para la URL del episodio -> $data")
        val document = app.get(data).document

        document.select("iframe[src]").forEachIndexed { index, iframe ->
            val iframeSrc = iframe.attr("src")
            Log.d(name, "loadLinks: Procesando iframe[$index] con src -> $iframeSrc")

            if (iframeSrc != null && (iframeSrc.startsWith("http://") || iframeSrc.startsWith("https://"))) {
                if (iframeSrc.contains("cubeembed.rpmvid.com")) {
                    Log.d(name, "loadLinks: Detectado iframe de CubeEmbed, llamando a CubeEmbedExtractorInternal.handleUrl.")
                    // Llamamos a la función auxiliar que hemos creado dentro del extractor
                    // Ya que no estamos sobrescribiendo directamente 'get', podemos llamar a nuestra propia función.
                    CubeEmbedExtractorInternal().handleUrl(iframeSrc, mainUrl, subtitleCallback, callback)
                } else {
                    Log.d(name, "loadLinks: Llamando a loadExtractor genérico para el iframe -> $iframeSrc")
                    val success = loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
                    Log.d(name, "loadLinks: loadExtractor genérico para $iframeSrc completado con éxito -> $success")
                }

            } else {
                Log.w(name, "loadLinks: Iframe[$index] con src inválido o nulo, ignorando -> '${iframe.attr("src")}'")
            }
        }
        Log.d(name, "loadLinks: Finalizado el procesamiento de enlaces para la URL -> $data")
        return true
    }

    // --- INICIO DEL CÓDIGO DEL EXTRACTOR ANIDADO ---
    class CubeEmbedExtractorInternal : ExtractorApi() {
        override val name = "CubeEmbed"
        override val mainUrl = "https://cubeembed.rpmvid.com"
        override val requiresReferer = false

        // NO HAY 'override fun get' AQUÍ.
        // En su lugar, definimos nuestra propia función suspendida.
        suspend fun handleUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean { // <- Esta función sí retorna Boolean, como espera loadExtractor
            Log.d(name, "handleUrl: Intentando extraer de CubeEmbed -> $url")

            try {
                val success = loadExtractor(
                    url = url,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (success) {
                    Log.d(name, "handleUrl: ExtractorLink enviado con éxito por loadExtractor.")
                } else {
                    Log.e(name, "handleUrl: loadExtractor falló o no encontró enlaces para $url.")
                }
                return success
            } catch (e: Exception) {
                Log.e(name, "Error al extraer de CubeEmbed en handleUrl: ${e.message}", e)
                return false
            }
        }

        // Si tu ExtractorApi (por la decompilación que me pasaste) requiere que sobrescribas
        // una de las versiones de 'getUrl', entonces debes añadirla, pero sin la lógica de extracción.
        // La lógica se delega a 'handleUrl'.

        // Por ejemplo, si el compilador sigue quejándose, podrías añadir esto:
        /*
        override suspend fun get(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Any? { // Coincide con la firma de Object/Any? de la decompilación
            // Simplemente llama a tu función handleUrl
            return handleUrl(url, referer, subtitleCallback, callback)
        }
        */
        // O si te pide la otra firma:
        /*
        override suspend fun get(
            url: String,
            referer: String?
        ): List<ExtractorLink>? {
            // Esta firma no tiene los callbacks directamente, así que no se usa para stream.
            // Si te la exige, devuelves una lista vacía.
            return emptyList()
        }
        */
        // Sin embargo, mi intento actual es evitar el override directo del 'get'
        // y usar 'handleUrl' como un

    }
    // --- FIN DEL CÓDIGO DEL EXTRACTOR ANIDADO ---
}