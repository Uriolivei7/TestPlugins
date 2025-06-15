package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*
import android.util.Log


class LatanimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            // Mantener esta lógica, parece correcta para el nombre
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            // Mantener esta lógica, parece correcta para el nombre
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
            // ¡IMPORTANTE! Revisa si estas URLs siguen mostrando la misma estructura HTML que has compartido.
            // Si la página de /animes usa una estructura diferente a "Series recientes", necesitarás un selector diferente para esa.
            Pair(
                "$mainUrl/animes?fecha=false&genero=false&letra=false&categoria=Película",
                "Peliculas"
            ),
            Pair("$mainUrl/animes", "Animes"),
        )

        val items = ArrayList<HomePageList>()
        val isHorizontal = true

        urls.apmap { (url, name) ->
            // !!! Selector principal corregido basado en el ÚLTIMO HTML proporcionado (para "Series recientes") !!!
            val home = appGetChildMainUrl(url).document.select("li.col.mb-4.ficha_efecto").map {
                // Selectores internos ahora son relativos a 'it' (el li.col.mb-4.ficha_efecto)
                val itemLink = it.selectFirst("article a")!!
                val title = itemLink.selectFirst("h3.title_cap")?.text() // O solo "h3" si es más robusto
                    ?: "" // Manejar caso de título nulo
                val itemUrl = itemLink.attr("href")

                // !!! NECESITAS VERIFICAR CÓMO SE OBTIENE LA IMAGEN DENTRO DE "div.tarjeta" !!!
                // Esto es una suposición, puede que sea diferente.
                val poster = itemLink.selectFirst("div.tarjeta img")?.attr("data-src")
                    ?: itemLink.selectFirst("div.tarjeta img")?.attr("src")
                    ?: "" // Si no encuentra nada, deja vacío

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
        // !!! Asumo que la estructura de búsqueda es SIMILAR a la de "Series recientes". Si no, ajústala !!!
        return appGetChildMainUrl("$mainUrl/buscar?q=$query").document.select("li.col.mb-4.ficha_efecto").map {
            val itemLink = it.selectFirst("article a")!!
            val title = itemLink.selectFirst("h3.title_cap")?.text() ?: ""
            val href = fixUrl(itemLink.attr("href"))

            // !!! NECESITAS VERIFICAR CÓMO SE OBTIENE LA IMAGEN DENTRO DE "div.tarjeta" en la página de búsqueda !!!
            val image = itemLink.selectFirst("div.tarjeta img")?.attr("data-src")
                ?: itemLink.selectFirst("div.tarjeta img")?.attr("src")
                ?: ""

            AnimeSearchResponse(
                title,
                href,
                this.name,
                TvType.Anime, // Asegúrate de que el TvType es apropiado. Podría ser TvType.Movie para películas en los resultados.
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
        // !!! Los selectores en load() son para la página de detalles de un anime/película.
        // Necesitas volver a revisar el HTML de una página de detalles individual. !!!
        // Ej: https://latanime.org/anime/las-aventuras-de-la-hija-del-rey-demonio-s4-latino

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
                ?: it.selectFirst("h2")?.text() // Posiblemente h2 si es una página de "capítulo" directamente
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
        // Log para indicar que se está llamando a loadLinks y con qué 'data' (la URL del episodio)
        Log.d("LatanimePlugin", "loadLinks called with data: $data")

        // Selector para encontrar el elemento que contiene la URL codificada del reproductor
        // Asegúrate de que este selector (div.container-fluid ... li#play-video) es CORRECTO
        // para la página de un episodio individual en LatAnime.
        appGetChildMainUrl(data).document.select("div.container-fluid div.row div.col-md-12.col-lg-8.seiya ul.cap_repro.d-flex.flex-wrap li#play-video").apmap {
            // Log para mostrar el HTML del elemento que se está procesando
            Log.d("LatanimePlugin", "Found player element: ${it.outerHtml()}")

            val encodedurl = it.select("a").attr("data-player")

            // Log para mostrar la URL codificada que se encontró
            Log.d("LatanimePlugin", "Encoded URL found: $encodedurl")

            if (encodedurl.isNullOrEmpty()) {
                // Log de advertencia si la URL codificada está vacía
                Log.w("LatanimePlugin", "Encoded URL is null or empty for $data. Could not find player data-player attribute.")
                return@apmap // Salir de esta iteración si no hay URL codificada
            }

            val urlDecoded = base64Decode(encodedurl)
            // Log para mostrar la URL después de la decodificación Base64
            Log.d("LatanimePlugin", "Decoded URL (Base64): $urlDecoded")

            // Aplicar los reemplazos de URL. Revisa si estos patrones siguen siendo válidos.
            val url = (urlDecoded).replace("https://monoschinos2.com/reproductor?url=", "")
                .replace("https://sblona.com", "https://watchsb.com")

            // Log para mostrar la URL final que se pasará al extractor
            Log.d("LatanimePlugin", "Final URL for Extractor: $url")

            // Llamar al loadExtractor de Cloudstream para procesar la URL del video
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        // Log para indicar que loadLinks ha terminado de procesar
        Log.d("LatanimePlugin", "loadLinks finished for data: $data")
        return true
    }
}