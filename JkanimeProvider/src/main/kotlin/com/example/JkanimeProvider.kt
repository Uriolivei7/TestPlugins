package com.example


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Episode // <--- Asegúrate de que esta importación esté
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
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
        val urls = listOf(
            Pair(
                "$mainUrl/directorio/?filtro=fecha&tipo=TV&estado=1&fecha=none&temporada=none&orden=desc",
                "En emisión"
            ),
            Pair(
                "$mainUrl/directorio/animes/",
                "Animes"
            ),
            Pair(
                "$mainUrl/directorio/peliculas/",
                "Películas"
            ),
        )

        val items = ArrayList<HomePageList>()
        val isHorizontal = true

        // --- Últimos episodios (sección "Programación" / "Trending Anime") ---
        // Basado en image_492d95.png y image_492d38.png
        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl).document.select("div.trending_anime div.mb-4.d-flex.align-items-stretch.mb-3.dir1").mapNotNull {
                    val title = it.selectFirst("h5.strlimit.card-title")?.text()
                    val poster = it.selectFirst("img.card-img-top")?.attr("src")
                    val itemUrl = it.selectFirst("a")?.attr("href") // Declaración aquí

                    // Comprobación de nulos antes de usar itemUrl
                    if (title == null || itemUrl == null) {
                        return@mapNotNull null // Salta si el título o la URL son nulos
                    }

                    val dubstat = if (title.contains("Latino") || title.contains("Castellano"))
                        DubStatus.Dubbed else DubStatus.Subbed

                    val epNumText = it.selectFirst("span.badge.badge-primary")?.text() // "Ep 1"
                    val epNum = epNumText?.replace("Ep ", "")?.toIntOrNull()

                    newAnimeSearchResponse(title, itemUrl) { // Uso de itemUrl
                        this.posterUrl = poster
                        addDubStatus(dubstat, epNum)
                    }
                }.filterNotNull(),
                isHorizontal
            )
        )

        // --- Listados de Directorio (Animes, Películas) ---
        // Basado en image_48d689.png y image_48d6fe.png
        urls.apmap { (url, name) ->
            val soup = app.get(url).document
            val home = soup.select("div.row.model1.autoimagedir div.mb-4.d-flex.align-items-stretch.mb-3.dir1").mapNotNull {
                val title = it.selectFirst("h5.strlimit.card-title")?.text()
                val poster = it.selectFirst("img.card-img-top")?.attr("src")
                val href = it.selectFirst("a")?.attr("href")

                if (title != null && href != null && poster != null) {
                    AnimeSearchResponse(
                        title,
                        fixUrl(href),
                        this.name,
                        TvType.Anime, // Aquí podrías querer usar getType(tipo) si encuentras el tipo en el HTML
                        fixUrl(poster),
                        null,
                        if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                            DubStatus.Dubbed
                        ) else EnumSet.of(DubStatus.Subbed),
                    )
                } else null
            }.filterNotNull() // Asegura que solo se añadan elementos no nulos
            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    /* Las clases MainSearch, Animes, AnimeTypes están comentadas porque no se usan actualmente. */

    override suspend fun search(query: String): List<SearchResponse> {
        val urls = listOf(
            "$mainUrl/buscar/$query/1/",
            "$mainUrl/buscar/$query/2/",
            "$mainUrl/buscar/$query/3/"
        )
        val search = ArrayList<SearchResponse>()
        urls.apmap { ss ->
            val doc = app.get(ss).document
            // Selector actualizado para los resultados de búsqueda, basado en image_48d305.png
            doc.select("div.row.page_directorio div.mb-4.d-flex.align-items-stretch.mb-3.dir1").mapNotNull {
                val title = it.selectFirst("h5.strlimit.card-title")?.text()
                val href = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("img.card-img-top")?.attr("src")

                if (title != null && href != null && img != null) { // Comprobar nulos
                    val isDub = title.contains("Latino") || title.contains("Castellano")
                    search.add(
                        newAnimeSearchResponse(title, href) {
                            this.posterUrl = fixUrl(img)
                            addDubStatus(isDub, !isDub)
                        })
                } else null
            }
        }
        return search.filterNotNull() // Filtrar nulos si se añade alguno accidentalmente
    }

    data class EpsInfo (
        @JsonProperty("number" ) var number : String? = null,
        @JsonProperty("title"  ) var title  : String? = null,
        @JsonProperty("image"  ) var image  : String? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document

        // --- Título y Descripción ---
        // Basado en image_48cb9f.png
        val title = doc.selectFirst("div.anime_info h3 span")?.text()
        val description = doc.selectFirst("div.anime_info p.scroll")?.text()

        // --- Póster ---
        // Este selector es una suposición, si el póster principal no carga, necesitas revisar su selector.
        val poster = doc.selectFirst(".set-bg")?.attr("data-setbg")


        // --- Géneros, Estado, Tipo ---
        // **NUEVOS SELECTORES BASADOS EN image_48d2a9.png Y SUPOSICIONES**
        // AUN PUEDEN NECESITAR AJUSTE AL INSPECCIONAR LA PÁGINA DE DETALLES.
        val genres = doc.select("div.anime_info ul li a[href*='genero']").map { it.text() }

        var status: ShowStatus? = null
        val statusText = doc.select("div.anime_info ul li").firstOrNull {
            it.text().contains("Concluido") || it.text().contains("En emisión") || it.text().contains("En emision")
        }?.text()
        status = when (statusText) {
            "En emisión", "En emision" -> ShowStatus.Ongoing
            "Concluido" -> ShowStatus.Completed
            else -> null
        }

        var type: String? = null
        type = doc.select("div.anime_info ul li").firstOrNull {
            it.text().contains("TV") || it.text().contains("OVA") || it.text().contains("Película") || it.text().contains("ONA") || it.text().contains("Especial")
        }?.text()


        // --- Anime ID para paginación de episodios ---
        // Basado en image_48cb9f.png, el data-anime está en div#guardar_anime
        val animeID = doc.selectFirst("div#guardar_anime")?.attr("data-anime")?.toIntOrNull()


        val episodes = ArrayList<Episode>()

        // --- Paginación de episodios ---
        // Basado en image_48c878.png, los selectores de paginación han cambiado.
        val pags = doc.select("ul.list li[data-value]").map { it.attr("data-value").substringAfter("#pag") }

        pags.apmap { pagnum ->
            if (animeID == null) return@apmap // Si no hay animeID, no se puede cargar la paginación
            val res = app.get("$mainUrl/ajax/pagination_episodes/$animeID/$pagnum/").text
            val json = parseJson<ArrayList<EpsInfo>>(res)
            json.apmap { info ->
                val imagetest = !info.image.isNullOrBlank()
                val image = if (imagetest) "https://cdn.jkdesu.com/assets/images/animes/video/image_thumb/${info.image}" else null

                // Asegúrate de que info.number no sea nulo antes de usarlo
                if (info.number != null) {
                    // Aquí también pasamos 'link' como 'data' en el constructor de Episode
                    val link = "${url.removeSuffix("/")}/${info.number}"
                    val ep = Episode(
                        link, // <-- Usamos 'link' que ahora se mapea a 'data' de Episode
                        name = info.title, // Usa el título del episodio del JSON
                        posterUrl = image
                    )
                    episodes.add(ep)
                }
            }
        }

        // Asegúrate de que title no sea nulo antes de pasarlo a newAnimeLoadResponse
        val finalType = type ?: "Anime"
        if (title == null) throw ErrorLoadingException("Título no encontrado para $url")

        return newAnimeLoadResponse(title, url, getType(finalType)) {
            this.posterUrl = poster
            // MODIFICACIÓN: Usar groupBy y map, y ahora it.data en lugar de it.url
            addEpisodes(DubStatus.Subbed, episodes.groupBy { it.data }.map { it.value.first() })
            this.showStatus = status // Puede ser nulo
            this.plot = description // Puede ser nulo
            this.tags = genres // Puede ser nulo
        }
    }

    data class Nozomi(
        @JsonProperty("file") val file: String?
    )

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
                source = name,
                name = name,
                url = url,
                type = if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO // Corregido a ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = getQualityFromName(quality)
            }
        )
        return true
    }

    private fun fetchjkanime(text: String?): List<String> {
        if (text.isNullOrEmpty()) {
            return listOf()
        }
        val linkRegex =
            Regex("""(iframe.*class.*width)""") // Esta regex puede necesitar ajuste si cambian el formato
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
                val servers = serversRegex.findAll(script.data()).map { it.value }.toList().firstOrNull() // Usa firstOrNull para seguridad
                if (servers != null) {
                    val serJson = parseJson<ArrayList<ServersEncoded>>(servers)
                    serJson.apmap {
                        val encodedurl = it.remote
                        val urlDecoded = base64Decode(encodedurl)
                        loadExtractor(urlDecoded, mainUrl, subtitleCallback, callback)
                    }
                }
            }


            if (script.data().contains("var video = []")) {
                val videos = script.data().replace("\\/", "/")
                // Solo llama a fetchjkanime una vez
                fetchjkanime(videos).apmap { link ->
                    val cleanLink = link
                        .replace("$mainUrl/jkfembed.php?u=", "https://embedsito.com/v/")
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

                    fetchUrls(cleanLink).forEach { links -> // links ya es la URL limpia
                        loadExtractor(links, data, subtitleCallback, callback)

                        if (links.contains("um2.php")) {
                            val doc = app.get(links, referer = data).document
                            val gsplaykey = doc.select("form input[value]").attr("value")
                            if (gsplaykey.isNotBlank()) {
                                app.post(
                                    "$mainUrl/gsplay/redirect_post.php",
                                    headers = mapOf(
                                        "Host" to "jkanime.net",
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                        "Accept-Language" to "en-US,en;q=0.5",
                                        "Referer" to cleanLink, // Usar cleanLink como Referer
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
                                    val nozomiurl = json.file
                                    if (nozomiurl != null) {
                                        streamClean(
                                            "Nozomi",
                                            nozomiurl,
                                            "", // Referer vacío, ajusta si es necesario
                                            null,
                                            callback,
                                            nozomiurl.contains(".m3u8")
                                        )
                                    }
                                }
                            }
                        }
                        if (links.contains("um.php")) {
                            val desutext = app.get(links, referer = data).text
                            val desuRegex = Regex("((https:|http:)//.*\\.m3u8)")
                            val file = desuRegex.find(desutext)?.value
                            if (file != null) {
                                val namedesu = "Desu"
                                generateM3u8(
                                    namedesu,
                                    file,
                                    mainUrl, // Referer para m3u8
                                ).forEach { desurl ->
                                    streamClean(
                                        namedesu,
                                        desurl.url,
                                        mainUrl,
                                        desurl.quality.toString(),
                                        callback,
                                        true
                                    )
                                }
                            }
                        }
                        if (links.contains("jkmedia")) {
                            app.get(
                                links,
                                referer = data,
                                allowRedirects = false
                            ).okhttpResponse.headers.values("location").apmap { xtremeurl ->
                                val namex = "Xtreme S"
                                streamClean(
                                    namex,
                                    xtremeurl,
                                    "", // Referer vacío, ajusta si es necesario
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