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
import android.util.Base64 // Importa la clase Base64 de Android para decodificación
import com.fasterxml.jackson.databind.ObjectMapper

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
            val cubembedUrl = iframeSrc // Esta es la URL base del iframe de Cubembed
            val refererForEmbed = data
            val originForEmbed = mainUrl

            // Headers para la petición al iframe (aunque aquí no la usaremos para el HTML del iframe)
            val embedHeaders = mapOf(
                "Referer" to refererForEmbed,
                "Origin" to originForEmbed,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "es,en-US;q=0.7,en;q=0.3"
            )

            try {
                val baseUri = URI(cubembedUrl)
                // Extraer el ID del hash (ej. #ourng -> ourng)
                val videoId = baseUri.fragment?.substringBefore("&")

                if (videoId.isNullOrBlank() || videoId.length <= 1) { // Longitud > 1 como en el JS original
                    println("${name}: No se pudo extraer el ID del video del hash de Cubembed URL: $cubembedUrl")
                    return false
                }

                val apiUrl = "https://cubeembed.rpmvid.com/api/v1/stream?id=$videoId"
                println("${name}: URL de la API de Cubembed generada: $apiUrl")

                // Headers para la API de stream (pueden ser los mismos o más específicos)
                val apiHeaders = mapOf(
                    "Referer" to cubembedUrl, // El referer para la API es la URL del iframe
                    "Origin" to "https://cubeembed.rpmvid.com", // El origen para la API es el dominio de Cubembed
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                    "Accept" to "*/*", // La API devuelve JSON, no HTML
                    "Accept-Language" to "es,en-US;q=0.7,en;q=0.3"
                )

                val apiResponse = app.get(apiUrl, headers = apiHeaders).text
                println("${name}: Respuesta Base64 de la API de Cubembed: $apiResponse")

                // Decodificar la respuesta Base64
                val decodedBytes = Base64.decode(apiResponse, Base64.DEFAULT)
                val decodedString = String(decodedBytes, Charsets.UTF_8)
                println("${name}: Respuesta decodificada (posible JSON): $decodedString")

                val mapper = ObjectMapper() // Si necesitas una instancia local
                val apiJson = mapper.readValue(decodedString, CubembedApiResponse::class.java)

                val fileUrl = apiJson.file
                if (!fileUrl.isNullOrBlank()) {
                    println("${name}: ¡Éxito! URL de video M3U8 obtenida de la API decodificada: $fileUrl")
                    callback(
                        ExtractorLink(
                            source = "Cubembed",
                            name = "Cubembed",
                            url = fileUrl,
                            referer = cubembedUrl, // El referer para el link final suele ser la URL del iframe
                            quality = 0, // Puedes ajustar esto si la API devuelve calidad
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return true
                } else {
                    println("${name}: 'file' (URL de video) no encontrada en la respuesta decodificada de Cubembed.")
                }

            } catch (e: Exception) {
                println("${name}: Error al procesar la API de Cubembed: ${e.message}")
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

    data class CubembedApiResponse(
        @JsonProperty("file")
        val file: String?,
        @JsonProperty("quality")
        val quality: String? = null
    )
}