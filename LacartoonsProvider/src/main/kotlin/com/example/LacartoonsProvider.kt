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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

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
            val embedId = cubembedUrl.substringAfterLast("#") // Extrae el ID, ej., "ur3pb"

            if (embedId.isEmpty()) {
                println("${name}: No se pudo extraer el ID del embed de Cubembed de la URL: $cubembedUrl")
                return false
            }

            // Construimos la URL del endpoint que parece devolver el M3U8 directamente
            // Basado en tu captura de red: video?id=ur3pb&w=1680&h=1050&r=lacartoon...
            // Los parámetros 'w', 'h' y 'r' podrían ser importantes. 'r' es el referer principal.
            val refererMainPage = mainUrl // Usa el mainUrl de tu proveedor como referer original
            val videoApiUrl = "https://cubeembed.rpmvid.com/video?id=$embedId&w=1920&h=1080&r=${encode(refererMainPage)}"
            // Se usa encode para el parámetro 'r' por si contiene caracteres especiales

            // Encabezados para la solicitud al endpoint /video
            // Deben coincidir con los que el navegador envía para esa solicitud (xhr/fetch)
            val videoApiHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Referer" to cubembedUrl.substringBefore("#"), // El referer del iframe (sin el #id)
                "Origin" to cubembedUrl.substringBefore("#"), // El origin del iframe
                "Accept" to "*/*",
                "Accept-Language" to "es-ES,es;q=0.5",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )

            try {
                println("${name}: Realizando solicitud GET a ${videoApiUrl}")
                // Realizamos la solicitud GET al endpoint /video.
                // Esperamos que la respuesta sea un REDIRECCIONAMIENTO (302) a la URL del M3U8
                // o que el CUERPO de la respuesta contenga la URL del M3U8.
                // Cloudstream automáticamente sigue redireccionamientos.
                val response = app.get(videoApiUrl, headers = videoApiHeaders, allowRedirects = true)

                val finalUrl = response.url // Esta será la URL final después de redireccionamientos

                // Verificar si la URL final es un M3U8
                if (finalUrl.contains(".m3u8")) {
                    println("${name}: ¡Éxito! URL de video M3U8 obtenida después de la solicitud GET: $finalUrl")

                    // Los encabezados para el M3U8 real
                    val finalM3u8Headers = mapOf(
                        "Referer" to "https://cubeembed.rpmvid.com/", // El referer para el M3U8 final es el dominio base del embed
                        "Origin" to "https://cubeembed.rpmvid.com",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
                    )

                    callback(
                        ExtractorLink(
                            source = "Cubembed",
                            name = "Cubembed",
                            url = finalUrl,
                            referer = "https://cubeembed.rpmvid.com/",
                            quality = 0,
                            type = ExtractorLinkType.M3U8,
                            headers = finalM3u8Headers
                        )
                    )
                    return true
                } else {
                    println("${name}: La URL final no es un M3U8. Contenido de la respuesta: ${response.text.take(500)}...")
                    // Si no es un M3U8, puede ser que la URL del M3U8 esté en el cuerpo de la respuesta
                    // Intentaremos buscarlo en el cuerpo como JSON o texto plano.
                    val m3u8InBodyRegex = Regex("""["'](https?://[^"']*\.m3u8(?:\?[^"']*)?)["']""")
                    val matchInBody = m3u8InBodyRegex.find(response.text)
                    if (matchInBody != null) {
                        val m3u8UrlFromBody = matchInBody.groupValues[1]
                        println("${name}: ¡Éxito! URL de video M3U8 encontrada en el cuerpo de la respuesta: $m3u8UrlFromBody")

                        val finalM3u8Headers = mapOf(
                            "Referer" to "https://cubeembed.rpmvid.com/",
                            "Origin" to "https://cubeembed.rpmvid.com",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
                        )
                        callback(
                            ExtractorLink(
                                source = "Cubembed",
                                name = "Cubembed",
                                url = m3u8UrlFromBody,
                                referer = "https://cubeembed.rpmvid.com/",
                                quality = 0,
                                type = ExtractorLinkType.M3U8,
                                headers = finalM3u8Headers
                            )
                        )
                        return true
                    }
                }

            } catch (e: Exception) {
                println("${name}: Error al realizar la solicitud GET al endpoint /video de Cubembed: ${e.message}")
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

    // Esta clase de datos ya no se usa con esta nueva lógica, pero la mantenemos.
    data class CubembedApiResponse(
        @JsonProperty("file")
        val file: String?,
        @JsonProperty("quality")
        val quality: String? = null
    )
}