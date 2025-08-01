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

class OtakuversoProvider : MainAPI() {
    override var mainUrl = "https://otakuverso.net"
    override var name = "Otakuverso"
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
        val titleTextFromH2 = linkElement?.selectFirst("h2.font-GDSherpa-Bold.font15")?.text()?.trim()
        val titleTextFromImgAlt = linkElement?.selectFirst("img.lazyload")?.attr("alt")?.trim()
            ?: linkElement?.selectFirst("img.img-fluid")?.attr("alt")?.trim()
        val finalTitle = titleTextFromH2 ?: titleTextFromImgAlt
        val link = linkElement?.attr("href")
        val posterElement = element.selectFirst("img.lazyload")
            ?: element.selectFirst("img.img-fluid")
        val img = posterElement?.attr("data-src") ?: posterElement?.attr("src")

        val releaseDateText = element.selectFirst("p.font15.mb-0.text-white.mt-2 span.bog")?.text()?.trim()
        val releaseYear = Regex("""\d{4}""").find(releaseDateText ?: "")?.value?.toIntOrNull()

        if (finalTitle != null && link != null) {
            return newAnimeSearchResponse(
                finalTitle,
                fixUrl(link)
            ) {
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
                Log.e("OtakuversoProvider", "safeAppGet error for URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) delay(delayMs)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val url = if (page == 1) "$mainUrl/home" else "$mainUrl/home/page/$page/"
        val html = safeAppGet(url) ?: return null
        val doc = Jsoup.parse(html)

        doc.selectFirst("div.reciente.mt-3:has(h3:contains(EPISODIOS ESTRENO))")?.let { container ->
            val animes = container.select(".row .col-6.col-sm-6.col-md-4.col-lg-3.col-xl-2.pre.text-white.mb20").mapNotNull { extractEpisodeItem(it) }
            if (animes.isNotEmpty()) items.add(HomePageList("Episodios Estreno", animes))
        }

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

    private fun extractEpisodeItem(element: Element): AnimeSearchResponse? {
        try {
            val linkElement = element.selectFirst("a") ?: return null
            val titleElement = element.selectFirst("h2 a") ?: return null

            val title = titleElement.text().trim()
            val episodeUrl = linkElement.attr("href")

            val animeUrl = episodeUrl.substringBeforeLast("/episodio-", episodeUrl)

            val posterElement = element.selectFirst("img.lazyload")
                ?: element.selectFirst("img.img-fluid")
            val img = posterElement?.attr("data-src") ?: posterElement?.attr("src")

            val episodeNumberText = element.selectFirst("p.font15 span.bog")?.text()?.replace("Episodio ", "")?.trim() ?: ""

            return newAnimeSearchResponse(
                title,
                fixUrl(animeUrl)
            ) {
                this.type = TvType.Anime
                this.posterUrl = img
            }
        } catch (e: Exception) {
            Log.e("OtakuversoProvider", "Error extracting episode item: ${e.message}", e)
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscador?q=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)

        return doc.select("div.animes_lista div.col-6").mapNotNull { element ->
            val title = element.selectFirst("p.font-GDSherpa-Bold")?.text()?.trim()
            val link = element.selectFirst("a")?.attr("href")
            val img = element.selectFirst("img.img-fluid")?.attr("src")

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
        val poster = doc.selectFirst("div.img-in img.d-inline-block")?.attr("src")
            ?: doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("src")
            ?: doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("data-src") ?: ""
        val description = doc.selectFirst("p.font14.mb-0.text-white.mt-0.mt-lg-2")?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""

        val tags = doc.select("ul.fichas li:contains(Etiquetas:) a").map { it.text() }
            ?: doc.select("ul.fichas li a").map { it.text() } ?: emptyList()

        val releaseDateText = doc.selectFirst("div.font14.mb-0.text-white.mt-0.mt-lg-2 span.date")?.text()?.trim()
        val year = Regex("""Estreno:\s*(\d{4})""").find(releaseDateText ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val status = parseStatus(doc.selectFirst("span.btn-anime-info.font12.text-white.border.border-white")?.text()?.trim() ?: "")

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

            val episodesOnPage = pageDoc.select("div.row div.col-6.col-sm-6.col-md-4.col-lg-3.col-xl-2.pre.text-white.mb20.text-center").mapNotNull { element ->
                val epLinkElement = element.selectFirst("a.mb5.item-temp")

                if (epLinkElement == null) {
                    Log.w("OtakuversoProvider", "Elemento de episodio sin a.mb5.item-temp: ${element.outerHtml().take(200)}")
                    return@mapNotNull null
                }

                val epUrl = fixUrl(epLinkElement.attr("href")).also {
                    if (it.isBlank()) Log.w("OtakuversoProvider", "EpLinkElement href is blank for an episode item: ${element.outerHtml().take(200)}")
                }

                var episodeNumber: Int? = null
                val episodeUrlMatch = Regex("""episodio-(\d+)""").find(epUrl)
                episodeNumber = episodeUrlMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

                var epTitle: String = element.selectFirst("p.font-GDSherpa-Bold.font14.mb-1.text-left")?.text()?.trim() ?: ""
                var epDescription: String = ""

                if (epTitle.isBlank()) {
                    epTitle = epLinkElement.attr("title")?.trim() ?: ""
                }

                epDescription = element.selectFirst("p.font14.mb-0.mt-2 span.bog")?.text()?.trim() ?: ""

                if (episodeNumber == null) {
                    val titleNumberMatch = Regex("""Episodio (\d+)""").find(epTitle)
                    episodeNumber = titleNumberMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                }

                if (epTitle.isBlank()) {
                    epTitle = element.selectFirst("span.font14.mb-1 a.text-white")?.text()?.trim() ?: ""
                }

                if (epTitle.isBlank() && episodeNumber != null) {
                    epTitle = "Episodio $episodeNumber"
                } else if (epTitle.isBlank()) {
                    epTitle = "Episodio Desconocido"
                }

                if (epTitle.isBlank() || epUrl.isBlank()) {
                    Log.w("OtakuversoProvider", "Episodio incompleto encontrado: URL='$epUrl', Título='$epTitle'")
                    return@mapNotNull null
                }

                val epPoster = epLinkElement.selectFirst("img.img-fluid")?.attr("src")
                    ?: epLinkElement.selectFirst("img.img-fluid")?.attr("data-src") ?: ""

                val episodeData = EpisodeLoadData(epTitle, epUrl)
                Log.d("OtakuversoProvider", "load - Creando episodio con data: ${episodeData.toJson()}")
                newEpisode(episodeData.toJson()) {
                    this.name = epDescription
                    this.season = null
                    this.episode = episodeNumber
                    this.posterUrl = epPoster
                    this.description = epTitle
                }
            }
            allEpisodes.addAll(episodesOnPage)
        }

        val finalEpisodes = allEpisodes.reversed()

        return newTvSeriesLoadResponse(
            name = title,
            url = finalUrlToFetch,
            type = TvType.Anime,
            episodes = finalEpisodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year

            this.recommendations = doc.select("div.pl-lg-4.pr-lg-4.mb20 div.row div.col-6.col-sm-6.col-md-4.col-lg-3.col-xl-2.pre.text-white.mb20").mapNotNull { element ->
                val recTitle = element.selectFirst("h1.font-GDSherpa-Bold.font14.mb-1")?.text()?.trim()
                val recUrl = element.selectFirst("a.item-temp")?.attr("href")
                val recPoster = element.selectFirst("img.img-fluid")?.attr("src")

                if (recTitle != null && recUrl != null) {
                    newAnimeSearchResponse(
                        recTitle,
                        fixUrl(recUrl)
                    ) {
                        this.posterUrl = recPoster
                        this.type = TvType.Anime
                    }
                } else null
            }
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
                } catch (e: Exception) { }
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