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

class OtakustvProvider : MainAPI() {
    override var mainUrl = "https://www1.otakustv.com"
    override var name = "OtakusTV"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val titleElement = element.selectFirst("h2.font-GDSherpa-Bold a")
            ?: element.selectFirst("a")

        val title = titleElement?.attr("title") ?: titleElement?.text()?.trim()
        val link = titleElement?.attr("href")

        val posterElement = element.selectFirst("img.lazyload")
            ?: element.selectFirst("img.img-fluid")
        val img = posterElement?.attr("data-src") ?: posterElement?.attr("src")

        if (title != null && link != null) {
            return newAnimeSearchResponse(
                title,
                fixUrl(link)
            ) {
                this.type = TvType.Anime
                this.posterUrl = img
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
                Log.e("OtakustvProvider", "safeAppGet error for URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val html = safeAppGet(url) ?: return null
        val doc = Jsoup.parse(html)

        doc.selectFirst("div.reciente:has(h3:contains(ANIMES FINALIZADOS))")?.let { container ->
            val animes = container.select(".carusel_ranking .item").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Animes Finalizados", animes))
        }

        doc.selectFirst("div.ranking:has(h3:contains(RANKING))")?.let { container ->
            val animes = container.select(".carusel_ranking .item").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Ranking", animes))
        }

        doc.selectFirst("div.simulcasts:has(h3:contains(SIMULCASTS))")?.let { container ->
            val animes = container.select(".carusel_simulcast .item").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Simulcasts", animes))
        }

        doc.selectFirst("div.latino:has(h3:contains(DOBLADAS AL LATINO))")?.let { container ->
            val animes = container.select(".carusel_latino .item").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Dobladas al Latino", animes))
        }

        doc.selectFirst("div.reciente:has(h3:contains(RECIENTEMENTE AÑADIDO)):not(:has(h3:contains(ANIMES FINALIZADOS)))")?.let { container ->
            val animes = container.select(".carusel_reciente .item").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Recientemente Añadido", animes))
        }

        doc.selectFirst("div.pronto:has(h3:contains(PROXIMAMENTE))")?.let { container ->
            val animes = container.select(".carusel_pronto .item").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Próximamente", animes))
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscador?q=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)

        return doc.select("div.animes_lista div.col-6").mapNotNull {
            val title = it.selectFirst("p.font-GDSherpa-Bold")?.text()?.trim()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("img.lazyload")?.attr("src") ?: it.selectFirst("img.lazyload")?.attr("data-src")

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

        val animeBaseUrlMatch = Regex("""(.+)/episodio-\d+/?$""").find(cleanUrl)
        val finalUrlToFetch = if (animeBaseUrlMatch != null) {
            val base = animeBaseUrlMatch.groupValues[1]
            if (!base.endsWith("/")) "$base/" else base
        } else {
            if (!cleanUrl.endsWith("/")) "$cleanUrl/" else cleanUrl
        }
        if (finalUrlToFetch.isBlank()) return null

        val html = safeAppGet(finalUrlToFetch) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1[itemprop=\"name\"]")?.text() ?: doc.selectFirst("h1.tit_ocl")?.text() ?: ""
        val poster = doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("src")
            ?: doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("data-src") ?: ""
        val description = doc.selectFirst("p.font14.mb-0.text-white.mt-0.mt-lg-2")?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""
        val tags = doc.select("ul.fichas li:contains(Genero:) a").map { it.text() }
        val year = doc.selectFirst("ul.fichas li:contains(Estreno:) strong")?.text()?.trim()?.split("-")?.firstOrNull()?.toIntOrNull()
        val status = parseStatus(doc.selectFirst("ul.fichas li:contains(Estado:) strong")?.text()?.trim() ?: "")

        val additionalTags = mutableListOf<String>()
        val audioText = doc.selectFirst("ul.fichas li:contains(Idioma:) strong")?.text()?.trim()
        if (audioText != null) {
            when {
                audioText.contains("latino", ignoreCase = true) -> additionalTags.add("Doblado Latino")
                audioText.contains("subtitulado", ignoreCase = true) || audioText.contains("sub español", ignoreCase = true) -> additionalTags.add("Subtitulado")
            }
        }

        val allEpisodeUrls = mutableListOf<String>()
        val dropdownMenuLinks = doc.select("div.dropdown-menu a")
        if (dropdownMenuLinks.isNotEmpty()) {
            dropdownMenuLinks.forEach { linkElement ->
                val pageUrl = fixUrl(linkElement.attr("href"))
                if (pageUrl.isNotBlank()) allEpisodeUrls.add(pageUrl)
            }
        } else {
            allEpisodeUrls.add(finalUrlToFetch)
        }

        val allEpisodes = ArrayList<Episode>()
        for (pageUrl in allEpisodeUrls) {
            val pageHtml = safeAppGet(pageUrl) ?: continue
            val pageDoc = Jsoup.parse(pageHtml)

            val episodesOnPage = pageDoc.select("div.row > div[class*=\"col-\"]").mapNotNull { element ->
                val epLinkElement = element.selectFirst("a.item-temp")
                val epUrl = fixUrl(epLinkElement?.attr("href") ?: "")
                val epTitle = element.selectFirst("h2.font-GDSherpa-Bold")?.text()?.trim() ?: "" // Corrected selector for episode title
                val epPoster = epLinkElement?.selectFirst("img.img-fluid")?.attr("src") ?: epLinkElement?.selectFirst("img.img-fluid")?.attr("data-src") ?: ""
                val episodeNumber = epUrl.split("-").lastOrNull()?.toIntOrNull()

                if (epUrl.isNotBlank() && epTitle.isNotBlank()) {
                    newEpisode(EpisodeLoadData(epTitle, epUrl).toJson()) {
                        this.name = epTitle
                        this.season = null
                        this.episode = episodeNumber
                        this.posterUrl = epPoster
                    }
                } else null
            }
            allEpisodes.addAll(episodesOnPage)
        }

        val finalEpisodes = allEpisodes.reversed()

        // --- INICIO: Lógica para "También te puede interesar" ---
        val recommendations = doc.select("div.pl-lg-4.pr-lg-4.mb20 div.row div[class*=\"col-\"]").mapNotNull { element ->
            val recTitle = element.selectFirst("h2.font-GDSherpa-Bold")?.text()?.trim()
            val recLink = element.selectFirst("a.item-temp")?.attr("href")
            val recImg = element.selectFirst("a.item-temp img.img-fluid")?.attr("src") ?: element.selectFirst("a.item-temp img.img-fluid")?.attr("data-src")

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
        // --- FIN: Lógica para "También te puede interesar" ---


        return newTvSeriesLoadResponse(
            name = title,
            url = finalUrlToFetch,
            type = TvType.Anime,
            episodes = finalEpisodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags + additionalTags
            this.year = year
            //this.status = status
            this.recommendations = recommendations // Añadir las recomendaciones aquí
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
        if (targetUrl.isBlank()) return false

        val initialHtml = safeAppGet(targetUrl) ?: return false
        val doc = Jsoup.parse(initialHtml)

        val encryptedValues = mutableSetOf<String>()
        doc.select("select#ssel option").forEach { option ->
            option.attr("value").takeIf { it.isNotBlank() }?.let { encryptedValues.add(it) }
        }
        doc.select("nav.menu_server ul li a").forEach { link ->
            link.attr("rel").takeIf { it.isNotBlank() }?.let { encryptedValues.add(it) }
        }

        var linksFound = false
        encryptedValues.toList().amap { encryptedId: String ->
            val requestUrl = "$mainUrl/play-video?id=$encryptedId"
            val responseJsonString = try {
                app.get(
                    requestUrl,
                    headers = mapOf(
                        "Referer" to targetUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    interceptor = cfKiller
                ).text
            } catch (e: Exception) { null }

            if (!responseJsonString.isNullOrBlank()) {
                try {
                    val iframeHtml = tryParseJson<Map<String, String>>(responseJsonString)?.get("html")
                    if (!iframeHtml.isNullOrBlank()) {
                        var iframeSrc = Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("src")
                        if (!iframeSrc.isNullOrBlank()) {
                            if (iframeSrc.contains("drive.google.com") && iframeSrc.contains("/preview")) {
                                iframeSrc = iframeSrc.replace("/preview", "/edit")
                            }
                            loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
                            linksFound = true
                        }
                    }
                } catch (e: Exception) { Log.e("OtakustvProvider", "Error parsing iframe HTML for encrypted ID: $encryptedId", e) }
            } else {
                Log.w("OtakustvProvider", "Empty or null response for encrypted ID: $encryptedId")
            }
        }

        if (!linksFound) {
            var playerIframeSrc = doc.selectFirst("div.st-vid #result_server iframe#ytplayer")?.attr("src")
            if (!playerIframeSrc.isNullOrBlank()) {
                if (playerIframeSrc.contains("drive.google.com") && playerIframeSrc.contains("/preview")) {
                    playerIframeSrc = playerIframeSrc.replace("/preview", "/edit")
                }
                loadExtractor(fixUrl(playerIframeSrc), targetUrl, subtitleCallback, callback)
                linksFound = true
            }
        }

        return linksFound
    }

    private fun parseStatus(statusString: String): ShowStatus {
        return when (statusString.lowercase()) {
            "finalizado" -> ShowStatus.Completed
            "en emision" -> ShowStatus.Ongoing
            "en progreso" -> ShowStatus.Ongoing
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
}