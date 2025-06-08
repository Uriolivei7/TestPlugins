package com.example // ¡MUY IMPORTANTE! Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.util.Log // Importar Log para depuración
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// ¡CRÍTICO! Añadir esta anotación para que el plugin sea reconocido por CloudStream
class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino" // Nombre más amigable para el usuario
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
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
            Pair("Cartoons", "$mainUrl/genre_series/toons"),
        )

        val homePageLists = urls.apmap { (name, url) ->
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
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link) // Pasa la URL directamente
                    ) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else null
            }
            HomePageList(name, homeItems)
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").mapNotNull {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset") ?: it.selectFirst("div.poster img")?.attr("src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link) // Pasa la URL directamente
                ) {
                    this.type = TvType.TvSeries // Asume TvType.TvSeries para búsquedas, puedes ajustar esto si sabes el tipo real
                    this.posterUrl = img
                }
            } else null
        }
    }

    // Data class para pasar datos a newEpisode y loadLinks cuando es un episodio
    data class EpisodeLoadData(
        val title: String,
        val url: String // Usamos 'url' para mayor claridad
    )

    override suspend fun load(url: String): LoadResponse? {
        // --- INICIO DE LA CORRECCIÓN CLAVE ---
        Log.d("SoloLatino", "load - URL de entrada: $url")

        var cleanUrl = url
        // Intentar limpiar la URL si viene envuelta en {"url":"..."}
        // Esta regex asegura que el contenido capturado sea una URL válida con http/https
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("SoloLatino", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            // Si no viene en formato JSON, intenta limpiar la URL para que siempre tenga un esquema
            // Esto es importante para el caso de search() que devuelve la URL directa.
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//") // Asume HTTPS y quita // si existe
                Log.d("SoloLatino", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("SoloLatino", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("SoloLatino", "load - ERROR: URL limpia está en blanco.")
            return null
        }
        // --- FIN DE LA CORRECCIÓN CLAVE ---

        // Usa 'cleanUrl' en lugar de 'url' para todas las operaciones posteriores
        val doc = app.get(cleanUrl).document
        val tvType = if (cleanUrl.contains("peliculas")) TvType.Movie else TvType.TvSeries // Puede que necesites una lógica más robusta aquí
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
                        ?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                    val episodeNumber = element.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()

                    val realimg = element.selectFirst("div.imagen img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson() // Pasa EpisodeLoadData como JSON
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realimg
                        }
                    } else null
                }
            }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = cleanUrl, // Usar cleanUrl
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
                    url = cleanUrl, // Usar cleanUrl
                    type = tvType,
                    dataUrl = cleanUrl // Usar cleanUrl
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
        Log.d("SoloLatino", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        // PRIMERO: Intenta limpiar la cadena que viene con "url":"..." o cualquier otro formato.
        // Esta regex es muy permisiva y capturará la primera URL http/https encontrada.
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("SoloLatino", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("SoloLatino", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String

        // SEGUNDO: Intenta parsear la data limpia como JSON para episodios.
        // Si no es un JSON de episodio, asume que 'cleanedData' es una URL directa (para películas).
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url // Usa 'url' del EpisodeLoadData
            Log.d("SoloLatino", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            // Si no es JSON de episodio, 'cleanedData' YA DEBE SER una URL directa (para películas).
            // APLICA fixUrl AQUÍ para asegurar que tenga el esquema correcto si viene de otra fuente
            targetUrl = fixUrl(cleanedData)
            Log.d("SoloLatino", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("SoloLatino", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        // El resto del código de loadLinks ahora usará 'targetUrl'
        // para todas las peticiones HTTP y como 'referer' para extractores.

        // Obtener el documento de la página principal de SoloLatino.net
        val doc = app.get(targetUrl).document

        // 1. Intentar obtener el iframe del reproductor usando el selector más específico
        val iframeSrc = doc.selectFirst("div#dooplay_player_response_1 iframe.metaframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("SoloLatino", "No se encontró iframe del reproductor con el selector específico en SoloLatino.net. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback) // 'targetUrl' como referer
                }
                return true
            }
            Log.d("SoloLatino", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("SoloLatino", "Iframe encontrado: $iframeSrc")

        // 2. Hacer una petición al src del iframe (embed69.org) para obtener su contenido
        val frameDoc = try {
            app.get(fixUrl(iframeSrc)).document
        } catch (e: Exception) {
            Log.e("SoloLatino", "Error al obtener el contenido del iframe ($iframeSrc): ${e.message}")
            return false
        }

        // --- INICIO DE LA NUEVA LÓGICA PARA embed69.org ---
        // La imagen muestra botones para StreamWish, Filemoon, Voe, Vidhide, Download.
        // Necesitamos extraer las URLs de esos botones.
        val playerButtonLinks = mutableListOf<String>()

        // Ejemplo de selector para los botones de reproductores en embed69.org
        // Suponiendo que son divs con la clase 'item-host' y un atributo 'data-url' o similar
        // O que son enlaces <a> directos. Necesitamos inspeccionar el HTML de embed69.org para esto.

        // Por ahora, vamos a intentar con un selector genérico para div con clase que contenga un enlace o data-url.
        // Basado en la imagen, parecen ser <a> tags o div con onclick/data-url.
        // Necesitarás *inspeccionar el HTML REAL* de embed69.org en tu navegador para obtener los selectores correctos.
        // Por ejemplo, si son <a href="https://streamwish.to/...">
        val extractorLinks = frameDoc.select("div.server-item a").mapNotNull { // Este selector es una suposición, ajusta según el HTML real
            it.attr("href")
        }.ifEmpty {
            // Si no son enlaces directos, podrían ser divs con un atributo de datos, o onclick
            // Por ejemplo, si el HTML es <div class="server-item" data-url="https://streamwish.to/...">
            frameDoc.select("div.server-item").mapNotNull {
                it.attr("data-url").ifBlank { null }
            }
        }

        if (extractorLinks.isNotEmpty()) {
            Log.d("SoloLatino", "Enlaces de reproductores encontrados en embed69.org: $extractorLinks")
            extractorLinks.apmap { playerUrl ->
                Log.d("SoloLatino", "Cargando extractor para link de embed69.org: $playerUrl")
                loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback) // iframeSrc como referer para el extractor
            }
            return true
        }
        // --- FIN DE LA NUEVA LÓGICA PARA embed69.org ---


        Log.d("SoloLatino", "No se encontraron enlaces de reproductores en embed69.org.")
        return false
    }
}