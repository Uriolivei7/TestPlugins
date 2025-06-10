package com.example

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

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private fun log(message: String) {
        Log.d(name, message)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val seriesPageUrl = "$mainUrl/series-online"

        // *** AÑADIDO: HEADERS PARA IMITAR NAVEGADOR ***
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "es-ES,es;q=0.9",
            "Connection" to "keep-alive"
            // Puedes añadir "Referer" si es necesario, aunque en la página principal suele ser opcional
            // "Referer" to mainUrl
        )

        val mainPageResponse = app.get(seriesPageUrl, headers = headers) // PASAMOS LOS HEADERS AQUÍ
        val mainPageDoc = Jsoup.parse(mainPageResponse.text)

        // *** AGREGAR ESTA LÍNEA TEMPORALMENTE PARA DEPURAR ***
        log("getMainPage - HTML completo recibido: ${mainPageDoc.html()}")
        // *** FIN DE LA LÍNEA TEMPORAL ***

        // Ahora el selector para el contenedor principal de series debería ser más específico
        // Usaremos `main.content #dle-content` para asegurar que estamos en la sección correcta
        val sectionsContainers = mainPageDoc.select("main.content div#dle-content")

        if (sectionsContainers.isEmpty()) {
            log("getMainPage - ERROR: No se encontraron los contenedores de series usando 'main.content div#dle-content'.")
            // Intenta con el selector original si el más específico falla (como fallback)
            val fallbackContainers = mainPageDoc.select("div#dle-content")
            if (fallbackContainers.isEmpty()){
                return null
            } else {
                log("getMainPage - Usando selector de fallback 'div#dle-content'.")
                // Si el fallback funciona, usa ese para continuar
                val seriesElements = fallbackContainers.select("div.short")
                val series = seriesElements.mapNotNull { element ->
                    val aElement = element.selectFirst("a.short_img_box.with_mask")
                    val link = aElement?.attr("href")
                    val imgElement = aElement?.selectFirst("img")
                    val img = imgElement?.attr("data-src") ?: imgElement?.attr("src")

                    val titleElement = element.selectFirst("div.short_title a")
                    val title = titleElement?.text()

                    if (title != null && link != null && img != null) {
                        val fixedLink = fixUrl(link)
                        val fixedImg = fixUrl(img)
                        log("getMainPage - Ítem extraído (fallback): Título='$title', Link Fijo='$fixedLink', Img Fija='$fixedImg'")
                        TvSeriesSearchResponse(
                            name = title.trim(),
                            url = fixedLink,
                            posterUrl = fixedImg,
                            type = TvType.TvSeries,
                            apiName = this.name
                        )
                    } else {
                        log("getMainPage - Ítem incompleto (fallback): Título='$title', Link='$link', Img='$img'")
                        null
                    }
                }
                if (series.isNotEmpty()) {
                    items.add(HomePageList("Series Online", series))
                    log("getMainPage - Encontrados ${series.size} ítems para 'Series Online' (fallback)")
                } else {
                    log("getMainPage - No se encontraron ítems en la página de series (fallback).")
                }
                return HomePageResponse(items.toList(), false)
            }
        }

        // Si el selector específico `main.content div#dle-content` funciona, continuamos aquí
        val seriesElements = sectionsContainers.select("div.short")

        val series = seriesElements.mapNotNull { element ->
            val aElement = element.selectFirst("a.short_img_box.with_mask") // Este es el enlace principal con la imagen
            val link = aElement?.attr("href")
            val imgElement = aElement?.selectFirst("img")
            // Usa data-src primero, luego src como fallback si la imagen es lazy-loaded
            val img = imgElement?.attr("data-src") ?: imgElement?.attr("src")

            // *** CAMBIO AQUÍ: Selector para el título ***
            // El título ahora está en un <a> dentro de un <div class="short_title">
            val titleElement = element.selectFirst("div.short_title a")
            val title = titleElement?.text()

            if (title != null && link != null && img != null) {
                val fixedLink = fixUrl(link)
                val fixedImg = fixUrl(img)
                log("getMainPage - Ítem extraído: Título='$title', Link Fijo='$fixedLink', Img Fija='$fixedImg'")
                TvSeriesSearchResponse(
                    name = title.trim(),
                    url = fixedLink,
                    posterUrl = fixedImg,
                    type = TvType.TvSeries,
                    apiName = this.name
                )
            } else {
                log("getMainPage - Ítem incompleto: Título='$title', Link='$link', Img='$img'")
                null
            }
        }

        if (series.isNotEmpty()) {
            items.add(HomePageList("Series Online", series))
            log("getMainPage - Encontrados ${series.size} ítems para 'Series Online'")
        } else {
            log("getMainPage - No se encontraron ítems en la página de series.")
        }

        if (items.isEmpty()) {
            log("getMainPage - ADVERTENCIA: No se agregaron listas de series a la página principal. Posiblemente no se encontraron series.")
        }

        return HomePageResponse(items.toList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = ArrayList<SearchResponse>()
        // También aquí podrías considerar añadir los headers si la búsqueda directa no funciona bien
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
                // Mantén los headers existentes para XHR, pero también podrías añadir el User-Agent etc.
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                    "Accept" to "application/json, text/javascript, */*; q=0.01", // Típicamente para XHR
                    "Accept-Language" to "es-ES,es;q=0.9",
                    "Connection" to "keep-alive"
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
                            posterUrl = null, // La búsqueda directa no da poster
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
            // *** AÑADIDO: HEADERS TAMBIÉN PARA LOAD ***
            app.get(cleanUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "es-ES,es;q=0.9",
                "Connection" to "keep-alive",
                "Referer" to mainUrl // Aquí el referer es importante para las páginas internas
            )).document
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
        val tags = emptyList<String>() // No veo tags específicos en el HTML de la serie, si hay, ajusta

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
                        posterUrl = poster // Usar el póster de la serie para los episodios
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

        // Recomendaciones (si la estructura no ha cambiado)
        val recommendations = doc.select("div.item").mapNotNull { recElement ->
            val recTitle = recElement.selectFirst("h3 a")?.text()
            val recLink = recElement.selectFirst("a")?.attr("href")
            val recPoster = recElement.selectFirst("img")?.attr("src") // Asumo src aquí, si es data-src, ajústalo

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
            // *** AÑADIDO: HEADERS TAMBIÉN PARA LOADLINKS ***
            app.get(targetUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "es-ES,es;q=0.9",
                "Connection" to "keep-alive",
                "Referer" to targetUrl // El referer para los links suele ser la propia URL del episodio
            )).document
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
                // Asegúrate de que el ID del span sea correcto si hay variaciones
                val serverName = streamerElement.selectFirst("span[id*='player_V_DIV_5']")?.text()
                    ?: streamerElement.selectFirst("span")?.text()?.replace("OPCI??N ", "Opción ")?.trim()
                    ?: "Servidor Desconocido"

                if (encodedUrl.isNotBlank()) {
                    val base64Part = encodedUrl.substringAfter("/streamer/")

                    try {
                        val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                        val decodedUrl = String(decodedBytes, UTF_8)
                        log("loadLinks - Decodificado URL para $serverName: $decodedUrl")

                        val linkType = if (decodedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = serverName,
                                url = fixUrl(decodedUrl),
                                referer = targetUrl, // Mantener el referer del episodio
                                quality = 0,
                                headers = emptyMap(), // Opcional: si el extractor necesita headers específicos
                                extractorData = null,
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