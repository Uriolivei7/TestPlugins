package com.example // ¡MUY IMPORTANTE! Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.util.Log // Importar Log para los mensajes de depuración
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller // Necesario si el sitio usa Cloudflare
import com.lagradost.cloudstream3.utils.* // Importa todas las utilidades como en NetflixMirrorProvider
import com.lagradost.cloudstream3.APIHolder.unixTime // Importación de unixTime como en NetflixMirrorProvider
import com.lagradost.cloudstream3.utils.AppUtils.toJson // ¡CORREGIDA! Importación explícita de toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson // ¡CORREGIDA! Importación explícita de tryParseJson

import org.jsoup.Jsoup // Importa Jsoup para parsear HTML
import org.jsoup.nodes.Element // Importa Element de Jsoup

// ELIMINADAS: import kotlinx.coroutines.async y kotlinx.coroutines.awaitAll

// ¡CRÍTICO! Añadir esta anotación para que el plugin sea reconocido por CloudStream
class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net" // Asegúrate de que la URL no termine en '/'
    override var name = "SoloLatino" // Alineado con el nombre de tu ejemplo "SoloLatino"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime, // Añadido Anime y Cartoon si los soportas
        TvType.Cartoon,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Cartoons", "$mainUrl/genre_series/toons"), // Ejemplo de categoría de cartoons
        )

        // CORRECCIÓN CRÍTICA: Usando apmap para concurrencia (como en NetflixMirrorProvider)
        val homePageLists = urls.apmap { (name, url) -> // apmap es una extensión de CloudStream
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Cartoons" -> TvType.Cartoon
                else -> TvType.Others
            }
            val doc = app.get(url).document
            val homeItems = doc.select("div.items article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text()
                val link = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset") ?: it.selectFirst("div.poster img")?.attr("src")

                if (title != null && link != null) {
                    // Usando newAnimeSearchResponse como en NetflixMirror
                    newAnimeSearchResponse(
                        title, // Nombre del contenido
                        DataId(fixUrl(link)).toJson() // Datos (URL o ID) como JSON
                    ) {
                        this.type = tvType // Tipo de contenido (Movie, TvSeries, etc.)
                        this.posterUrl = img // URL del póster
                    }
                } else null
            }
            HomePageList(name, homeItems) // Crear HomePageList aquí
        }

        items.addAll(homePageLists)

        // Usando newHomePageResponse (como en tu ejemplo NetflixMirrorProvider)
        return newHomePageResponse(items, false) // El segundo parámetro es para si es una lista horizontal, false por defecto
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").mapNotNull {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset") ?: it.selectFirst("div.poster img")?.attr("src")

            if (title != null && link != null) {
                // Usando newAnimeSearchResponse como en NetflixMirror
                newAnimeSearchResponse(
                    title, // Nombre del contenido
                    DataId(fixUrl(link)).toJson() // Datos (URL o ID) como JSON
                ) {
                    this.type = TvType.TvSeries // Asume TvType.TvSeries para búsquedas
                    this.posterUrl = img
                }
            } else null
        }
    }

    // Data class para pasar datos a newEpisode y loadLinks, como en NetflixMirrorProvider
    data class EpisodeLoadData(
        val title: String,
        val id: String // id (o url) para la data que se pasa
    )

    // Data class para pasar la URL o ID como data en SearchResponse
    data class DataId(
        val url: String // O 'id: String' if SoloLatino uses IDs like NetflixMirror
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }
        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { seasonElement ->
                seasonElement.select("ul.episodios li").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.episodiotitle div.epst")?.text() ?: ""

                    val seasonNumber = element.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull() // Usando toIntOrNull()
                    val episodeNumber = element.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull() // Usando toIntOrNull()

                    val realimg = element.selectFirst("div.imagen img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        // CORRECCIÓN CLAVE: newEpisode en NetflixMirrorProvider toma EpisodeLoadData
                        // y luego un lambda para otras propiedades.
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl) // El primer parámetro 'data' es un objeto
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realimg
                            // runTime también se puede establecer si está disponible
                        }
                    } else null
                }
            }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = url,
                    type = tvType,
                    episodes = episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(
                    name = title,
                    url = url,
                    type = tvType,
                    dataUrl = url
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SoloLatino", "loadLinks llamada para URL: $data")

        // CORRECCIÓN: Parsear el String 'data' a EpisodeLoadData como en NetflixMirrorProvider
        val (_, idForLink) = try { // Usar _ para ignorar titleForLink si no se usa
            tryParseJson<EpisodeLoadData>(data) ?: return false
        } catch (e: Exception) {
            Log.e("SoloLatino", "Error parsing LoadData JSON: ${e.message}")
            return false
        }

        // 1. Intentar obtener el iframe del reproductor
        val iframeSrc = app.get(idForLink).document.selectFirst("iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("SoloLatino", "No se encontró iframe en la página principal del episodio/película.")
            val scriptContent = app.get(idForLink).document.select("script").map { it.html() }.joinToString("\n")

            // CORRECCIÓN: Eliminar el escape redundante '\/'
            val directRegex = """url:\s*['"](https?://[^'"]+)['"]""".toRegex() // Quitado \/
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                // CORRECCIÓN CRÍTICA: Usando apmap para concurrencia (como en NetflixMirrorProvider)
                directMatches.apmap { directUrl -> // apmap es una extensión de CloudStream
                    loadExtractor(directUrl, idForLink, subtitleCallback, callback)
                }
                return true
            }
            return false
        }

        Log.d("SoloLatino", "Iframe encontrado: $iframeSrc")

        // 2. Hacer una petición al src del iframe para obtener su contenido
        val frameDoc = try {
            app.get(fixUrl(iframeSrc)).document
        } catch (e: Exception) {
            Log.e("SoloLatino", "Error al obtener el contenido del iframe ($iframeSrc): ${e.message}")
            return false
        }

        val frameHtml = frameDoc.html()
        Log.d("SoloLatino", "HTML del iframe (fragmento): ${frameHtml.take(500)}...")

        // 3. Aplicar regex para encontrar la URL del reproductor dentro del contenido del iframe
        // CORRECCIÓN: Eliminar el escape redundante '\/'
        val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
        val playerLinks = regex.findAll(frameHtml).map {
            it.groupValues[2]
        }.toList()

        if (playerLinks.isEmpty()) {
            Log.d("SoloLatino", "No se encontraron enlaces go_to_player/go_to_playerVast en el iframe. Intentando buscar un reproductor directo...")
            val videoSrc = frameDoc.selectFirst("video source")?.attr("src") ?: frameDoc.selectFirst("video")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                callback.invoke(
                    // CORRECCIÓN CLAVE: newExtractorLink como en NetflixMirrorProvider: name, label, file, type,
                    // y luego un lambda para referer y quality.
                    newExtractorLink(
                        name, // Nombre del proveedor
                        "Direct Play", // label (fuente)
                        fixUrl(videoSrc), // file (url del video)
                        type = ExtractorLinkType.M3U8 // Tipo de enlace (M3U8 como en NetflixMirrorProvider)
                    ) {
                        this.referer = "$mainUrl/" // Referer (corregido a "$mainUrl/" como en NetflixMirrorProvider)
                        this.quality = Qualities.Unknown.value // Calidad
                    }
                )
                return true
            }

            Log.d("SoloLatino", "No se encontró ningún enlace de video obvio en el iframe ni en scripts.")
            return false
        }

        Log.d("SoloLatino", "Enlaces de reproductor encontrados por regex: $playerLinks")

        // 4. Cargar los enlaces encontrados usando loadExtractor
        // CORRECCIÓN CRÍTICA: Usando apmap para concurrencia (como en NetflixMirrorProvider)
        playerLinks.apmap { playerUrl -> // apmap es una extensión de CloudStream
            Log.d("SoloLatino", "Cargando extractor para: $playerUrl")
            loadExtractor(fixUrl(playerUrl), idForLink, subtitleCallback, callback) // Usar idForLink para el referer de loadExtractor
        }
        return true
    }
}
