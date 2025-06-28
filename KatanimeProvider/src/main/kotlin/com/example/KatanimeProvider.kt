package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay

class KatanimeProvider : MainAPI() {
    override var mainUrl = "https://katanime.net"
    override var name = "Katanime"
    override val supportedTypes = setOf(
        TvType.Anime
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Capítulos Recientes", "$mainUrl/capitulos/page/$page/"),
            Pair("Animes Recientes", "$mainUrl/page/$page/")
        )

        val homePageLists = urls.apmap { (name, url) ->
            val doc = app.get(url).document

            val homeItems = if (name == "Capítulos Recientes") {
                // Selector para "Capítulos recientes":
                // Contenedor: div#content-left div#article-div
                // Item individual: div[class*="_135yj__"][class*="chap__2MjKi"]
                doc.select("div#content-left div#article-div div[class*=\"chap__2MjKi\"]").mapNotNull {
                    val anchor = it.selectFirst("a[itemprop=\"url\"]")
                    val link = anchor?.attr("href")
                    // Imagen: div[class*="_1-8M9__"] img
                    val img = it.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("data-src") ?: it.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("src")
                    // Título del capítulo: span[class*="_2y8kd"][class*="etag"]
                    val chapterTitle = it.selectFirst("span[class*=\"_2y8kd\"][class*=\"etag\"]")?.text()
                    // Título de la serie: div[class*="_2NNxg"] a
                    val seriesTitle = it.selectFirst("div[class*=\"_2NNxg\"] a")?.text()

                    if (link != null && chapterTitle != null && seriesTitle != null) {
                        newAnimeSearchResponse(
                            "$seriesTitle - $chapterTitle", // Combinar título de serie y capítulo
                            fixUrl(link)
                        ) {
                            this.type = TvType.Anime
                            this.posterUrl = img
                        }
                    } else null
                }
            } else {
                // Selector para "Animes recientes":
                // Contenedor: div#content-full div#article-div.recientes
                // Item individual: div[class*="_135yj__"][class*="extra__2MjKi"]
                doc.select("div#content-full div#article-div.recientes div[class*=\"extra__2MjKi\"]").mapNotNull {
                    val anchor = it.selectFirst("a[itemprop=\"url\"]")
                    val link = anchor?.attr("href")
                    // Imagen: div[class*="_1-8M9__"] img
                    val img = it.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("data-src") ?: it.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("src")
                    // Título del anime: div[class*="_2NNxg"] a
                    val title = it.selectFirst("div[class*=\"_2NNxg\"] a")?.text()

                    if (title != null && link != null) {
                        newAnimeSearchResponse(
                            title,
                            fixUrl(link)
                        ) {
                            this.type = TvType.Anime
                            this.posterUrl = img
                        }
                    } else null
                }
            }
            HomePageList(name, homeItems)
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        val doc = app.get(url).document
        // Los resultados de búsqueda están en div#article-div
        // Y los items individuales tienen una clase que contiene "full__2MjKi"
        // Este selector ya parece correcto según la última revisión, pero lo mantengo explícito.
        return doc.select("div#article-div div[class*=\"full__2MjKi\"]").mapNotNull {
            val anchor = it.selectFirst("a[itemprop=\"url\"]")
            // Título: div[class*="_2NNxg"] a
            val title = it.selectFirst("div[class*=\"_2NNxg\"] a")?.text()
            val link = anchor?.attr("href")
            // Imagen: div[class*="_1-8M9__"] img
            val img = it.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("data-src") ?: it.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("src")

            val typeTag = it.selectFirst("span[class*=\"_2y8kd\"][class*=\"etag\"][class*=\"tag\"]")?.text() // Asegurarse de que el tag es el correcto
            val tvType = when {
                typeTag?.contains("Pelicula", ignoreCase = true) == true -> TvType.Movie
                else -> TvType.Anime
            }

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = img
                }
            } else null
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Katanime", "load - URL de entrada: $url")

        val cleanUrl = fixUrl(url)

        if (cleanUrl.isBlank()) {
            Log.e("Katanime", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl).document
        val tvType = TvType.Anime

        // Título de la serie: h1[class*="comics-title"]
        val title = doc.selectFirst("h1[class*=\"comics-title\"]")?.text() ?: ""

        // Poster: img[class*="lozad"] dentro de div#animeinfo
        val poster = doc.selectFirst("div#animeinfo img[class*=\"lozad\"]")?.attr("data-src")
            ?: doc.selectFirst("div#animeinfo img[class*=\"lozad\"]")?.attr("src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: ""

        // Descripción: div#sinopsis
        val description = doc.selectFirst("div#sinopsis")?.text() ?: ""

        // Tags/Géneros: span[id="ranking"] (si es que contiene el género principal)
        val tags = doc.select("span[class*=\"_2y8kd\"][class*=\"etag\"][class*=\"tag\"]").map { it.text() }
        // Si hay otros elementos de tags en la página de detalles, se añadirán aquí.
        // Ej: doc.select("div.genre-list a").map { it.text() }

        // Lógica para extraer episodios.
        // Los episodios están en div#c_list ul li a.cap_list
        val episodes = doc.select("div#c_list li a.cap_list").mapNotNull { element ->
            val epurl = fixUrl(element.attr("href") ?: "")
            // Título del capítulo: h3[class*="entry-title-h2"]
            val epTitle = element.selectFirst("h3[class*=\"entry-title-h2\"]")?.text() ?: ""

            val episodeNumberRegex = Regex("""Capítulo\s*(\d+)""")
            val episodeNumber = episodeNumberRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            val realimg = poster // Usar el poster de la serie para el episodio.

            if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                newEpisode(
                    EpisodeLoadData(epTitle, epurl).toJson()
                ) {
                    this.name = epTitle
                    this.season = 1 // Asumimos temporada 1.
                    this.episode = episodeNumber
                    this.posterUrl = realimg
                }
            } else null
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            type = tvType,
            episodes = episodes,
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    private fun decodeBase64(encodedString: String): String? {
        return try {
            // Asegurarse de que no haya padding incorrecto (a veces hay un '=' extra)
            val cleanEncodedString = encodedString.replace("=", "") // Eliminar padding extra si existe
            String(Base64.decode(cleanEncodedString, Base64.DEFAULT), UTF_8)
        } catch (e: IllegalArgumentException) {
            Log.e("Katanime", "Error al decodificar Base64: ${e.message}", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Katanime", "loadLinks - Data de entrada: $data")

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Katanime", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(data)
            Log.d("Katanime", "loadLinks - URL final (directa): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Katanime", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl).document

        // Buscar la lista de opciones de reproductores
        // Las opciones están en ul#ul-drop-dropcaps li a
        val playerOptions = doc.select("ul#ul-drop-dropcaps li a")

        if (playerOptions.isEmpty()) {
            Log.e("Katanime", "No se encontraron opciones de reproductor en la página: $targetUrl")
            // Fallback: Si no hay opciones de reproductor, intentar buscar iframes directamente como antes.
            // Según las últimas imágenes, el iframe principal está en section#player_section > div > iframe
            val fallbackIframeSrc = doc.selectFirst("section#player_section div iframe[class=\"embed-responsive-item\"]")?.attr("src")
                ?: doc.selectFirst("div.elementor-widget-container iframe")?.attr("src") // Antiguo selector general
                ?: doc.selectFirst("iframe[src*=\"player.\"]")?.attr("src") // Intento general si el anterior falla

            if (!fallbackIframeSrc.isNullOrBlank()) {
                Log.d("Katanime", "Usando iframe de fallback: $fallbackIframeSrc")
                // Si la URL del iframe es la URL del reproductor de Katanime.net, necesitamos ir más profundo
                if (fallbackIframeSrc.contains("katanime.net/reproductor?url=")) {
                    val encodedInnerUrl = fallbackIframeSrc.substringAfter("url=")
                    val decodedInnerUrl = decodeBase64(encodedInnerUrl)
                    if (decodedInnerUrl != null) {
                        Log.d("Katanime", "Iframe de Katanime.net encontrado, URL interna decodificada: $decodedInnerUrl")
                        loadExtractor(fixUrl(decodedInnerUrl), targetUrl, subtitleCallback, callback)
                    } else {
                        Log.e("Katanime", "No se pudo decodificar la URL interna del iframe de Katanime.net: $encodedInnerUrl")
                    }
                } else {
                    loadExtractor(fixUrl(fallbackIframeSrc), targetUrl, subtitleCallback, callback)
                }
                return true
            }

            // Último fallback: buscar videos directos en scripts
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            val directVideoRegex = Regex("""["'](https?:\/\/[^"']+\.(?:mp4|m3u8|avi|mkv|mov|flv|webm))["']""")
            val directVideoMatches = directVideoRegex.findAll(scriptContent)

            if (directVideoMatches.any()) {
                Log.d("Katanime", "Encontrados posibles enlaces de video directos en scripts como fallback.")
                directVideoMatches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    Log.d("Katanime", "Cargando extractor para enlace directo de script (fallback): $videoUrl")
                    loadExtractor(fixUrl(videoUrl), targetUrl, subtitleCallback, callback)
                }
                return true
            }

            return false
        }

        // Si se encontraron opciones de reproductor (lo más probable)
        for (option in playerOptions) {
            val encodedUrl = option.attr("data-player")
            val serverName = option.attr("data-player-name")

            if (encodedUrl.isNotBlank()) {
                val decodedUrl = decodeBase64(encodedUrl)
                if (decodedUrl != null) {
                    Log.d("Katanime", "Servidor: $serverName, URL decodificada: $decodedUrl")
                    loadExtractor(fixUrl(decodedUrl), targetUrl, subtitleCallback, callback)
                } else {
                    Log.e("Katanime", "No se pudo decodificar la URL para el servidor: $serverName, encoded: $encodedUrl")
                }
            } else {
                Log.w("Katanime", "La opción de reproductor '$serverName' no tiene un atributo 'data-player'.")
            }
        }

        return true
    }
}