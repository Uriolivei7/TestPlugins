package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.* // MANTEN ESTA IMPORTACIÓN para ExtractorLink y otros utils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay

// Importaciones ESENCIALES y correctas:
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse

// ***** CORRECCIÓN CLAVE PARA loadExtractor *****
import com.lagradost.cloudstream3.utils.loadExtractor // Importación correcta para la función de nivel superior

// ***** IMPORTACIÓN PARA amap (para List y Set si aplica) *****
// Dado que 'amap' es una función de extensión para List, si tu IDE no la autocorrige,
// y está en el mismo paquete que MainAPI, no necesitas una importación específica.
// Si aún te da error, intenta con:
// import com.lagradost.cloudstream3.amap
// o
// import com.lagradost.cloudstream3.extractors.amap // si fuera el caso
// Para este caso, dado que el stub que me pasaste de 'amap' está directamente en
// `package com.lagradost.cloudstream3`, la importación ya debería estar cubierta
// por `com.lagradost.cloudstream3.*` si es de nivel superior.
// Si el error persiste, la solución es convertir a List ANTES de llamar a amap.
// La versión de `List<A>.amap` es la que vamos a usar.

class OtakustvProvider : MainAPI() {
    override var mainUrl = "https://www1.otakustv.com"
    override var name = "Otakustv"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val titleElement = element.selectFirst("h2.font-GDSherpa-Bold a")
            ?: element.selectFirst("a")

        val title = titleElement?.attr("title") ?: titleElement?.text()?.trim()
        val link = titleElement?.attr("href")

        val posterElement = element.selectFirst("img.lazyload")
            ?: element.selectFirst("img.img-fluid")
        val img = posterElement?.attr("data-src") ?: posterElement?.attr("src")

        if (title != null && link != null) {
            return newAnimeSearchResponse(
                title,
                fixUrl(link)
            ) {
                this.type = TvType.Anime
                this.posterUrl = img
            }
        }
        return null
    }

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L
    ): String? {
        for (i in 0 until retries) {
            try {
                Log.d("OtakustvProvider", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs)
                if (res.isSuccessful) {
                    Log.d("OtakustvProvider", "safeAppGet - Petición exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("OtakustvProvider", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("OtakustvProvider", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("OtakustvProvider", "safeAppGet - Reintentando en ${delayMs / 1000.0} segundos...")
                delay(delayMs)
            }
        }
        Log.e("OtakustvProvider", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"

        val html = safeAppGet(url)
        if (html == null) {
            Log.e("OtakustvProvider", "getMainPage - No se pudo obtener HTML para $url")
            return null
        }
        val doc = Jsoup.parse(html)

        val finishedAnimesContainer = doc.selectFirst("div.reciente:has(h3:contains(ANIMES FINALIZADOS))")
        if (finishedAnimesContainer != null) {
            val finishedAnimes = finishedAnimesContainer.select(".carusel_ranking .item").mapNotNull { item: Element ->
                extractAnimeItem(item)
            }
            if (finishedAnimes.isNotEmpty()) {
                items.add(HomePageList("Animes Finalizados", finishedAnimes))
                Log.d("OtakustvProvider", "getMainPage - Añadidos ${finishedAnimes.size} Animes Finalizados.")
            } else {
                Log.d("OtakustvProvider", "getMainPage - No se encontraron Animes Finalizados en el carrusel.")
            }
        } else {
            Log.d("OtakustvProvider", "getMainPage - No se encontró el contenedor de Animes Finalizados.")
        }

        val rankingContainer = doc.selectFirst("div.ranking:has(h3:contains(RANKING))")
        if (rankingContainer != null) {
            val rankingAnimes = rankingContainer.select(".carusel_ranking .item").mapNotNull { item: Element ->
                extractAnimeItem(item)
            }
            if (rankingAnimes.isNotEmpty()) {
                items.add(HomePageList("Ranking", rankingAnimes))
                Log.d("OtakustvProvider", "getMainPage - Añadidos ${rankingAnimes.size} Animes en Ranking.")
            } else {
                Log.d("OtakustvProvider", "getMainPage - No se encontraron Animes en Ranking en el carrusel.")
            }
        } else {
            Log.d("OtakustvProvider", "getMainPage - No se encontró el contenedor de Ranking.")
        }

        val simulcastsContainer = doc.selectFirst("div.simulcasts:has(h3:contains(SIMULCASTS))")
        if (simulcastsContainer != null) {
            val simulcastAnimes = simulcastsContainer.select(".carusel_simulcast .item").mapNotNull { item: Element ->
                extractAnimeItem(item)
            }
            if (simulcastAnimes.isNotEmpty()) {
                items.add(HomePageList("Simulcasts", simulcastAnimes))
                Log.d("OtakustvProvider", "getMainPage - Añadidos ${simulcastAnimes.size} Simulcasts.")
            } else {
                Log.d("OtakustvProvider", "getMainPage - No se encontraron Simulcasts en el carrusel.")
            }
        } else {
            Log.d("OtakustvProvider", "getMainPage - No se encontró el contenedor de Simulcasts.")
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscador?q=$query"
        val html = safeAppGet(url)
        if (html == null) {
            Log.e("OtakustvProvider", "search - No se pudo obtener HTML para la búsqueda: $url")
            return emptyList()
        }
        val doc = Jsoup.parse(html)

        return doc.select("div.animes_lista div.col-6").mapNotNull {
            val titleElement = it.selectFirst("p.font-GDSherpa-Bold")
            val title = titleElement?.text()?.trim()
            val link = it.selectFirst("a")?.attr("href")

            val posterElement = it.selectFirst("img.lazyload")
            val img = posterElement?.attr("src") ?: posterElement?.attr("data-src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = img
                }
            } else null
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("OtakustvProvider", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"(?:,"title":"[^"]+")?\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("OtakustvProvider", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("OtakustvProvider", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("OtakustvProvider", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        val episodeRegex = Regex("""(.+)/episodio-\d+/?$""")
        val animeBaseUrlMatch = episodeRegex.find(cleanUrl)
        val finalUrlToFetch = if (animeBaseUrlMatch != null) {
            val base = animeBaseUrlMatch.groupValues[1]
            if (!base.endsWith("/")) "$base/" else base
        } else {
            if (!cleanUrl.endsWith("/")) "$cleanUrl/" else cleanUrl
        }
        Log.d("OtakustvProvider", "load - URL final para fetch HTML (base del anime): $finalUrlToFetch")

        if (finalUrlToFetch.isBlank()) {
            Log.e("OtakustvProvider", "load - ERROR: URL base del anime está en blanco después de procesar.")
            return null
        }

        val html = safeAppGet(finalUrlToFetch)
        if (html == null) {
            Log.e("OtakustvProvider", "load - No se pudo obtener HTML para la URL principal del anime: $finalUrlToFetch")
            return null
        }
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1[itemprop=\"name\"]")?.text() ?: doc.selectFirst("h1.tit_ocl")?.text() ?: ""
        Log.d("OtakustvProvider", "load - Título extraído: $title")

        val poster = doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("src")
            ?: doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("data-src")
            ?: ""
        Log.d("OtakustvProvider", "load - Póster extraído: $poster")

        val descriptionElement = doc.selectFirst("p.font14.mb-0.text-white.mt-0.mt-lg-2")
        val description = descriptionElement?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""
        Log.d("OtakustvProvider", "load - Descripción extraída: $description")

        val tags = doc.select("ul.fichas li:contains(Genero:) a").map { it.text() }
        if (tags.isEmpty()) {
            Log.w("OtakustvProvider", "load - No se encontraron tags/géneros con el selector 'ul.fichas li:contains(Genero:) a'.")
        } else {
            Log.d("OtakustvProvider", "load - Tags extraídos: $tags")
        }

        val releaseDateText = doc.selectFirst("ul.fichas li:contains(Estreno:) strong")?.text()?.trim()
        val year = releaseDateText?.split("-")?.firstOrNull()?.toIntOrNull()
        Log.d("OtakustvProvider", "load - Año extraído: $year")

        val statusText = doc.selectFirst("ul.fichas li:contains(Estado:) strong")?.text()?.trim()
        val status = parseStatus(statusText ?: "")
        Log.d("OtakustvProvider", "load - Estado extraído: $status")

        val episodes = doc.select("div.row > div[class*=\"col-\"]").mapNotNull { element ->
            val epLinkElement = element.selectFirst("a.item-temp")
            val epUrl = fixUrl(epLinkElement?.attr("href") ?: "")

            val epTitleElement = element.selectFirst("span.font-GDSherpa-Bold a")
            val epTitle = epTitleElement?.text()?.trim() ?: ""

            val epPoster = epLinkElement?.selectFirst("img.img-fluid")?.attr("src") ?: ""

            val episodeNumber = epUrl.split("-").lastOrNull()?.toIntOrNull()

            if (epUrl.isNotBlank() && epTitle.isNotBlank()) {
                newEpisode(
                    EpisodeLoadData(epTitle, epUrl).toJson()
                ) {
                    this.name = epTitle
                    this.season = null
                    this.episode = episodeNumber
                    this.posterUrl = epPoster
                }
            } else null
        }.reversed()
        Log.d("OtakustvProvider", "load - Se encontraron ${episodes.size} episodios.")

        return newTvSeriesLoadResponse(
            name = title,
            url = finalUrlToFetch,
            type = TvType.Anime,
            episodes = episodes,
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            // Eliminado 'this.status = status' si te da error en tu versión
            // Si el constructor requiere 'status' como un parámetro directo,
            // lo pondrías así: newTvSeriesLoadResponse(name, url, type, episodes, status=status)
            // pero el error anterior sugería que la lambda no lo aceptaba.
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("OtakustvProvider", "loadLinks - Data de entrada: $data")

        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data)

        if (targetUrl.isBlank()) {
            Log.e("OtakustvProvider", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val initialHtml = safeAppGet(targetUrl)
        if (initialHtml == null) {
            Log.e("OtakustvProvider", "loadLinks - No se pudo obtener HTML para la URL principal del contenido: $targetUrl")
            return false
        }
        val doc = Jsoup.parse(initialHtml)

        val encryptedValues = mutableSetOf<String>()

        doc.select("select#ssel option").forEach { option ->
            val value = option.attr("value")
            if (value.isNotBlank()) {
                encryptedValues.add(value)
                Log.d("OtakustvProvider", "loadLinks - Añadido valor de select: $value")
            }
        }

        doc.select("nav.menu_server ul li a").forEach { link ->
            val rel = link.attr("rel")
            if (rel.isNotBlank()) {
                encryptedValues.add(rel)
                Log.d("OtakustvProvider", "loadLinks - Añadido valor de nav rel: $rel")
            }
        }

        var linksFound = false
        // **** CORRECCIÓN: Convertir Set a List antes de llamar a amap ****
        encryptedValues.toList().amap { encryptedId: String -> // Ahora 'amap' se llama sobre una List
            Log.d("OtakustvProvider", "loadLinks - Pidiendo HTML del reproductor para ID cifrado: $encryptedId")

            val requestUrl = "$mainUrl/play-video?id=$encryptedId"

            val responseJsonString = try {
                // 'app.get' está bien aquí, ya está dentro de un suspend y 'amap' lo permite.
                app.get(
                    requestUrl,
                    headers = mapOf(
                        "Referer" to targetUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    interceptor = cfKiller
                ).text
            } catch (e: Exception) {
                Log.e("OtakustvProvider", "loadLinks - Error al hacer petición AJAX para $encryptedId: ${e.message}", e)
                null
            }

            if (!responseJsonString.isNullOrBlank()) {
                try {
                    val jsonResponse = tryParseJson<Map<String, String>>(responseJsonString)
                    val iframeHtml = jsonResponse?.get("html")

                    if (!iframeHtml.isNullOrBlank()) {
                        val iframeDoc = Jsoup.parse(iframeHtml)
                        val iframeSrc = iframeDoc.selectFirst("iframe")?.attr("src")

                        if (!iframeSrc.isNullOrBlank()) {
                            Log.d("OtakustvProvider", "loadLinks - Iframe src encontrado para ID $encryptedId: $iframeSrc")
                            linksFound = true
                            // loadExtractor sigue con la misma firma, debería funcionar si la importación es correcta.
                            loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
                        } else {
                            Log.w("OtakustvProvider", "loadLinks - No se encontró 'src' en el iframe de la respuesta para ID: $encryptedId")
                        }
                    } else {
                        Log.w("OtakustvProvider", "loadLinks - HTML de iframe vacío o no válido en la respuesta para ID: $encryptedId")
                    }
                } catch (e: Exception) {
                    Log.e("OtakustvProvider", "loadLinks - Error al parsear JSON o HTML de la respuesta para ID $encryptedId: ${e.message}", e)
                }
            } else {
                Log.w("OtakustvProvider", "loadLinks - Respuesta nula o vacía de la petición AJAX para ID: $encryptedId")
            }
        }

        if (!linksFound) {
            val playerIframeSrc = doc.selectFirst("div.st-vid #result_server iframe#ytplayer")?.attr("src")
            if (!playerIframeSrc.isNullOrBlank()) {
                Log.d("OtakustvProvider", "loadLinks - No se encontraron enlaces de servidor a través de API, usando iframe principal: $playerIframeSrc")
                loadExtractor(fixUrl(playerIframeSrc), targetUrl, subtitleCallback, callback)
                linksFound = true
            } else {
                Log.w("OtakustvProvider", "loadLinks - No se encontró ningún reproductor de video válido en el HTML inicial o scripts como fallback.")
            }
        }

        return linksFound
    }

    private fun parseStatus(statusString: String): ShowStatus {
        return when (statusString.lowercase()) {
            "finalizado" -> ShowStatus.Completed
            "en emision" -> ShowStatus.Ongoing
            "en progreso" -> ShowStatus.Ongoing
            else -> ShowStatus.Ongoing
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("/")) {
            mainUrl + url
        } else {
            url
        }
    }
}