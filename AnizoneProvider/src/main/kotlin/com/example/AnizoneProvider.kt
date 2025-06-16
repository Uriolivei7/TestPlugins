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
import com.lagradost.cloudstream3.newMovieSearchResponse // Usamos newMovieSearchResponse
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
import android.util.Log // Usamos Log nativo
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
                // Asegurar que 'cookies' se inicialice como MutableMap
                this@AnizoneProvider.cookies = initReq.cookies.toMutableMap()
                val doc = initReq.document
                wireData["token"] = doc.select("script[data-csrf]").attr("data-csrf")
                wireData["wireSnapshot"] = getSnapshot(doc)
                sortAnimeLatest()
            } catch (e: Exception) {
                // Usar Log.e nativo en lugar de log de Cloudstream
                Log.e(name, "AnizoneProvider: Error durante la inicialización: ${e.message}", e)
            }
        }
    }

    private suspend fun sortAnimeLatest() {
        // liveWireBuilder espera Map<String, String> para 'updates' y MutableMap para 'biscuit' y 'wireCreds'.
        // `mapOf` crea un Map inmutable, que es compatible con la firma de liveWireBuilder.
        liveWireBuilder(mapOf("sort" to "release-desc"), mutableListOf(), this.cookies, this.wireData, true)
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

        val payloadMap: Map<String, Any> = mapOf(
            "_token" to (wireCreds["token"] ?: ""), // Usar elvis operator por si token es nulo
            "components" to listOf(
                mapOf("snapshot" to wireCreds["wireSnapshot"], "updates" to updates,
                    "calls" to calls
                )
            )
        )

        val jsonPayloadString: String = payloadMap.toJson()
        val requestBody: RequestBody = jsonPayloadString.toRequestBody(
            "application/json".toMediaTypeOrNull() ?: "application/json".toMediaType()
        )

        val req = app.post(
            "$mainUrl/livewire/update",
            requestBody = requestBody,
            headers = mapOf("Content-Type" to "application/json"),
            cookies = biscuit, // Pasa el MutableMap directamente
        )

        if (remember) {
            wireCreds["wireSnapshot"] = getSnapshot(JSONObject(req.text))
            // Asegurar que req.cookies se convierta a MutableMap antes de putAll
            biscuit.putAll(req.cookies.toMutableMap())
        }

        return JSONObject(req.text)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest
    ): HomePageResponse {
        val doc = getHtmlFromWire(
            liveWireBuilder(
                mutableMapOf("type" to request.data), // Usar mutableMapOf si el tipo inferido es un problema
                mutableListOf(),
                this.cookies,
                this.wireData,
                true
            )
        )

        var home : List<Element> = doc.select("div[wire:key]")

        if (page>1)
            home = home.takeLast(12)

        return newHomePageResponse(
            HomePageList(request.name, home.map { toResult(it)}, isHorizontalImages = false),
            hasNext = (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]")!=null)
        )
    }

    private fun toResult(post: Element): SearchResponse {
        val title = post.selectFirst("img")?.attr("alt") ?: ""
        val url = post.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = post.selectFirst("img")?.attr("src")

        // Usar newMovieSearchResponse como alternativa a newSearchResponse
        return newMovieSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getHtmlFromWire(
            liveWireBuilder(
                mutableMapOf("search" to query),
                mutableListOf(),
                this.cookies,
                this.wireData,
                false
            )
        )
        return doc.select("div[wire:key]").mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {

        val r = app.get(url)
        var doc = r.document

        // Corrección del error Type mismatch: inferred type is Map<String, String> but MutableMap<String, String> was expected :193
        // Convertir r.cookies a MutableMap
        val cookie = r.cookies.toMutableMap()
        val wireData = mutableMapOf(
            "wireSnapshot" to getSnapshot(doc=doc),
            "token" to doc.select("script[data-csrf]").attr("data-csrf")
        )

        val title = doc.selectFirst("h1")?.text()
            ?: throw NotImplementedError("Unable to find title")

        val bgImage = doc.selectFirst("main img")?.attr("src")
        val synopsis = doc.selectFirst(".sr-only + div")?.text() ?: ""

        val rowLines = doc.select("span.inline-block").map { it.text() }
        val releasedYear = rowLines.getOrNull(3)
        val status = if (rowLines.getOrNull(1) == "Completed") ShowStatus.Completed
        else if (rowLines.getOrNull(1) == "Ongoing") ShowStatus.Ongoing else null

        val genres = doc.select("a[wire:navigate][wire:key]").map { it.text() }

        while (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]")!=null) {
            doc = getHtmlFromWire(liveWireBuilder(
                mutableMapOf(), mutableListOf(
                    mapOf("path" to "", "method" to "loadMore", "params" to listOf<String>())
                ), cookie, wireData, true
            )
            )
        }

        val epiElms = doc.select("li[x-data]")

        val episodes = epiElms.map{ elt ->
            newEpisode(
                data = elt.selectFirst("a")?.attr("href") ?: "") {
                this.name = elt.selectFirst("h3")?.text()
                    ?.substringAfter(":")?.trim()
                this.season = 1
                this.posterUrl = elt.selectFirst("img")?.attr("src")

                this.date = elt.selectFirst("span.span-tiempo")?.text()?.let { dateText ->
                    try {
                        SimpleDateFormat("dd MMM.yyyy", Locale.US).parse(dateText)?.time // Formato de fecha del sitio
                    } catch (e: Exception) {
                        Log.e(name, "Error al analizar la fecha '$dateText': ${e.message}", e)
                        null
                    }
                } ?: 0L
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = bgImage
            this.plot = synopsis
            this.tags = genres
            this.year = releasedYear?.toIntOrNull()
            this.showStatus = status
            addEpisodes(DubStatus.None, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val web = app.get(data).document
        val sourceName = web.selectFirst("span.truncate")?.text() ?: "Anizone"
        val mediaPlayer = web.selectFirst("media-player")

        val m3U8 = mediaPlayer?.attr("src")
        if (m3U8.isNullOrEmpty()) {
            Log.w(name, "No se encontró la fuente M3U8 en media-player para $data")
            return false
        }

        mediaPlayer?.select("track")?.forEach { trackElement ->
            val label = trackElement.attr("label")
            val src = trackElement.attr("src")
            if (label.isNotBlank() && src.isNotBlank()) {
                subtitleCallback.invoke(
                    SubtitleFile(label, src)
                )
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

        return true
    }
}