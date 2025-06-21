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
import android.util.Base64

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
            val cubembedUrl = iframeSrc // Esta es la URL del iframe (ej: https://cubeembed.rpmvid.com/#ourng)
            // No necesitamos un referer para el app.get del iframe en sí si ya lo tenemos en el User-Agent

            // Encabezados para la solicitud del HTML del iframe (aunque ya vimos que no trae el m3u8 directamente)
            // Se mantiene el User-Agent móvil de la prueba anterior
            val embedRequestHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.112 Mobile Safari/537.36",
                "Referer" to cubembedUrl.substringBefore("#"), // Referer al dominio base del iframe
                "Origin" to cubembedUrl.substringBefore("#"), // Origin al dominio base del iframe
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "es,en-US;q=0.7,en;q=0.3"
            )

            try {
                // Obtener el HTML del iframe
                val embedDoc = app.get(cubembedUrl, headers = embedRequestHeaders).document

                val htmlContent = embedDoc.html()

                println("${name}: URL del iframe de Cubembed: $cubembedUrl")
                println("${name}: Tamaño del HTML de Cubembed recibido: ${htmlContent.length} caracteres")
                println("${name}: --- INICIO HTML CUBEMBED ---")
                val chunkSize = 1000 // Tamaño de cada "pedazo" de HTML a imprimir
                for (i in 0 until htmlContent.length step chunkSize) {
                    val end = (i + chunkSize).coerceAtMost(htmlContent.length)
                    println("${name}: CHUNK ${i/chunkSize}: ${htmlContent.substring(i, end)}")
                }
                println("${name}: --- FIN HTML CUBEMBED ---")

                // NUEVA LÓGICA: INTENTAR ENCONTRAR LA RUTA HLS DINÁMICA EN EL JAVASCRIPT/HTML RECIBIDO
                // Esta regex intenta encontrar "/hls/ALGO_DINAMICO.m3u8" o "/hls/ALGO_DINAMICO/algo/master.m3u8"
                // Busca cualquier cosa que empiece con /hls/ y termine con .m3u8, dentro de comillas
                val hlsPathRegex = Regex("""["'](/hls/[^"']+\.m3u8)["']""")
                val match = hlsPathRegex.find(htmlContent)

                if (match != null) {
                    val relativeM3u8Src = match.groupValues[1] // Captura la URL relativa completa (ej: /hls/.../master.m3u8)
                    val baseUri = URI(cubembedUrl.substringBefore("#")) // La base es el dominio del iframe
                    val m3u8Url = baseUri.resolve(relativeM3u8Src).toString()

                    println("${name}: ¡Éxito! URL de video M3U8 encontrada en el HTML (posiblemente en script): $m3u8Url")

                    // Encabezados específicos para la SOLICITUD DEL ARCHIVO M3U8, basados en el cURL
                    val finalM3u8Headers = mapOf(
                        "Referer" to "https://cubeembed.rpmvid.com/", // El Referer que usó el navegador para el M3U8
                        "Origin" to "https://cubeembed.rpmvid.com", // El Origin para el M3U8
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36", // User-Agent de escritorio del cURL
                        "Accept" to "*/*",
                        "Accept-Language" to "es-ES,es;q=0.5",
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                        "Sec-Gpc" to "1"
                    )

                    callback(
                        ExtractorLink(
                            source = "Cubembed",
                            name = "Cubembed",
                            url = m3u8Url,
                            referer = "https://cubeembed.rpmvid.com/", // El referer para el M3U8
                            quality = 0, // Puedes ajustar la calidad si la obtienes de alguna parte
                            type = ExtractorLinkType.M3U8,
                            headers = finalM3u8Headers // ¡Pasar los encabezados completos aquí!
                        )
                    )
                    return true
                } else {
                    println("${name}: No se encontró la ruta HLS dinámica (.m3u8) en el HTML inicial del iframe. El contenido es incompleto o se carga por JS complejo.")
                }

            } catch (e: Exception) {
                println("${name}: Error al obtener o parsear el HTML del embed de Cubembed: ${e.message}")
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