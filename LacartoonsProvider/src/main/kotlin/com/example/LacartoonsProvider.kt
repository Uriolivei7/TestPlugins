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

    // Ya no se usa para las URLs de Cubembed, pero se mantiene para Lacartoons.com si es necesario
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

            // Encabezados comunes para ambas llamadas API
            val commonApiHeaders = mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br, zstd",
                "Accept-Language" to "es-ES,es;q=0.5",
                "Priority" to "u=1, i",
                "Referer" to cubembedUrl.substringBefore("#"),
                "Sec-Ch-Ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Brave\";v=\"138\"",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to "\"Windows\"",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Gpc" to "1",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                // Si hay cookies importantes después del "verify human", se pondrían aquí
                // "Cookie" to "nombre=valor; otro=valor"
            )

            try {
                // --- PASO 1: Llamada a /api/v1/info?id=... ---
                val infoApiUrl = "https://cubeembed.rpmvid.com/api/v1/info?id=$embedId"
                println("${name}: Realizando solicitud GET a la API de info: ${infoApiUrl}")
                val infoResponse = app.get(infoApiUrl, headers = commonApiHeaders)
                val infoEncodedString = infoResponse.text // Se espera Base64

                println("${name}: Cadena Base64 recibida de /info: ${infoEncodedString.take(100)}...")
                val infoDecodedBytes = Base64.decode(infoEncodedString, Base64.DEFAULT)
                val infoDecodedString = String(infoDecodedBytes, Charsets.UTF_8)
                println("${name}: Cadena decodificada de /info: ${infoDecodedString.take(500)}...")


                // Pequeño retraso para simular el comportamiento del navegador
                delay(1000)

                // --- PASO 2: Llamada a /api/v1/video?id=... ---
                // El parámetro 'r=' está vacío según tu última información.
                val videoApiUrl = "https://cubeembed.rpmvid.com/api/v1/video?id=$embedId&w=1280&h=800&r=" // Usar w=1280&h=800 y r= vacío

                println("${name}: Realizando solicitud GET a la API de video: ${videoApiUrl}")
                val videoResponse = app.get(videoApiUrl, headers = commonApiHeaders, allowRedirects = true)
                val videoEncodedString = videoResponse.text // Se espera Base64

                println("${name}: Cadena Base64 recibida de /video: ${videoEncodedString.take(100)}...")
                val videoDecodedBytes = Base64.decode(videoEncodedString, Base64.DEFAULT)
                val videoDecodedString = String(videoDecodedBytes, Charsets.UTF_8)
                println("${name}: Cadena decodificada de /video: ${videoDecodedString.take(500)}...")

                // Buscar M3U8 en la respuesta decodificada de /video (lo más probable)
                val m3u8Regex = Regex("""(https?://[^"']*\.m3u8(?:\?[^"']*)?)""")
                val match = m3u8Regex.find(videoDecodedString)

                if (match != null) {
                    val m3u8Url = match.groupValues[1]

                    println("${name}: ¡Éxito! URL de video M3U8 extraída de la cadena decodificada de /video: $m3u8Url")

                    val finalM3u8Headers = mapOf(
                        "Referer" to "https://cubeembed.rpmvid.com/",
                        "Origin" to "https://cubeembed.rpmvid.com",
                        "User-Agent" to commonApiHeaders["User-Agent"]!! // UA consistente
                    )

                    callback(
                        ExtractorLink(
                            source = "Cubembed",
                            name = "Cubembed",
                            url = m3u8Url,
                            referer = "https://cubeembed.rpmvid.com/",
                            quality = 0,
                            type = ExtractorLinkType.M3U8,
                            headers = finalM3u8Headers
                        )
                    )
                    return true
                } else {
                    println("${name}: No se encontró la URL HLS (.m3u8) en la cadena decodificada de /video.")
                    // Si no está en /video, podríamos buscar en /info si es necesario, pero es menos probable para el M3U8 final.
                    val matchInfo = m3u8Regex.find(infoDecodedString)
                    if (matchInfo != null) {
                        val m3u8Url = matchInfo.groupValues[1]
                        println("${name}: ¡Éxito! URL de video M3U8 extraída de la cadena decodificada de /info: $m3u8Url")
                        val finalM3u8Headers = mapOf(
                            "Referer" to "https://cubeembed.rpmvid.com/",
                            "Origin" to "https://cubeembed.rpmvid.com",
                            "User-Agent" to commonApiHeaders["User-Agent"]!! // UA consistente
                        )

                        callback(
                            ExtractorLink(
                                source = "Cubembed",
                                name = "Cubembed",
                                url = m3u8Url,
                                referer = "https://cubeembed.rpmvid.com/",
                                quality = 0,
                                type = ExtractorLinkType.M3U8,
                                headers = finalM3u8Headers
                            )
                        )
                        return true
                    } else {
                        println("${name}: No se encontró la URL HLS (.m3u8) en la cadena decodificada de /info tampoco.")
                    }
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