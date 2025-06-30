package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder
import java.net.URI
import org.jsoup.nodes.Document
import android.util.Base64 // Importar Base64 de Android
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.delay

class LacartoonsProvider:MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LACartoons"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun Document.toSearchResult():List<SearchResponse>{
        return this.select(".categorias .conjuntos-series a").map {
            val title = it.selectFirst("p.nombre-serie")?.text()
            val href = fixUrl(it.attr("href"))
            val img = fixUrl(it.selectFirst("img")!!.attr("src"))
            newTvSeriesSearchResponse(title!!, href){
                this.posterUrl = img
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get(mainUrl).document
        val home = soup.toSearchResult()
        items.add(HomePageList("Series", home))
        return HomePageResponse(items)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?utf8=✓&Titulo=$query").document
        return doc.toSearchResult()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h2.text-center")?.text()
        val description = doc.selectFirst(".informacion-serie-seccion p:contains(Reseña)")?.text()?.substringAfter("Reseña:")?.trim()
        val poster = doc.selectFirst(".imagen-serie img")?.attr("src")
        val backposter = doc.selectFirst("img.fondo-serie-seccion")?.attr("src")
        val episodes = doc.select("ul.listas-de-episodion li").map {
            val regexep = Regex("Capitulo.(\\d+)|Capitulo.(\\d+)\\-")
            val href = it.selectFirst("a")?.attr("href")
            val name = it.selectFirst("a")?.text()?.replace(regexep, "")?.replace("-","")
            val seasonnum = href?.substringAfter("t=")
            val epnum = regexep.find(name.toString())?.destructured?.component1()
            Episode(
                fixUrl(href!!),
                name,
                seasonnum.toString().toIntOrNull(),
                epnum.toString().toIntOrNull(),
            )
        }

        return newTvSeriesLoadResponse(title!!, url, TvType.Cartoon, episodes){
            this.posterUrl = fixUrl(poster!!)
            this.backgroundPosterUrl = fixUrl(backposter!!)
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String, // La URL del episodio de Lacartoons
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframeSrc = doc.selectFirst(".serie-video-informacion iframe")?.attr("src")

        if (iframeSrc == null) {
            println("${name}: No se encontró iframe para el episodio: $data")
            return false
        }

        if (iframeSrc.contains("cubeembed.rpmvid.com")) {
            println("${name}: Detectado iframe de cubeembed.rpmvid.com, procesando internamente.")
            val cubembedUrl = iframeSrc // Ej: https://cubeembed.rpmvid.com/#ur3pb
            val embedId = cubembedUrl.substringAfterLast("#")

            if (embedId.isEmpty()) {
                println("${name}: No se pudo extraer el ID del embed de Cubembed de la URL: $cubembedUrl")
                return false
            }

            // --- PASO 1: Llamada a /info?id=... (Basado en la captura de red) ---
            // Esta llamada parece ser la primera y podría ser parte de la "verificación humana"
            // Su respuesta puede no ser directamente el M3U8, pero es un paso que el navegador hace.
            val infoApiUrl = "https://cubeembed.rpmvid.com/info?id=$embedId"
            val infoApiHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.112 Safari/537.36",
                "Referer" to cubembedUrl.substringBefore("#"),
                "Origin" to cubembedUrl.substringBefore("#"),
                "Accept" to "*/*",
                "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8,en-US;q=0.7",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )

            try {
                println("${name}: Realizando solicitud GET a la API de info: ${infoApiUrl}")
                val infoResponse = app.get(infoApiUrl, headers = infoApiHeaders)
                println("${name}: Respuesta de /info (primeros 500 chars): ${infoResponse.text.take(500)}...")

                // Pequeño retraso para simular el comportamiento del navegador después de la primera llamada
                // Podría ser parte de la "resolución automática" del captcha
                delay(1000) // Esperar 1 segundo

                // --- PASO 2: Llamada a /video?id=... (Ahora que sabemos que devuelve Base64) ---
                val refererMainPage = mainUrl
                val videoApiUrl = "https://cubeembed.rpmvid.com/video?id=$embedId&w=1680&h=1050&r=${encode(refererMainPage)}" // Usar 1680x1050 de la captura original

                // Encabezados para la solicitud a /video?id=... (¡Mantener los precisos de tu captura!)
                val videoApiHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.112 Safari/537.36",
                    "Referer" to cubembedUrl.substringBefore("#"), // El referer del iframe (sin el #id)
                    "Origin" to cubembedUrl.substringBefore("#"), // El origin del iframe
                    "Accept" to "*/*",
                    "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8,en-US;q=0.7",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    // Asegúrate de que no haya cookies que el navegador envía y tú no
                )

                println("${name}: Realizando solicitud GET a la API de video: ${videoApiUrl}")
                val videoResponse = app.get(videoApiUrl, headers = videoApiHeaders, allowRedirects = true)
                val encodedString = videoResponse.text // La respuesta es la cadena Base64

                println("${name}: Cadena Base64 recibida de /video: ${encodedString.take(100)}...")

                // Decodificar la cadena Base64
                val decodedBytes = Base64.decode(encodedString, Base64.DEFAULT)
                val decodedString = String(decodedBytes, Charsets.UTF_8) // Convertir bytes a String

                println("${name}: Cadena decodificada de Base64: ${decodedString.take(500)}...")

                // Ahora, la cadena decodificada DEBERÍA contener la URL del M3U8
                // La URL del M3U8 en tu captura era: master.m3u8?v=1751082628
                // Esto sugiere que la cadena decodificada contendrá una URL como https://cdn.rpmvid.com/hls/.../master.m3u8?v=...
                // Usaremos una regex para extraerla de la cadena decodificada.
                val m3u8Regex = Regex("""(https?://[^"']*\.m3u8(?:\?[^"']*)?)""")
                val match = m3u8Regex.find(decodedString)

                if (match != null) {
                    val m3u8Url = match.groupValues[1]

                    println("${name}: ¡Éxito! URL de video M3U8 extraída de la cadena decodificada: $m3u8Url")

                    // Los encabezados para la solicitud del M3U8 real
                    val finalM3u8Headers = mapOf(
                        "Referer" to "https://cubeembed.rpmvid.com/", // El referer para el M3U8 final es el dominio base del embed
                        "Origin" to "https://cubeembed.rpmvid.com",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.112 Safari/537.36"
                    )

                    callback(
                        ExtractorLink(
                            source = "Cubembed",
                            name = "Cubembed",
                            url = m3u8Url,
                            referer = "https://cubeembed.rpmvid.com/",
                            quality = 0, // Puedes ajustar la calidad si la obtienes de alguna parte
                            type = ExtractorLinkType.M3U8,
                            headers = finalM3u8Headers // ¡Pasar los encabezados completos aquí!
                        )
                    )
                    return true
                } else {
                    println("${name}: No se encontró la URL HLS (.m3u8) en la cadena decodificada.")
                }

            } catch (e: Exception) {
                println("${name}: Error al procesar el embed de Cubembed (info/video API): ${e.message}")
                e.printStackTrace()
            }
            return false

        } else if (iframeSrc.contains("dhtpre.com")) {
            println("${name}: Detectado iframe de dhtpre.com, usando loadExtractor.")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        } else {
            println("${name}: Tipo de iframe desconocido: $iframeSrc. Intentando con loadExtractor por defecto.")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
    }
    // Esta clase ya no se usaría con esta lógica de Base64
    data class CubembedApiResponse(
        @JsonProperty("file")
        val file: String?,
        @JsonProperty("quality")
        val quality: String? = null
    )
}