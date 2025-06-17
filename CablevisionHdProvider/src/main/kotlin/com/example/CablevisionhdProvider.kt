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
import com.lagradost.cloudstream3.utils.Qualities
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

    // Asumiendo que `data` es la URL inicial del canal: https://www.tvporinternet2.com/panamericana-en-vivo-por-internet.html

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "Iniciando loadLinks para la URL del canal: $data")

        // Headers para la página principal, basados en el cURL que proporcionaste anteriormente
        val mainPageHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.8",
            "Cache-Control" to "max-age=0",
            "Priority" to "u=0, i",
            "Referer" to mainUrl, // Reemplaza mainUrl con "https://www.tvporinternet2.com/" si es una constante
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-User" to "?1",
            "Sec-GPC" to "1",
            "Upgrade-Insecure-Requests" to "1"
        )

        // 1. Obtener la página principal (donde se encuentra el iframe del video)
        val mainPageResponse = app.get(data, headers = mainPageHeaders)
        val mainPageHtml = mainPageResponse.document.html()
        Log.d(name, "HTML recibido para la página del canal (principal): ${mainPageHtml.take(500)}...") // Log solo un fragmento


        // Buscar el iframe que contiene el video
        // Utiliza el selector CSS que identifica el iframe en la imagen:
        val videoIframeElement = mainPageResponse.document.selectFirst("iframe[name=player]") // Selector CSS: busca un <iframe> con el atributo name="player"
        val videoIframeSrc = videoIframeElement?.attr("src")


        if (videoIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se encontró la URL del iframe del video en la página principal.")
            return false
        }

        Log.d(name, "URL del iframe del video encontrada: $videoIframeSrc")

        // 2. Obtener el contenido del iframe del video
        val videoIframeHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.8",
            "Priority" to "u=0, i",
            "Referer" to data, // ¡REFERER ES LA URL DE LA PÁGINA PRINCIPAL!
            "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin", // Porque `live/panamericana.php` está en el mismo dominio
            "Sec-GPC" to "1",
            "Upgrade-Insecure-Requests" to "1"
        )

        val videoIframeHtml = app.get(videoIframeSrc, headers = videoIframeHeaders).document.html()
        Log.d(name, "HTML recibido del iframe del video: ${videoIframeHtml.take(500)}...")


        // A partir de aquí, el código para buscar el Clappr y decodificar sigue siendo el mismo.
        // Solo necesitarás revisar si el Clappr con 4 capas de atob está en este HTML,
        // o si este HTML de `panamericana.php` carga *otro* iframe.
        // Si carga otro iframe, entonces necesitarás un tercer paso similar.

        val clapprSourceRegex = Regex("""source:\s*atob\(atob\(atob\(atob\(['"]([^'"]+)['"]\)\)\)\)""")
        val match = clapprSourceRegex.find(videoIframeHtml) // Buscar en el HTML del primer iframe

        if (match != null) {
            val encodedSource = match.groupValues[1]
            Log.d(name, "Cadena Clappr codificada (4 capas) encontrada: $encodedSource")

            try {
                // Decodificar 4 veces Base64
                val decoded1 = atob(encodedSource)
                val decoded2 = atob(decoded1)
                val decoded3 = atob(decoded2)
                val finalUrl = atob(decoded3)

                Log.d(name, "URL de stream decodificada: $finalUrl")

                if (finalUrl.startsWith("http")) {
                    callback(newExtractorLink(
                        source = this.name, // El source de ExtractorLink
                        name = this.name,   // El nombre que aparecerá en la UI (ej: "Panamericana")
                        url = finalUrl,     // La URL final del stream
                        type = ExtractorLinkType.M3U8 // El tipo de enlace (M3U8, STREAM, etc.)
                    ) {
                        // Este es el bloque 'initializer'
                        // Aquí asignas las propiedades del ExtractorLink
                        quality = Qualities.Unknown.ordinal // O simplemente 0 si Quality no se resuelve
                        referer = videoIframeSrc          // El referer
                        headers = mapOf("Referer" to videoIframeSrc) // Los headers
                        // Otros campos si ExtractorLink los tiene y los necesitas, ej: isDash = true
                    })
                    return true
                }
            } catch (e: Exception) {
                Log.e(name, "Error al decodificar la URL Base64 de Clappr: ${e.message}", e)
            }
        } else {
            Log.w(name, "No se encontró el patrón de source de Clappr (4 capas) en el primer iframe: $videoIframeSrc")

            // Aquí iría la lógica para buscar el tercer iframe si `panamericana.php` lo carga.
            // Basado en tu anterior cURL: 'https://live.saohgdasregions.fun/tvporinternet.php?stream=463_'
            val finalStreamIframeSrcRegex = Regex("""<iframe\s+[^>]*src=["'](https?://live\.saohgdasregions\.fun/[^"']+)["']""")
            val finalIframeMatch = finalStreamIframeSrcRegex.find(videoIframeHtml)

            if (finalIframeMatch != null) {
                val finalStreamIframeSrc = finalIframeMatch.groupValues[1]
                Log.d(name, "Segundo iframe de stream encontrado: $finalStreamIframeSrc")

                val finalStreamIframeHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "es-ES,es;q=0.8",
                    "Connection" to "keep-alive",
                    "Referer" to mainUrl, // Confirmar si el referer es la página principal o el primer iframe
                    "sec-ch-ua" to "\"Brave\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                    "sec-ch-ua-mobile" to "?0",
                    "sec-ch-ua-platform" to "\"Windows\"",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Sec-GPC" to "1",
                    "Upgrade-Insecure-Requests" to "1"
                )

                val finalStreamHtml = app.get(finalStreamIframeSrc, headers = finalStreamIframeHeaders).document.html()
                Log.d(name, "HTML recibido del iframe final del stream: ${finalStreamHtml.take(500)}...")

                // Buscar el patrón de Clappr en este HTML final
                val finalCombinedScripts = finalStreamHtml.substringAfter("<head>").substringBefore("</body>")
                    .replace("\\n", "")
                    .replace("\\t", "")
                    .replace("\\r", "")

                val finalMatch = clapprSourceRegex.find(finalCombinedScripts)

                if (finalMatch != null) {
                    val finalEncodedSource = finalMatch.groupValues[1]
                    Log.d(name, "Cadena Clappr codificada (4 capas) encontrada en iframe final: $finalEncodedSource")

                    try {
                        val decoded1 = atob(finalEncodedSource)
                        val decoded2 = atob(decoded1)
                        val decoded3 = atob(decoded2)
                        val finalUrl = atob(decoded3)

                        Log.d(name, "URL de stream decodificada del iframe final: $finalUrl")

                        if (finalUrl.startsWith("http")) {
                            callback(newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = finalUrl,
                                type = ExtractorLinkType.M3U8 // O ExtractorLinkType.STREAM
                            ) {
                                quality = Qualities.Unknown.ordinal // O simplemente 0
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