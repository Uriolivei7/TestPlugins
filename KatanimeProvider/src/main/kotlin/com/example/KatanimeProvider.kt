package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.nodes.Element
import android.util.Base64 // Asegúrate de que esta importación exista
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
                doc.select("div#content-left div[class^=_135yj__]").mapNotNull {
                    val anchor = it.selectFirst("a[itemprop=\"url\"]")
                    val link = anchor?.attr("href")
                    val img = it.selectFirst("div[class^=_1-8M9__] img")?.attr("data-src") ?: it.selectFirst("div[class^=_1-8M9__] img")?.attr("src")
                    val titleFull = it.selectFirst("span[class^=_2y8kd etag]")?.text()
                    val seriesTitle = it.selectFirst("div[class^=_2NNxg] a")?.text()

                    if (link != null && titleFull != null && seriesTitle != null) {
                        newAnimeSearchResponse(
                            "$seriesTitle - $titleFull",
                            fixUrl(link)
                        ) {
                            this.type = TvType.Anime
                            this.posterUrl = img
                        }
                    } else null
                }
            } else {
                doc.select("div#content-full div[class^=_135yj__]").mapNotNull {
                    val anchor = it.selectFirst("a[itemprop=\"url\"]")
                    val link = anchor?.attr("href")
                    val img = it.selectFirst("div[class^=_1-8M9__] img")?.attr("data-src") ?: it.selectFirst("div[class^=_1-8M9__] img")?.attr("src")
                    val title = it.selectFirst("div[class^=_2NNxg] a")?.text()

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
        return doc.select("div#article-div div[class*=\"full__2MjKi\"]").mapNotNull {
            val anchor = it.selectFirst("a[itemprop=\"url\"]")
            val title = it.selectFirst("div[class^=_2NNxg] a")?.text()
            val link = anchor?.attr("href")
            val img = it.selectFirst("div[class^=_1-8M9__] img")?.attr("data-src") ?: it.selectFirst("div[class^=_1-8M9__] img")?.attr("src")

            val typeTag = it.selectFirst("span[class^=_2y8kd etag tag]")?.text()
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

        val title = doc.selectFirst("div.elementor-widget-container h1")?.text() ?: ""

        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: doc.selectFirst("div.elementor-widget-image img")?.attr("src")
            ?: ""

        val description = doc.select("div.elementor-widget-theme-post-content div.elementor-widget-container p").joinToString("\n") { it.text() }

        val tags = doc.select("span[class^=_2y8kd etag tag]").map { it.text() }

        val episodes = doc.select("div.elementor-widget-theme-post-content ul li a").mapNotNull { element ->
            val epurl = fixUrl(element.attr("href") ?: "")
            val epTitle = element.text() ?: ""

            val episodeNumberRegex = Regex("""Capítulo\s*(\d+)""") // Ajustado a "Capítulo"
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

    // Función para decodificar Base64
    private fun decodeBase64(encodedString: String): String? {
        return try {
            String(Base64.decode(encodedString, Base64.DEFAULT), UTF_8)
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

        // Primero, busca la lista de opciones de reproductores
        val playerOptions = doc.select("ul#ul-drop-dropcaps li a")

        if (playerOptions.isEmpty()) {
            Log.e("Katanime", "No se encontraron opciones de reproductor en la página: $targetUrl")
            // Fallback: Si no hay opciones de reproductor, intentar buscar iframes directamente como antes.
            val fallbackIframeSrc = doc.selectFirst("div.elementor-widget-container iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*=\"player.\"]")?.attr("src")
                ?: doc.selectFirst("div[id*=\"player\"] iframe")?.attr("src")
                ?: doc.selectFirst("div.video-player iframe")?.attr("src")

            if (!fallbackIframeSrc.isNullOrBlank()) {
                Log.d("Katanime", "Usando iframe de fallback: $fallbackIframeSrc")
                loadExtractor(fixUrl(fallbackIframeSrc), targetUrl, subtitleCallback, callback)
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

        // Si se encontraron opciones de reproductor, procesarlas
        for (option in playerOptions) {
            val encodedUrl = option.attr("data-player")
            val serverName = option.attr("data-player-name") // "Mp4upload", "Mega", etc.

            if (encodedUrl.isNotBlank()) {
                val decodedUrl = decodeBase64(encodedUrl)
                if (decodedUrl != null) {
                    Log.d("Katanime", "Servidor: $serverName, URL decodificada: $decodedUrl")
                    // Pasar la URL decodificada al extractor de CloudStream
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