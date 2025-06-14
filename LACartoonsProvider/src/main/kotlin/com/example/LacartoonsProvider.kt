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
import java.net.URI // Importa la clase URI para resolver URLs relativas
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
            val cubembedUrl = iframeSrc // Esta es la URL del iframe (ej: https://cubeembed.rpmvid.com/#ourng)
            val refererForEmbed = data
            val originForEmbed = mainUrl

            val embedHeaders = mapOf(
                "Referer" to refererForEmbed,
                "Origin" to originForEmbed,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "es,en-US;q=0.7,en;q=0.3"
            )

            try {
                val embedDoc = app.get(cubembedUrl, headers = embedHeaders).document

                var m3u8Url: String? = null

                // 1. Encontrar el script principal dinámico
                val mainScriptElement = embedDoc.selectFirst("script[type=module][src*=/assets/index-]")

                if (mainScriptElement != null) {
                    val relativeScriptUrl = mainScriptElement.attr("src")

                    // Construir la URL absoluta para el script JS
                    // Usar la base del dominio del iframe (e.g., https://cubeembed.rpmvid.com)
                    val baseDomain = URI(cubembedUrl).host // Obtiene "cubeembed.rpmvid.com" del iframeSrc
                    val scriptBaseUri = URI("https://$baseDomain") // Reconstruye "https://cubeembed.rpmvid.com"

                    val fullScriptUrl = scriptBaseUri.resolve(relativeScriptUrl).toString()
                    println("${name}: Intentando descargar JavaScript principal: $fullScriptUrl")

                    try {
                        val scriptContent = app.get(fullScriptUrl, headers = embedHeaders).text

                        // 2. Buscar la URL .m3u8 dentro del contenido del script
                        val m3u8Regex = Regex("""(https?://[^"']*\.m3u8(?:[^"']*)?)""") // Regex para encontrar URLs .m3u8
                        val matchResult = m3u8Regex.find(scriptContent)

                        if (matchResult != null) {
                            m3u8Url = matchResult.groupValues[1]
                            println("${name}: ¡Éxito! URL de video M3U8 encontrada en el JavaScript principal: $m3u8Url")
                        } else {
                            println("${name}: No se encontró la fuente de video M3U8 en el contenido del JavaScript principal.")
                        }
                    } catch (e: Exception) {
                        println("${name}: Error al descargar o leer el JavaScript principal: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    println("${name}: No se encontró el script principal 'index-XXXX.js' en el embed.")
                }


                if (!m3u8Url.isNullOrBlank()) {
                    callback(
                        ExtractorLink(
                            source = "Cubembed",
                            name = "Cubembed",
                            url = m3u8Url, // Usar la URL M3U8 absoluta encontrada en el JS
                            referer = cubembedUrl, // El referer para el link final debe ser la URL del iframe
                            quality = 0, // Calidad, puedes ajustarla si puedes extraerla
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return true
                } else {
                    println("${name}: No se pudo extraer la URL M3U8 de Cubembed para URL: $cubembedUrl")
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

    // Esta data class puede ser eliminada si no se usa en ningún otro lugar.
    data class CubembedApiResponse(
        @JsonProperty("file")
        val file: String?,
        @JsonProperty("quality")
        val quality: String? = null
    )
}