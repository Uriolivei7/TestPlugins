package com.example

import android.util.Log // Asegúrate de tener esta importación
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import java.util.*


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
        return app.get(url, interceptor = cloudflareKiller )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/emision", "En emisión"),
            Pair(
                "$mainUrl/animes?fecha=false&genero=false&letra=false&categoria=Película",
                "Peliculas"
            ),
            Pair("$mainUrl/animes", "Animes"),
        )

        val items = ArrayList<HomePageList>()
        // Puedes descomentar y adaptar esto si necesitas la sección de "Capítulos actualizados"
        // y tiene una estructura diferente a las demás, como la de image_30e8c0.png
        // items.add(
        //         HomePageList(
        //                 "Capítulos actualizados",
        //                 appGetChildMainUrl(mainUrl).document.select("div.col-6.col-md-6.col-lg-3.mb-3").map { // Selector de la sección "Añadidos recientemente"
        //                     val itemLink = it.selectFirst("a")!!
        //                     val title = itemLink.selectFirst("div.info h2.mt-3")?.text() ?: ""
        //                     val poster = itemLink.selectFirst("div.imgrec img.lozad.nxtmainimg")?.attr("data-src")
        //                             ?: itemLink.selectFirst("div.imgrec img.lozad.nxtmainimg")?.attr("src") ?: ""
        //                     newAnimeSearchResponse(title, fixUrl(itemLink.attr("href"))) {
        //                         this.posterUrl = fixUrl(poster)
        //                         addDubStatus(getDubStatus(title))
        //                         this.posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
        //                     }
        //                 }, true) // true para isHorizontal
        // )


        urls.apmap { (url, name) ->
            // !!! Selector principal corregido para "En emisión", "Peliculas", "Animes" (basado en image_316ce4.png) !!!
            val home = appGetChildMainUrl(url).document.select("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").map {
                val itemLink = it.selectFirst("a")!! // El enlace <a> dentro del contenedor
                val title = itemLink.selectFirst("div.seriedetails h3.my-1")?.text() // Título dentro de div.seriedetails
                    ?: ""
                val itemUrl = itemLink.attr("href")

                // Póster dentro de div.serieimg
                val poster = itemLink.selectFirst("div.serieimg img.img-fluid2")?.attr("data-src")
                    ?: itemLink.selectFirst("div.serieimg img.img-fluid2")?.attr("src")
                    ?: ""

                newAnimeSearchResponse(title, fixUrl(itemUrl)) {
                    this.posterUrl = fixUrl(poster)
                    addDubStatus(getDubStatus(title))
                    this.posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
                }
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // !!! Selectores corregidos para la página de búsqueda (basado en image_316ce4.png, misma estructura) !!!
        return appGetChildMainUrl("$mainUrl/buscar?q=$query").document.select("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").map {
            val itemLink = it.selectFirst("a")!!
            val title = itemLink.selectFirst("div.seriedetails h3.my-1")?.text() ?: ""
            val href = fixUrl(itemLink.attr("href"))

            val image = itemLink.selectFirst("div.serieimg img.img-fluid2")?.attr("data-src")
                ?: itemLink.selectFirst("div.serieimg img.img-fluid2")?.attr("src")
                ?: ""

            AnimeSearchResponse(
                title,
                href,
                this.name,
                TvType.Anime, // Puedes ajustar esto si la búsqueda puede retornar películas y quieres distinguirlas
                fixUrl(image),
                null,
                if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                    DubStatus.Dubbed
                ) else EnumSet.of(DubStatus.Subbed),
                posterHeaders = if (image.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = appGetChildMainUrl(url).document
        // !!! Revisa estos selectores para la página de detalles individual.
        // La estructura de la página de detalles puede ser diferente a la de las listas.
        // Abre una página de anime/película (ej: https://latanime.org/anime/las-aventuras-de-la-hija-del-rey-demonio-s4-latino)
        // y usa F12 para verificar los selectores.
        val poster = doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha img.img-fluid2")!!.attr("src")
        val backimage = doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha img.img-fluid2")!!.attr("src")
        val title = doc.selectFirst("div.col-lg-9.col-md-8 h2")!!.text()
        val type = doc.selectFirst("div.chapterdetls2")?.text() ?: ""
        val description = doc.selectFirst("div.col-lg-9.col-md-8 p.my-2.opacity-75")!!.text().replace("Ver menos", "")
        val genres = doc.select("div.col-lg-9.col-md-8 a div.btn").map { it.text() }
        val status = when (doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha div.my-2")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val episodes = doc.select("div.row div.col-lg-9.col-md-8 div.row div a").map {
            val name = it.selectFirst("div.cap-layout")?.text()
                ?: it.selectFirst("h2")?.text()
                ?: ""
            val link = it!!.attr("href")
            val epThumb = it.selectFirst(".animeimghv")?.attr("data-src")
                ?: it.selectFirst("div.animeimgdiv img.animeimghv")?.attr("src")
                ?: ""
            Episode(link, name, posterUrl = epThumb)
        }
        return newAnimeLoadResponse(title, url, getType(title)) {
            posterUrl = poster
            backgroundPosterUrl = backimage
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
            posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("LatanimePlugin", "loadLinks called with data: $data")

        appGetChildMainUrl(data).document.select("div.container-fluid div.row div.col-md-12.col-lg-8.seiya ul.cap_repro.d-flex.flex-wrap li#play-video").apmap {
            Log.d("LatanimePlugin", "Found player element: ${it.outerHtml()}")

            val encodedurl = it.select("a").attr("data-player")

            Log.d("LatanimePlugin", "Encoded URL found: $encodedurl")

            if (encodedurl.isNullOrEmpty()) {
                Log.w("LatanimePlugin", "Encoded URL is null or empty for $data. Could not find player data-player attribute.")
                return@apmap
            }

            val urlDecoded = base64Decode(encodedurl)
            Log.d("LatanimePlugin", "Decoded URL (Base64): $urlDecoded")

            val url = (urlDecoded).replace("https://monoschinos2.com/reproductor?url=", "")
                .replace("https://sblona.com", "https://watchsb.com")

            Log.d("LatanimePlugin", "Final URL for Extractor: $url")

            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        Log.d("LatanimePlugin", "loadLinks finished for data: $data")
        return true
    }
}