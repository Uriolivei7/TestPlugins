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
        Log.d(name, "HTML COMPLETO de mainChannelPageDoc: ${mainChannelPageDoc.html()}") // DEBUG: Volcar HTML completo
        val firstIframeSrc = mainChannelPageDoc.selectFirst("iframe[name=\"player\"]")?.attr("src")
        Log.d(name, "SRC del PRIMER Iframe (player): $firstIframeSrc")

        if (firstIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se encontró el SRC del primer iframe principal en la página: $data")
            return false
        }

        val channelName = firstIframeSrc.split("/").last().replace(".php", "")
        val finalStreamIframeSrc = "https://live.saohgdasregions.fun/stream.php?canal=$channelName&target=1"
        Log.d(name, "URL del stream FINAL construida: $finalStreamIframeSrc")

        if (finalStreamIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se pudo construir la URL final del stream.")
            return false
        }

        // PASO 2: Solicitar la página final del stream.
        val finalStreamPageResponse = app.get(finalStreamIframeSrc, headers = mapOf(
            "Host" to URL(finalStreamIframeSrc).host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to firstIframeSrc, // EL REFERER ahora es la página del primer iframe
            "Alt-Used" to URL(finalStreamIframeSrc).host,
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Accept-Encoding" to "gzip, deflate, br"
        ))
        val finalResolvedUrl = finalStreamPageResponse.url
        val finalStreamPageDoc = finalStreamPageResponse.document
        Log.d(name, "HTML COMPLETO de finalStreamPageDoc (página del stream real): ${finalStreamPageDoc.html()}") // DEBUG: Volcar HTML completo

        Log.d(name, "URL de la página de stream resuelta: $finalResolvedUrl")
        Log.d(name, "Contenido de la página de stream resuelta (primeros 500 chars): ${finalStreamPageDoc.html().take(500)}...")

        val scriptContent = finalStreamPageDoc.select("script").joinToString("") { it.html() }
        Log.d(name, "Contenido combinado de scripts (primeros 500 chars): ${scriptContent.take(500)}...")

        var streamSourceUrl: String? = null

        // Nuevo paso: Extraer y decodificar la cadena Base64 del script de Clappr
        val clapprSourceRegex = "source:\\s*atob\\(atob\\(atob\\(atob\\(\"(.*?)\"\\)\\)\\)\\)".toRegex()
        val clapprMatch = clapprSourceRegex.find(scriptContent)

        if (clapprMatch != null) {
            val encodedString = clapprMatch.groupValues[1]
            Log.d(name, "Cadena Base64 encontrada en Clappr: ${encodedString.take(50)}...")
            val decodedStreamUrl = decodeBase64UntilUnchanged(encodedString)
            if (decodedStreamUrl.isNotBlank()) {
                streamSourceUrl = decodedStreamUrl
                Log.d(name, "¡URL de stream decodificada de Clappr (Base64) con éxito!: $streamSourceUrl")
            } else {
                Log.w(name, "La URL decodificada de Clappr (Base64) está vacía o en blanco.")
            }
        } else {
            Log.w(name, "No se encontró el patrón de source de Clappr en la página: $finalResolvedUrl")
            // Si la regex de Clappr falla, y como fallback (aunque es poco probable que se use ahora),
            // se puede mantener el intento de la regex .m3u8 directa o JsUnpacker.
            // La regex para el M3U8 es la que viste en el Network tab
            val m3u8Regex = "https://live\\d*\\.saohgdasregions\\.fun:\\d+/[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+(?:/index)?\\.m3u8\\?token=[a-zA-Z0-9_-]+".toRegex()
            val m3u8Match = m3u8Regex.find(scriptContent) // Buscamos en el contenido de los scripts

            if (m3u8Match != null) {
                streamSourceUrl = m3u8Match.value.replace("&amp;", "&")
                Log.d(name, "¡URL de stream .m3u8 encontrada por Regex directa (fallback)!: $streamSourceUrl")
            } else {
                Log.w(name, "No se encontró la URL del stream .m3u8 con el Regex en la página $finalResolvedUrl")
                val oldScriptPacked = finalStreamPageDoc.select("script").find { it.html().contains("function(p,a,c,k,e,d)") }?.html()
                Log.d(name, "Script Packed encontrado (lógica antigua): ${oldScriptPacked != null}")

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
                            streamSourceUrl = extractedurl
                        } else {
                            Log.w(name, "extractedurl está vacía o en blanco después de la decodificación (método antiguo) para hash: ${hash.take(50)}...")
                        }
                    } else {
                        Log.w(name, "JsUnpacker no detectó un script empaquetado (método antiguo) en $finalResolvedUrl")
                    }
                } else {
                    Log.w(name, "No se encontró el script 'function(p,a,c,k,e,d)' ni el patrón MARIOCSCryptOld para ${finalResolvedUrl}.")
                }
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
                    this.referer = finalResolvedUrl // Referer para el M3U8 es la URL de la página del stream.
                }
            )
        } else {
            Log.w(name, "¡No se pudo encontrar ninguna URL de stream válida para $data!")
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