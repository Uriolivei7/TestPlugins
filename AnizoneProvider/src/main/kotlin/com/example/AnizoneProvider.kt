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
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var isLivewireInitialized = false

    // Mutex para sincronizar el acceso a la inicialización de Livewire
    private val livewireInitMutex = Mutex()

    /**
     * Inicializa las credenciales de Livewire (cookies, token, snapshot) desde la página principal.
     * Utiliza un Mutex para asegurar que solo un hilo la ejecute a la vez.
     * Si falla, la bandera isLivewireInitialized se mantendrá en false.
     */
    private suspend fun initializeLivewire() {
        livewireInitMutex.withLock { // Asegura que solo un hilo pueda ejecutar este bloque a la vez
            if (isLivewireInitialized && !wireData["token"].isNullOrBlank() && !wireData["wireSnapshot"].isNullOrBlank()) {
                Log.d(name, "initializeLivewire: Livewire ya inicializado y credenciales válidas. Saltando inicialización.")
                return // Ya inicializado, salimos
            }

            Log.d(name, "initializeLivewire: Intentando inicializar Livewire (dentro de Mutex)...")
            try {
                val initReq = app.get("$mainUrl/anime")
                this.cookies = initReq.cookies.toMutableMap()
                val doc = initReq.document
                wireData["token"] = doc.select("script[data-csrf]").attr("data-csrf")
                wireData["wireSnapshot"] = getSnapshot(doc)

                isLivewireInitialized = true // Marcamos que la inicialización fue exitosa
                Log.d(name, "initializeLivewire: Livewire inicializado exitosamente (dentro de Mutex).")
            } catch (e: Exception) {
                isLivewireInitialized = false // La inicialización falló
                Log.e(name, "initializeLivewire: Error durante la inicialización de Livewire (dentro de Mutex): ${e.message}", e)
                throw e // Relanzar la excepción para que el llamador maneje el fallo
            }
        }
    }

    /**
     * Función auxiliar para obtener el snapshot de Livewire de un documento Jsoup.
     */
    private fun getSnapshot(doc : Document) : String {
        return doc.select("main div[wire:snapshot]")
            .attr("wire:snapshot").replace("&quot;", "\"")
    }

    /**
     * Función auxiliar para obtener el snapshot de Livewire de un objeto JSON de respuesta.
     */
    private fun getSnapshot(json : JSONObject) : String {
        return json.getJSONArray("components")
            .getJSONObject(0).getString("snapshot")
    }

    /**
     * Función auxiliar para obtener el HTML de la respuesta JSON de Livewire.
     */
    private fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(json.getJSONArray("components")
            .getJSONObject(0).getJSONObject("effects")
            .getString("html"))
    }

    /**
     * Constructor principal para las peticiones Livewire.
     * Incorpora lógica de reintento si el token o snapshot parecen inválidos.
     *
     * @param updates Un mapa de actualizaciones de estado para el componente Livewire.
     * @param calls Una lista de llamadas a métodos de Livewire.
     * @param biscuit El mapa de cookies actual que se enviará con la petición.
     * @param wireCreds El mapa que contiene wireSnapshot y _token.
     * @param remember Si es true, actualizará wireCreds y biscuit con la nueva respuesta.
     * @param retryCount Contador de reintentos para evitar bucles infinitos.
     * @return El objeto JSONObject de la respuesta de Livewire.
     * @throws IllegalStateException Si el token o snapshot faltan después de la inicialización, o si se exceden los reintentos.
     * @throws Exception Si ocurre un error de red o de parsing.
     */
    private suspend fun liveWireBuilder (
        updates : Map<String,String>,
        calls: List<Map<String, Any>>,
        biscuit : MutableMap<String, String>,
        wireCreds : MutableMap<String,String>,
        remember : Boolean,
        retryCount: Int = 0
    ): JSONObject {
        val maxRetries = 2 // Aumentamos a 2 reintentos (total de 3 intentos: original + 2 reintentos)

        try {
            // 1. Verificar si Livewire está inicializado. Si no, intentar inicializarlo.
            // Siempre intentamos inicializar si no está inicializado, o si las credenciales parecen vacías.
            if (!isLivewireInitialized || wireCreds["token"].isNullOrBlank() || wireCreds["wireSnapshot"].isNullOrBlank()) {
                Log.d(name, "liveWireBuilder: Livewire no inicializado o credenciales vacías. Intentando inicializar (reintento: $retryCount)...")
                initializeLivewire() // Intenta inicializar. Si falla, lanzará una excepción.
                // Si la inicialización fue exitosa, isLivewireInitialized será true y wireCreds estará actualizado.
            }

            // Asegurarse de que las credenciales no sean nulas después de la inicialización (o re-inicialización)
            val currentToken = wireCreds["token"] ?: throw IllegalStateException("Livewire token is missing after initialization.")
            val currentSnapshot = wireCreds["wireSnapshot"] ?: throw IllegalStateException("Livewire snapshot is missing after initialization.")

            val payloadMap: Map<String, Any> = mapOf(
                "_token" to currentToken,
                "components" to listOf(
                    mapOf("snapshot" to currentSnapshot, "updates" to updates,
                        "calls" to calls
                    )
                )
            )

            val jsonPayloadString: String = payloadMap.toJson()
            Log.d(name, "liveWireBuilder: Payload JSON enviado (parcial): ${jsonPayloadString.take(200)}")

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

            // *** Detección de Token/Snapshot Inválido/Expirado ***
            // Si la respuesta es un HTML de error o un JSON que no contiene 'snapshot' en la estructura esperada
            if (!req.isSuccessful || responseText.contains("This page has expired", ignoreCase = true) ||
                responseText.contains("token mismatch", ignoreCase = true) ||
                !responseText.contains("\"snapshot\"") // Buscar la clave "snapshot" en la respuesta JSON
            ) {
                Log.w(name, "liveWireBuilder: Posible token Livewire o snapshot expirado/inválido. Estado HTTP: ${req.code}. Reintentando (intento: ${retryCount + 1})...")
                isLivewireInitialized = false // Forzamos la re-inicialización
                if (retryCount < maxRetries) {
                    // Reintentar la llamada recursivamente después de forzar la re-inicialización
                    return liveWireBuilder(updates, calls, biscuit, wireCreds, remember, retryCount + 1)
                } else {
                    Log.e(name, "liveWireBuilder: Se excedió el número máximo de reintentos para Livewire. El token/snapshot sigue inválido o hay un problema persistente.")
                    throw IllegalStateException("Demasiados reintentos para Livewire, token/snapshot sigue inválido o hay un problema persistente.")
                }
            }

            val jsonResponse = JSONObject(responseText) // Intentar parsear la respuesta a JSON

            if (remember) {
                // Asegurarse de que el nuevo snapshot exista en la respuesta antes de intentar obtenerlo
                val newSnapshot = try {
                    getSnapshot(jsonResponse)
                } catch (e: Exception) {
                    Log.e(name, "liveWireBuilder: No se pudo obtener el nuevo snapshot de la respuesta JSON, pero la respuesta parecía válida. ${e.message}", e)
                    // Si no hay nuevo snapshot pero la llamada fue exitosa, no actualizamos.
                    // Esto podría indicar un problema de parsing o un cambio en la estructura de Livewire.
                    throw e
                }
                wireCreds["wireSnapshot"] = newSnapshot
                biscuit.putAll(req.cookies.toMutableMap())
                Log.d(name, "liveWireBuilder: Cookies y wireSnapshot actualizados (remember=true).")
            } else {
                Log.d(name, "liveWireBuilder: Cookies y wireSnapshot NO actualizados (remember=false).")
            }

            return jsonResponse
        } catch (e: Exception) {
            Log.e(name, "liveWireBuilder: Error general al ejecutar Livewire (no relacionado con token/snapshot directamente): ${e.message}", e)
            isLivewireInitialized = false // Forzamos la re-inicialización para el próximo intento por precaución
            throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            // Asegurarse de que Livewire esté inicializado antes de hacer la primera llamada a liveWireBuilder
            // La función initializeLivewire() ahora maneja la concurrencia internamente.
            if (!isLivewireInitialized) {
                Log.d(name, "getMainPage: Livewire no inicializado. Llamando a initializeLivewire()...")
                initializeLivewire()
            }

            // Realizar la primera llamada para obtener la página principal o aplicar el tipo
            var initialLivewireResponse = liveWireBuilder(
                mutableMapOf("type" to request.data),
                mutableListOf(),
                this.cookies,
                this.wireData,
                true
            )
            var doc = getHtmlFromWire(initialLivewireResponse)
            var home: List<Element> = doc.select("div[wire:key]")

            // Manejo de paginación si se solicita una página mayor a 1
            if (page > 1) {
                Log.w(name, "getMainPage: Paginación para página principal, intentando cargar más páginas. Página solicitada: $page")
                // Iterar para cargar las páginas adicionales
                for (i in 1 until page) {
                    val loadMoreResponse = liveWireBuilder(
                        mutableMapOf(),
                        mutableListOf(mapOf("path" to "", "method" to "loadMore", "params" to listOf<String>())),
                        this.cookies,
                        this.wireData,
                        true
                    )
                    val newDoc = getHtmlFromWire(loadMoreResponse)
                    val newElements = newDoc.select("div[wire:key]")
                    if (newElements.isEmpty()) {
                        Log.w(name, "getMainPage: No se encontraron más elementos al cargar la página ${i + 1}.")
                        break // Salir si no hay más elementos
                    }
                    home = newElements // Sobrescribimos 'home' con los elementos de la nueva página.
                    // Si quieres acumularlos, deberías hacer home.plus(newElements)
                    // Pero para una 'página' específica, generalmente solo quieres los elementos de esa página.
                }
            }


            Log.d(name, "getMainPage: Se encontraron ${home.size} resultados para ${request.name}.")
            return newHomePageResponse(
                HomePageList(request.name, home.map { toResult(it)}, isHorizontalImages = false),
                hasNext = (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]")!=null)
            )
        } catch (e: Exception) {
            Log.e(name, "getMainPage: Error al cargar la página principal: ${e.message}", e)
            throw e
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
            if (!isLivewireInitialized) {
                Log.d(name, "search: Livewire no inicializado. Llamando a initializeLivewire()...")
                initializeLivewire()
            }

            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val searchUrl = "$mainUrl/anime?search=$encodedQuery"
            val r = app.get(searchUrl)
            val doc = r.document
            val directResults = doc.select("div[wire:key]").mapNotNull { toResult(it) }

            if (directResults.isNotEmpty()) {
                Log.d(name, "search: Búsqueda directa por URL exitosa, se encontraron ${directResults.size} resultados.")
                return directResults
            } else {
                Log.w(name, "search: Búsqueda directa por URL no encontró resultados. Cayendo a búsqueda Livewire...")
            }
        } catch (e: Exception) {
            Log.e(name, "search: Error en búsqueda directa por URL: ${e.message}", e)
            Log.w(name, "search: Cayendo a búsqueda Livewire debido a error en búsqueda directa.")
        }

        try {
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
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val r = app.get(url)
            var doc = r.document

            val localCookies = r.cookies.toMutableMap()
            val localWireData = mutableMapOf(
                "wireSnapshot" to getSnapshot(doc=doc),
                "token" to doc.select("script[data-csrf]").attr("data-csrf")
            )
            Log.d(name, "load: Cargando URL: $url, wireData local inicial: $localWireData")

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
            val maxLoadMoreRetries = 5
            while (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]")!=null && loadMoreCount < maxLoadMoreRetries) {
                Log.d(name, "load: Detectado 'Load More', intentando cargar más episodios. Intento: ${loadMoreCount + 1}")
                try {
                    val liveWireResponse = liveWireBuilder(
                        mutableMapOf(), mutableListOf(
                            mapOf("path" to "", "method" to "loadMore", "params" to listOf<String>())
                        ), localCookies, localWireData, true
                    )
                    doc = getHtmlFromWire(liveWireResponse)
                    loadMoreCount++
                } catch (e: Exception) {
                    Log.e(name, "load: Error al cargar más episodios con Livewire: ${e.message}", e)
                    break
                }
            }
            if (loadMoreCount >= maxLoadMoreRetries) {
                Log.w(name, "load: Se alcanzó el límite de carga de más episodios ($maxLoadMoreRetries veces). Podrían faltar episodios.")
            }

            val epiElms = doc.select("li[x-data]")
            Log.d(name, "load: Se encontraron ${epiElms.size} elementos de episodio después de cargar más.")

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
                                SimpleDateFormat("dd MMM.yyyy", Locale.US).parse(dateText)?.time
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