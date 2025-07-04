package com.example


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink // Importar newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE // Importar INFER_TYPE
import java.util.*
import kotlin.collections.ArrayList


class JkanimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }
    }

    override var mainUrl = "https://jkanime.net"
    override var name = "JKAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()

        val document = app.get(mainUrl).document

        // Últimos episodios (Latest Episodes - from the old structure)
        items.add(
            HomePageList(
                "Últimos episodios",
                document.select(".listadoanime-home a.bloqq").map {
                    val title = it.selectFirst("h5")?.text()
                    val dubstat = if (title!!.contains("Latino") || title.contains("Castellano"))
                        DubStatus.Dubbed else DubStatus.Subbed
                    val poster =
                        it.selectFirst(".anime__sidebar__comment__item__pic img")?.attr("src") ?: ""
                    val epRegex = Regex("/(\\d+)/|/especial/|/ova/")
                    val url = it.attr("href").replace(epRegex, "")
                    val epNum =
                        it.selectFirst("h6")?.text()?.replace("Episodio ", "")?.toIntOrNull()
                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = poster
                        addDubStatus(dubstat, epNum)
                    }
                }
            )
        )

        // Animes (from the new tabbed section)
        document.select("#animes .card").mapNotNull {
            val title = it.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                TvType.Anime,
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("Animes", it))
            }
        }

        // Donghuas (from the new tabbed section)
        document.select("#donghuas .card").mapNotNull {
            val title = it.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                TvType.Anime, // Donghuas are generally TvType.Anime
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("Donghuas", it))
            }
        }

        // OVAs & Películas (from the new tabbed section)
        document.select("#ovas .card").mapNotNull {
            val title = it.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            val typeText = it.selectFirst(".badge.badge-primary")?.text() ?: ""
            val type = when {
                typeText.contains("Pelicula", ignoreCase = true) -> TvType.AnimeMovie
                typeText.contains("OVA", ignoreCase = true) -> TvType.OVA
                typeText.contains("ONA", ignoreCase = true) -> TvType.OVA // ONAs are often grouped with OVAs
                else -> TvType.Anime
            }
            val isDub = title.contains("Latino") || title.contains("Castellano")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                type,
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("OVAs y Películas", it))
            }
        }

        // Animes Recientes (from the new trending_div section)
        document.select(".trending_div .mode3 .p-3.d-flex").mapNotNull {
            val title = it.selectFirst(".card-body-home h5.card-title a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst(".custom_thumb_home a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst(".custom_thumb_home img")?.attr("src") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            val typeText = it.selectFirst(".card-info .badge:not(.currently):not(.finished):not(.notyet)")?.text() ?: ""
            val type = when {
                typeText.contains("Pelicula", ignoreCase = true) -> TvType.AnimeMovie
                typeText.contains("OVA", ignoreCase = true) || typeText.contains("ONA", ignoreCase = true) -> TvType.OVA
                else -> TvType.Anime
            }

            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                type,
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("Animes Recientes", it, false)) // Explicitly set isHorizontal to false
            }
        }


        // Top Animes (from the new .upto and .lower sections)
        document.select(".row.upto .col.toplist, .row.lower .col.toplist").mapNotNull {
            val title = it.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                TvType.Anime, // Assuming most top animes are TV series
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("Top Animes", it))
            }
        }


        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    data class MainSearch(
        @JsonProperty("animes") val animes: List<Animes>,
        @JsonProperty("anime_types") val animeTypes: AnimeTypes
    )

    data class Animes(
        @JsonProperty("id") val id: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("image") val image: String,
        @JsonProperty("synopsis") val synopsis: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("status") val status: String,
        @JsonProperty("thumbnail") val thumbnail: String
    )

    data class AnimeTypes(
        @JsonProperty("TV") val TV: String,
        @JsonProperty("OVA") val OVA: String,
        @JsonProperty("Movie") val Movie: String,
        @JsonProperty("Special") val Special: String,
        @JsonProperty("ONA") val ONA: String,
        @JsonProperty("Music") val Music: String
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val search = ArrayList<SearchResponse>()
        val doc = app.get("$mainUrl/buscar/$query").document

        doc.select(".row.page_directorio .col-lg-2.col-md-6.col-sm-6").mapNotNull {
            val title = it.selectFirst(".anime__item__text h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst(".anime__item a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst(".anime__item__pic.set-bg")?.attr("data-setbg") ?: ""

            val typeText = it.selectFirst(".anime__item__text ul li.anime")?.text()?.trim() ?: "Serie"
            val type = when {
                typeText.contains("Pelicula", ignoreCase = true) -> TvType.AnimeMovie
                typeText.contains("OVA", ignoreCase = true) -> TvType.OVA
                typeText.contains("Especial", ignoreCase = true) -> TvType.OVA
                typeText.contains("ONA", ignoreCase = true) -> TvType.OVA
                else -> TvType.Anime
            }

            val isDub = title.contains("Latino", ignoreCase = true) || title.contains("Castellano", ignoreCase = true)

            search.add(
                AnimeSearchResponse(
                    title,
                    fixUrl(href),
                    this.name,
                    type,
                    fixUrl(poster),
                    null,
                    if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
                )
            )
        }
        return search
    }

    data class EpsInfo (
        @JsonProperty("number" ) var number : String? = null,
        @JsonProperty("title"  ) var title  : String? = null,
        @JsonProperty("image"  ) var image  : String? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document

        val poster = doc.selectFirst(".anime__details__pic.set-bg")?.attr("data-setbg") ?:
        doc.selectFirst("img.anime__details__pic")?.attr("src")

        val title = doc.selectFirst(".anime__details__title > h3")?.text()?.trim()
        val description = doc.selectFirst(".anime__details__text > p")?.text()?.trim()

        val genres = doc.select("div.anime__details__widget ul li")
            .filter { it.text().contains("Géneros:", ignoreCase = true) }
            .flatMap { it.select("a") }
            .map { it.text().trim() }

        val statusText = doc.select("div.anime__details__widget ul li")
            .firstOrNull { it.text().contains("Estado:", ignoreCase = true) }
            ?.text()?.substringAfter("Estado:")?.trim()

        val status = when (statusText) {
            "En emisión" -> ShowStatus.Ongoing
            "En emision" -> ShowStatus.Ongoing
            "Concluido" -> ShowStatus.Completed
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }

        val typeText = doc.select("div.anime__details__widget ul li")
            .firstOrNull { it.text().contains("Tipo:", ignoreCase = true) }
            ?.text()?.substringAfter("Tipo:")?.trim()

        val animeID = doc.selectFirst("div.ml-2")?.attr("data-anime")?.toInt()

        val episodes = ArrayList<Episode>()

        if (animeID != null) {
            val pags = doc.select("a.numbers").map { it.attr("href").substringAfter("#pag") }
            if (pags.isEmpty()) {
                val res = app.get("$mainUrl/ajax/pagination_episodes/$animeID/1/").text
                val json = parseJson<ArrayList<EpsInfo>>(res)
                json.apmap { info ->
                    val imagetest = !info.image.isNullOrBlank()
                    val epPoster = if (imagetest) "https://cdn.jkdesu.com/assets/images/animes/video/image_thumb/${info.image}" else null
                    val link = "${url.removeSuffix("/")}/${info.number}"
                    val ep = Episode(
                        link,
                        name = info.title,
                        posterUrl = epPoster,
                        episode = info.number?.toIntOrNull()
                    )
                    episodes.add(ep)
                }
            } else {
                pags.apmap { pagnum ->
                    val res = app.get("$mainUrl/ajax/pagination_episodes/$animeID/$pagnum/").text
                    val json = parseJson<ArrayList<EpsInfo>>(res)
                    json.apmap { info ->
                        val imagetest = !info.image.isNullOrBlank()
                        val epPoster = if (imagetest) "https://cdn.jkdesu.com/assets/images/animes/video/image_thumb/${info.image}" else null
                        val link = "${url.removeSuffix("/")}/${info.number}"
                        val ep = Episode(
                            link,
                            name = info.title,
                            posterUrl = epPoster,
                            episode = info.number?.toIntOrNull()
                        )
                        episodes.add(ep)
                    }
                }
            }
        }

        episodes.sortBy { it.episode }

        return newAnimeLoadResponse(title ?: "Unknown Title", url, getType(typeText ?: "Serie")) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
        }
    }


    data class Nozomi(
        @JsonProperty("file") val file: String?
    )

    // Marcar streamClean como suspend para que pueda llamar a newExtractorLink
    private suspend fun streamClean(
        name: String,
        url: String,
        referer: String,
        quality: String?,
        callback: (ExtractorLink) -> Unit,
        m3u8: Boolean
    ): Boolean {
        callback(
            newExtractorLink(
                name,
                name,
                url,
                type = if (m3u8) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.quality = getQualityFromName(quality)
                this.headers = mapOf("Referer" to referer)
            }
        )
        return true
    }


    private fun fetchjkanime(text: String?): List<String> {
        if (text.isNullOrEmpty()) {
            return listOf()
        }
        val linkRegex =
            Regex("""(iframe.*class.*width)""")
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"").replace(Regex("(iframe(.class|.src=\")|=\"player_conte\".*src=\"|\".scrolling|\".width)"),"") }.toList()
    }



    data class ServersEncoded (
        @JsonProperty("remote" ) val remote : String,
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        app.get(data).document.select("script").apmap { script ->

            if (script.data().contains(Regex("slug|remote"))) {
                val serversRegex = Regex("\\[\\{.*?\"remote\".*?\"\\}\\]")
                val servers = serversRegex.findAll(script.data()).map { it.value }.toList().first()
                val serJson = parseJson<ArrayList<ServersEncoded>>(servers)
                serJson.apmap {
                    val encodedurl = it.remote
                    val urlDecoded = base64Decode(encodedurl)
                    loadExtractor(urlDecoded, mainUrl, subtitleCallback, callback)
                }

            }


            if (script.data().contains("var video = []")) {
                val videos = script.data().replace("\\/", "/")
                fetchjkanime(videos).map { it }.toList()
                fetchjkanime(videos).map {
                    it.replace("$mainUrl/jkfembed.php?u=", "https://embedsito.com/v/")
                        .replace("$mainUrl/jkokru.php?u=", "http://ok.ru/videoembed/")
                        .replace("$mainUrl/jkvmixdrop.php?u=", "https://mixdrop.co/e/")
                        .replace("$mainUrl/jk.php?u=", "$mainUrl/")
                        .replace("/jkfembed.php?u=","https://embedsito.com/v/")
                        .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                        .replace("/jkvmixdrop.php?u=", "https://mixdrop.co/e/")
                        .replace("/jk.php?u=", "$mainUrl/")
                        .replace("/um2.php?","$mainUrl/um2.php?")
                        .replace("/um.php?","$mainUrl/um.php?")
                        .replace("=\"player_conte\" src=", "")
                }.apmap { link ->
                    fetchUrls(link).forEach {links ->
                        loadExtractor(links, data, subtitleCallback, callback)
                        if (links.contains("um2.php")) {
                            val doc = app.get(links, referer = data).document
                            val gsplaykey = doc.select("form input[value]").attr("value")
                            app.post(
                                "$mainUrl/gsplay/redirect_post.php",
                                headers = mapOf(
                                    "Host" to "jkanime.net",
                                    "User-Agent" to USER_AGENT,
                                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                    "Accept-Language" to "en-US,en;q=0.5",
                                    "Referer" to link,
                                    "Content-Type" to "application/x-www-form-urlencoded",
                                    "Origin" to "https://jkanime.net",
                                    "DNT" to "1",
                                    "Connection" to "keep-alive",
                                    "Upgrade-Insecure-Requests" to "1",
                                    "Sec-Fetch-Dest" to "iframe",
                                    "Sec-Fetch-Mode" to "navigate",
                                    "Sec-Fetch-Site" to "same-origin",
                                    "TE" to "trailers",
                                    "Pragma" to "no-cache",
                                    "Cache-Control" to "no-cache",
                                ),
                                data = mapOf(Pair("data", gsplaykey)),
                                allowRedirects = false
                            ).okhttpResponse.headers.values("location").apmap { loc ->
                                val postkey = loc.replace("/gsplay/player.html#", "")
                                val nozomitext = app.post(
                                    "$mainUrl/gsplay/api.php",
                                    headers = mapOf(
                                        "Host" to "jkanime.net",
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                                        "Accept-Language" to "en-US,en;q=0.5",
                                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                        "X-Requested-With" to "XMLHttpRequest",
                                        "Origin" to "https://jkanime.net",
                                        "DNT" to "1",
                                        "Connection" to "keep-alive",
                                        "Sec-Fetch-Dest" to "empty",
                                        "Sec-Fetch-Mode" to "cors",
                                        "Sec-Fetch-Site" to "same-origin",
                                    ),
                                    data = mapOf(Pair("v", postkey)),
                                    allowRedirects = false
                                ).text
                                val json = parseJson<Nozomi>(nozomitext)
                                val nozomiurl = listOf(json.file)
                                if (nozomiurl.isEmpty()) null else
                                    nozomiurl.forEach { url ->
                                        if (url != null) {
                                            streamClean( // Llamada a la función suspend
                                                "Nozomi",
                                                url,
                                                "",
                                                null,
                                                callback,
                                                url.contains(".m3u8")
                                            )
                                        }
                                    }
                            }
                        }
                        if (links.contains("um.php")) {
                            val desutext = app.get(links, referer = data).text
                            val desuRegex = Regex("((https:|http:)//.*\\.m3u8)")
                            val file = desuRegex.find(desutext)?.value
                            val namedesu = "Desu"
                            generateM3u8(
                                namedesu,
                                file!!,
                                mainUrl,
                            ).forEach { desurl ->
                                streamClean( // Llamada a la función suspend
                                    namedesu,
                                    desurl.url,
                                    mainUrl,
                                    desurl.quality.toString(),
                                    callback,
                                    true
                                )
                            }
                        }
                        if (links.contains("jkmedia")) {
                            app.get(
                                links,
                                referer = data,
                                allowRedirects = false
                            ).okhttpResponse.headers.values("location").apmap { xtremeurl ->
                                val namex = "Xtreme S"
                                streamClean( // Llamada a la función suspend
                                    namex,
                                    xtremeurl,
                                    "",
                                    null,
                                    callback,
                                    xtremeurl.contains(".m3u8")
                                )
                            }
                        }
                    }
                }
            }

        }

        return true
    }
}