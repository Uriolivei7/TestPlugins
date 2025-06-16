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
    override var name = "CablevisionHd" // Podrías considerar cambiar esto a "TV por Internet" si quieres reflejar el nuevo nombre
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
            // Estos selectores parecen seguir funcionando bien según tu feedback
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
        // Estos selectores parecen seguir funcionando bien según tu feedback
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

        // --- INICIO DE AJUSTES PARA POSTER Y TITULO ---
        // Nuevo selector para el título basado en tus capturas de pantalla
        val title = doc.selectFirst("h1.text-3xl.font-bold.mb-4")?.text()
            ?: "" // Si no se encuentra, se deja vacío

        // Según las capturas de pantalla de la página de canal individual, no hay un poster grande y claro.
        // Si el poster debe venir de la página principal, se debería pasar en la 'data' de alguna forma
        // o si hay un favicon, se podría usar. Por ahora, lo dejamos vacío si no se encuentra en esta página.
        val poster = doc.selectFirst("link[rel=\"shortcut icon\"]")?.attr("href") ?: ""
        // Esta línea intenta obtener el favicon de la página, que a menudo es el logo.
        // Si hay un elemento de imagen específico para el poster en la página de detalle que no has capturado,
        // necesitaríamos el selector para ese.

        val desc: String? = null // No hay un elemento claro para la descripción en las capturas.
        // --- FIN DE AJUSTES PARA POSTER Y TITULO ---

        return newMovieLoadResponse(
            title,
            url, TvType.Live, url
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster) // Usamos el mismo para background si no hay uno específico
            this.plot = desc
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("a.btn.btn-md").apmap {
            // Este selector a.btn.btn-md parece ser de la web anterior,
            // según las capturas, los enlaces de opciones están en un div.flex.flex-wrap.gap-3 a
            // Cambiaremos el selector para los enlaces de "Opción 1", "Opción 2", etc.
            val trembedlink = it.attr("href")

            // Asegurarse de que el enlace sea relevante, aunque con el nuevo selector no debería haber botones que no lo sean.
            if (trembedlink.contains("/live")) {
                Log.d(name, "TrembedLink: $trembedlink")

                val tremrequestDoc = app.get(trembedlink, headers = mapOf(
                    "Host" to "www.tvporinternet2.com", // *** CAMBIO CRÍTICO A LA NUEVA URL ***
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Referer" to data,
                    "Alt-Used" to "www.tvporinternet2.com", // *** CAMBIO CRÍTICO A LA NUEVA URL ***
                    "Connection" to "keep-alive",
                    "Cookie" to "TawkConnectionTime=0; twk_idm_key=qMfE5UE9JTs3JUBCtVUR1",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "iframe", // Aunque no sea un iframe directo, a veces ayuda
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin",
                )).document

                // En tvporinternet2.com, la página a la que apuntan los enlaces de "Opción X"
                // YA contiene directamente el script con la URL del stream, no un iframe que redirige a otro sitio.
                // Por lo tanto, el 'iframe'?.attr("src") no es necesario aquí para trembedlink2.
                // En su lugar, el 'trembedlink' YA ES lo que solía ser trembedlink2.
                // El log 'TrembedLink2 (iframe src)' ahora será 'URL de la página de stream'.

                val finalRedirectedUrl = tremrequestDoc.location() // La URL final de la solicitud
                Log.d(name, "URL de la página de stream: $finalRedirectedUrl")

                val scriptContent = tremrequestDoc.select("script").joinToString("") { it.html() }
                Log.d(name, "Contenido combinado de scripts (primeros 500 chars): ${scriptContent.take(500)}...")

                // El Regex para .m3u8 sigue siendo válido ya que las URLs de stream no cambiaron su estructura
                val m3u8Regex = "https://live\\d*\\.saohgdasregions\\.fun:\\d+/[a-zA-Z0-9_-]+(?:/index)?\\.m3u8\\?token=[a-zA-Z0-9_-]+(?:&amp;remote=\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})?(?:&expires=\\d+)?".toRegex()

                val matchResult = m3u8Regex.find(scriptContent)

                if (matchResult != null) {
                    var extractedurl = matchResult.value
                    extractedurl = extractedurl.replace("&amp;", "&")
                    Log.d(name, "¡URL de stream .m3u8 encontrada por Regex!: $extractedurl")

                    callback(
                        newExtractorLink(
                            source = "TV por Internet", // Nombre del extractor
                            name = "Canal de TV",
                            url = extractedurl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = getQualityFromName("Normal")
                            // El referer debe ser la URL de la página que contiene el script con el stream (finalRedirectedUrl)
                            this.referer = finalRedirectedUrl
                        }
                    )
                    return@apmap
                } else {
                    Log.w(name, "No se encontró la URL del stream .m3u8 con el Regex en la página $finalRedirectedUrl")

                    // Lógica de respaldo (JsUnpacker antigua) si el nuevo método falla
                    val oldScriptPacked = tremrequestDoc.select("script").find { it.html().contains("function(p,a,c,k,e,d)") }?.html()
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
                                callback(
                                    newExtractorLink(
                                        source = "TV por Internet (Old Method)",
                                        name = "Canal de TV (Old Method)",
                                        url = extractedurl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = getQualityFromName("Normal")
                                        this.referer = finalRedirectedUrl
                                    }
                                )
                            } else {
                                Log.w(name, "extractedurl está vacía o en blanco después de la decodificación (método antiguo) para hash: ${hash.take(50)}...")
                            }
                        } else {
                            Log.w(name, "JsUnpacker no detectó un script empaquetado (método antiguo) en $finalRedirectedUrl")
                        }
                    } else {
                        Log.w(name, "No se encontró el script 'function(p,a,c,k,e,d)' ni el patrón MARIOCSCryptOld para ${finalRedirectedUrl}.")
                    }
                }
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