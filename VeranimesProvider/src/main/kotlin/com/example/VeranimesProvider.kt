package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlinx.coroutines.delay

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse

import com.lagradost.cloudstream3.utils.loadExtractor

class VerAnimesProvider : MainAPI() {
    override var mainUrl = "https://wwv.veranimes.net"
    override var name = "VerAnimes"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a")
        val titleText = element.selectFirst("h3.h a")?.text()?.trim()

        val link = linkElement?.attr("href")
        val posterElement = element.selectFirst("img")
        val img = posterElement?.attr("src")

        val releaseYear = null

        if (titleText != null && link != null) {
            return newAnimeSearchResponse(
                titleText,
                fixUrl(link)
            ) {
                // Aquí 'this' se refiere al AnimeSearchResponse que se está construyendo
                this.type = TvType.Anime
                this.posterUrl = img
                this.year = releaseYear
            }
        }
        return null
    }

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L
    ): String? {
        for (i in 0 until retries) {
            try {
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs)
                if (res.isSuccessful) return res.text
            } catch (e: Exception) {
                Log.e("VerAnimesProvider", "Error en safeAppGet para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val url = mainUrl
        val html = safeAppGet(url) ?: return null
        val doc = Jsoup.parse(html)

        doc.selectFirst("div.th:has(h2.h:contains(Nuevos episodios agregados)) + div.ul.episodes")?.let { container ->
            val animes = container.select("article.li").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Nuevos Episodios Agregados", animes))
        }

        doc.selectFirst("div.blq:has(h2.h:contains(Últimas Peliculas))")?.let { container ->
            val animes = container.select("ul li").mapNotNull { element ->
                val linkElement = element.selectFirst("a")
                val titleText = element.selectFirst("h3.h a")?.text()?.trim()
                val link = linkElement?.attr("href")
                val posterElement = element.selectFirst("img")
                val img = posterElement?.attr("src")
                val genres = element.select("p.g a").map { it.text().trim() }

                if (titleText != null && link != null) {
                    newAnimeSearchResponse(
                        titleText,
                        fixUrl(link)
                    ) {
                        this.type = TvType.Anime
                    }
                } else null
            }
            if (animes.isNotEmpty()) items.add(HomePageList("Últimas Películas", animes))
        }

        doc.selectFirst("div.blq:has(h2.h:contains(Últimos Ovas))")?.let { container ->
            val animes = container.select("ul li").mapNotNull { element ->
                val linkElement = element.selectFirst("a")
                val titleText = element.selectFirst("h3.h a")?.text()?.trim()
                val link = linkElement?.attr("href")
                val posterElement = element.selectFirst("img")
                val img = posterElement?.attr("src")
                val genres = element.select("p.g a").map { it.text().trim() }

                if (titleText != null && link != null) {
                    newAnimeSearchResponse(
                        titleText,
                        fixUrl(link)
                    ) {
                        this.type = TvType.Anime
                    }
                } else null
            }
            if (animes.isNotEmpty()) items.add(HomePageList("Últimos OVAs", animes))
        }

        doc.selectFirst("div.blq:has(h2.h:contains(Últimos Especiales))")?.let { container ->
            val animes = container.select("ul li").mapNotNull { element ->
                val linkElement = element.selectFirst("a")
                val titleText = element.selectFirst("h3.h a")?.text()?.trim()
                val link = linkElement?.attr("href")
                val posterElement = element.selectFirst("img")
                val img = posterElement?.attr("src")
                val genres = element.select("p.g a").map { it.text().trim() }

                if (titleText != null && link != null) {
                    newAnimeSearchResponse(
                        titleText,
                        fixUrl(link)
                    ) {
                        this.type = TvType.Anime
                    }
                } else null
            }
            if (animes.isNotEmpty()) items.add(HomePageList("Últimos Especiales", animes))
        }

        doc.selectFirst("div.th:has(h2.h:contains(Nuevos Animes Agregados)) + div.ul.x6")?.let { container ->
            val animes = container.select("article.li").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Nuevos Animes Agregados", animes))
        }

        return HomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/animes?buscar=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)

        return doc.select("div.ul.x5 article.li").mapNotNull {
            val title = it.selectFirst("h3.h a")?.text()?.trim()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("img")?.attr("src")

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

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"(?:,"title":"[^"]+")?\}""").find(url)
        if (urlJsonMatch != null) cleanUrl = urlJsonMatch.groupValues[1]
        else if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) cleanUrl = "https://" + cleanUrl.removePrefix("//")

        val animeBaseUrlMatch = Regex("""(.+)/ver/(.+?)(?:-episodio-\d+)?/?$""").find(cleanUrl)
        val finalUrlToFetch = if (animeBaseUrlMatch != null) {
            val baseAnimeName = animeBaseUrlMatch.groupValues[2].substringBeforeLast("-episodio", "")
            if (baseAnimeName.isNotBlank()) "$mainUrl/anime/$baseAnimeName" else cleanUrl
        } else {
            cleanUrl
        }

        if (finalUrlToFetch.isBlank()) {
            Log.e("VerAnimesProvider", "URL final para obtener está en blanco: $cleanUrl")
            return null
        }

        val html = safeAppGet(finalUrlToFetch) ?: run {
            Log.e("VerAnimesProvider", "Falló la obtención del HTML para: $finalUrlToFetch")
            return null
        }
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("div.ti h1 strong")?.text() ?: ""
        val poster = doc.selectFirst("div.sc div.l figure.i img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.tx p")?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""

        val localTags = doc.select("ul.gn li a").map { it.text().trim() }
        val year = doc.selectFirst("div.ti div span.a")?.text()?.trim()?.toIntOrNull()
        val localStatus = parseStatus(doc.selectFirst("div.ti div span.fi")?.text()?.trim() ?: "")

        val additionalTags = mutableListOf<String>()

        val allEpisodes = ArrayList<Episode>()
        val episodeContainers = doc.select("ul.ep li")

        if (episodeContainers.isEmpty()) {
            Log.w("VerAnimesProvider", "No se encontraron episodios con el selector 'ul.ep li' para $finalUrlToFetch")
        } else {
            episodeContainers.mapNotNullTo(allEpisodes) { element ->
                val epLinkElement = element.selectFirst("a")
                val epUrl = fixUrl(epLinkElement?.attr("href") ?: "")
                val epTitleText = epLinkElement?.selectFirst("span")?.text()?.trim() ?: ""

                var episodeNumber: Int? = null
                val episodeNumberMatch = Regex("""\d+""").find(epTitleText)
                episodeNumber = episodeNumberMatch?.value?.toIntOrNull()

                val finalEpTitle = epTitleText.ifBlank { "Episodio ${episodeNumber ?: "Desconocido"}" }

                val epPoster = poster

                if (epUrl.isBlank()) {
                    Log.w("VerAnimesProvider", "Episodio incompleto encontrado: URL en blanco para elemento: ${element.outerHtml().take(100)}")
                    return@mapNotNullTo null
                }

                newEpisode(EpisodeLoadData(finalEpTitle, epUrl).toJson()) {
                    this.name = finalEpTitle
                    this.season = null
                    this.episode = episodeNumber
                    this.posterUrl = epPoster
                    this.description = finalEpTitle
                }
            }
        }

        val finalEpisodes = allEpisodes.reversed()

        val recommendations = doc.select("aside#r div.ul.x2 article.li").mapNotNull { element ->
            val recTitle = element.selectFirst("h3.h a")?.text()?.trim()
            val recLink = element.selectFirst("a")?.attr("href")
            val recImg = element.selectFirst("img")?.attr("src")

            if (recTitle != null && recLink != null && recImg != null) {
                newAnimeSearchResponse(
                    recTitle,
                    fixUrl(recLink)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = recImg
                }
            } else {
                null
            }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = finalUrlToFetch,
            type = TvType.Anime,
            episodes = finalEpisodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = localTags + additionalTags
            this.year = year
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data)

        Log.d("VerAnimesProvider", "loadLinks - URL a cargar: $targetUrl")

        if (targetUrl.isBlank()) {
            Log.e("VerAnimesProvider", "loadLinks - targetUrl está en blanco para data: $data")
            return false
        }

        val initialHtml = safeAppGet(targetUrl) ?: run {
            Log.e("VerAnimesProvider", "loadLinks - Falló la obtención del HTML para: $targetUrl")
            return false
        }
        Log.d("VerAnimesProvider", "loadLinks - HTML recibido (primeros 500 caracteres): ${initialHtml.take(500)}")

        val doc = Jsoup.parse(initialHtml)

        var linksFound = false

        val playerIframe = doc.selectFirst("div.ply iframe")

        if (playerIframe != null) {
            var iframeSrc = playerIframe.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                if (iframeSrc.contains("drive.google.com") && iframeSrc.contains("/preview")) {
                    iframeSrc = iframeSrc.replace("/preview", "/edit")
                    Log.d("VerAnimesProvider", "loadLinks - URL de Google Drive modificada a: $iframeSrc")
                }
                Log.d("VerAnimesProvider", "loadLinks - Extrayendo del iframe principal: $iframeSrc")
                loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
                linksFound = true
            } else {
                Log.w("VerAnimesProvider", "loadLinks - El src del iframe del reproductor principal es nulo/vacío.")
            }
        } else {
            Log.w("VerAnimesProvider", "loadLinks - No se encontró el iframe del reproductor principal con el selector 'div.ply iframe'.")
        }

        doc.select("ul.opt li").forEach { liElement ->
            val encryptedUrlHex = liElement.attr("encrypt")
            val serverName = liElement.attr("title").ifBlank { liElement.selectFirst("span")?.text()?.trim() }

            if (encryptedUrlHex.isNotBlank()) {
                try {
                    val decryptedUrl = encryptedUrlHex.chunked(2)
                        .map { it.toInt(16).toChar() }
                        .joinToString("")

                    if (decryptedUrl.isNotBlank()) {
                        Log.d("VerAnimesProvider", "loadLinks - Extractor del servidor encriptado '$serverName': $decryptedUrl")
                        loadExtractor(fixUrl(decryptedUrl), targetUrl, subtitleCallback, callback)
                        linksFound = true
                    }
                } catch (e: Exception) {
                    Log.e("VerAnimesProvider", "Error al desencriptar URL para el servidor '$serverName': ${e.message}", e)
                }
            }
        }

        val downloadButton = doc.selectFirst("ul.ct a.d")
        downloadButton?.attr("data-dwn")?.let { downloadData ->
            try {
                val jsonArray = tryParseJson<List<List<Any>>>(downloadData)
                jsonArray?.forEach { entry ->
                    if (entry.size >= 3 && entry[2] is String) {
                        val downloadUrl = entry[2] as String
                        val downloadServerName = entry[0] as? String ?: "Enlace de Descarga"
                        if (downloadUrl.isNotBlank()) {
                            Log.d("VerAnimesProvider", "loadLinks - Extractor del enlace de descarga '$downloadServerName': $downloadUrl")
                            loadExtractor(fixUrl(downloadUrl), targetUrl, subtitleCallback, callback)
                            linksFound = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VerAnimesProvider", "Error al parsear los datos de descarga: ${e.message}", e)
            }
        }

        return linksFound
    }

    private fun parseStatus(statusString: String): ShowStatus {
        return when (statusString.lowercase()) {
            "finalizado" -> ShowStatus.Completed
            "en emisión" -> ShowStatus.Ongoing
            "en curso" -> ShowStatus.Ongoing
            else -> ShowStatus.Ongoing
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("/")) {
            mainUrl + url
        } else {
            url
        }
    }

    private fun String.hexToString(): String {
        return chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
    }
}