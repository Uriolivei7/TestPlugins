package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink // Importación para la clase ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName // Importación para la función getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink // Importación para la función newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType // ¡Importación CLAVE para el tipo 'Type'!
import java.net.URL
import java.nio.charset.StandardCharsets

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

    private fun decodeBase64UntilUnchanged(encodedString: String): String {
        var decodedString = encodedString
        var previousDecodedString = ""
        var attempts = 0
        val maxAttempts = 5

        while (decodedString != previousDecodedString && attempts < maxAttempts) {
            previousDecodedString = decodedString
            decodedString = try {
                val cleanedString = decodedString.replace('-', '+').replace('_', '/')
                val paddedString = if (cleanedString.length % 4 == 0) cleanedString else cleanedString + "====".substring(0, 4 - (cleanedString.length % 4))
                val decodedBytes = Base64.decode(paddedString, Base64.DEFAULT)
                String(decodedBytes, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                Log.e(name, "Error decodificando Base64 (intento ${attempts + 1}): ${e.message} - Cadena: ${decodedString.take(50)}...")
                return previousDecodedString
            }
            attempts++
        }
        return decodedString
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
        val optionUrls = listOf(
            "live/panamericana.php",
            "live2/panamericana.php",
            "live3/panamericana.php",
            "live4/panamericana.php",
            "live5/panamericana.php",
            "live6/panamericana.php"
        ).map { "$mainUrl/$it" }

        var streamFound = false

        for ((index, sourceOptionUrl) in optionUrls.withIndex()) {
            Log.d(name, "Procesando URL opción: $sourceOptionUrl (Opción ${index + 1})")
            try {
                val optionPageResponse = app.get(
                    sourceOptionUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                        "Accept-Charset" to "UTF-8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Accept-Encoding" to "gzip, deflate, br",
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "document",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                        "Referer" to data,
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache"
                    ),
                    allowRedirects = true,
                    timeout = 10000
                )

                val optionPageDoc = optionPageResponse.document
                val finalOptionPageUrl = optionPageResponse.url
                Log.d(name, "HTML recibido para $finalOptionPageUrl: ${optionPageDoc.html().take(1000)}...")

                val scriptContent = optionPageDoc.select("script").joinToString("") { it.html() }
                Log.d(name, "Contenido de scripts combinado: ${scriptContent.take(1000)}...")

                // Intentar extraer fuente Clappr
                val clapprSourceRegex = "source:\\s*atob\\(.*?\\(\"(.*?)\"\\)".toRegex()
                val clapprMatch = clapprSourceRegex.find(scriptContent)
                if (clapprMatch != null) {
                    val encodedString = clapprMatch.groupValues[1]
                    Log.d(name, "Cadena Base64 encontrada en Clappr: ${encodedString.take(50)}...")
                    val decodedStreamUrl = decodeBase64UntilUnchanged(encodedString)
                    if (decodedStreamUrl.isNotBlank() && decodedStreamUrl.startsWith("http")) {
                        Log.d(name, "URL de stream Clappr decodificada con éxito: $decodedStreamUrl")
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Canal de TV (Opción ${index + 1})",
                                url = decodedStreamUrl,
                                type = ExtractorLinkType.M3U8 // ¡CORREGIDO AQUÍ! Se usa ExtractorLinkType.M3U8
                            ) {
                                this.quality = getQualityFromName("Normal")
                                this.referer = finalOptionPageUrl
                            }
                        )
                        streamFound = true
                        break
                    } else {
                        Log.w(name, "URL Clappr decodificada vacía o inválida: $decodedStreamUrl")
                    }
                } else {
                    Log.w(name, "No se encontró el patrón de source de Clappr en la página: $finalOptionPageUrl")
                }

                // Respaldo: Buscar URLs .m3u8
                val m3u8Regex = "https?://[^\"'\\s]+\\.m3u8(?:\\?[^\"'\\s]*)?".toRegex()
                val m3u8Match = m3u8Regex.find(optionPageResponse.text)
                if (m3u8Match != null) {
                    val m3u8Url = m3u8Match.value
                    Log.d(name, "URL de stream .m3u8 encontrada por regex: $m3u8Url")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Canal de TV (Opción ${index + 1})",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8 // ¡CORREGIDO AQUÍ! Se usa ExtractorLinkType.M3U8
                        ) {
                            this.quality = getQualityFromName("Normal")
                            this.referer = finalOptionPageUrl
                        }
                    )
                    streamFound = true
                    break
                } else {
                    Log.w(name, "No se encontró URL de stream .m3u8 por regex en la página: $finalOptionPageUrl")
                }
            } catch (e: Exception) {
                Log.e(name, "Error al procesar $sourceOptionUrl: ${e.message}")
            }
        }

        if (!streamFound) {
            Log.w(name, "No se encontró ninguna URL de stream válida tras probar todas las opciones para $data")
        }
        return streamFound
    }

    fun getBaseUrl(urlString: String): String {
        val url = URL(urlString)
        return "${url.protocol}://${url.host}"
    }

    fun getHostUrl(urlString: String): String {
        val url = URL(urlString)
        return url.host
    }
}