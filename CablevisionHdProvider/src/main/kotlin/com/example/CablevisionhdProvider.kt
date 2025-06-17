package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URL
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.utils.Qualities // Importación para Qualities
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

class CablevisionhdProvider : MainAPI() {

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

    // User-Agent común para simular un navegador Chrome/Brave
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    // Esta función decodificará una sola capa de Base64
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

    // Esta función decodificará Base64 múltiples veces
    private fun decodeBase64MultipleTimes(encodedString: String, times: Int): String {
        var currentDecoded = encodedString
        for (i in 0 until times) {
            currentDecoded = decodeBase64(currentDecoded)
            if (currentDecoded.isEmpty()) { // Si falla en alguna decodificación, retorna vacío
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

        // Headers comunes para todas las solicitudes, simulando un navegador
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

        // 1. Obtener la página principal (donde se encuentra el iframe del video)
        val mainPageRequestHeaders = commonHeaders.toMutableMap().apply {
            put("Cache-Control", "max-age=0")
            put("Priority", "u=0, i")
            put("Referer", mainUrl)
            put("Sec-Fetch-Dest", "document")
            put("Sec-Fetch-Mode", "navigate")
            put("Sec-Fetch-Site", "same-origin")
            put("Sec-Fetch-User", "?1")
        }

        val mainPageResponse = app.get(data, headers = mainPageRequestHeaders)
        val mainPageHtml = mainPageResponse.document.html()
        Log.d(name, "HTML recibido para la página del canal (principal): ${mainPageHtml.take(500)}...")

        val videoIframeElement = mainPageResponse.document.selectFirst("iframe[name=player]")
        val videoIframeSrc = videoIframeElement?.attr("src")

        if (videoIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se encontró la URL del iframe del video en la página principal.")
            return false
        }

        Log.d(name, "URL del iframe del video encontrada: $videoIframeSrc")

        // 2. Obtener el contenido del iframe del video
        val videoIframeRequestHeaders = commonHeaders.toMutableMap().apply {
            put("Priority", "u=0, i")
            put("Referer", data) // Referer es la URL de la página principal del canal
            put("Sec-Fetch-Dest", "iframe")
            put("Sec-Fetch-Mode", "navigate")
            put("Sec-Fetch-Site", "same-origin")
        }

        val videoIframeHtml = app.get(videoIframeSrc, headers = videoIframeRequestHeaders).document.html()
        Log.d(name, "HTML recibido del iframe del video: ${videoIframeHtml.take(500)}...")

        val clapprSourceRegex = Regex("""source:\s*atob\(atob\(atob\(atob\(['"]([^'"]+)['"]\)\)\)\)""")
        val match = clapprSourceRegex.find(videoIframeHtml)

        // Si el Clappr se encuentra directamente en el primer iframe
        if (match != null) {
            val encodedSource = match.groupValues[1]
            Log.d(name, "Cadena Clappr codificada (4 capas) encontrada en primer iframe: $encodedSource")

            try {
                val finalUrl = decodeBase64MultipleTimes(encodedSource, 4)

                Log.d(name, "URL de stream decodificada: $finalUrl")

                if (finalUrl.startsWith("http")) {
                    callback(newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.Unknown.ordinal
                        referer = videoIframeSrc // El referer es el iframe del video
                        headers = mapOf("Referer" to videoIframeSrc)
                    })
                    return true
                }
            } catch (e: Exception) {
                Log.e(name, "Error al decodificar la URL Base64 de Clappr del primer iframe: ${e.message}", e)
            }
        } else {
            Log.w(name, "No se encontró el patrón de source de Clappr (4 capas) en el primer iframe: $videoIframeSrc")

            // Si el primer iframe carga *otro* iframe que contiene el stream final
            val finalStreamIframeSrcRegex = Regex("""<iframe\s+[^>]*src=["'](https?://live\.saohgdasregions\.fun/[^"']+)["']""")
            val finalIframeMatch = finalStreamIframeSrcRegex.find(videoIframeHtml)

            if (finalIframeMatch != null) {
                val finalStreamIframeSrc = finalIframeMatch.groupValues[1]
                Log.d(name, "Segundo iframe de stream encontrado: $finalStreamIframeSrc")

                // El Referer para el iframe final del stream debe ser el mainUrl del sitio.
                val finalStreamIframeRequestHeaders = commonHeaders.toMutableMap().apply {
                    put("Connection", "keep-alive")
                    put("Referer", mainUrl) // ¡AQUÍ ESTÁ LA CORRECCIÓN CLAVE! Referer es el mainUrl.
                    put("Sec-Fetch-Dest", "iframe")
                    put("Sec-Fetch-Mode", "navigate")
                    put("Sec-Fetch-Site", "cross-site")
                    put("Sec-Fetch-Storage-Access", "active")
                }

                val finalStreamResponse = app.get(finalStreamIframeSrc, headers = finalStreamIframeRequestHeaders)
                val finalStreamHtml = finalStreamResponse.document.html()

                // Si hay una redirección, OkHttp la seguirá automáticamente, y el HTML que recibamos
                // será el de la URL final después de la redirección.
                // El log de `finalStreamResponse.code == 302` no es necesario si OkHttp lo sigue.
                // Lo importante es el HTML resultante y si el Referer es el correcto.

                Log.d(name, "HTML recibido del iframe final del stream: ${finalStreamHtml.take(500)}...")

                val finalCombinedScripts = finalStreamHtml.substringAfter("<head>").substringBefore("</body>")
                    .replace("\\n", "")
                    .replace("\\t", "")
                    .replace("\\r", "")

                val finalMatch = clapprSourceRegex.find(finalCombinedScripts)

                if (finalMatch != null) {
                    val finalEncodedSource = finalMatch.groupValues[1]
                    Log.d(name, "Cadena Clappr codificada (4 capas) encontrada en iframe final: $finalEncodedSource")

                    try {
                        val finalUrl = decodeBase64MultipleTimes(finalEncodedSource, 4)

                        Log.d(name, "URL de stream decodificada del iframe final: $finalUrl")

                        if (finalUrl.startsWith("http")) {
                            callback(newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = finalUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                quality = Qualities.Unknown.ordinal
                                // El referer para el stream final es la URL del iframe que lo contiene (que es la URL final de la redirección)
                                referer = finalStreamIframeSrc
                                headers = mapOf("Referer" to finalStreamIframeSrc)
                            })
                            return true
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error al decodificar la URL Base64 de Clappr desde iframe final: ${e.message}", e)
                    }
                } else {
                    Log.w(name, "No se encontró el patrón de source de Clappr (4 capas) en el iframe final del stream: $finalStreamIframeSrc")
                }
            }
        }

        Log.w(name, "No se encontró ninguna URL de stream válida tras analizar la página del canal: $data")
        return false
    }

    fun getBaseUrl(urlString: String): String {
        val url = URL(urlString)
        return "${url.protocol}://${url.host}"
    }

    fun getHostUrl(urlString: String): String {
        val url = URL(urlString)
        return url.host
    }

    fun atob(encodedString: String): String {
        return String(Base64.decode(encodedString, Base64.DEFAULT), Charsets.UTF_8)
    }
}