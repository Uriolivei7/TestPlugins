package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import java.util.* // Importa java.util.* para EnumSet

class LatanimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }

        private fun base64Decode(encoded: String): String {
            return try {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                Log.e("LatanimePlugin", "Error decoding Base64: ${e.message}")
                ""
            }
        }
    }

    override var mainUrl = "https://latanime.org"
    override var name = "LatAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    private val cloudflareKiller = CloudflareKiller()
    suspend fun appGetChildMainUrl(url: String): NiceResponse {
        // Asegúrate de que los headers se pasen correctamente para Cloudflare
        return app.get(url, interceptor = cloudflareKiller, headers = cloudflareKiller.getCookieHeaders(mainUrl).toMap())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? { // Cambiado a HomePageResponse? para manejar errores
        val urls = listOf(
            Pair("$mainUrl/emision", "En emisión"),
            Pair(
                "$mainUrl/animes?fecha=false&genero=false&letra=false&categoria=Película",
                "Peliculas"
            ),
            Pair("$mainUrl/animes", "Animes"),
        )

        val items = ArrayList<HomePageList>()
        try {
            urls.map { (url, name) -> // Reemplazado apmap con map
                val doc = appGetChildMainUrl(url).document
                delay(2000) // Retardo para evitar sobrecargar el servidor
                val home = doc.select("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").mapNotNull { article -> // Usar mapNotNull para filtrar nulos
                    val itemLink = article.selectFirst("a")
                    val title = itemLink?.selectFirst("div.seriedetails h3.my-1")?.text() ?: ""
                    val itemUrl = itemLink?.attr("href")

                    if (itemUrl == null) {
                        Log.w("LatanimePlugin", "WARN: itemUrl es nulo para un elemento en getMainPage.")
                        return@mapNotNull null // Saltar este elemento
                    }

                    val posterElement = article.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series img.img-fluid2.shadow-sm")
                    val src = posterElement?.attr("src") ?: ""
                    val dataSrc = posterElement?.attr("data-src") ?: ""
                    val poster = if (dataSrc.isNotEmpty()) fixUrl(dataSrc) else if (src.isNotEmpty()) fixUrl(src) else ""

                    // Usar newAnimeSearchResponse y el lambda
                    newAnimeSearchResponse(title, fixUrl(itemUrl)) {
                        this.posterUrl = poster
                        addDubStatus(getDubStatus(title))
                        this.posterHeaders = cloudflareKiller.getCookieHeaders(mainUrl).toMap()
                    }
                }
                if (home.isNotEmpty()) { // Añadir solo si hay elementos válidos
                    items.add(HomePageList(name, home))
                }
            }
        } catch (e: Exception) {
            Log.e("LatanimePlugin", "ERROR en getMainPage: ${e.message}", e)
            throw ErrorLoadingException("Error al cargar la página principal: ${e.message}")
        }

        if (items.isEmpty()) { // Comprobar si no hay elementos después de intentar añadir
            throw ErrorLoadingException("No se pudieron cargar elementos de la página principal.")
        }
        // Usar newHomePageResponse
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = appGetChildMainUrl("$mainUrl/buscar?q=$query").document
        delay(2000) // Retardo reducido
        return doc.select("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").mapNotNull { article -> // Usar mapNotNull
            val itemLink = article.selectFirst("a")
            val title = itemLink?.selectFirst("div.seriedetails h3.my-1")?.text() ?: ""
            val href = itemLink?.attr("href")

            if (href == null) {
                Log.w("LatanimePlugin", "WARN: href es nulo para un elemento en search.")
                return@mapNotNull null
            }

            val imageElement = article.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series img.img-fluid2.shadow-sm")
            val src = imageElement?.attr("src") ?: ""
            val dataSrc = imageElement?.attr("data-src") ?: ""
            val image = if (dataSrc.isNotEmpty()) fixUrl(dataSrc) else if (src.isNotEmpty()) fixUrl(src) else ""

            // Usar newAnimeSearchResponse y el lambda
            newAnimeSearchResponse(title, fixUrl(href)) {
                this.type = TvType.Anime // Asumiendo que la búsqueda general es para Anime
                this.posterUrl = image
                addDubStatus(getDubStatus(title))
                this.posterHeaders = cloudflareKiller.getCookieHeaders(mainUrl).toMap()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = appGetChildMainUrl(url).document
        val posterElement = doc.selectFirst("div.p-2.cap-layout.d-flex.align-items-center.gap-2 img.lozad.rounded-3")
        val dataSrc = posterElement?.attr("data-src") ?: ""
        val src = posterElement?.attr("src") ?: ""
        val poster = if (dataSrc.isNotEmpty()) fixUrl(dataSrc) else if (src.isNotEmpty()) fixUrl(src) else ""
        val backimage = poster // Usar el póster como imagen de fondo si no hay una específica
        val title = doc.selectFirst("div.col-lg-9.col-md-8 h2")?.text()
            ?: throw ErrorLoadingException("Título no encontrado en $url")
        val type = doc.selectFirst("div.chapterdetls2")?.text() ?: "" // Este 'type' es un String, no un TvType
        val description = doc.selectFirst("div.col-lg-9.col-md-8 p.my-2.opacity-75")?.text()?.replace("Ver menos", "") ?: ""
        val genres = doc.select("div.col-lg-9.col-md-8 a div.btn").map { it.text() }
        val status = when (doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha div.my-2")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val episodes = doc.select("div.row div.col-lg-9.col-md-8 div.row div a").mapNotNull { episodeLink -> // mapNotNull
            val name = episodeLink.selectFirst("div.cap-layout")?.text()
                ?: episodeLink.selectFirst("h2")?.text()
                ?: ""
            val link = episodeLink.attr("href")

            if (link.isNullOrEmpty()) {
                Log.w("LatanimePlugin", "WARN: Link de episodio nulo o vacío en load para $url.")
                return@mapNotNull null
            }

            val epThumb = episodeLink.selectFirst(".animeimghv")?.attr("data-src")
                ?: episodeLink.selectFirst("div.animeimgdiv img.animeimghv")?.attr("src")
                ?: ""
            newEpisode(link) {
                this.name = name
                this.posterUrl = epThumb
                this.runTime = null
            }
        }
        return newAnimeLoadResponse(title, url, getType(type)) { // getType(type) para convertir String a TvType
            this.posterUrl = poster
            this.backgroundPosterUrl = backimage
            addEpisodes(DubStatus.Subbed, episodes) // Asumiendo que todos son subtitulados por defecto, ajusta si es necesario.
            this.showStatus = status
            this.plot = description
            this.tags = genres
            this.posterHeaders = cloudflareKiller.getCookieHeaders(mainUrl).toMap()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("LatanimePlugin", "loadLinks called with data: $data")

        var foundLinks = false
        val doc = appGetChildMainUrl(data).document
        // No hay necesidad de un delay tan largo aquí, la extracción ya tiene sus propios tiempos.
        try {
            doc.select("ul.cap_repro li#play-video").forEach { playerElement -> // Reemplazado apmap con forEach
                Log.d("LatanimePlugin", "Found player element: ${playerElement.outerHtml()}")

                val encodedUrl = playerElement.selectFirst("a.play-video")?.attr("data-player")
                Log.d("LatanimePlugin", "Encoded URL found: $encodedUrl")

                if (encodedUrl.isNullOrEmpty()) {
                    Log.w("LatanimePlugin", "Encoded URL is null or empty for $data. Could not find player data-player attribute.")
                    return@forEach // Saltar a la siguiente iteración
                }

                val urlDecoded = base64Decode(encodedUrl)
                Log.d("LatanimePlugin", "Decoded URL (Base64): $urlDecoded")

                val url = urlDecoded.replace("https://monoschinos2.com/reproductor?url=", "")
                    .replace("https://sblona.com", "https://watchsb.com")
                Log.d("LatanimePlugin", "Final URL for Extractor: $url")

                // Asegúrate de que url no sea nula o vacía antes de pasarla al extractor
                if (url.isNotEmpty()) {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                } else {
                    Log.w("LatanimePlugin", "WARN: URL final para el extractor está vacía después de decodificar y reemplazar.")
                }
            }
        } catch (e: Exception) {
            Log.e("LatanimePlugin", "Error in loadLinks for data '$data': ${e.message}", e)
        }

        Log.d("LatanimePlugin", "loadLinks finished for data: $data with foundLinks: $foundLinks")
        return foundLinks
    }
}