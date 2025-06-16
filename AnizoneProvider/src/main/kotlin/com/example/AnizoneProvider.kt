package com.example

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// NUEVAS IMPORTACIONES
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class AnizoneProvider : MainAPI() {

    override var mainUrl = "https://anizone.to"
    override var name = "AniZone"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "2" to "Últimas Series TV",
        "4" to "Últimas Películas",
        "6" to "Últimas Web"
    )

    private var cookies = mutableMapOf<String, String>()
    private var wireData = mutableMapOf(
        "wireSnapshot" to "",
        "token" to ""
    )

    init {
        runBlocking {
            try {
                val initReq = app.get("$mainUrl/anime")
                this@AnizoneProvider.cookies = initReq.cookies.toMutableMap()
                val doc = initReq.document
                wireData["token"] = doc.select("script[data-csrf]").attr("data-csrf")
                wireData["wireSnapshot"] = getSnapshot(doc)
                sortAnimeLatest()
                Log.d(name, "AnizoneProvider: Inicialización completa.")
            } catch (e: Exception) {
                Log.e(name, "AnizoneProvider: Error durante la inicialización: ${e.message}", e)
            }
        }
    }

    private suspend fun sortAnimeLatest() {
        try {
            liveWireBuilder(mapOf("sort" to "release-desc"), mutableListOf(), this.cookies, this.wireData, true)
            Log.d(name, "AnizoneProvider: sortAnimeLatest ejecutado.")
        } catch (e: Exception) {
            Log.e(name, "AnizoneProvider: Error en sortAnimeLatest: ${e.message}", e)
        }
    }

    private fun getSnapshot(doc : Document) : String {
        return doc.select("main div[wire:snapshot]")
            .attr("wire:snapshot").replace("&quot;", "\"")
    }
    private fun getSnapshot(json : JSONObject) : String {
        return json.getJSONArray("components")
            .getJSONObject(0).getString("snapshot")
    }

    private  fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(json.getJSONArray("components")
            .getJSONObject(0).getJSONObject("effects")
            .getString("html"))
    }

    private suspend fun liveWireBuilder (updates : Map<String,String>, calls: List<Map<String, Any>>,
                                         biscuit : MutableMap<String, String>,
                                         wireCreds : MutableMap<String,String>,
                                         remember : Boolean): JSONObject {
        try {
            val payloadMap: Map<String, Any> = mapOf(
                "_token" to (wireCreds["token"] ?: ""),
                "components" to listOf(
                    mapOf("snapshot" to wireCreds["wireSnapshot"], "updates" to updates,
                        "calls" to calls
                    )
                )
            )

            val jsonPayloadString: String = payloadMap.toJson()
            Log.d(name, "liveWireBuilder: Payload JSON enviado: $jsonPayloadString")

            val requestBody: RequestBody = jsonPayloadString.toRequestBody(
                "application/json".toMediaTypeOrNull() ?: "application/json".toMediaType()
            )

            val req = app.post(
                "$mainUrl/livewire/update",
                requestBody = requestBody,
                headers = mapOf("Content-Type" to "application/json"),
                cookies = biscuit,
            )

            val responseText = req.text
            Log.d(name, "liveWireBuilder: Respuesta de Livewire (parcial, si es muy larga): ${responseText.take(500)}")

            if (remember) {
                wireCreds["wireSnapshot"] = getSnapshot(JSONObject(responseText))
                biscuit.putAll(req.cookies.toMutableMap())
                Log.d(name, "liveWireBuilder: Cookies y wireSnapshot actualizados (remember=true).")
            } else {
                Log.d(name, "liveWireBuilder: Cookies y wireSnapshot NO actualizados (remember=false).")
            }

            return JSONObject(responseText)
        } catch (e: Exception) {
            Log.e(name, "liveWireBuilder: Error al ejecutar Livewire: ${e.message}", e)
            throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest
    ): HomePageResponse {
        try {
            val doc = getHtmlFromWire(
                liveWireBuilder(
                    mutableMapOf("type" to request.data),
                    mutableListOf(),
                    this.cookies,
                    this.wireData,
                    true
                )
            )

            var home : List<Element> = doc.select("div[wire:key]")

            if (page>1)
                home = home.takeLast(12)

            Log.d(name, "getMainPage: Se encontraron ${home.size} resultados para ${request.name}.")
            return newHomePageResponse(
                HomePageList(request.name, home.map { toResult(it)}, isHorizontalImages = false),
                hasNext = (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]")!=null)
            )
        } catch (e: Exception) {
            Log.e(name, "getMainPage: Error al cargar la página principal: ${e.message}", e)
            return newHomePageResponse(HomePageList(request.name, emptyList()))
        }
    }

    private fun toResult(post: Element): SearchResponse {
        val title = post.selectFirst("img")?.attr("alt") ?: ""
        val url = post.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = post.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(name, "quickSearch: Ejecutando búsqueda rápida para: $query")
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search: Intentando búsqueda directa por URL para: $query")
        try {
            // Utiliza URLEncoder.encode en lugar de AppUtils.encodeUrl
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val searchUrl = "$mainUrl/anime?search=$encodedQuery"
            val r = app.get(searchUrl)
            val doc = r.document
            val directResults = doc.select("div[wire:key]").mapNotNull { toResult(it) }

            if (directResults.isNotEmpty()) {
                Log.d(name, "search: Búsqueda directa por URL exitosa, se encontraron ${directResults.size} resultados.")
                return directResults
            } else {
                Log.w(name, "search: Búsqueda directa por URL no encontró resultados, intentando con Livewire...")
            }
        } catch (e: Exception) {
            Log.e(name, "search: Error en búsqueda directa por URL: ${e.message}", e)
            Log.w(name, "search: Cayendo a búsqueda Livewire debido a error en búsqueda directa.")
        }

        // --- Lógica de búsqueda Livewire (fallback o principal si la directa no funciona) ---
        try {
            Log.d(name, "search: Ejecutando búsqueda Livewire para: $query")
            val doc = getHtmlFromWire(
                liveWireBuilder(
                    mutableMapOf("search" to query),
                    mutableListOf(),
                    this.cookies,
                    this.wireData,
                    true
                )
            )
            val results = doc.select("div[wire:key]").mapNotNull { toResult(it) }
            Log.d(name, "search: Se encontraron ${results.size} resultados Livewire para '$query'.")
            if (results.isEmpty()) {
                Log.w(name, "search: Livewire no encontró resultados. Verificar el selector 'div[wire:key]' o la respuesta Livewire.")
                Log.d(name, "search: HTML devuelto por Livewire (parcial): ${doc.html().take(1000)}")
            }
            return results
        } catch (e: Exception) {
            Log.e(name, "search: Error al ejecutar la búsqueda Livewire: ${e.message}", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val r = app.get(url)
            var doc = r.document

            val cookie = r.cookies.toMutableMap()
            val wireData = mutableMapOf(
                "wireSnapshot" to getSnapshot(doc=doc),
                "token" to doc.select("script[data-csrf]").attr("data-csrf")
            )
            Log.d(name, "load: Cargando URL: $url, wireData inicial: $wireData")

            val title = doc.selectFirst("h1")?.text()
                ?: throw NotImplementedError("Unable to find title")

            val bgImage = doc.selectFirst("main img")?.attr("src")
            val synopsis = doc.selectFirst(".sr-only + div")?.text() ?: ""

            val rowLines = doc.select("span.inline-block").map { it.text() }
            val releasedYear = rowLines.getOrNull(3)
            val status = if (rowLines.getOrNull(1) == "Completed") ShowStatus.Completed
            else if (rowLines.getOrNull(1) == "Ongoing") ShowStatus.Ongoing else null

            val genres = doc.select("a[wire:navigate][wire:key]").map { it.text() }

            var loadMoreCount = 0
            while (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]")!=null && loadMoreCount < 5) {
                Log.d(name, "load: Detectado 'Load More', intentando cargar más episodios.")
                val liveWireResponse = liveWireBuilder(
                    mutableMapOf(), mutableListOf(
                        mapOf("path" to "", "method" to "loadMore", "params" to listOf<String>())
                    ), cookie, wireData, true
                )
                doc = getHtmlFromWire(liveWireResponse)
                loadMoreCount++
            }
            if (loadMoreCount >= 5) {
                Log.w(name, "load: Se alcanzó el límite de carga de más episodios (5 veces). Podrían faltar episodios.")
            }


            val epiElms = doc.select("li[x-data]")
            Log.d(name, "load: Se encontraron ${epiElms.size} elementos de episodio.")

            val episodes = epiElms.mapNotNull{ elt ->
                try {
                    newEpisode(
                        data = elt.selectFirst("a")?.attr("href") ?: run {
                            Log.w(name, "load: Elemento de episodio sin URL de enlace: ${elt.html().take(200)}")
                            return@mapNotNull null
                        }
                    ) {
                        this.name = elt.selectFirst("h3")?.text()
                            ?.substringAfter(":")?.trim() ?: run {
                            Log.w(name, "load: Episodio sin nombre: ${elt.html().take(200)}")
                            null
                        }
                        this.season = 1
                        this.posterUrl = elt.selectFirst("img")?.attr("src")

                        this.date = elt.selectFirst("span.span-tiempo")?.text()?.let { dateText ->
                            try {
                                SimpleDateFormat("dd MMM.yyyy", Locale.US).parse(dateText)?.time // Formato de fecha del sitio
                            } catch (e: Exception) {
                                Log.e(name, "load: Error al analizar la fecha '$dateText': ${e.message}", e)
                                null
                            }
                        } ?: 0L
                    }
                } catch (e: Exception) {
                    Log.e(name, "load: Error al procesar elemento de episodio: ${e.message}. Elemento: ${elt.html().take(200)}", e)
                    null
                }
            }
            Log.d(name, "load: Se procesaron ${episodes.size} episodios válidos.")


            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = bgImage
                this.plot = synopsis
                this.tags = genres
                this.year = releasedYear?.toIntOrNull()
                this.showStatus = status
                addEpisodes(DubStatus.None, episodes)
            }
        } catch (e: Exception) {
            Log.e(name, "load: Error al cargar los detalles del anime: ${e.message}", e)
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val web = app.get(data).document
            val sourceName = web.selectFirst("span.truncate")?.text() ?: "Anizone"
            val mediaPlayer = web.selectFirst("media-player")

            val m3U8 = mediaPlayer?.attr("src")
            if (m3U8.isNullOrEmpty()) {
                Log.w(name, "loadLinks: No se encontró la fuente M3U8 en media-player para $data")
                return false
            }

            mediaPlayer?.select("track")?.forEach { trackElement ->
                val label = trackElement.attr("label")
                val src = trackElement.attr("src")
                if (label.isNotBlank() && src.isNotBlank()) {
                    subtitleCallback.invoke(
                        SubtitleFile(label, src)
                    )
                    Log.d(name, "loadLinks: Subtítulo encontrado: $label, URL: $src")
                }
            }

            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = name,
                    url = m3U8,
                    type = ExtractorLinkType.M3U8
                )
            )
            Log.d(name, "loadLinks: Enlace extractor añadido para $sourceName: $m3U8")
            return true
        } catch (e: Exception) {
            Log.e(name, "loadLinks: Error al cargar enlaces para $data: ${e.message}", e)
            return false
        }
    }
}