package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URL
import org.jsoup.nodes.Element // Importa Element de Jsoup

class TvporinternetProvider : MainAPI() {

    override var mainUrl = "https://www.tvporinternet2.com"
    override var name = "TVporInternet"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Live,
    )

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private fun decodeBase64(encodedString: String): String {
        return try {
            val cleanedString = encodedString.replace('-', '+').replace('_', '/')
            val paddedString = if (cleanedString.length % 4 == 0) cleanedString else cleanedString + "====".substring(0, 4 - (cleanedString.length % 4))
            val decodedBytes = Base64.decode(paddedString, Base64.DEFAULT)
            String(decodedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(name, "Error decodificando Base64: ${e.message} - Cadena: ${encodedString.take(50)}...")
            return ""
        }
    }

    private fun decodeBase64MultipleTimes(encodedString: String, times: Int): String {
        var currentDecoded = encodedString
        for (i in 0 until times) {
            currentDecoded = decodeBase64(currentDecoded)
            if (currentDecoded.isEmpty()) {
                return ""
            }
        }
        return currentDecoded
    }

    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium", "Vuelvete Premium (No ADS)", "Únete a Whatsapp", "Únete a Telegram", "¿Nos invitas el cafe?")
    val deportesCat = setOf("TUDN", "WWE", "Afizzionados", "Gol Perú", "Gol TV", "TNT SPORTS", "Fox Sports Premium", "TYC Sports", "Movistar Deportes (Perú)", "Movistar La Liga", "Movistar Liga De Campeones", "Dazn F1", "Dazn La Liga", "Bein La Liga", "Bein Sports Extra", "Directv Sports", "Directv Sports 2", "Directv Sports Plus", "Espn Deportes", "Espn Extra", "Espn Premium", "Espn", "Espn 2", "Espn 3", "Espn 4", "Espn Mexico", "Espn 2 Mexico", "Espn 3 Mexico", "Fox Deportes", "Fox Sports", "Fox Sports 2", "Fox Sports 3", "Fox Sports Mexico", "Fox Sports 2 Mexico", "Fox Sports 3 Mexico")
    val entretenimientoCat = setOf("Telefe", "El Trece", "Televisión Pública", "Telemundo Puerto rico", "Univisión", "Univisión Tlnovelas", "Pasiones", "Caracol", "RCN", "Latina", "America TV", "Willax TV", "ATV", "Las Estrellas", "Tl Novelas", "Galavision", "Azteca 7", "Azteca Uno", "Canal 5", "Distrito Comedia")
    val noticiasCat = setOf("Telemundo 51")
    val peliculasCat = setOf("Movistar Accion", "Movistar Drama", "Universal Channel", "TNT", "TNT Series", "Star Channel", "Star Action", "Star Series", "Cinemax", "Space", "Syfy", "Warner Channel", "Warner Channel (México)", "Cinecanal", "FX", "AXN", "AMC", "Studio Universal", "Multipremier", "Golden", "Golden Plus", "Golden Edge", "Golden Premier", "Golden Premier 2", "Sony", "DHE", "NEXT HD")
    val infantilCat = setOf("Cartoon Network", "Tooncast", "Cartoonito", "Disney Channel", "Disney JR", "Nick")
    val educacionCat = setOf("Discovery Channel", "Discovery World", "Discovery Theater", "Discovery Science", "Discovery Familia", "History", "History 2", "Animal Planet", "Nat Geo", "Nat Geo Mundo")
    val dos47Cat = setOf("24/7")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Deportes", mainUrl),
            Pair("Entretenimiento", mainUrl),
            Pair("Noticias", mainUrl),
            Pair("Peliculas", mainUrl),
            Pair("Infantil", mainUrl),
            Pair("Educacion", mainUrl),
            Pair("24/7", mainUrl),
            Pair("Todos", mainUrl),
        )
        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select("div.p-2").filterNot { element ->
                val text = element.selectFirst("p.des")?.text() ?: ""
                nowAllowed.any { text.contains(it, ignoreCase = true) } || text.isBlank()
            }.filter {
                val text = it.selectFirst("p.des")?.text()?.trim() ?: ""
                when (name) {
                    "Deportes" -> deportesCat.any { text.contains(it, ignoreCase = true) }
                    "Entretenimiento" -> entretenimientoCat.any { text.contains(it, ignoreCase = true) }
                    "Noticias" -> noticiasCat.any { text.contains(it, ignoreCase = true) }
                    "Peliculas" -> peliculasCat.any { text.contains(it, ignoreCase = true) }
                    "Infantil" -> infantilCat.any { text.contains(it, ignoreCase = true) }
                    "Educacion" -> educacionCat.any { text.contains(it, ignoreCase = true) }
                    "24/7" -> dos47Cat.any { text.contains(it, ignoreCase = true) }
                    "Todos" -> true
                    else -> true
                }
            }.map {
                val title = it.selectFirst("p.des")?.text() ?: ""
                val img = it.selectFirst("a img.w-28")?.attr("src") ?: ""
                val link = it.selectFirst("a")?.attr("href") ?: ""
                LiveSearchResponse(
                    title,
                    link,
                    this.name,
                    TvType.Live,
                    fixUrl(img),
                    null,
                    null,
                )
            }
            items.add(HomePageList(name, home, true))
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl
        val doc = app.get(url).document
        return doc.select("div.p-2").filterNot { element ->
            val text = element.selectFirst("p.des")?.text() ?: ""
            nowAllowed.any { text.contains(it, ignoreCase = true) } || text.isBlank()
        }.filter { element ->
            element.selectFirst("p.des")?.text()?.contains(query, ignoreCase = true) ?: false
        }.map {
            val title = it.selectFirst("p.des")?.text() ?: ""
            val img = it.selectFirst("a img.w-28")?.attr("src") ?: ""
            val link = it.selectFirst("a")?.attr("href") ?: ""
            LiveSearchResponse(
                title,
                link,
                this.name,
                TvType.Live,
                fixUrl(img),
                null,
                null,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.text-3xl.font-bold.mb-4")?.text() ?: ""

        val poster = doc.selectFirst("link[rel=\"shortcut icon\"]")?.attr("href") ?: ""

        val desc: String? = null

        return newMovieLoadResponse(
            title,
            url, TvType.Live, url
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "Iniciando loadLinks para la URL del canal: $data")

        val commonHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.8",
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-GPC" to "1",
            "Upgrade-Insecure-Requests" to "1"
        )

        val mainPageResponse = app.get(data, headers = commonHeaders)
        val mainPageDocument = mainPageResponse.document
        val mainPageHtml = mainPageDocument.html()
        Log.d(name, "HTML recibido para la página del canal (principal): ${mainPageHtml.take(500)}...")

        // CORRECCIÓN FINAL para el filtro de opciones.
        // Se itera sobre los elementos y se agrega a una nueva lista si cumplen la condición.
        val videoOptionLinks = mutableListOf<Element>()
        for (element in mainPageDocument.select("div.flex.flex-wrap.gap-3 > a")) {
            if (element.text()?.contains("Opción", ignoreCase = true) == true) {
                videoOptionLinks.add(element)
            }
        }

        var foundAnyLink = false

        if (videoOptionLinks.isEmpty()) {
            Log.w(name, "No se encontraron enlaces de opciones de video explícitas en la página principal: $data. Intentando con el iframe principal directamente.")
            // Si no hay opciones explícitas, intentar con el iframe principal directamente si existe
            if (extractLinksFromIframe(data, mainPageDocument, commonHeaders, callback, "Default")) {
                foundAnyLink = true
            }
        } else {
            for (optionLinkElement in videoOptionLinks) {
                val optionHref = optionLinkElement.attr("href")
                // Se obtiene el texto directamente del <a> ya que no tiene <p.des>
                val optionName = optionLinkElement.text() ?: "Opción Desconocida"
                val fullOptionUrl = fixUrl(optionHref) // Asegurar que la URL sea absoluta

                Log.d(name, "Procesando opción: $optionName - URL: $fullOptionUrl")

                if (extractLinksFromOptionPage(fullOptionUrl, data, commonHeaders, callback, optionName)) {
                    foundAnyLink = true
                }
            }
        }

        if (!foundAnyLink) {
            Log.w(name, "No se encontró ninguna URL de stream válida tras analizar las opciones para el canal: $data")
        }
        return foundAnyLink
    }

    private suspend fun extractLinksFromOptionPage(
        optionPageUrl: String,
        refererToOptionPage: String,
        commonHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        optionName: String
    ): Boolean {
        Log.d(name, "Extrayendo enlaces de la página de opción: $optionPageUrl (Opción: $optionName)")

        val optionPageRequestHeaders = commonHeaders.toMutableMap().apply {
            put("Referer", refererToOptionPage)
            put("Sec-Fetch-Site", "same-origin")
        }

        val optionPageResponse = app.get(optionPageUrl, headers = optionPageRequestHeaders)
        val optionPageDocument = optionPageResponse.document
        val optionPageHtml = optionPageDocument.html()
        Log.d(name, "HTML recibido para la página de opción (${optionName}): ${optionPageHtml.take(500)}...")

        return extractLinksFromIframe(optionPageUrl, optionPageDocument, commonHeaders, callback, optionName)
    }

    private suspend fun extractLinksFromIframe(
        currentUrl: String,
        document: org.jsoup.nodes.Document,
        commonHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        optionIdentifier: String = ""
    ): Boolean {
        val videoIframeElement = document.selectFirst("iframe[name=player]")
        val videoIframeSrc = videoIframeElement?.attr("src")

        if (videoIframeSrc.isNullOrBlank()) {
            Log.w(name, "[$optionIdentifier] No se encontró la URL del iframe del video en la página: $currentUrl")
            return false
        }

        val fullVideoIframeSrc = fixUrl(videoIframeSrc)
        Log.d(name, "[$optionIdentifier] URL del iframe del video encontrada: $fullVideoIframeSrc")

        // Detectar cubemedia.rpmvid.com y manejarlo diferente
        if (fullVideoIframeSrc.contains("cubemedia.rpmvid.com")) {
            Log.d(name, "[$optionIdentifier] Detectado iframe de cubemedia.rpmvid.com. Iniciando extracción específica.")
            return extractLinksFromCubemediaIframe(fullVideoIframeSrc, currentUrl, commonHeaders, callback, optionIdentifier)
        }

        val videoIframeRequestHeaders = commonHeaders.toMutableMap().apply {
            put("Priority", "u=0, i")
            put("Referer", currentUrl)
            put("Sec-Fetch-Dest", "iframe")
            put("Sec-Fetch-Mode", "navigate")
            put("Sec-Fetch-Site", "same-origin")
        }

        val videoIframeHtml = app.get(fullVideoIframeSrc, headers = videoIframeRequestHeaders).document.html()
        Log.d(name, "[$optionIdentifier] HTML recibido del iframe del video: ${videoIframeHtml.take(500)}...")

        // Regex más flexible para Clappr
        val clapprSourceRegex = Regex("""source\s*:\s*atob\(\s*atob\(\s*atob\(\s*atob\(\s*['"]([^'"]+)['"]\s*\)\s*\)\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL)

        val firstIframeMatch = clapprSourceRegex.find(videoIframeHtml)

        if (firstIframeMatch != null) {
            val encodedSource = firstIframeMatch.groupValues[1]
            Log.d(name, "[$optionIdentifier] Cadena Clappr codificada (4 capas) encontrada en primer iframe: $encodedSource")

            try {
                val finalUrl = decodeBase64MultipleTimes(encodedSource, 4)

                Log.d(name, "[$optionIdentifier] URL de stream decodificada: $finalUrl")

                if (finalUrl.startsWith("http")) {
                    return parseM3U8Stream(finalUrl, fullVideoIframeSrc, callback, optionIdentifier)
                }
            } catch (e: Exception) {
                Log.e(name, "[$optionIdentifier] Error al decodificar la URL Base64 de Clappr del primer iframe o al parsear M3U8: ${e.message}", e)
            }
        } else {
            Log.w(name, "[$optionIdentifier] No se encontró el patrón de source de Clappr (4 capas) en el primer iframe: $fullVideoIframeSrc. Buscando un segundo iframe... (esto es esperado para ciertos canales)")

            val finalStreamIframeSrcRegex = Regex("""<iframe\s+[^>]*src=["'](https?://live\.saohgdasregions\.fun/[^"']+)["']""")
            val finalIframeMatch = finalStreamIframeSrcRegex.find(videoIframeHtml)

            if (finalIframeMatch != null) {
                val finalStreamIframeSrcWithAmp = finalIframeMatch.groupValues[1]
                val finalStreamIframeSrc = finalStreamIframeSrcWithAmp.replace("&amp;", "&")
                Log.d(name, "[$optionIdentifier] Segundo iframe de stream encontrado: $finalStreamIframeSrc")

                val finalStreamIframeRequestHeaders = commonHeaders.toMutableMap().apply {
                    put("Connection", "keep-alive")
                    put("Referer", fullVideoIframeSrc)
                    put("Sec-Fetch-Dest", "iframe")
                    put("Sec-Fetch-Mode", "navigate")
                    put("Sec-Fetch-Site", "cross-site")
                    put("Sec-Fetch-Storage-Access", "active")
                }

                val finalStreamResponse = app.get(finalStreamIframeSrc, headers = finalStreamIframeRequestHeaders)
                val finalStreamHtml = finalStreamResponse.document.html()

                Log.d(name, "[$optionIdentifier] HTML COMPLETO recibido del iframe final del stream: ${finalStreamHtml.take(500)}...")

                val finalMatch = clapprSourceRegex.find(finalStreamHtml)

                if (finalMatch != null) {
                    val finalEncodedSource = finalMatch.groupValues[1]
                    Log.d(name, "[$optionIdentifier] Cadena Clappr codificada (4 capas) encontrada en iframe final: $finalEncodedSource")

                    try {
                        val finalUrl = decodeBase64MultipleTimes(finalEncodedSource, 4)

                        Log.d(name, "[$optionIdentifier] URL de stream decodificada del iframe final (maestra M3U8): $finalUrl")

                        if (finalUrl.startsWith("http")) {
                            return parseM3U8Stream(finalUrl, finalStreamIframeSrc, callback, optionIdentifier)
                        }
                    } catch (e: Exception) {
                        Log.e(name, "[$optionIdentifier] Error al decodificar la URL Base64 de Clappr desde iframe final o al parsear M3U8: ${e.message}", e)
                    }
                } else {
                    Log.w(name, "[$optionIdentifier] No se encontró el patrón de source de Clappr (4 capas) en el iframe final del stream: $finalStreamIframeSrc. Intentando un patrón de 'source' más genérico.")

                    val genericSourceRegex = Regex("""source:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
                    val genericMatch = genericSourceRegex.find(finalStreamHtml)

                    if (genericMatch != null) {
                        val streamUrl = genericMatch.groupValues[1]
                        Log.d(name, "[$optionIdentifier] URL de stream (genérica M3U8) encontrada en iframe final: $streamUrl")
                        if (streamUrl.startsWith("http")) {
                            return parseM3U8Stream(streamUrl, finalStreamIframeSrc, callback, optionIdentifier)
                        }
                    } else {
                        Log.w(name, "[$optionIdentifier] No se encontró ningún patrón de source (ni Clappr codificado ni genérico) en el iframe final del stream: $finalStreamIframeSrc")
                    }
                }
            }
        }
        return false
    }

    // Función para Cubemedia
    private suspend fun extractLinksFromCubemediaIframe(
        cubemediaIframeUrl: String,
        refererToCubemedia: String,
        commonHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        optionIdentifier: String = ""
    ): Boolean {
        Log.d(name, "[$optionIdentifier] Extrayendo enlaces de Cubemedia: $cubemediaIframeUrl")

        val cubemediaHeaders = commonHeaders.toMutableMap().apply {
            put("Referer", refererToCubemedia)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            put("Sec-Fetch-Site", "cross-site")
            put("Sec-Fetch-Mode", "navigate")
            put("Sec-Fetch-Dest", "iframe")
        }

        val cubemediaResponse = app.get(cubemediaIframeUrl, headers = cubemediaHeaders)
        val cubemediaDocument = cubemediaResponse.document
        val cubemediaHtml = cubemediaDocument.html()
        Log.d(name, "[$optionIdentifier] HTML recibido de Cubemedia iframe: ${cubemediaHtml.take(500)}...")

        // Buscar el elemento <video> y su <source> directamente
        val sourceElement = cubemediaDocument.selectFirst("video[crossorigin][aria-hidden=\"true\"] source[src]")
        if (sourceElement != null) {
            val relativeStreamUrl = sourceElement.attr("src")
            val cubemediaBaseUrl = "https://cubemedia.rpmvid.com" // Base URL para Cubemedia
            val fullStreamUrl = if (relativeStreamUrl.startsWith("/")) {
                "$cubemediaBaseUrl$relativeStreamUrl"
            } else {
                "$cubemediaBaseUrl/$relativeStreamUrl"
            }

            Log.d(name, "[$optionIdentifier] URL de stream de Cubemedia encontrada: $fullStreamUrl")

            // Parsear el M3U8 de Cubemedia
            return parseM3U8Stream(fullStreamUrl, cubemediaIframeUrl, callback, optionIdentifier)
        } else {
            Log.w(name, "[$optionIdentifier] No se encontró la etiqueta <source> dentro del <video> en el iframe de Cubemedia.")
        }
        return false
    }

    private suspend fun parseM3U8Stream(
        m3u8Url: String,
        refererForM3u8: String,
        callback: (ExtractorLink) -> Unit,
        optionIdentifier: String = ""
    ): Boolean {
        Log.d(name, "[$optionIdentifier] Iniciando parseo de M3U8: $m3u8Url")

        val m3u8Headers = mapOf(
            "Referer" to refererForM3u8,
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*", // Más amplio para M3U8
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive"
        )
        val m3u8Response = app.get(m3u8Url, headers = m3u8Headers)
        val m3u8Content = m3u8Response.text

        if (m3u8Content.isNullOrBlank()) {
            Log.w(name, "[$optionIdentifier] Contenido M3U8 maestro vacío o nulo para: $m3u8Url")
            return false
        }

        Log.d(name, "[$optionIdentifier] Contenido M3U8 maestro: ${m3u8Content.take(500)}...")

        val baseUrl = getBaseUrl(m3u8Url)

        // Ajustar la regex para capturar sub-streams M3U8 de forma más robusta
        // La regex ahora manejará URLs que pueden o no tener un prefijo de barra '/'
        val streamRegex = Regex("""#EXT-X-STREAM-INF:BANDWIDTH=(\d+)(?:,RESOLUTION=(\d+x\d+))?[^\n]*?\n\s*([^\s#][^\n]*)""")
        var foundStreams = false

        streamRegex.findAll(m3u8Content).forEach { matchResult ->
            foundStreams = true
            val bandwidth = matchResult.groupValues[1]
            val resolution = matchResult.groupValues.getOrNull(2).orEmpty()
            val streamRelativeUrl = matchResult.groupValues[3].trim()

            // Corregir la construcción de la URL completa
            val streamFullUrl = if (streamRelativeUrl.startsWith("http")) {
                streamRelativeUrl
            } else {
                val base = if (baseUrl.endsWith("/") && streamRelativeUrl.startsWith("/")) {
                    baseUrl.dropLast(1)
                } else if (!baseUrl.endsWith("/") && !streamRelativeUrl.startsWith("/")) {
                    "$baseUrl/"
                } else {
                    baseUrl
                }
                "$base$streamRelativeUrl"
            }

            val qualityName = if (resolution.isNotBlank()) resolution else "Calidad $bandwidth"
            val qualityInt = getQualityFromName(resolution.ifBlank { "Auto" })

            callback(newExtractorLink(
                source = this.name,
                name = "$optionIdentifier - $qualityName",
                url = streamFullUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = qualityInt
                this.referer = m3u8Url
                this.headers = mapOf("Referer" to m3u8Url)
            })
        }

        if (!foundStreams) {
            Log.d(name, "[$optionIdentifier] No se encontraron sub-streams #EXT-X-STREAM-INF en el M3U8 maestro, reportando el stream maestro directo.")
            callback(newExtractorLink(
                source = this.name,
                name = "$optionIdentifier - Auto",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.ordinal
                this.referer = refererForM3u8
                this.headers = mapOf("Referer" to refererForM3u8)
            })
        }
        return true
    }

    private fun getBaseUrl(urlString: String): String {
        return try {
            val url = URL(urlString)
            val path = url.path
            val lastSlash = url.path.lastIndexOf('/')
            val basePath = if (lastSlash != -1) url.path.substring(0, lastSlash + 1) else "/"
            "${url.protocol}://${url.host}$basePath"
        } catch (e: Exception) {
            Log.e(name, "Error al obtener base URL de $urlString: ${e.message}")
            urlString
        }
    }
}