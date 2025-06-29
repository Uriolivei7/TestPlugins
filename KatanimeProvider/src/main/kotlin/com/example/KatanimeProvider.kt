package com.example // Cambia esto si tu paquete real es diferente

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.FormBody
import kotlinx.coroutines.delay

class KatanimeProvider : MainAPI() {
    override var name = "Katanime"
    override var mainUrl = "https://katanime.net"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "es"

    override val hasMainPage = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        val response = app.get(url, interceptor = cfKiller).document
        val searchResults = response.select("a.anime-card")
        val animeList = ArrayList<SearchResponse>()

        for (result : Element in searchResults) {
            val title = result.selectFirst("div.title")?.text()?.trim()
            val posterUrl = result.selectFirst("div.cover img")?.attr("src")?.let { fixUrl(it) }
            val link = result.attr("href")?.let { fixUrl(it) }

            if (title != null && link != null) {
                animeList.add(
                    newTvSeriesSearchResponse( // Usamos newTvSeriesSearchResponse
                        name = title,
                        url = link
                    ) {
                        // REMOVIDO: this.apiName = this@KatanimeProvider.name // Esto causaba el error "Val cannot be reassigned"
                        this.posterUrl = posterUrl // Esto sí es correcto aquí
                        this.type = TvType.Anime // Esto sí es correcto aquí
                    }
                )
            }
        }
        return animeList
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String,
        val poster: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Katanime", "load - URL de entrada: $url")
        val cleanUrl = fixUrl(url)

        val doc = app.get(cleanUrl, interceptor = cfKiller).document

        val title = doc.selectFirst("h1.title")?.text() ?: ""
        val posterUrl = doc.selectFirst("div.cover img")?.attr("src")?.let { fixUrl(it) }
        val description = doc.selectFirst("div.description-p p")?.text()?.trim() ?: ""
        val tags = doc.select("ul.genres li a").map { it.text().trim() }

        val dataUrl = doc.selectFirst("div[data-url]")?.attr("data-url")?.let { fixUrl(it) }
        val csrfToken = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content") ?: ""

        val episodesList = getEpisodes(dataUrl, csrfToken, cleanUrl)

        return newTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            type = TvType.Anime,
            episodes = episodesList
        ) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.year = null
            this.rating = null
            this.duration = null
            this.recommendations = null
            this.actors = null
            this.trailers = mutableListOf()
            this.comingSoon = false
            this.syncData = mutableMapOf()
            this.posterHeaders = null
            this.contentRating = null
        }
    }

    private suspend fun getEpisodes(dataUrl: String?, csrfToken: String?, refererUrl: String): List<Episode> {
        if (dataUrl == null || csrfToken == null) {
            Log.e("Katanime", "getEpisodes - dataUrl o csrfToken es nulo. No se pueden obtener episodios.")
            return emptyList()
        }

        val episodes = ArrayList<Episode>()
        var currentPage = 1
        var hasMorePages = true

        while (hasMorePages) {
            Log.d("Katanime", "getEpisodes - Intentando obtener página $currentPage de episodios de: $dataUrl")

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
                "X-XSRF-TOKEN" to csrfToken
            )

            try {
                app.get(refererUrl, interceptor = cfKiller)
                Log.d("Katanime", "getEpisodes - Cookies refrescadas (GET a refererUrl) exitosamente.")
            } catch (e: Exception) {
                Log.w("Katanime", "getEpisodes - Error al intentar refrescar cookies: ${e.message}")
            }

            val response = try {
                app.post(dataUrl, requestBody = requestBody, headers = headers, interceptor = cfKiller)
            } catch (e: Exception) {
                Log.e("Katanime", "getEpisodes - Error en solicitud POST a $dataUrl: ${e.message}")
                hasMorePages = false
                break
            }

            val jsonString = response.body.string()
            Log.d("Katanime", "getEpisodes - Respuesta JSON (primeros 500 chars): ${jsonString.take(500)}")

            val episodeData = tryParseJson<EpisodeResponse>(jsonString)

            if (episodeData != null && episodeData.ep.data.isNotEmpty()) {
                for (epItem in episodeData.ep.data) {
                    val episodeNum = epItem.numero?.toIntOrNull()
                    episodes.add(
                        newEpisode(
                            EpisodeLoadData(
                                title = "Episodio ${epItem.numero ?: "N/A"}",
                                url = fixUrl(epItem.url),
                                poster = epItem.thumb?.let { fixUrl(it) }
                            ).toJson()
                        ) {
                            this.name = "Episodio ${epItem.numero ?: "N/A"}"
                            this.episode = episodeNum
                            this.posterUrl = epItem.thumb?.let { fixUrl(it) }
                        }
                    )
                }
                hasMorePages = episodeData.ep.next_page_url != null
                if (hasMorePages) {
                    currentPage++
                }
            } else {
                Log.d("Katanime", "getEpisodes - No se encontraron datos de episodios o JSON nulo/vacío para la página $currentPage.")
                hasMorePages = false
            }
        }
        Log.d("Katanime", "getEpisodes - Total de episodios encontrados: ${episodes.size}")
        return episodes.reversed()
    }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Katanime", "loadLinks - Data de entrada: $data")

        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data)

        if (targetUrl.isBlank()) {
            Log.e("Katanime", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl, interceptor = cfKiller).document

        val playerDivs = doc.select("div.player-data")
        var foundLinks = false

        for (playerDiv in playerDivs) {
            val serverName = playerDiv.selectFirst("a")?.text()?.trim() ?: "Desconocido"
            val playerUrl = playerDiv.attr("data-player")

            if (playerUrl.isNotBlank()) {
                Log.d("Katanime", "loadLinks - Encontrado player '$serverName' con URL: $playerUrl")
                val fixedPlayerUrl = fixUrl(playerUrl)

                try {
                    Log.d("Katanime", "loadLinks - yendo a la URL del reproductor: $fixedPlayerUrl")
                    val playerDoc = app.get(fixedPlayerUrl, interceptor = cfKiller).document
                    val finalIframeSrc = playerDoc.selectFirst("iframe")?.attr("src")

                    if (!finalIframeSrc.isNullOrBlank()) {
                        Log.d("Katanime", "loadLinks - Encontrado iframe final en $serverName: $finalIframeSrc")

                        try {
                            if (loadExtractor(fixUrl(finalIframeSrc), fixedPlayerUrl, subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        } catch (e: Exception) {
                            Log.e("Katanime", "Error al cargar extractor para $finalIframeSrc: ${e.message}")
                        }

                    } else {
                        Log.w("Katanime", "loadLinks - No se encontró iframe dentro de $fixedPlayerUrl para $serverName.")
                    }
                } catch (e: Exception) {
                    Log.e("Katanime", "loadLinks - Error al obtener contenido del reproductor $fixedPlayerUrl ($serverName): ${e.message}")
                }
            } else {
                Log.w("Katanime", "loadLinks - Elemento player-data sin 'data-player' válido.")
            }
        }

        return foundLinks
    }
}