package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
// import android.util.Log // <--- Comentamos esta línea si sigue dando error, y usaremos println o algo similar para depuración.
import com.fasterxml.jackson.annotation.JsonProperty

// IMPORTS QUE DEBEN SER CORRECTAS SEGÚN LOS STUBS Y LA PRÁCTICA COMÚN
// import com.lagradost.cloudstream3.extractors.Qualities // ¡COMENTADO! Ya no la necesitamos.
import com.lagradost.cloudstream3.utils.AppUtils // Para tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Para ExtractorLinkType.M3U8

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
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframeSrc = doc.selectFirst(".serie-video-informacion iframe")?.attr("src")

        if (iframeSrc == null) {
            // Reemplazamos Log.w con println para depuración si Log no funciona
            println("${name}: No iframe found for episode: $data")
            return false
        }

        val videoId = iframeSrc.substringAfterLast("#", "").trim()
        if (videoId.isBlank()) {
            println("${name}: Could not extract video ID from iframe src: $iframeSrc")
            return false
        }

        val apiUrl = "https://cubembed.rpmvid.com/api/v1/video?id=$videoId&w=1280&h=800&r="

        val headers = mapOf(
            "Referer" to data,
            "Accept" to "*/*",
            "Accept-Language" to "es,en-US;q=0.7,en;q=0.3",
            "Origin" to "https://cubembed.rpmvid.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        )

        try {
            val apiResponse = app.get(apiUrl, headers = headers)

            println("${name}: API Request URL: $apiUrl")
            println("${name}: API Response Status Code: ${apiResponse.code}")
            println("${name}: API Response Body: ${apiResponse.text}")

            if (apiResponse.code == 200) {
                // Usamos AppUtils.tryParseJson, confirmado por el stub de AppUtils
                val responseJson = AppUtils.tryParseJson<CubembedApiResponse>(apiResponse.text)

                if (responseJson != null) {
                    val videoUrl = responseJson.videoUrl
                    val qualityStr = responseJson.quality

                    if (!videoUrl.isNullOrBlank()) {
                        println("${name}: Successfully extracted video URL: $videoUrl")

                        // ¡CAMBIO CLAVE AQUÍ! Asignamos valores numéricos directos para la calidad
                        val quality = when (qualityStr?.lowercase()) {
                            "360p" -> 360
                            "480p" -> 480
                            "720p" -> 720
                            "1080p" -> 1080
                            "2160p" -> 2160
                            else -> 0 // Calidad desconocida
                        }

                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = qualityStr ?: "Normal",
                                url = videoUrl,
                                referer = videoUrl,
                                quality = quality, // Usamos el valor numérico directo
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        return true
                    } else {
                        println("${name}: Video URL is null or blank in API response for ID: $videoId. Response: ${apiResponse.text}")
                    }
                } else {
                    println("${name}: Failed to parse API response JSON for ID: $videoId. Raw response: ${apiResponse.text}")
                }
            } else {
                println("${name}: API Request failed for $apiUrl with status: ${apiResponse.code}, body: ${apiResponse.text}")
            }
        } catch (e: Exception) {
            println("${name}: Error fetching video from Cubembed API: ${e.message}")
            e.printStackTrace() // Para imprimir el stack trace completo si hay un error
        }

        return false
    }

    data class CubembedApiResponse(
        @JsonProperty("file")
        val videoUrl: String?,
        @JsonProperty("quality")
        val quality: String? = null
    )
}