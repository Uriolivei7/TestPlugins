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
        data: String, // data es la URL de la página de detalle del canal (ej: https://www.tvporinternet2.com/channel/americatv)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // PASO 1: Obtener el SRC del IFRAME principal de la página de detalle del canal.
        // Este iframe es el que realmente carga el contenido del stream.
        val mainChannelPageDoc = app.get(data).document
        val firstIframeSrc = mainChannelPageDoc.selectFirst("iframe[name=\"player\"]")?.attr("src")
        Log.d(name, "SRC del PRIMER Iframe (player): $firstIframeSrc")

        if (firstIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se encontr?? el SRC del primer iframe principal en la p??gina: $data")
            return false
        }

        // PASO 2: Solicitar la p??gina que carga el primer iframe.
        // Esta p??gina (ej: https://www.tvporinternet2.com/live/americatv.php)
        // contiene un SEGUNDO iframe con el stream real.
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
        // Este es el iframe que apunta directamente al servidor del stream.
        val finalStreamIframeSrc = secondIframePageDoc.selectFirst("iframe.embed-responsive-item[name=\"iframe\"]")?.attr("src")
        Log.d(name, "SRC del SEGUNDO Iframe (stream): $finalStreamIframeSrc")

        if (finalStreamIframeSrc.isNullOrBlank()) {
            Log.w(name, "No se encontr?? el SRC del segundo iframe de stream en la p??gina: $firstIframeSrc")
            return false
        }

        // PASO 4: Solicitar la p??gina del segundo iframe para obtener el script con el .m3u8
        val finalStreamPageDoc = app.get(finalStreamIframeSrc, headers = mapOf(
            // El Host y Alt-Used ahora deben coincidir con el dominio del segundo iframe src
            "Host" to URL(finalStreamIframeSrc).host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to firstIframeSrc, // El referer es la p??gina del primer iframe
            "Alt-Used" to URL(finalStreamIframeSrc).host,
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site", // Ahora es cross-site porque el dominio del iframe es diferente
        )).document

        val finalResolvedUrl = finalStreamPageDoc.location()
        Log.d(name, "URL de la p??gina de stream resuelta: $finalResolvedUrl")

        val scriptContent = finalStreamPageDoc.select("script").joinToString("") { it.html() }
        Log.d(name, "Contenido combinado de scripts (primeros 500 chars): ${scriptContent.take(500)}...")

        // Nueva regex basada en las capturas de red. Buscamos cualquier .m3u8 en el dominio saohgdasregions.fun
        // La parte del token es variable, así que capturamos todo lo que viene después de ".m3u8?token="
        val m3u8Regex = "https://live\\d*\\.saohgdasregions\\.fun:\\d+/[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+(?:/index)?\\.m3u8\\?token=[a-zA-Z0-9_-]+".toRegex()

        val matchResult = m3u8Regex.find(scriptContent)

        if (matchResult != null) {
            var extractedurl = matchResult.value
            extractedurl = extractedurl.replace("&amp;", "&")
            Log.d(name, "¡URL de stream .m3u8 encontrada por Regex!: $extractedurl")

            callback(
                newExtractorLink(
                    source = "TV por Internet",
                    name = "Canal de TV",
                    url = extractedurl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = getQualityFromName("Normal")
                    this.referer = finalResolvedUrl // Referer es la URL de la p??gina que contiene el script
                }
            )
        } else {
            Log.w(name, "No se encontr?? la URL del stream .m3u8 con el Regex en la p??gina $finalResolvedUrl")

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