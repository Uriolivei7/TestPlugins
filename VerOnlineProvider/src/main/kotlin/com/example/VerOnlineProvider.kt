package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo.

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

class VerOnlineProvider : MainAPI() {
    override var mainUrl = "https://www.verseriesonline.net"
    override var name = "VerOnline"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "es"

    // CORREGIDO: 'hasLatest' ha sido eliminado ya que no existe en tu MainAPI.
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private fun log(message: String) {
        Log.d(name, message)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val mainPageResponse = app.get(mainUrl)
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)
        val csrfToken = mainPageDoc.select("meta[name=csrf-token]").attr("content")

        if (csrfToken.isBlank()) {
            log("getMainPage - ERROR: No se pudo obtener el token CSRF de la página principal.")
            return null
        }
        log("getMainPage - Token CSRF encontrado: $csrfToken")

        val api_url = "$mainUrl/api/ajax_request"
        val post_data_latest = mapOf(
            "action" to "get_data",
            "page" to page.toString(),
            "type" to "series",
            "cat" to ""
        )
        val post_data_animation = mapOf(
            "action" to "get_data",
            "page" to page.toString(),
            "type" to "series",
            "cat" to "animacion"
        )

        val listsToFetch = listOf(
            Pair("Últimas Series", post_data_latest),
            Pair("Series de Animación", post_data_animation)
        )

        val homePageLists = listsToFetch.apmap { (listName, basePostData) ->
            val tvType = when {
                listName.contains("Series") || listName.contains("Animación") -> TvType.TvSeries
                else -> TvType.Others
            }
            try {
                val postDataWithToken = basePostData.toMutableMap().apply {
                    put("_token", csrfToken)
                }

                log("getMainPage - Intentando POST a: $api_url con datos: $postDataWithToken")
                val res = app.post(
                    url = api_url,
                    data = postDataWithToken,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to mainUrl
                    )
                )
                val doc = Jsoup.parse(res.text)

                log("getMainPage - HTML recibido para $listName (primeros 1000 chars): ${doc.html().take(1000)}")

                val homeItems = doc.select("div.col-md-3.col-6.col-sm-4.my-2 a.movie_box_link").mapNotNull { aElement ->
                    val title = aElement.selectFirst("div.name")?.text() ?: ""
                    val link = aElement.attr("href") ?: ""
                    val img = aElement.selectFirst("img")?.attr("data-src")
                        ?: aElement.selectFirst("img")?.attr("src") ?: ""

                    if (title.isNotBlank() && link.isNotBlank()) {
                        TvSeriesSearchResponse(
                            name = title,
                            url = fixUrl(link),
                            posterUrl = img,
                            type = tvType,
                            apiName = this.name
                        )
                    } else {
                        log("getMainPage - Ítem incompleto encontrado para $listName: Título='$title', Link='$link', Img='$img'")
                        null
                    }
                }
                log("getMainPage - Encontrados ${homeItems.size} ítems para $listName")
                HomePageList(listName, homeItems)
            } catch (e: Exception) {
                log("getMainPage - Error al obtener la página principal para $listName (API POST): ${e.message} - ${e.stackTraceToString()}")
                null
            }
        }.filterNotNull()

        items.addAll(homePageLists)
        return HomePageResponse(items.toList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = ArrayList<SearchResponse>()
        val mainPageResponse = app.get(mainUrl)
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)
        val csrfToken = mainPageDoc.select("meta[name=csrf-token]").attr("content")

        if (csrfToken.isBlank()) {
            log("search - ERROR: No se pudo obtener el token CSRF para la búsqueda.")
            return emptyList()
        }
        log("search - Token CSRF para búsqueda: $csrfToken")

        val searchUrl = "$mainUrl/livesearch"
        val postData = mapOf(
            "search" to query,
            "_token" to csrfToken
        )

        log("search - Intentando POST de búsqueda a URL: $searchUrl con datos: $postData")
        try {
            val res = app.post(
                url = searchUrl,
                data = postData,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl
                )
            )
            val doc = Jsoup.parse(res.text)

            log("search - HTML de búsqueda recibido (primeros 1000 chars): ${doc.html().take(1000)}")

            val items = doc.select("a")
            for (item in items) {
                val link = item.attr("href") ?: ""
                val title = item.text() ?: ""

                if (title.isNotBlank() && link.isNotBlank()) {
                    searchResults.add(
                        TvSeriesSearchResponse(
                            name = title,
                            url = fixUrl(link),
                            posterUrl = null,
                            type = TvType.TvSeries,
                            apiName = this.name
                        )
                    )
                }
            }
            log("search - Encontrados ${searchResults.size} resultados para '$query'")
            return searchResults
        } catch (e: Exception) {
            log("search - Error en la búsqueda para '$query' en URL $searchUrl: ${e.message} - ${e.stackTraceToString()}")
            return emptyList()
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        log("load - URL de entrada: $url")

        var cleanUrl = url
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(url)
        if (parsedEpisodeData != null) {
            cleanUrl = parsedEpisodeData.url
            log("load - URL limpia por JSON de EpisodeLoadData: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                log("load - URL limpiada con HTTPS: $cleanUrl")
            }
            log("load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            log("load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            log("load - ERROR al obtener el documento para URL: $cleanUrl - ${e.message} - ${e.stackTraceToString()}")
            return null
        }

        log("load - HTML recibido para la URL de la serie (primeros 2000 chars): ${doc.html().take(2000)}")

        val title = doc.selectFirst("h1.movs-title")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""
        val poster = doc.selectFirst("div.film-poster img")?.attr("data-src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""
        val plot = doc.selectFirst("div.description.full")?.text()
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content") ?: ""
        val year = doc.selectFirst("div.info-text:contains(Año) span")?.text()?.toIntOrNull()
        val tags = emptyList<String>()

        val allEpisodes = ArrayList<Episode>()

        val episodeElements = doc.select("ul.listing.items.full li")

        if (episodeElements.isNotEmpty()) {
            log("load - Parece ser una página de serie con episodios listados directamente.")
            episodeElements.mapNotNull { episodeElement ->
                val epLink = episodeElement.selectFirst("a")?.attr("href") ?: ""
                val epTitleText = episodeElement.selectFirst("div.name")?.text() ?: ""
                val ssEpiText = episodeElement.selectFirst("div.ss-epi")?.text()

                val seasonNumber = ssEpiText?.substringAfter("Temporada ")?.substringBefore(" ")?.toIntOrNull()
                val episodeNumber = ssEpiText?.substringAfter("Episodio ")?.toIntOrNull()

                if (epLink.isNotBlank() && epTitleText.isNotBlank()) {
                    log("load - Episodio: Título='$epTitleText', URL='$epLink', Temporada=$seasonNumber, Episodio=$episodeNumber")
                    Episode(
                        data = EpisodeLoadData(epTitleText, fixUrl(epLink)).toJson(),
                        name = epTitleText,
                        season = seasonNumber,
                        episode = episodeNumber,
                        posterUrl = poster
                    )
                } else {
                    log("load - Episodio incompleto: URL=$epLink, Título=$epTitleText")
                    null
                }
            }.let {
                allEpisodes.addAll(it)
            }
        } else {
            log("load - No se encontraron episodios en 'ul.listing.items.full li'. La estructura puede haber cambiado o no hay episodios listados directamente.")
        }

        log("load - Total de episodios encontrados (final): ${allEpisodes.size}")

        val recommendations = doc.select("div.item").mapNotNull { recElement ->
            val recTitle = recElement.selectFirst("h3 a")?.text()
            val recLink = recElement.selectFirst("a")?.attr("href")
            val recPoster = recElement.selectFirst("img")?.attr("src")

            if (recTitle != null && recLink != null && recPoster != null) {
                TvSeriesSearchResponse(
                    name = recTitle,
                    url = fixUrl(recLink),
                    posterUrl = fixUrl(recPoster),
                    type = TvType.TvSeries,
                    apiName = this.name
                )
            } else null
        }

        return TvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            apiName = this.name,
            type = TvType.TvSeries,
            episodes = allEpisodes,
            posterUrl = fixUrl(poster),
            backgroundPosterUrl = fixUrl(poster),
            plot = plot,
            year = year,
            tags = tags,
            recommendations = recommendations
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("loadLinks - Data de entrada: $data")

        var targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            log("loadLinks - URL final del episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(data)
            log("loadLinks - URL final de película (directa o ya limpia y con fixUrl): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            log("loadLinks - ERROR: La URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            log("loadLinks - ERROR al obtener el documento para URL: $targetUrl - ${e.message} - ${e.stackTraceToString()}")
            return false
        }

        val streamerElements = doc.select("li.streamer")

        var foundLinks = false
        if (streamerElements.isNotEmpty()) {
            log("loadLinks - Se encontraron ${streamerElements.size} elementos 'li.streamer'. Procesando...")
            streamerElements.apmap { streamerElement ->
                val encodedUrl = streamerElement.attr("data-url") ?: ""
                val serverName = streamerElement.selectFirst("span[id*='player_V_DIV_5']")?.text()
                    ?: streamerElement.selectFirst("span")?.text()?.replace("OPCIÓN ", "Opción ")?.trim()
                    ?: "Servidor Desconocido"

                if (encodedUrl.isNotBlank()) {
                    val base64Part = encodedUrl.substringAfter("/streamer/")

                    try {
                        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                        val decodedUrl = String(decodedBytes, UTF_8)
                        log("loadLinks - Decodificado URL para $serverName: $decodedUrl")

                        val linkType = if (decodedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        // Usamos el constructor principal de ExtractorLink con todos los parámetros obligatorios.
                        // Referencia: public constructor(source: kotlin.String, name: kotlin.String, url: kotlin.String, referer: kotlin.String, quality: kotlin.Int, headers: kotlin.collections.Map<kotlin.String, kotlin.String> = COMPILED_CODE, extractorData: kotlin.String? = COMPILED_CODE, type: com.lagradost.cloudstream3.utils.ExtractorLinkType)
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = serverName,
                                url = fixUrl(decodedUrl),
                                referer = targetUrl, // Referer es la URL de la página donde se encontró el link
                                quality = 0, // No tenemos una calidad específica de la página, así que 0.
                                headers = emptyMap(), // Map vacío para headers, ya que no se especifica
                                extractorData = null, // null para extractorData
                                type = linkType
                            )
                        )
                        foundLinks = true

                    } catch (e: IllegalArgumentException) {
                        log("loadLinks - Error al decodificar Base64 de $encodedUrl: ${e.message}")
                    } catch (e: Exception) {
                        log("loadLinks - Error general al procesar link de $serverName ($encodedUrl): ${e.message} - ${e.stackTraceToString()}")
                    }
                }
            }
        } else {
            log("loadLinks - No se encontraron elementos 'li.streamer' en la página del episodio. Buscando alternativas.")

            val iframeSrc = doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
                ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                log("loadLinks - Encontrado iframe directo en la página: $iframeSrc. Intentando ExtractorLink.")
                val iframeLinkType = if (iframeSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "Directo Iframe",
                        url = fixUrl(iframeSrc),
                        referer = targetUrl,
                        quality = 0,
                        headers = emptyMap(),
                        extractorData = null,
                        type = iframeLinkType
                    )
                )
                foundLinks = true
            }

            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                log("loadLinks - Encontrados enlaces directos en scripts. Intentando ExtractorLink.")
                directMatches.apmap { directUrl ->
                    val directLinkType = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "Directo Script",
                            url = fixUrl(directUrl),
                            referer = targetUrl,
                            quality = 0,
                            headers = emptyMap(),
                            extractorData = null,
                            type = directLinkType
                        )
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}