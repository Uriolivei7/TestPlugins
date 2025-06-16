package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URL

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
                String(decodedBytes)
            } catch (e: IllegalArgumentException) {
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
        val mainChannelPageDoc = app.get(data, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )).document
        Log.d(name, "HTML COMPLETO de mainChannelPageDoc (Página del canal principal): ${mainChannelPageDoc.html().take(500)}...")

        val optionLinks = mainChannelPageDoc.select("div.flex.flex-wrap.gap-3 a")
            .mapNotNull { it.attr("href") }
            .filter { it.isNotBlank() && it != "#" && it != "${mainUrl}" }
            .distinct()

        Log.d(name, "URLs de opciones encontradas en la página principal: $optionLinks")

        val allPossibleSourcesToProcess = if (optionLinks.isEmpty()) {
            val firstIframeSrc = mainChannelPageDoc.selectFirst("iframe[name=\"player\"]")?.attr("src")
            if (!firstIframeSrc.isNullOrBlank()) listOf(firstIframeSrc) else emptyList()
        } else {
            optionLinks.map { "$mainUrl/$it" } // Asegurar URLs absolutas
        }

        if (allPossibleSourcesToProcess.isEmpty()) {
            Log.w(name, "No se encontró ninguna URL de opción ni iframe 'player' principal en la página: $data")
            return false
        }

        var streamFound = false

        for ((index, sourceOptionUrl) in allPossibleSourcesToProcess.withIndex()) {
            Log.d(name, "Intentando procesar la URL de opción: $sourceOptionUrl (Opción ${index + 1})")

            val optionPageResponse = app.get(sourceOptionUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Referer" to data // Usar la URL original como referer
            ), allowRedirects = true)

            val optionPageDoc = optionPageResponse.document
            val finalOptionPageUrl = optionPageResponse.url
            Log.d(name, "HTML recibido para ${finalOptionPageUrl} (después de simular navegador): ${optionPageDoc.html().take(500)}...")

            // Verificar si hay redirección o script de JavaScript
            val scriptRedirect = optionPageDoc.selectFirst("script[language=\"javascript\"][type=\"text/javascript\"]")?.html()
            if (scriptRedirect != null && scriptRedirect.contains("if(self==top)")) {
                Log.w(name, "Página de opción ${sourceOptionUrl} devolvió el script de redirección 'self==top'. Intentando siguiente opción.")
                continue
            }

            // Buscar iframe anidado
            val playerIframeSrc = optionPageDoc.selectFirst("iframe[src*=\"live.saohgdasregions.fun\"]")?.attr("src")
                ?: optionPageDoc.selectFirst("iframe.embed-responsive-item")?.attr("src") // Selector alternativo

            if (playerIframeSrc.isNullOrBlank()) {
                Log.w(name, "No se encontró el iframe del reproductor (live.saohgdasregions.fun) en la página de opción: $finalOptionPageUrl")
                continue
            }
            Log.d(name, "SRC del iframe del reproductor encontrado: $playerIframeSrc")

            // Solicitar la página del iframe
            val finalStreamPageResponse = app.get(playerIframeSrc, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to finalOptionPageUrl,
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1"
            ), allowRedirects = true)

            val finalResolvedPlayerPageUrl = finalStreamPageResponse.url
            val finalStreamPageDoc = finalStreamPageResponse.document
            Log.d(name, "HTML de la página del reproductor ($finalResolvedPlayerPageUrl): ${finalStreamPageDoc.html().take(500)}...")

            val scriptContent = finalStreamPageDoc.select("script").joinToString("") { it.html() }
            Log.d(name, "Contenido combinado de scripts (primeros 500 chars) de la página del reproductor: ${scriptContent.take(500)}...")

            var streamSourceUrl: String? = null

            // Estrategia A: Clappr con Base64 anidado
            val clapprSourceRegex = "source:\\s*(?:atob\\(){1,5}\"(.*?)\"(?:\\)){1,5}".toRegex()
            val clapprMatch = clapprSourceRegex.find(scriptContent)
            if (clapprMatch != null) {
                val encodedString = clapprMatch.groupValues[1]
                Log.d(name, "Cadena Base64 encontrada en Clappr: ${encodedString.take(50)}...")
                val decodedStreamUrl = decodeBase64UntilUnchanged(encodedString)
                if (decodedStreamUrl.isNotBlank() && decodedStreamUrl.startsWith("http")) {
                    streamSourceUrl = decodedStreamUrl
                    Log.d(name, "¡URL de stream decodificada de Clappr (Base64) con éxito!: $streamSourceUrl")
                } else {
                    Log.w(name, "La URL decodificada de Clappr (Base64) está vacía, no es una URL, o no es válida.")
                }
            } else {
                Log.w(name, "No se encontró el patrón de source de Clappr con atob(s) anidados en la página: $finalResolvedPlayerPageUrl")
            }

            // Estrategia B: Regex directa .m3u8
            if (streamSourceUrl.isNullOrBlank()) {
                val m3u8Regex = "https://live\\d*\\.saohgdasregions\\.fun(?::\\d+)?/[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+(?:/index)?\\.m3u8\\?token=[a-zA-Z0-9_.-]+".toRegex()
                val m3u8Match = m3u8Regex.find(scriptContent)
                if (m3u8Match != null) {
                    streamSourceUrl = m3u8Match.value.replace("&", "&")
                    Log.d(name, "¡URL de stream .m3u8 encontrada por Regex directa (fallback)!: $streamSourceUrl")
                } else {
                    Log.w(name, "No se encontró la URL del stream .m3u8 con el Regex en la página $finalResolvedPlayerPageUrl")
                }
            }

            if (streamSourceUrl != null) {
                callback(
                    newExtractorLink(
                        source = "TV por Internet",
                        name = "Canal de TV (Opción ${index + 1})",
                        url = streamSourceUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = getQualityFromName("Normal")
                        this.referer = finalResolvedPlayerPageUrl
                    }
                )
                streamFound = true
                break
            }
        }

        if (!streamFound) {
            Log.w(name, "¡No se pudo encontrar ninguna URL de stream válida después de probar todas las opciones para $data!")
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