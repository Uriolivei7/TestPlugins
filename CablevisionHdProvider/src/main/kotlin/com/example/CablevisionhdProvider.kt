package com.example

import android.util.Base64
import android.util.Log // Asegúrate de tener esta importación
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URL // Asegúrate de tener esta importación

class CablevisionhdProvider : MainAPI() {

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

    // Función para decodificar Base64 repetidamente hasta que no haya cambios
    private fun decodeBase64UntilUnchanged(encodedString: String): String {
        var decodedString = encodedString
        var previousDecodedString = ""
        while (decodedString != previousDecodedString) {
            previousDecodedString = decodedString
            decodedString = try {
                // Eliminar cualquier relleno de URL Base64 que pueda causar problemas si no es estricto
                val cleanedString = decodedString.replace('-', '+').replace('_', '/')
                val decodedBytes = Base64.decode(cleanedString, Base64.DEFAULT)
                String(decodedBytes)
            } catch (e: IllegalArgumentException) {
                // Si la decodificación falla (por ejemplo, no es una base64 válida), rompe el bucle
                Log.e(name, "Error decodificando Base64: ${e.message}")
                break
            }
        }
        return decodedString
    }

    // Listas de categorías (sin cambios, las mantengo)
    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium", "Vuelvete Premium (No ADS)", "Únete a Whatsapp", "Únete a Telegram", "¿Nos invitas el cafe?")
    val deportesCat = setOf("TUDN", "WWE", "Afizzionados", "Gol Perú", "Gol TV", "TNT SPORTS", "Fox Sports Premium", "TYC Sports", "Movistar Deportes (Perú)", "Movistar La Liga", "Movistar Liga De Campeones", "Dazn F1", "Dazn La Liga", "Bein La Liga", "Bein Sports Extra", "Directv Sports", "Directv Sports 2", "Directv Sports Plus", "Espn Deportes", "Espn Extra", "Espn Premium", "Espn", "Espn 2", "Espn 3", "Espn 4", "Espn Mexico", "Espn 2 Mexico", "Espn 3 Mexico", "Fox Deportes", "Fox Sports", "Fox Sports 2", "Fox Sports 3", "Fox Sports Mexico", "Fox Sports 2 Mexico", "Fox Sports 3 Mexico",)
    val entretenimientoCat = setOf("Telefe", "El Trece", "Televisión Pública", "Telemundo Puerto rico", "Univisión", "Univisión Tlnovelas", "Pasiones", "Caracol", "RCN", "Latina", "America TV", "Willax TV", "ATV", "Las Estrellas", "Tl Novelas", "Galavision", "Azteca 7", "Azteca Uno", "Canal 5", "Distrito Comedia",)
    val noticiasCat = setOf("Telemundo 51",)
    val peliculasCat = setOf("Movistar Accion", "Movistar Drama", "Universal Channel", "TNT", "TNT Series", "Star Channel", "Star Action", "Star Series", "Cinemax", "Space", "Syfy", "Warner Channel", "Warner Channel (México)", "Cinecanal", "FX", "AXN", "AMC", "Studio Universal", "Multipremier", "Golden", "Golden Plus", "Golden Edge", "Golden Premier", "Golden Premier 2", "Sony", "DHE", "NEXT HD",)
    val infantilCat = setOf("Cartoon Network", "Tooncast", "Cartoonito", "Disney Channel", "Disney JR", "Nick",)
    val educacionCat = setOf("Discovery Channel", "Discovery World", "Discovery Theater", "Discovery Science", "Discovery Familia", "History", "History 2", "Animal Planet", "Nat Geo", "Nat Geo Mundo",)
    val dos47Cat = setOf("24/7",)

    // MainPage, Search y Load (sin cambios significativos, se mantienen)
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
        val poster = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title div.block-title div.section.mt-2 div.card.bg-dark.text-white div.card-body img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"), "/p/original/")
            ?: ""
        val title = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title h2")?.text()
            ?: ""
        val desc = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title div.block-title div.section.mt-2 div.card.bg-dark.text-white div.card-body div.info")?.text()
            ?: ""

        return newMovieLoadResponse(
            title,
            url, TvType.Live, url
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = desc
        }

    }

    // Función loadLinks modificada
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Itera sobre los elementos que contienen los enlaces de stream
        app.get(data).document.select("a.btn.btn-md").apmap { // Usar apmap para ejecución concurrente si es posible, o map si apmap no está disponible aquí.
            val trembedlink = it.attr("href")
            if (trembedlink.contains("/stream")) {
                Log.d(name, "TrembedLink: $trembedlink") // LOG 1

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
                Log.d(name, "TrembedLink2 (iframe src): $trembedlink2") // LOG 2

                if (trembedlink2.isBlank()) {
                    Log.w(name, "TrembedLink2 está vacía, no se encontró iframe en $trembedlink. Saltando esta iteración.") // LOG 3
                    return@apmap // Usar @apmap para salir de la lambda si estás dentro de apmap
                }

                val tremrequest2 = app.get(trembedlink2, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Referer" to trembedlink, // Referer ahora es el trembedlink original (cablevisionhd)
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                )).document

                val finalRedirectedUrl = tremrequest2.location()
                Log.d(name, "URL final después de redirección (TrembedLink2): $finalRedirectedUrl")

                // 1. *** NUEVA LÓGICA DE EXTRACCIÓN PARA TVPORINTERNET2.COM (PRIORITARIA) ***
                val scriptContent = tremrequest2.select("script").joinToString("") { it.html() }
                Log.d(name, "Contenido combinado de scripts (primeros 500 chars): ${scriptContent.take(500)}...")

                // Este Regex debe coincidir con el formato exacto de la URL del m3u8.
                // AJÚSTALO SI ES NECESARIO DESPUÉS DE LA INSPECCIÓN MANUAL.
                // Ejemplo: https://live.saohgdasregions.fun:9092/MzguMjUwLjE1OS45NQ==/5_.m3u8?token=Dlx6RTyEJGkrRMo0_N_-IA&expires=1750092930
                val m3u8Regex = "https://live\\.saohgdasregions\\.fun:9092/[a-zA-Z0-9_-]+=/?/[a-zA-Z0-9_]+\\.m3u8\\?token=[a-zA-Z0-9_-]+(?:&expires=\\d+)?".toRegex()

                val matchResult = m3u8Regex.find(scriptContent)

                if (matchResult != null) {
                    val extractedurl = matchResult.value
                    Log.d(name, "¡URL de stream .m3u8 encontrada por Regex!: $extractedurl")

                    callback(
                        newExtractorLink(
                            source = "CablevisionHD", // Puedes usar este nombre genérico o buscar el nombre del canal en el HTML
                            name = "Canal de TV",    // Ajusta según el nombre real del canal si lo obtienes dinámicamente
                            url = extractedurl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = getQualityFromName("Normal") // O "720p", "1080p" si puedes determinarlo
                            this.referer = URL(extractedurl).protocol + "://" + URL(extractedurl).authority
                        }
                    )
                    // No es necesario retornar true aquí, ya que apmap procesa todos los elementos.
                    // Si encuentras el link, simplemente se añade a la callback.
                    return@apmap
                } else {
                    Log.w(name, "No se encontró la URL del stream .m3u8 con el nuevo Regex en la página $finalRedirectedUrl")

                    // 2. *** Lógica de respaldo (JsUnpacker antigua) - OPCIONAL ***
                    // Esto se intentará SOLO si el Regex del m3u8 falla.
                    val oldScriptPacked = tremrequest2.select("script").find { it.html().contains("function(p,a,c,k,e,d)") }?.html()
                    Log.d(name, "Script Packed encontrado (lógica antigua): ${oldScriptPacked != null}") // LOG 4 (modificado)

                    if (oldScriptPacked != null) {
                        val script = JsUnpacker(oldScriptPacked)
                        Log.d(name, "JsUnpacker detect: ${script.detect()}") // LOG 6

                        if (script.detect()) {
                            val unpackedScript = script.unpack()
                            Log.d(name, "Script antiguo desempaquetado: ${unpackedScript?.take(200)}...") // LOG 7

                            val mariocRegex = """MARIOCSCryptOld\("(.*?)"\)""".toRegex()
                            val mariocMatch = mariocRegex.find(unpackedScript ?: "")
                            Log.d(name, "Regex Match found (MARIOCSCryptOld): ${mariocMatch != null}") // LOG 8 (modificado)

                            val hash = mariocMatch?.groupValues?.get(1) ?: ""
                            Log.d(name, "Hash extraído (antes de decodificar): ${hash.take(50)}...") // LOG 9

                            val extractedurl = decodeBase64UntilUnchanged(hash)
                            Log.d(name, "URL extraída (final, método antiguo): $extractedurl") // LOG 10 (modificado)

                            if (extractedurl.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        source = "CablevisionHD",
                                        name = "Canal de TV (Old Method)",
                                        url = extractedurl,
                                        type = ExtractorLinkType.M3U8 // Asumimos M3U8 para este tipo de stream
                                    ) {
                                        this.quality = getQualityFromName("Normal")
                                        this.referer = URL(extractedurl).protocol + "://" + URL(extractedurl).authority
                                    }
                                )
                            } else {
                                Log.w(name, "extractedurl está vacía o en blanco después de la decodificación (método antiguo) para hash: ${hash.take(50)}...") // LOG 11 (modificado)
                            }
                        } else {
                            Log.w(name, "JsUnpacker no detectó un script empaquetado (método antiguo) en $trembedlink2") // LOG 12 (modificado)
                        }
                    } else {
                        Log.w(name, "No se encontró el script 'function(p,a,c,k,e,d)' ni el patrón MARIOCSCryptOld para ${finalRedirectedUrl}.")
                    }
                }
            }
        }
        // Retorna true al final, indicando que la función de extracción se ejecutó.
        // Las llamadas a `callback` se hacen dentro del bucle.
        return true
    }

    // Funciones auxiliares (sin cambios, se mantienen)
    fun getBaseUrl(urlString: String): String {
        val url = URL(urlString)
        return "${url.protocol}://${url.host}"
    }

    fun getHostUrl(urlString: String): String {
        val url = URL(urlString)
        return url.host
    }
}