package com.example // Asegúrate de que este paquete sea correcto para tu proyecto

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse

import com.lagradost.cloudstream3.utils.loadExtractor

class OtakuversoProvider : MainAPI() {
    // Asegúrate de que esto esté bien configurado y se use la extensión recompilada
    override var mainUrl = "https://otakuverso.net/home"
    override var name = "Otakuverso"
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
                Log.d("OtakuversoProvider", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs)
                if (res.isSuccessful) {
                    Log.d("OtakuversoProvider", "safeAppGet - Petición exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("OtakuversoProvider", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("OtakuversoProvider", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("OtakuversoProvider", "safeAppGet - Reintentando en ${delayMs / 1000.0} segundos...")
                delay(delayMs)
            }
        }
        Log.e("OtakuversoProvider", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        Log.d("OtakuversoProvider", "getMainPage - Fetching URL: $url (page $page)")


        val html = safeAppGet(url)
        if (html == null) {
            Log.e("OtakuversoProvider", "getMainPage - No se pudo obtener HTML para $url")
            return null
        }
        val doc = Jsoup.parse(html)

        // ANIMES FINALIZADOS
        val finishedAnimesContainer = doc.selectFirst("div.reciente:has(h3:contains(ANIMES FINALIZADOS))")
        if (finishedAnimesContainer != null) {
            val finishedAnimes = finishedAnimesContainer.select(".carusel_ranking .item").mapNotNull { item: Element ->
                extractAnimeItem(item)
            }
            if (finishedAnimes.isNotEmpty()) {
                items.add(HomePageList("Animes Finalizados", finishedAnimes))
                Log.d("OtakuversoProvider", "getMainPage - Añadidos ${finishedAnimes.size} Animes Finalizados.")
            } else {
                Log.d("OtakuversoProvider", "getMainPage - No se encontraron Animes Finalizados en el carrusel.")
            }
        } else {
            Log.d("OtakuversoProvider", "getMainPage - No se encontró el contenedor de Animes Finalizados.")
        }

        // RANKING
        val rankingContainer = doc.selectFirst("div.ranking:has(h3:contains(RANKING))")
        if (rankingContainer != null) {
            val rankingAnimes = rankingContainer.select(".carusel_ranking .item").mapNotNull { item: Element ->
                extractAnimeItem(item)
            }
            if (rankingAnimes.isNotEmpty()) {
                items.add(HomePageList("Ranking", rankingAnimes))
                Log.d("OtakuversoProvider", "getMainPage - Añadidos ${rankingAnimes.size} Animes en Ranking.")
            } else {
                Log.d("OtakuversoProvider", "getMainPage - No se encontraron Animes en Ranking en el carrusel.")
            }
        } else {
            Log.d("OtakuversoProvider", "getMainPage - No se encontró el contenedor de Ranking.")
        }

        // SIMULCASTS
        val simulcastsContainer = doc.selectFirst("div.simulcasts:has(h3:contains(SIMULCASTS))")
        if (simulcastsContainer != null) {
            val simulcastAnimes = simulcastsContainer.select(".carusel_simulcast .item").mapNotNull { item: Element ->
                extractAnimeItem(item)
            }
            if (simulcastAnimes.isNotEmpty()) {
                items.add(HomePageList("Simulcasts", simulcastAnimes))
                Log.d("OtakuversoProvider", "getMainPage - Añadidos ${simulcastAnimes.size} Simulcasts.")
            } else {
                Log.d("OtakuversoProvider", "getMainPage - No se encontraron Simulcasts en el carrusel.")
            }
        } else {
            Log.d("OtakuversoProvider", "getMainPage - No se encontró el contenedor de Simulcasts.")
        }

        // RECIENTEMENTE AÑADIDO
        val recentlyAddedContainer = doc.selectFirst("div.reciente:has(h3:contains(RECIENTEMENTE AÑADIDO)):not(:has(h3:contains(ANIMES FINALIZADOS)))")
        if (recentlyAddedContainer != null) {
            val recentlyAddedAnimes = recentlyAddedContainer.select(".carusel_reciente .item").mapNotNull { item: Element ->
                extractAnimeItem(item)
            }
            if (recentlyAddedAnimes.isNotEmpty()) {
                items.add(HomePageList("Recientemente Añadido", recentlyAddedAnimes))
                Log.d("OtakuversoProvider", "getMainPage - Añadidos ${recentlyAddedAnimes.size} Animes Recientemente Añadidos.")
            } else {
                Log.d("OtakuversoProvider", "getMainPage - No se encontraron Animes Recientemente Añadidos.")
            }
        } else {
            Log.d("OtakuversoProvider", "getMainPage - No se encontró el contenedor de Recientemente Añadido.")
        }

        // PROXIMAMENTE
        val soonContainer = doc.selectFirst("div.pronto:has(h3:contains(PROXIMAMENTE))")
        if (soonContainer != null) {
            val soonAnimes = soonContainer.select(".carusel_pronto .item").mapNotNull { item: Element ->
                extractAnimeItem(item)
            }
            if (soonAnimes.isNotEmpty()) {
                items.add(HomePageList("Próximamente", soonAnimes))
                Log.d("OtakuversoProvider", "getMainPage - Añadidos ${soonAnimes.size} Animes Próximamente.")
            } else {
                Log.d("OtakuversoProvider", "getMainPage - No se encontraron Animes Próximamente.")
            }
        } else {
            Log.d("OtakuversoProvider", "getMainPage - No se encontró el contenedor de Próximamente.")
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscador?q=$query"
        val html = safeAppGet(url)
        if (html == null) {
            Log.e("OtakuversoProvider", "search - No se pudo obtener HTML para la búsqueda: $url")
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
        Log.d("OtakuversoProvider", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"(?:,"title":"[^"]+")?\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("OtakuversoProvider", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("OtakuversoProvider", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("OtakuversoProvider", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        val episodeRegex = Regex("""(.+)/episodio-\d+/?$""")
        val animeBaseUrlMatch = episodeRegex.find(cleanUrl)
        val finalUrlToFetch = if (animeBaseUrlMatch != null) {
            val base = animeBaseUrlMatch.groupValues[1]
            if (!base.endsWith("/")) "$base/" else base
        } else {
            if (!cleanUrl.endsWith("/")) "$cleanUrl/" else cleanUrl
        }
        Log.d("OtakuversoProvider", "load - URL final para fetch HTML (base del anime): $finalUrlToFetch")

        if (finalUrlToFetch.isBlank()) {
            Log.e("OtakuversoProvider", "load - ERROR: URL base del anime está en blanco después de procesar.")
            return null
        }

        val html = safeAppGet(finalUrlToFetch)
        if (html == null) {
            Log.e("OtakuversoProvider", "load - No se pudo obtener HTML para la URL principal del anime: $finalUrlToFetch")
            return null
        }
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1[itemprop=\"name\"]")?.text() ?: doc.selectFirst("h1.tit_ocl")?.text() ?: ""
        Log.d("OtakuversoProvider", "load - Título extraído: $title")

        // *** CORRECCIÓN: Nuevo selector para el póster ***
        val poster = doc.selectFirst("div.img-in img.d-inline-block")?.attr("src")
            ?: doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("src") // Mantenemos el antiguo por si acaso
            ?: doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("data-src")
            ?: ""
        Log.d("OtakuversoProvider", "load - Póster extraído: $poster")

        val descriptionElement = doc.selectFirst("p.font14.mb-0.text-white.mt-0.mt-lg-2")
        val description = descriptionElement?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""
        Log.d("OtakuversoProvider", "load - Descripción extraída: $description")

        // *** CORRECCIÓN: Nuevo selector para tags/géneros ***
        // No hay un selector claro para "Genero:" en el HTML proporcionado.
        // Solo veo "ul.fichas li:contains(Estreno:) strong" y "ul.fichas li:contains(Estado:) strong"
        // Si hay una sección de géneros, la extraeríamos así:
        val tags = doc.select("ul.fichas li:contains(Etiquetas:) a").map { it.text() } // Si "Etiquetas:" existe
            ?: doc.select("ul.fichas li a").map { it.text() } // Intento más general si no hay etiqueta específica
            ?: emptyList() // Fallback a lista vacía

        if (tags.isEmpty()) {
            Log.w("OtakuversoProvider", "load - No se encontraron tags/géneros con los selectores 'ul.fichas li:contains(Etiquetas:) a' o 'ul.fichas li a'.")
        } else {
            Log.d("OtakuversoProvider", "load - Tags extraídos: $tags")
        }

        // *** CORRECCIÓN: Nuevo selector y parseo para el año de estreno ***
        val releaseDateText = doc.selectFirst("div.font14.mb-0.text-white.mt-0.mt-lg-2 span.date")?.text()?.trim()
        val year = Regex("""Estreno:\s*(\d{4})""").find(releaseDateText ?: "")?.groupValues?.get(1)?.toIntOrNull()
        Log.d("OtakuversoProvider", "load - Año extraído: $year")

        val statusText = doc.selectFirst("span.btn-anime-info.font12.text-white.border.border-white")?.text()?.trim()
        val status = parseStatus(statusText ?: "")
        Log.d("OtakuversoProvider", "load - Estado extraído: $status")

        // *** CORRECCIÓN: Nuevo selector para episodios ***
        val episodes = doc.select("div.row div.col-6.col-sm-6.col-md-4.col-lg-3.col-xl-2.pre.text-white.mb20.text-center").mapNotNull { element ->
            val epLinkElement = element.selectFirst("a.mb5.item-temp")
            val epUrl = fixUrl(epLinkElement?.attr("href") ?: "")

            val epTitleElement = element.selectFirst("h1.font-GDSherpa-Bold.font14.mb-1.text-left a") // Título del episodio
            val epTitle = epTitleElement?.text()?.trim() ?: ""

            // El póster del episodio es una imagen de marcador de posición 'noimage', podrías usarla o dejarla nula
            val epPoster = epLinkElement?.selectFirst("img.img-fluid")?.attr("src") ?: ""

            val episodeNumber = epUrl.split("-").lastOrNull()?.toIntOrNull()

            if (epUrl.isNotBlank() && epTitle.isNotBlank()) {
                newEpisode(
                    EpisodeLoadData(epTitle, epUrl).toJson()
                ) {
                    this.name = epTitle
                    this.season = null // Si no hay temporadas, dejar en null
                    this.episode = episodeNumber
                    this.posterUrl = epPoster
                }
            } else null
        }.reversed() // Los episodios están en orden descendente, los invertimos para que estén en ascendente
        Log.d("OtakuversoProvider", "load - Se encontraron ${episodes.size} episodios.")

        return newTvSeriesLoadResponse(
            name = title,
            url = finalUrlToFetch,
            type = TvType.Anime,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            //this.status = status
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("OtakuversoProvider", "loadLinks - Data de entrada: $data")

        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data)

        if (targetUrl.isBlank()) {
            Log.e("OtakuversoProvider", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val initialHtml = safeAppGet(targetUrl)
        if (initialHtml == null) {
            Log.e("OtakuversoProvider", "loadLinks - No se pudo obtener HTML para la URL principal del contenido: $targetUrl")
            return false
        }
        val doc = Jsoup.parse(initialHtml)

        val encryptedValues = mutableSetOf<String>()

        doc.select("select#ssel option").forEach { option ->
            val value = option.attr("value")
            if (value.isNotBlank()) {
                encryptedValues.add(value)
                Log.d("OtakuversoProvider", "loadLinks - Añadido valor de select: $value")
            }
        }

        doc.select("nav.menu_server ul li a").forEach { link ->
            val rel = link.attr("rel")
            if (rel.isNotBlank()) {
                encryptedValues.add(rel)
                Log.d("OtakuversoProvider", "loadLinks - Añadido valor de nav rel: $rel")
            }
        }

        var linksFound = false
        encryptedValues.toList().amap { encryptedId: String ->
            Log.d("OtakuversoProvider", "loadLinks - Pidiendo HTML del reproductor para ID cifrado: $encryptedId")

            val requestUrl = "https://otakuverso.net/play-video?id=$encryptedId"

            val responseJsonString = try {
                app.get(
                    requestUrl,
                    headers = mapOf(
                        "Referer" to targetUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    interceptor = cfKiller
                ).text
            } catch (e: Exception) {
                Log.e("OtakuversoProvider", "loadLinks - Error al hacer petición AJAX para $encryptedId: ${e.message}", e)
                null
            }

            if (!responseJsonString.isNullOrBlank()) {
                try {
                    val jsonResponse = tryParseJson<Map<String, String>>(responseJsonString)
                    val iframeHtml = jsonResponse?.get("html")

                    if (!iframeHtml.isNullOrBlank()) {
                        val iframeDoc = Jsoup.parse(iframeHtml)
                        var iframeSrc = iframeDoc.selectFirst("iframe")?.attr("src")

                        if (!iframeSrc.isNullOrBlank()) {
                            if (iframeSrc.contains("drive.google.com") && iframeSrc.contains("/preview")) {
                                val modifiedSrc = iframeSrc.replace("/preview", "/edit")
                                Log.d("OtakuversoProvider", "loadLinks - Modificando URL de Google Drive de /preview a /edit: $modifiedSrc")
                                iframeSrc = modifiedSrc
                            }

                            if (iframeSrc.contains("mega.nz")) {
                                Log.w("OtakuversoProvider", "loadLinks - Enlace de MEGA.NZ encontrado. loadExtractor no soporta directamente MEGA.NZ.")
                            } else if (iframeSrc.contains("drive.google.com")) {
                                Log.d("OtakuversoProvider", "loadLinks - Enlace de GOOGLE DRIVE encontrado. Pasando a loadExtractor.")
                            } else {
                                Log.d("OtakuversoProvider", "loadLinks - Enlace de EXTRACTOR REGULAR: $iframeSrc")
                            }

                            loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
                            linksFound = true
                        } else {
                            Log.w("OtakuversoProvider", "loadLinks - No se encontró 'src' en el iframe de la respuesta para ID: $encryptedId")
                        }
                    } else {
                        Log.w("OtakuversoProvider", "loadLinks - HTML de iframe vacío o no válido en la respuesta para ID: $encryptedId")
                    }
                } catch (e: Exception) {
                    Log.e("OtakuversoProvider", "loadLinks - Error al parsear JSON o HTML de la respuesta para ID $encryptedId: ${e.message}", e)
                }
            } else {
                Log.w("OtakuversoProvider", "loadLinks - Respuesta nula o vacía de la petición AJAX para ID: $encryptedId")
            }
        }

        if (!linksFound) {
            val playerIframeSrc = doc.selectFirst("div.st-vid #result_server iframe#ytplayer")?.attr("src")
            if (!playerIframeSrc.isNullOrBlank()) {
                Log.d("OtakuversoProvider", "loadLinks - No se encontraron enlaces de servidor a través de API, usando iframe principal: $playerIframeSrc")
                var finalPlayerIframeSrc = playerIframeSrc
                if (finalPlayerIframeSrc.contains("drive.google.com") && finalPlayerIframeSrc.contains("/preview")) {
                    val modifiedSrc = finalPlayerIframeSrc.replace("/preview", "/edit")
                    Log.d("OtakuversoProvider", "loadLinks - Modificando URL de Google Drive del iframe principal de /preview a /edit: $modifiedSrc")
                    finalPlayerIframeSrc = modifiedSrc
                }

                loadExtractor(fixUrl(finalPlayerIframeSrc), targetUrl, subtitleCallback, callback)
                linksFound = true
            } else {
                Log.w("OtakuversoProvider", "loadLinks - No se encontró ningún reproductor de video válido en el HTML inicial o scripts como fallback.")
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
            "https://otakuverso.net" + url
        } else {
            url
        }
    }
}