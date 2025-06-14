package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
// import android.util.Log // Comentado si sigue dando error
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder

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

    // Función auxiliar para URL encoding
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
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframeSrc = doc.selectFirst(".serie-video-informacion iframe")?.attr("src")

        if (iframeSrc == null) {
            println("${name}: No iframe found for episode: $data")
            return false
        }

        // Modificamos la URL del iframe para quitar el #ourng, ya que es un hash que el servidor no procesa
        val embedUrl = iframeSrc.substringBeforeLast("#")
        if (embedUrl.isBlank()) {
            println("${name}: Could not extract embed URL from iframe src: $iframeSrc")
            return false
        }

        val embedHeaders = mapOf(
            "Referer" to data, // El referer es la página de lacartoons.com
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "es,en-US;q=0.7,en;q=0.3",
            "Upgrade-Insecure-Requests" to "1"
        )

        try {
            // Hacemos una solicitud GET al iframe para obtener su contenido HTML
            val embedDoc = app.get(embedUrl, headers = embedHeaders).document

            // Buscamos la URL del video directamente en el HTML del iframe
            // Basado en image_902d18.png, buscamos la etiqueta <source> dentro de <video>
            val videoSourceElement = embedDoc.selectFirst("video source[type=application/x-mpegurl]")
            val videoUrl = videoSourceElement?.attr("src")

            if (!videoUrl.isNullOrBlank()) {
                println("${name}: Successfully extracted video URL from embed: $videoUrl")

                // Aquí podemos intentar determinar la calidad basándonos en la URL o el nombre
                // Si la URL no contiene la calidad, puedes estimarla o dejarla como 0 (Unknown)
                // Por ahora, asumimos una calidad Unknown o podrías intentar parsearla del nombre si lo hubiera
                val quality = 0 // Qualities.Unknown.value

                // Si la URL del video tiene un 'quality' en su path, puedes intentar extraerlo.
                // Ejemplo: videoUrl.substringAfterLast("/").substringBefore(".").substringAfter("-")
                // En image_902d18.png se ve /hls/Ux8FO9u1ACyyq56m2odA/db/85vbcYxc/1u1ddo/tt/master.m3u8
                // No parece tener la calidad en el nombre de archivo directo.

                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "Cubembed (direct)", // Cambiamos el nombre para reflejar que es directo
                        url = videoUrl,
                        referer = embedUrl, // El referer para el stream es la URL del embed
                        quality = quality,
                        type = ExtractorLinkType.M3U8 // Confirmado por image_902d18.png
                    )
                )
                return true
            } else {
                println("${name}: Could not find video source in embed HTML for URL: $embedUrl")
            }

        } catch (e: Exception) {
            println("${name}: Error fetching embed content or extracting video: ${e.message}")
            e.printStackTrace()
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