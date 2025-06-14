package com.example // Asegúrate de que este paquete sea el correcto para TU proyecto.

import com.lagradost.cloudstream3.* // Importa la mayoría de las clases principales de CloudStream
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Importar explícitamente si se usa
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app // Para usar 'app.get'
import com.lagradost.cloudstream3.utils.loadExtractor // Para usar loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils // Para AppUtils.tryParseJson

import org.jsoup.nodes.Document // Para parsear HTML

// Importaciones para Jackson (JsonProperty) - Necesarias si vas a usar @JsonProperty
// Si JsonProperty es el único error de Jackson, solo esta línea es necesaria.
import com.fasterxml.jackson.annotation.JsonProperty

import java.net.URLEncoder

class LacartoonsProvider:MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LACartoons"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries
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
        data: String, // La URL del episodio
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

        if (iframeSrc.contains("cubembed.rpmvid.com")) {
            println("${name}: Detectado iframe de cubembed.rpmvid.com, procesando internamente.")
            // --- Lógica del antiguo CubembedExtractor.getUrl() movida aquí ---
            val cubembedUrl = iframeSrc
            val referer = data // El referer es la URL de la página del episodio de Lacartoons

            val videoId = cubembedUrl.substringAfterLast("#", "").trim()
            if (videoId.isBlank()) {
                println("${name}: No se pudo extraer el ID del video de la URL del iframe: $cubembedUrl")
                return false
            }

            val cubembedMainUrl = "https://cubembed.rpmvid.com" // Definir aquí si no existe como variable de clase
            val apiUrl = "$cubembedMainUrl/api/v1/video?id=$videoId&w=1280&h=800&r=${URLEncoder.encode(cubembedUrl, "UTF-8")}"

            val headers = mapOf(
                "Referer" to referer,
                "Origin" to "https://www.lacartoons.com",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "es,en-US;q=0.7,en;q=0.3",
                "X-Requested-With" to "XMLHttpRequest"
            )

            try {
                val apiResponse = app.get(apiUrl, headers = headers)

                println("${name}: Cubembed API Request URL: $apiUrl")
                println("${name}: Cubembed API Response Status Code: ${apiResponse.code}")
                println("${name}: Cubembed API Response Body: ${apiResponse.text}")

                if (apiResponse.code == 200) {
                    val responseJson = AppUtils.tryParseJson<CubembedApiResponse>(apiResponse.text)

                    if (responseJson != null && !responseJson.file.isNullOrBlank()) {
                        val videoUrl = responseJson.file
                        val qualityStr = responseJson.quality

                        println("${name}: Cubembed URL de video extraída: $videoUrl")

                        val quality = when (qualityStr?.lowercase()) {
                            "360p" -> 360
                            "480p" -> 480
                            "720p" -> 720
                            "1080p" -> 1080
                            "2160p" -> 2160
                            else -> 0
                        }

                        callback(
                            ExtractorLink(
                                source = "Cubembed", // El nombre del extractor interno
                                name = "Cubembed " + (qualityStr ?: ""),
                                url = videoUrl,
                                referer = cubembedUrl, // El referer para el video final debería ser el iframe
                                quality = quality,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        return true
                    } else {
                        println("${name}: La URL de video es nula o vacía en la respuesta de la API de Cubembed para ID: $videoId. Respuesta: ${apiResponse.text}")
                    }
                } else {
                    println("${name}: La solicitud a la API de Cubembed falló para $apiUrl con estado: ${apiResponse.code}, cuerpo: ${apiResponse.text}")
                }
            } catch (e: Exception) {
                println("${name}: Error al obtener video de la API de Cubembed: ${e.message}")
                e.printStackTrace()
            }

            // --- Intento de fallback si la API falla (copiado del extractor) ---
            try {
                val embedDoc = app.get(cubembedUrl, headers = headers).document

                val videoSourceElement = embedDoc.selectFirst("video source[type=application/x-mpegurl]")
                val videoUrlFromHtml = videoSourceElement?.attr("src")

                if (!videoUrlFromHtml.isNullOrBlank()) {
                    println("${name}: ¡Éxito! URL de video extraída del HTML del embed de Cubembed: $videoUrlFromHtml")
                    callback(
                        ExtractorLink(
                            source = "Cubembed (HTML)",
                            name = "Cubembed (HTML)",
                            url = videoUrlFromHtml,
                            referer = cubembedUrl,
                            quality = 0,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return true
                } else {
                    println("${name}: No se encontró la fuente de video en el HTML del embed de Cubembed para URL: $cubembedUrl")
                }
            } catch (e: Exception) {
                println("${name}: Error al obtener contenido del embed o extraer video del HTML de Cubembed: ${e.message}")
                e.printStackTrace()
            }
            // --- Fin de la lógica movida ---

            return false // Si no se pudo extraer con Cubembed

        } else if (iframeSrc.contains("dhtpre.com")) {
            println("${name}: Detectado iframe de dhtpre.com, usando loadExtractor (comportamiento por defecto).")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        } else {
            println("${name}: Tipo de iframe desconocido: $iframeSrc. Intentando con loadExtractor por defecto.")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
    }

    // Mover la data class ApiResponse dentro del Provider
    data class CubembedApiResponse(
        @JsonProperty("file") // Asegúrate de que esta anotación esté correctamente importada
        val file: String?,
        @JsonProperty("quality")
        val quality: String? = null
    )
}