package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URL
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import android.util.Log // Importación necesaria para android.util.Log

class CablevisionhdProvider : MainAPI() {

    // Define un TAG para tus logs, así es más fácil encontrarlos en logcat
    private val TAG = "CablevisionHD_Plugin"

    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "CablevisionHd"
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
                val decodedBytes = Base64.decode(decodedString, Base64.DEFAULT)
                String(decodedBytes)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Base64 decoding failed for: $decodedString") // Log de depuración
                break
            }
        }

        return decodedString
    }

    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium")

    val deportesCat = setOf(
        "TUDN",
        "WWE",
        "Afizzionados",
        "Gol Perú",
        "Gol TV",
        "TNT SPORTS",
        "Fox Sports Premium",
        "TYC Sports",
        "Movistar Deportes (Perú)",
        "Movistar La Liga",
        "Movistar Liga De Campeones",
        "Dazn F1",
        "Dazn La Liga",
        "Bein La Liga",
        "Bein Sports Extra",
        "Directv Sports",
        "Directv Sports 2",
        "Directv Sports Plus",
        "Espn Deportes",
        "Espn Extra",
        "Espn Premium",
        "Espn",
        "Espn 2",
        "Espn 3",
        "Espn 4",
        "Espn Mexico",
        "Espn 2 Mexico",
        "Espn 3 Mexico",
        "Fox Deportes",
        "Fox Sports",
        "Fox Sports 2",
        "Fox Sports 3",
        "Fox Sports Mexico",
        "Fox Sports 2 Mexico",
        "Fox Sports 3 Mexico",
    )

    val entretenimientoCat = setOf(
        "Telefe",
        "El Trece",
        "Televisión Pública",
        "Telemundo Puerto rico",
        "Univisión",
        "Univisión Tlnovelas",
        "Pasiones",
        "Caracol",
        "RCN",
        "Latina",
        "America TV",
        "Willax TV",
        "ATV",
        "Las Estrellas",
        "Tl Novelas",
        "Galavision",
        "Azteca 7",
        "Azteca Uno",
        "Canal 5",
        "Distrito Comedia",
    )

    val noticiasCat = setOf(
        "Telemundo 51",
    )

    val peliculasCat = setOf(
        "Movistar Accion",
        "Movistar Drama",
        "Universal Channel",
        "TNT",
        "TNT Series",
        "Star Channel",
        "Star Action",
        "Star Series",
        "Cinemax",
        "Space",
        "Syfy",
        "Warner Channel",
        "Warner Channel (México)",
        "Cinecanal",
        "FX",
        "AXN",
        "AMC",
        "Studio Universal",
        "Multipremier",
        "Golden",
        "Golden Plus",
        "Golden Edge",
        "Golden Premier",
        "Golden Premier 2",
        "Sony",
        "DHE",
        "NEXT HD",
    )

    val infantilCat = setOf(
        "Cartoon Network",
        "Tooncast",
        "Cartoonito",
        "Disney Channel",
        "Disney JR",
        "Nick",
    )

    val educacionCat = setOf(
        "Discovery Channel",
        "Discovery World",
        "Discovery Theater",
        "Discovery Science",
        "Discovery Familia",
        "History",
        "History 2",
        "Animal Planet",
        "Nat Geo",
        "Nat Geo Mundo",
    )

    val dos47Cat = setOf(
        "24/7",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "Inicia getMainPage") // Log de depuración
        val items = ArrayList<HomePageList>()
        // Cambiado para solo procesar "Todos" por ahora para simplificar el log del HTML
        val urls = listOf(
            Pair("Todos", mainUrl),
        )
        urls.apmap { (name, url) ->
            Log.d(TAG, "Intentando obtener documento para categoría $name en $url") // Log de depuración
            try {
                // *** CAMBIO CLAVE AQUÍ: AÑADIMOS HEADERS A app.get() ***
                val response = app.get(url, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "es-ES,es;q=0.5",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1"
                ))
                val html = response.text // Obtiene el HTML crudo como String
                Log.d(TAG, "HTML completo recibido para $name:\n$html") // LOGUEA EL HTML COMPLETO
                val doc = response.document // Convierte a documento Jsoup para el resto del proceso

                Log.d(TAG, "Documento obtenido para $name. Elementos encontrados antes de filtrar: ${doc.select("div.page-scroll div#page_container.page-container.bg-move-effect div div#canales.row div.canal-item.col-6.col-xs-6.col-sm-6.col-md-3.col-lg-3").size}") // Log de depuración

                val home = doc.select("div.page-scroll div#page_container.page-container.bg-move-effect div div#canales.row div.canal-item.col-6.col-xs-6.col-sm-6.col-md-3.col-lg-3").filterNot { element ->
                    val text = element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                        ?: ""
                    val isFilteredOut = nowAllowed.any {
                        text.contains(it, ignoreCase = true)
                    } || text.isBlank()
                    if (isFilteredOut) {
                        Log.d(TAG, "Filtrando por nowAllowed/isBlank: $text (Categoría: $name)") // Log de depuración
                    }
                    isFilteredOut
                }.filter {
                    val text = it.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()?.trim()
                        ?: ""
                    val matchesCategory = when (name) {
                        "Deportes" -> deportesCat.any { text.equals(it, ignoreCase = true) }
                        "Entretenimiento" -> entretenimientoCat.any { text.equals(it, ignoreCase = true) }
                        "Noticias" -> noticiasCat.any { text.equals(it, ignoreCase = true) }
                        "Peliculas" -> peliculasCat.any { text.equals(it, ignoreCase = true) }
                        "Infantil" -> infantilCat.any { text.equals(it, ignoreCase = true) }
                        "Educacion" -> educacionCat.any { text.equals(it, ignoreCase = true) }
                        "24/7" -> dos47Cat.any { text.contains(it, ignoreCase = true) }
                        "Todos" -> true
                        else -> true
                    }
                    if (!matchesCategory) {
                        Log.d(TAG, "Filtrando por NO coincidir con categoría $name: $text") // Log de depuración
                    }
                    matchesCategory
                }.map {
                    val title = it.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                        ?: ""
                    val img = it.selectFirst("div.lm-canal.lm-info-block.gray-default a div.container-image img")?.attr("src")
                        ?: ""
                    val link = it.selectFirst("div.lm-canal.lm-info-block.gray-default a")?.attr("href")
                        ?: ""
                    Log.d(TAG, "Canal detectado: Título='$title', Img='$img', Link='$link' (Categoría: $name)") // Log de depuración
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
                Log.d(TAG, "Categoría $name, elementos finales añadidos: ${home.size}") // Log de depuración
                items.add(HomePageList(name, home, true))
            } catch (e: Exception) {
                Log.e(TAG, "Error en getMainPage para categoría $name: ${e.message}") // Log de error
                Log.e(TAG, "Stacktrace: ${e.stackTraceToString()}") // Stacktrace completo del error
            }
        }
        Log.d(TAG, "Terminó getMainPage. Total de listas de HomePageList: ${items.size}") // Log de depuración
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "Inicia search con query: $query") // Log de depuración
        val url = mainUrl
        val doc = app.get(url).document
        val searchResults = doc.select("div.page-scroll div#page_container.page-container.bg-move-effect div div#canales.row div.canal-item.col-6.col-xs-6.col-sm-6.col-md-3.col-lg-3").filterNot { element ->
            val text = element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                ?: ""
            nowAllowed.any {
                text.contains(it, ignoreCase = true)
            } || text.isBlank()
        }.filter { element ->
            val title = element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
            title?.contains(query, ignoreCase = true) ?: false
        }.map {
            val title = it.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                ?: ""
            val img = it.selectFirst("div.lm-canal.lm-info-block.gray-default a div.container-image img")?.attr("src")
                ?: ""
            val link = it.selectFirst("div.lm-canal.lm-info-block.gray-default a")?.attr("href")
                ?: ""
            Log.d(TAG, "Canal encontrado en búsqueda: Título='$title'") // Log de depuración
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
        Log.d(TAG, "Terminó search. Resultados: ${searchResults.size}") // Log de depuración
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "Inicia load con URL: $url") // Log de depuración
        val doc = app.get(url).document
        val poster = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title div.block-title div.section.mt-2 div.card.bg-dark.text-white div.card-body img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"), "/p/original/")
            ?: ""
        val title = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title h2")?.text()
            ?: ""
        val desc = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title div.block-title div.section.mt-2 div.card.bg-dark.text-white div.card-body div.info")?.text()
            ?: ""

        Log.d(TAG, "Load - Título: $title, Poster: $poster") // Log de depuración
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
        Log.d(TAG, "Inicia loadLinks con data: $data") // Log de depuración
        app.get(data).document.select("a.btn.btn-md").map {
            val trembedlink = it.attr("href")
            Log.d(TAG, "Encontrado trembedlink: $trembedlink") // Log de depuración
            if (trembedlink.contains("/stream")) {
                val tremrequest = app.get(trembedlink, headers = mapOf(
                    "Host" to "www.cablevisionhd.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Referer" to data,
                    "Alt-Used" to "www.cablevisionhd.com",
                    "Connection" to "keep-alive",
                    "Cookie" to "TawkConnectionTime=0; twk_idm_key=qMfE5UE9JTs3JUBCtVUR1",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin",
                )).document
                val trembedlink2 = tremrequest.selectFirst("iframe")?.attr("src") ?: ""
                Log.d(TAG, "trembedlink2 (iframe src): $trembedlink2") // Log de depuración
                if (trembedlink2.isNotBlank()) {
                    val tremrequest2 = app.get(trembedlink2, headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Referer" to mainUrl,
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "cross-site",
                    )).document
                    val scriptPacked = tremrequest2.select("script").find { it.html().contains("function(p,a,c,k,e,d)") }?.html()
                    Log.d(TAG, "Script packed detectado: ${scriptPacked != null}") // Log de depuración
                    val script = JsUnpacker(scriptPacked)
                    if (script.detect()) {
                        val regex = """MARIOCSCryptOld\("(.*?)"\)""".toRegex()
                        val match = regex.find(script.unpack() ?: "")
                        val hash = match?.groupValues?.get(1) ?: ""
                        Log.d(TAG, "Hash extraído: $hash") // Log de depuración
                        val extractedurl = decodeBase64UntilUnchanged(hash)
                        Log.d(TAG, "URL extraída/decodificada: $extractedurl") // Log de depuración
                        if (extractedurl.isNotBlank()) {
                            callback(
                                newExtractorLink(
                                    it.text() ?: getHostUrl(extractedurl),
                                    it.text() ?: getHostUrl(extractedurl),
                                    extractedurl,
                                    if (extractedurl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    referer = "${getBaseUrl(extractedurl)}/"
                                    quality = getQualityFromName("")
                                }
                            )
                            Log.d(TAG, "ExtractorLink añadido para $extractedurl") // Log de depuración
                        } else {
                            Log.d(TAG, "URL extraída/decodificada está vacía.") // Log de depuración
                        }
                    } else {
                        Log.d(TAG, "JsUnpacker no detectó el script packed.") // Log de depuración
                    }
                } else {
                    Log.d(TAG, "trembedlink2 (iframe src) está vacío.") // Log de depuración
                }
            } else {
                Log.d(TAG, "trembedlink no contiene '/stream': $trembedlink") // Log de depuración
            }
        }
        Log.d(TAG, "Terminó loadLinks.") // Log de depuración
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