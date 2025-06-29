package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson // Necesario para tryParseJson

class KatanimeProvider : MainAPI() {
    override var name = "Katanime"
    override var mainUrl = "https://katanime.net"
    override var supportedTypes = setOf(TvType.Anime) // Katanime es de Anime

    // Instancia de CloudflareKiller para manejar posibles protecciones
    private val cfKiller = CloudflareKiller()

    data class KatanimeTvSeriesLoadResponse(
        override var name: String,
        override var url: String,
        override var apiName: String,
        override var type: TvType,
        override var posterUrl: String?,
        override var year: Int?,
        override var plot: String?,
        override var rating: Int?,
        override var tags: List<String>?,
        override var duration: Int?,
        override var trailers: MutableList<TrailerData>,
        override var recommendations: List<SearchResponse>?,
        override var actors: List<ActorData>?,
        override var comingSoon: Boolean,
        override var syncData: MutableMap<String, String>,
        override var posterHeaders: Map<String, String>?,
        override var backgroundPosterUrl: String?,
        override var contentRating: String?,
        val episodes: List<Episode>? // Lista de episodios de la serie
        // `uniqueUrl` se ELIMINA completamente de aquí.
        // Si el compilador dice "overrides nothing", significa que no existe en la interfaz.
    ) : LoadResponse

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        // Usa CloudflareKiller para obtener la página si está protegida
        val response = app.get(url, interceptor = cfKiller).document
        val searchResults = response.select("a.anime-card")
        val animeList = ArrayList<SearchResponse>()

        for (result : org.jsoup.nodes.Element in searchResults) {
            val title = result.select("div.title").text().trim()
            val posterUrl = result.selectFirst("div.cover img")?.attr("src")?.let { fixUrl(it) }
            val link = result.attr("href")?.let { fixUrl(it) }

            if (link != null) {
                animeList.add(
                    TvSeriesSearchResponse(
                        name = title,
                        url = link,
                        apiName = this.name,
                        type = TvType.Anime, // Aseguramos que sea TvType.Anime
                        posterUrl = posterUrl
                    )
                )
            }
        }
        return animeList
    }
    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = fixUrl(url)
        val doc = app.get(cleanUrl, interceptor = cfKiller).document

        // Extracción de datos básicos
        val title = doc.selectFirst("h1.title")?.text() ?: "N/A"
        val posterUrl = doc.selectFirst("div.cover img")?.attr("src")?.let { fixUrl(it) }
        val description = doc.selectFirst("div.description-p p")?.text()?.trim() ?: ""
        val tags = doc.select("ul.genres li a").map { it.text().trim() }

        // Extracción de datos específicos para la carga de episodios (AJAX POST)
        val dataUrl = doc.selectFirst("div[data-url]")?.attr("data-url")?.let { fixUrl(it) }
        val csrfToken = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content") ?: ""

        // Obtener la lista de episodios
        val episodesList = getEpisodes(dataUrl, csrfToken, cleanUrl)

        // Construir y devolver la respuesta de carga
        return KatanimeTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            apiName = this.name,
            type = TvType.Anime, // Mantener como Anime
            posterUrl = posterUrl,
            episodes = episodesList,
            tags = tags,
            year = null, // No se puede extraer fácilmente el año de esta estructura
            plot = description,
            rating = null, // No se puede extraer fácilmente el rating
            duration = null, // No se puede extraer fácilmente la duración
            recommendations = null, // Katanime no muestra recomendaciones fácilmente
            actors = null, // Katanime no muestra actores fácilmente
            trailers = mutableListOf(), // No se han encontrado trailers en la página
            comingSoon = false,
            syncData = mutableMapOf(),
            posterHeaders = null,
            backgroundPosterUrl = posterUrl,
            contentRating = null
            // `uniqueUrl` se ELIMINA del constructor de la clase.
        )
    }

    private suspend fun getEpisodes(dataUrl: String?, csrfToken: String?, refererUrl: String): List<Episode> {
        if (dataUrl == null || csrfToken == null) {
            println("Katanime: dataUrl o csrfToken es nulo. No se pueden obtener episodios.")
            return emptyList()
        }

        val episodes = ArrayList<Episode>()
        var currentPage = 1
        var hasMorePages = true

        while (hasMorePages) {
            println("Katanime: load - URL de datos de episodios (data-url): $dataUrl")
            println("Katanime: load - Token CSRF encontrado (antes de POST): $csrfToken")

            val requestBody = FormBody.Builder()
                .add("_token", csrfToken)
                .add("pagina", currentPage.toString())
                .build()

            val headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to refererUrl,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
                "Origin" to mainUrl,
                "X-XSRF-TOKEN" to csrfToken // A veces el X-XSRF-TOKEN es necesario
            )

            // Intento de refrescar cookies antes de la solicitud POST
            // Esto es crucial para sitios con Cloudflare o seguridad dinámica
            try {
                println("Katanime: Intentando refrescar cookies antes de POST para episodios.")
                app.get(refererUrl, interceptor = cfKiller)
                println("Katanime: Cookies refrescadas (GET a refererUrl) exitosamente.")
            } catch (e: Exception) {
                println("Katanime: Error al intentar refrescar cookies: ${e.message}")
            }

            // Realizar la solicitud POST para obtener los datos de episodios
            val response = app.post(dataUrl, requestBody = requestBody, headers = headers, interceptor = cfKiller)
            val jsonString = response.body.string()
            println("Katanime: load - HTML de episodios (primeros 1000 caracteres): ${jsonString.take(1000)}")

            // Parsear la respuesta JSON
            val episodeData = tryParseJson<EpisodeResponse>(jsonString)

            if (episodeData != null && episodeData.ep.data.isNotEmpty()) {
                for (epItem in episodeData.ep.data) {
                    episodes.add(
                        Episode(
                            data = fixUrl(epItem.url), // URL completa del episodio para `loadLinks`
                            name = "Episodio ${epItem.numero ?: "N/A"}",
                            episode = epItem.numero?.toIntOrNull(),
                            posterUrl = epItem.thumb?.let { fixUrl(it) }
                        )
                    )
                }
                hasMorePages = episodeData.ep.next_page_url != null
                if (hasMorePages) {
                    currentPage++
                }
            } else {
                println("Katanime: No se encontraron datos de episodios o JSON nulo/vacío.")
                hasMorePages = false
            }
        }
        println("Katanime: load - Total de episodios encontrados: ${episodes.size}")
        return episodes.reversed() // Katanime suele listar de más nuevo a más viejo
    }

    // --- Clases de datos para la respuesta JSON de episodios ---
    data class EpisodeResponse(
        val ep: EpisodesWrapper,
        val last: LastEpisode?
    )

    data class EpisodesWrapper(
        val current_page: Int,
        val data: List<EpisodeItem>,
        val first_page_url: String?,
        val from: Int,
        val last_page: Int,
        val last_page_url: String?,
        val links: List<LinkItem>?,
        val next_page_url: String?,
        val path: String,
        val per_page: Int,
        val prev_page_url: String?,
        val to: Int,
        val total: Int
    )

    data class EpisodeItem(
        val numero: String?,
        val thumb: String?,
        val created_at: String?,
        val url: String
    )

    data class LastEpisode(
        val numero: String?
    )

    data class LinkItem(
        val url: String?,
        val label: String,
        val active: Boolean
    )

    // El método loadLinks está comentado, lo cual está bien si no lo necesitas.
    // Deberías implementarlo cuando necesites extraer los enlaces de vídeo de cada episodio.
    /*
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tu lógica de extracción de enlaces para cada episodio iría aquí.
        // `data` contendrá la URL del episodio que obtuviste en `getEpisodes`.
        return false
    }
    */
}