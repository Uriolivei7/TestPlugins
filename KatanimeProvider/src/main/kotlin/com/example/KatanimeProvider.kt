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
                // Los ítems están en div#content-left > div#article-div > div[class*="chap__2MjKi"]
                doc.select("div#content-left div#article-div div[class*=\"chap__2MjKi\"]").mapNotNull { itemDiv ->
                    val anchor = itemDiv.selectFirst("a[itemprop=\"url\"][class*=\"_1A2Dc__38LRT\"]") // Más específico para el anchor
                    val link = anchor?.attr("href")
                    // Imagen: div[class*="_1-8M9__"] img
                    val img = itemDiv.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("data-src") ?: itemDiv.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("src")
                    // Título del capítulo: span[class*="_2y8kd"][class*="etag"]
                    val chapterTitle = itemDiv.selectFirst("span[class*=\"_2y8kd\"][class*=\"etag\"]")?.text()
                    // Título de la serie: div[class*="_2NNxg"] a
                    val seriesTitle = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text() // Más específico para el título de la serie

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
                // Los ítems están en div#content-full > div#article-div.recientes > div[class*="extra__2MjKi"]
                doc.select("div#content-full div#article-div.recientes div[class*=\"extra__2MjKi\"]").mapNotNull { itemDiv ->
                    val anchor = itemDiv.selectFirst("a[itemprop=\"url\"][class*=\"_1A2Dc__38LRT\"]")
                    val link = anchor?.attr("href")
                    // Imagen: div[class*="_1-8M9__"] img
                    val img = itemDiv.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("data-src") ?: itemDiv.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("src")
                    // Título del anime: div[class*="_2NNxg"] a
                    val title = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text()

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
        // Los resultados de búsqueda están en div#article-div > div[class*="full__2MjKi"]
        return doc.select("div#article-div div[class*=\"full__2MjKi\"]").mapNotNull { itemDiv ->
            val anchor = itemDiv.selectFirst("a[itemprop=\"url\"][class*=\"_1A2Dc__38LRT\"]")
            // Título: div[class*="_2NNxg"] a
            val title = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text()
            val link = anchor?.attr("href")
            // Imagen: div[class*="_1-8M9__"] img
            val img = itemDiv.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("data-src") ?: itemDiv.selectFirst("div[class*=\"_1-8M9__\"] img")?.attr("src")

            // El tag de tipo (Anime/Pelicula) está en span[class*="_2y8kd"][class*="etag"][class*="tag"]
            val typeTag = itemDiv.selectFirst("span[class*=\"_2y8kd\"][class*=\"etag\"][class*=\"tag\"]")?.text()
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

        val title = doc.selectFirst("h1[class*=\"comics-title\"]")?.text() ?: ""

        val poster = doc.selectFirst("div#animeinfo img[class*=\"lozad\"]")?.attr("data-src")
            ?: doc.selectFirst("div#animeinfo img[class*=\"lozad\"]")?.attr("src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: ""

        val description = doc.selectFirst("div#sinopsis")?.text() ?: ""

        val tags = doc.select("span[class*=\"_2y8kd\"][class*=\"etag\"][class*=\"tag\"]").map { it.text() }

        val episodes = doc.select("div#c_list li a.cap_list").mapNotNull { element ->
            val epurl = fixUrl(element.attr("href") ?: "")
            val epTitle = element.selectFirst("h3[class*=\"entry-title-h2\"]")?.text() ?: ""

            val episodeNumberRegex = Regex("""Capítulo\s*(\d+)""")
            val episodeNumber = episodeNumberRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            val realimg = poster

            if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                newEpisode(
                    EpisodeLoadData(epTitle, epurl).toJson()
                ) {
                    this.name = epTitle
                    this.season = 1
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
            val cleanEncodedString = encodedString.replace("=", "")
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

        val playerOptions = doc.select("ul#ul-drop-dropcaps li a")

        if (playerOptions.isEmpty()) {
            Log.e("Katanime", "No se encontraron opciones de reproductor en la página: $targetUrl")
            // Fallback: Buscar iframe principal directamente
            val fallbackIframeSrc = doc.selectFirst("section#player_section div iframe[class*=\"embed-responsive-item\"]")?.attr("src")
                ?: doc.selectFirst("div.elementor-widget-container iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*=\"player.\"]")?.attr("src")

            if (!fallbackIframeSrc.isNullOrBlank()) {
                Log.d("Katanime", "Usando iframe de fallback: $fallbackIframeSrc")
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