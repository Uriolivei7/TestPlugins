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
    override var name = "TVporInternet" // Nombre actualizado para los logs
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
        while (decodedString != previousDecodedString) {
            previousDecodedString = decodedString
            decodedString = try {
                val cleanedString = decodedString.replace('-', '+').replace('_', '/')
                val decodedBytes = Base64.decode(cleanedString, Base64.DEFAULT)
                String(decodedBytes)
            } catch (e: IllegalArgumentException) {
                Log.e(name, "Error decodificando Base64: ${e.message}")
                break
            }
        }
        return decodedString
    }

    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium", "Vuelvete Premium (No ADS)", "Únete a Whatsapp", "Únete a Telegram", "¿Nos invitas el cafe?")
    val deportesCat = setOf("TUDN", "WWE", "Afizzionados", "Gol Perú", "Gol TV", "TNT SPORTS", "Fox Sports Premium", "TYC Sports", "Movistar Deportes (Perú)", "Movistar La Liga", "Movistar Liga De Campeones", "Dazn F1", "Dazn La Liga", "Bein La Liga", "Bein Sports Extra", "Directv Sports", "Directv Sports 2", "Directv Sports Plus", "Espn Deportes", "Espn Extra", "Espn Premium", "Espn", "Espn 2", "Espn 3", "Espn 4", "Espn Mexico", "Espn 2 Mexico", "Espn 3 Mexico", "Fox Deportes", "Fox Sports", "Fox Sports 2", "Fox Sports 3", "Fox Sports Mexico", "Fox Sports 2 Mexico", "Fox Sports 3 Mexico",)
    val entretenimientoCat = setOf("Telefe", "El Trece", "Televisión Pública", "Telemundo Puerto rico", "Univisión", "Univisión Tlnovelas", "Pasiones", "Caracol", "RCN", "Latina", "America TV", "Willax TV", "ATV", "Las Estrellas", "Tl Novelas", "Galavision", "Azteca 7", "Azteca Uno", "Canal 5", "Distrito Comedia",)
    val noticiasCat = setOf("Telemundo 51",)
    val peliculasCat = setOf("Movistar Accion", "Movistar Drama", "Universal Channel", "TNT", "TNT Series", "Star Channel", "Star Action", "Star Series", "Cinemax", "Space", "Syfy", "Warner Channel", "Warner Channel (México)", "Cinecanal", "FX", "AXN", "AMC", "Studio Universal", "Multipremier", "Golden", "Golden Plus", "Golden Edge", "Golden Premier", "Golden Premier 2", "Sony", "DHE", "NEXT HD",)
    val infantilCat = setOf("Cartoon Network", "Tooncast", "Cartoonito", "Disney Channel", "Disney JR", "Nick",)
    val educacionCat = setOf("Discovery Channel", "Discovery World", "Discovery Theater", "Discovery Science", "Discovery Familia", "History", "History 2", "Animal Planet", "Nat Geo", "Nat Geo Mundo",)
    val dos47Cat = setOf("24/7",)

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
        // PASO 1: Obtener el SRC del IFRAME principal de la página de detalle del canal.
        val mainChannelPageDoc = app.get(data).document
        val firstIframeSrc = mainChannelPageDoc.selectFirst("iframe[name=\"player\"]")?.attr("src")
        Log.d(name, "SRC del PRIMER Iframe (player): $firstIframeSrc")

        if (firstIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se encontr?? el SRC del primer iframe principal en la p??gina: $data")
            return false
        }

        // PASO 2: Solicitar la p??gina que carga el primer iframe.
        val secondIframePageDoc = app.get(firstIframeSrc, headers = mapOf(
            "Host" to "www.tvporinternet2.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to data, // El referer es la p??gina de detalle del canal original
            "Alt-Used" to "www.tvporinternet2.com",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
        )).document

        // PASO 3: Obtener el SRC del SEGUNDO IFRAME dentro de la p??gina del primer iframe.
        val finalStreamIframeSrc = secondIframePageDoc.selectFirst("iframe.embed-responsive-item[name=\"iframe\"]")?.attr("src")
        Log.d(name, "SRC del SEGUNDO Iframe (stream): $finalStreamIframeSrc")

        if (finalStreamIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se encontr?? el SRC del segundo iframe de stream en la p??gina: $firstIframeSrc")
            return false
        }

        // PASO 4: Solicitar la p??gina del segundo iframe para obtener el script con el .m3u8 o la embedUrl
        // A?ADIMOS Accept-Encoding y ajustamos el Referer con getBaseUrl
        val finalStreamPageResponse = app.get(finalStreamIframeSrc, headers = mapOf(
            "Host" to URL(finalStreamIframeSrc).host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to firstIframeSrc, // EL REFERER sigue siendo la p??gina del primer iframe
            "Alt-Used" to URL(finalStreamIframeSrc).host,
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Accept-Encoding" to "gzip, deflate, br" // A?ADIDO: Muy com?n que falte y cause problemas
        ))

        // Si hay una redirecci?n, finalResolvedUrl ser? la URL final despu?s de la redirecci?n.
        // Si no, ser? la misma que finalStreamIframeSrc
        val finalResolvedUrl = finalStreamPageResponse.url // Obtener la URL final despu?s de cualquier redirecci?n
        val finalStreamPageDoc = finalStreamPageResponse.document // Obtener el documento del response

        Log.d(name, "URL de la p??gina de stream resuelta: $finalResolvedUrl")
        Log.d(name, "Contenido de la p??gina de stream resuelta (primeros 500 chars): ${finalStreamPageDoc.html().take(500)}...")


        val scriptContent = finalStreamPageDoc.select("script").joinToString("") { it.html() }
        Log.d(name, "Contenido combinado de scripts (primeros 500 chars): ${scriptContent.take(500)}...")

        // Intentamos encontrar la embedUrl primero
        val embedUrlRegex = """const embedUrl = "(https://embed\.saohgdasregions\.fun)";""".toRegex()
        val embedUrlMatch = embedUrlRegex.find(scriptContent)

        var streamSourceUrl: String? = null

        if (embedUrlMatch != null) {
            val embedUrl = embedUrlMatch.groupValues[1]
            Log.d(name, "¡embedUrl encontrada!: $embedUrl")

            // PASO 5: Solicitar la embedUrl y buscar el m3u8 allí
            val embedPageDoc = app.get(embedUrl, headers = mapOf(
                "Host" to URL(embedUrl).host,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to finalStreamIframeSrc, // El referer es la p??gina anterior
                "Alt-Used" to URL(embedUrl).host,
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Accept-Encoding" to "gzip, deflate, br" // A?ADIDO AQU? TAMBI?N
            )).document

            val embedPageContent = embedPageDoc.html() // Obtenemos todo el HTML de la página de embed
            Log.d(name, "Contenido de la p??gina embed (primeros 500 chars): ${embedPageContent.take(500)}...")

            // Regex para buscar el .m3u8 en el contenido de la página embed
            val m3u8Regex = "https://live\\d*\\.saohgdasregions\\.fun:\\d+/[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+(?:/index)?\\.m3u8\\?token=[a-zA-Z0-9_-]+".toRegex()
            val m3u8Match = m3u8Regex.find(embedPageContent)

            if (m3u8Match != null) {
                streamSourceUrl = m3u8Match.value.replace("&amp;", "&")
                Log.d(name, "¡URL de stream .m3u8 encontrada en embed page!: $streamSourceUrl")
            } else {
                Log.w(name, "No se encontr?? la URL del stream .m3u8 con el Regex en la p??gina embed: $embedUrl")
            }

        } else {
            Log.w(name, "No se encontr?? la embedUrl en el script content de $finalStreamIframeSrc")

            // Si no se encuentra la embedUrl, intentamos la regex de m3u8 directamente en el scriptContent original
            val m3u8RegexFallback = "https://live\\d*\\.saohgdasregions\\.fun:\\d+/[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+(?:/index)?\\.m3u8\\?token=[a-zA-Z0-9_-]+".toRegex()
            val matchResultFallback = m3u8RegexFallback.find(scriptContent)

            if (matchResultFallback != null) {
                streamSourceUrl = matchResultFallback.value.replace("&amp;", "&")
                Log.d(name, "¡URL de stream .m3u8 encontrada por Regex directa (fallback)!: $streamSourceUrl")
            } else {
                Log.w(name, "No se encontr?? la URL del stream .m3u8 con el Regex de fallback en la p??gina $finalResolvedUrl")
            }
        }

        if (streamSourceUrl != null) {
            callback(
                newExtractorLink(
                    source = "TV por Internet",
                    name = "Canal de TV",
                    url = streamSourceUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = getQualityFromName("Normal")
                    this.referer = finalResolvedUrl
                }
            )
        } else {
            // Lógica de respaldo (JsUnpacker antigua) si el nuevo método falla
            val oldScriptPacked = finalStreamPageDoc.select("script").find { it.html().contains("function(p,a,c,k,e,d)") }?.html()
            Log.d(name, "Script Packed encontrado (l??gica antigua): ${oldScriptPacked != null}")

            if (oldScriptPacked != null) {
                val script = JsUnpacker(oldScriptPacked)
                Log.d(name, "JsUnpacker detect: ${script.detect()}")

                if (script.detect()) {
                    val unpackedScript = script.unpack()
                    Log.d(name, "Script antiguo desempaquetado: ${unpackedScript?.take(200)}...")

                    val mariocRegex = """MARIOCSCryptOld\("(.*?)"\)""".toRegex()
                    val mariocMatch = mariocRegex.find(unpackedScript ?: "")
                    Log.d(name, "Regex Match found (MARIOCSCryptOld): ${mariocMatch != null}")

                    val hash = mariocMatch?.groupValues?.get(1) ?: ""
                    Log.d(name, "Hash extraído (antes de decodificar): ${hash.take(50)}...")

                    val extractedurl = decodeBase64UntilUnchanged(hash)
                    Log.d(name, "URL extraída (final, método antiguo): $extractedurl")

                    if (extractedurl.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = "TV por Internet (Old Method)",
                                name = "Canal de TV (Old Method)",
                                url = extractedurl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = getQualityFromName("Normal")
                                this.referer = finalResolvedUrl
                            }
                        )
                    } else {
                        Log.w(name, "extractedurl est?? vac??a o en blanco despu??s de la decodificaci??n (m??todo antiguo) para hash: ${hash.take(50)}...")
                    }
                } else {
                    Log.w(name, "JsUnpacker no detect?? un script empaquetado (m??todo antiguo) en $finalResolvedUrl")
                }
            } else {
                Log.w(name, "No se encontr?? el script 'function(p,a,c,k,e,d)' ni el patr??n MARIOCSCryptOld para ${finalResolvedUrl}.")
            }
        }
        return true
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