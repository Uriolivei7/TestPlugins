package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.collections.ArrayList
import kotlinx.coroutines.delay

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse

import com.lagradost.cloudstream3.utils.loadExtractor

class VerAnimesProvider : MainAPI() {
    override var mainUrl = "https://wwv.veranimes.net"
    override var name = "VerAnimes"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val cfKiller = CloudflareKiller()

    private fun extractAnimeItem(element: Element): AnimeSearchResponse? {
        val linkElement = element.selectFirst("a")
        val episodeLink = linkElement?.attr("href") // Esta es la URL del episodio

        val titleText = element.selectFirst("span.n b")?.text()?.trim()

        val posterElement = element.selectFirst("figure.i img")
        val img = posterElement?.attr("src")

        val releaseYear = null

        if (titleText != null && episodeLink != null) {
            // Se asume que la URL del episodio sigue el patrón /ver/nombre-del-anime/numero-episodio
            // Queremos la parte /ver/nombre-del-anime
            val baseAnimeUrlMatch = Regex("""(.+?\/ver\/[^\/]+)""").find(episodeLink)
            val baseAnimeUrl = baseAnimeUrlMatch?.groupValues?.get(1) ?: episodeLink.substringBeforeLast("/")

            Log.d("VerAnimesProvider", "extractAnimeItem - Título: $titleText, Enlace Episodio: $episodeLink, Enlace BASE Anime: $baseAnimeUrl, Póster: $img")

            return newAnimeSearchResponse(
                titleText,
                fixUrl(baseAnimeUrl) // Usamos la URL BASE del anime aquí
            ) {
                this.type = TvType.Anime
                this.posterUrl = img
                this.year = releaseYear
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
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs)
                if (res.isSuccessful) {
                    Log.d("VerAnimesProvider", "safeAppGet - Éxito al obtener URL: $url")
                    return res.text
                } else {
                    Log.w("VerAnimesProvider", "safeAppGet - Falló la URL: $url con código: ${res.code}")
                }
            } catch (e: Exception) {
                Log.e("VerAnimesProvider", "safeAppGet - Error al obtener URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("VerAnimesProvider", "safeAppGet - Reintento ${i + 1}/${retries} para URL: $url")
                delay(delayMs)
            }
        }
        Log.e("VerAnimesProvider", "safeAppGet - Falló después de $retries reintentos para URL: $url")
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val url = mainUrl
        val html = safeAppGet(url) ?: return null
        val doc = Jsoup.parse(html)
        Log.d("VerAnimesProvider", "getMainPage - HTML cargado para $url. Extracting home page lists.")

        // Selector para "Nuevos episodios agregados" basado en el HTML proporcionado.
        doc.selectFirst("div#nt-list")?.let { container ->
            val animes = container.select("div.li").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) {
                items.add(HomePageList("Nuevos Episodios Agregados", animes))
                Log.d("VerAnimesProvider", "getMainPage - Añadidos ${animes.size} Nuevos Episodios Agregados (desde #nt-list).")
            } else {
                Log.w("VerAnimesProvider", "getMainPage - No se encontraron animes para 'Nuevos Episodios Agregados' en #nt-list.")
            }
        } ?: Log.w("VerAnimesProvider", "getMainPage - Contenedor '#nt-list' no encontrado. Verifique si el HTML de la página principal ha cambiado.")


        doc.selectFirst("div.blq:has(h2.h:contains(Últimas Peliculas))")?.let { container ->
            val animes = container.select("ul li").mapNotNull { element ->
                val linkElement = element.selectFirst("a")
                val titleText = element.selectFirst("h3.h a")?.text()?.trim()
                val link = linkElement?.attr("href")
                val posterElement = element.selectFirst("img")
                val img = posterElement?.attr("src")
                val genres = element.select("p.g a").map { it.text().trim() }

                Log.d("VerAnimesProvider", "getMainPage - Últimas Peliculas - Título: $titleText, Link: $link, Póster: $img, Géneros: $genres")

                if (titleText != null && link != null) {
                    newAnimeSearchResponse(
                        titleText,
                        fixUrl(link)
                    ) {
                        this.type = TvType.Anime
                        this.posterUrl = img
                        //this.tags = genres
                    }
                } else null
            }
            if (animes.isNotEmpty()) {
                items.add(HomePageList("Últimas Películas", animes))
                Log.d("VerAnimesProvider", "getMainPage - Añadidos ${animes.size} Últimas Películas.")
            } else {
                Log.w("VerAnimesProvider", "getMainPage - No se encontraron animes para 'Últimas Películas'.")
            }
        } ?: Log.w("VerAnimesProvider", "getMainPage - Contenedor 'Últimas Peliculas' no encontrado.")


        doc.selectFirst("div.blq:has(h2.h:contains(Últimos Ovas))")?.let { container ->
            val animes = container.select("ul li").mapNotNull { element ->
                val linkElement = element.selectFirst("a")
                val titleText = element.selectFirst("h3.h a")?.text()?.trim()
                val link = linkElement?.attr("href")
                val posterElement = element.selectFirst("img")
                val img = posterElement?.attr("src")
                val genres = element.select("p.g a").map { it.text().trim() }

                Log.d("VerAnimesProvider", "getMainPage - Últimos Ovas - Título: $titleText, Link: $link, Póster: $img, Géneros: $genres")

                if (titleText != null && link != null) {
                    newAnimeSearchResponse(
                        titleText,
                        fixUrl(link)
                    ) {
                        this.type = TvType.Anime
                        this.posterUrl = img
                        //this.tags = genres
                    }
                } else null
            }
            if (animes.isNotEmpty()) {
                items.add(HomePageList("Últimos OVAs", animes))
                Log.d("VerAnimesProvider", "getMainPage - Añadidos ${animes.size} Últimos OVAs.")
            } else {
                Log.w("VerAnimesProvider", "getMainPage - No se encontraron animes para 'Últimos OVAs'.")
            }
        } ?: Log.w("VerAnimesProvider", "getMainPage - Contenedor 'Últimos Ovas' no encontrado.")


        doc.selectFirst("div.blq:has(h2.h:contains(Últimos Especiales))")?.let { container ->
            val animes = container.select("ul li").mapNotNull { element ->
                val linkElement = element.selectFirst("a")
                val titleText = element.selectFirst("h3.h a")?.text()?.trim()
                val link = linkElement?.attr("href")
                val posterElement = element.selectFirst("img")
                val img = posterElement?.attr("src")
                val genres = element.select("p.g a").map { it.text().trim() }

                Log.d("VerAnimesProvider", "getMainPage - Últimos Especiales - Título: $titleText, Link: $link, Póster: $img, Géneros: $genres")

                if (titleText != null && link != null) {
                    newAnimeSearchResponse(
                        titleText,
                        fixUrl(link)
                    ) {
                        this.type = TvType.Anime
                        this.posterUrl = img
                        //this.tags = genres
                    }
                } else null
            }
            if (animes.isNotEmpty()) {
                items.add(HomePageList("Últimos Especiales", animes))
                Log.d("VerAnimesProvider", "getMainPage - Añadidos ${animes.size} Últimos Especiales.")
            } else {
                Log.w("VerAnimesProvider", "getMainPage - No se encontraron animes para 'Últimos Especiales'.")
            }
        } ?: Log.w("VerAnimesProvider", "getMainPage - Contenedor 'Últimos Especiales' no encontrado.")


        doc.selectFirst("div.th:has(h2.h:contains(Nuevos Animes Agregados)) + div.ul.x6")?.let { container ->
            val animes = container.select("article.li").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) {
                items.add(HomePageList("Nuevos Animes Agregados", animes))
                Log.d("VerAnimesProvider", "getMainPage - Añadidos ${animes.size} Nuevos Animes Agregados.")
            } else {
                Log.w("VerAnimesProvider", "getMainPage - No se encontraron animes para 'Nuevos Animes Agregados'.")
            }
        } ?: Log.w("VerAnimesProvider", "getMainPage - Contenedor 'Nuevos Animes Agregados' no encontrado.")


        Log.d("VerAnimesProvider", "getMainPage - Completado, total de listas: ${items.size}")
        return HomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/animes?buscar=$query"
        val html = safeAppGet(url) ?: return emptyList()
        val doc = Jsoup.parse(html)
        Log.d("VerAnimesProvider", "search - Buscando '$query'. HTML cargado.")

        return doc.select("div.ul.x5 article.li").mapNotNull {
            val title = it.selectFirst("h3.h a")?.text()?.trim()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("img")?.attr("src")

            Log.d("VerAnimesProvider", "search - Resultado: Título: $title, Link: $link, Póster: $img")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = img
                }
            } else {
                Log.w("VerAnimesProvider", "search - Elemento de búsqueda incompleto (título o link nulo): ${it.outerHtml().take(100)}")
                null
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"(?:,"title":"[^"]+")?\}""").find(url)
        if (urlJsonMatch != null) cleanUrl = urlJsonMatch.groupValues[1]
        else if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) cleanUrl = "https://" + cleanUrl.removePrefix("//")

        val animeBaseUrlMatch = Regex("""(.+)\/(ver|anime)\/(.+?)(?:-episodio-\d+)?(?:-\d+)?\/?$""").find(cleanUrl)
        val finalUrlToFetch = if (animeBaseUrlMatch != null) {
            val baseUrl = animeBaseUrlMatch.groupValues[1]
            val type = animeBaseUrlMatch.groupValues[2] // "ver" o "anime"
            val animeSlug = animeBaseUrlMatch.groupValues[3].substringBeforeLast("-episodio").substringBeforeLast("-") // Obtener solo el slug del anime
            "$baseUrl/$type/$animeSlug"
        } else {
            cleanUrl
        }

        Log.d("VerAnimesProvider", "load - URL original: $url, URL limpia: $cleanUrl, URL final a obtener (base del anime): $finalUrlToFetch")

        if (finalUrlToFetch.isBlank()) {
            Log.e("VerAnimesProvider", "load - URL final para obtener está en blanco: $cleanUrl")
            return null
        }

        val html = safeAppGet(finalUrlToFetch) ?: run {
            Log.e("VerAnimesProvider", "load - Falló la obtención del HTML para: $finalUrlToFetch")
            return null
        }
        Log.d("VerAnimesProvider", "load - HTML recibido para $finalUrlToFetch (primeros 500 caracteres): ${html.take(500)}")

        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("div.ti h1 strong")?.text() ?: ""
        val poster = doc.selectFirst("div.sc div.l figure.i img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.tx p")?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""

        Log.d("VerAnimesProvider", "load - Título extraído: '$title', Póster extraído: '$poster', Descripción extraída: '${description.take(50)}...'")
        if (poster.isBlank()) {
            Log.w("VerAnimesProvider", "load - ¡ADVERTENCIA! No se encontró póster con selector 'div.sc div.l figure.i img' para $finalUrlToFetch")
        }

        val localTags = doc.select("ul.gn li a").map { it.text().trim() }
        val year = doc.selectFirst("div.ti div span.a")?.text()?.trim()?.toIntOrNull()
        val localStatus = parseStatus(doc.selectFirst("div.ti div span.fi")?.text()?.trim() ?: "")

        Log.d("VerAnimesProvider", "load - Géneros/Tags: $localTags, Año: $year, Estado: $localStatus")

        val additionalTags = mutableListOf<String>()

        val allEpisodes = ArrayList<Episode>()
        // --- MODIFICACIÓN CLAVE PARA EPISODIOS ---
        // Usamos el selector más específico para asegurar que encontramos los episodios
        // dentro de la sección con id="l"
        val episodeContainers = doc.select("section#l ul.ep li")

        if (episodeContainers.isEmpty()) {
            Log.w("VerAnimesProvider", "load - ¡ADVERTENCIA! No se encontraron episodios con el selector 'section#l ul.ep li' para $finalUrlToFetch. Verifique el HTML.")
        } else {
            Log.d("VerAnimesProvider", "load - Se encontraron ${episodeContainers.size} contenedores de episodios.")
        }

        episodeContainers.mapNotNullTo(allEpisodes) { element ->
            val epLinkElement = element.selectFirst("a")
            val epUrl = fixUrl(epLinkElement?.attr("href") ?: "") // Usar fixUrl aquí también
            val epTitleText = epLinkElement?.selectFirst("span")?.text()?.trim() ?: ""

            var episodeNumber: Int? = null
            val episodeNumberMatch = Regex("""\d+""").find(epTitleText)
            episodeNumber = episodeNumberMatch?.value?.toIntOrNull()

            val finalEpTitle = epTitleText.ifBlank { "Episodio ${episodeNumber ?: "Desconocido"}" }

            val epPoster = poster

            Log.d("VerAnimesProvider", "load - Procesando episodio: Título: '$finalEpTitle', URL: '$epUrl', Número: $episodeNumber")

            if (epUrl.isBlank()) {
                Log.w("VerAnimesProvider", "load - Episodio incompleto encontrado: URL en blanco para elemento: ${element.outerHtml().take(100)}")
                return@mapNotNullTo null
            }

            newEpisode(EpisodeLoadData(finalEpTitle, epUrl).toJson()) {
                this.name = finalEpTitle
                this.season = null
                this.episode = episodeNumber
                this.posterUrl = epPoster
                this.description = finalEpTitle
            }
        }
        Log.d("VerAnimesProvider", "load - Total de episodios encontrados: ${allEpisodes.size}")

        val finalEpisodes = allEpisodes.reversed()

        val recommendations = doc.select("aside#r div.ul.x2 article.li").mapNotNull { element ->
            val recTitle = element.selectFirst("h3.h a")?.text()?.trim()
            val recLink = element.selectFirst("a")?.attr("href")
            val recImg = element.selectFirst("img")?.attr("src")

            if (recTitle != null && recLink != null && recImg != null) {
                newAnimeSearchResponse(
                    recTitle,
                    fixUrl(recLink)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = recImg
                }
            } else {
                Log.w("VerAnimesProvider", "load - Recomendación incompleta (título, link o póster nulo): ${element.outerHtml().take(100)}")
                null
            }
        }
        Log.d("VerAnimesProvider", "load - Total de recomendaciones encontradas: ${recommendations.size}")

        return newTvSeriesLoadResponse(
            name = title,
            url = finalUrlToFetch,
            type = TvType.Anime,
            episodes = finalEpisodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = localTags + additionalTags
            this.year = year
            //this.status = localStatus
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data)

        Log.d("VerAnimesProvider", "loadLinks - URL a cargar: $targetUrl")

        if (targetUrl.isBlank()) {
            Log.e("VerAnimesProvider", "loadLinks - targetUrl está en blanco para data: $data")
            return false
        }

        val initialHtml = safeAppGet(targetUrl) ?: run {
            Log.e("VerAnimesProvider", "loadLinks - Falló la obtención del HTML para: $targetUrl")
            return false
        }
        Log.d("VerAnimesProvider", "loadLinks - HTML recibido para $targetUrl (primeros 500 caracteres): ${initialHtml.take(500)}")

        val doc = Jsoup.parse(initialHtml)

        var linksFound = false

        val playerIframe = doc.selectFirst("div.ply iframe")

        if (playerIframe != null) {
            var iframeSrc = playerIframe.attr("src")
            Log.d("VerAnimesProvider", "loadLinks - Iframe principal encontrado, src: '$iframeSrc'")
            if (!iframeSrc.isNullOrBlank()) {
                if (iframeSrc.contains("drive.google.com") && iframeSrc.contains("/preview")) {
                    iframeSrc = iframeSrc.replace("/preview", "/edit")
                    Log.d("VerAnimesProvider", "loadLinks - URL de Google Drive modificada a: $iframeSrc")
                }
                Log.d("VerAnimesProvider", "loadLinks - Extrayendo del iframe principal: $iframeSrc")
                loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
                linksFound = true
            } else {
                Log.w("VerAnimesProvider", "loadLinks - El src del iframe del reproductor principal es nulo/vacío.")
            }
        } else {
            Log.w("VerAnimesProvider", "loadLinks - No se encontró el iframe del reproductor principal con el selector 'div.ply iframe'.")
        }

        doc.select("ul.opt li").forEach { liElement ->
            val encryptedUrlHex = liElement.attr("encrypt")
            val serverName = liElement.attr("title").ifBlank { liElement.selectFirst("span")?.text()?.trim() }
            Log.d("VerAnimesProvider", "loadLinks - Procesando servidor: '$serverName', Encrypted URL: '$encryptedUrlHex'")

            if (encryptedUrlHex.isNotBlank()) {
                try {
                    val decryptedUrl = encryptedUrlHex.chunked(2)
                        .map { it.toInt(16).toChar() }
                        .joinToString("")

                    if (decryptedUrl.isNotBlank()) {
                        Log.d("VerAnimesProvider", "loadLinks - Extractor del servidor desencriptado '$serverName': $decryptedUrl")
                        loadExtractor(fixUrl(decryptedUrl), targetUrl, subtitleCallback, callback)
                        linksFound = true
                    } else {
                        Log.w("VerAnimesProvider", "loadLinks - URL desencriptada vacía para servidor '$serverName'.")
                    }
                } catch (e: Exception) {
                    Log.e("VerAnimesProvider", "loadLinks - Error al desencriptar URL para el servidor '$serverName': ${e.message}", e)
                }
            } else {
                Log.w("VerAnimesProvider", "loadLinks - URL encriptada vacía para servidor '$serverName'.")
            }
        }

        val downloadButton = doc.selectFirst("ul.ct a.d")
        downloadButton?.attr("data-dwn")?.let { downloadData ->
            Log.d("VerAnimesProvider", "loadLinks - Botón de descarga encontrado, data-dwn: '$downloadData'")
            try {
                val jsonArray = tryParseJson<List<List<Any>>>(downloadData)
                jsonArray?.forEach { entry ->
                    if (entry.size >= 3 && entry[2] is String) {
                        val downloadUrl = entry[2] as String
                        val downloadServerName = entry[0] as? String ?: "Enlace de Descarga"
                        if (downloadUrl.isNotBlank()) {
                            Log.d("VerAnimesProvider", "loadLinks - Extractor del enlace de descarga '$downloadServerName': $downloadUrl")
                            loadExtractor(fixUrl(downloadUrl), targetUrl, subtitleCallback, callback)
                            linksFound = true
                        } else {
                            Log.w("VerAnimesProvider", "loadLinks - URL de descarga vacía para servidor '$downloadServerName'.")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VerAnimesProvider", "loadLinks - Error al parsear los datos de descarga: ${e.message}", e)
            }
        } ?: Log.w("VerAnimesProvider", "loadLinks - Botón de descarga no encontrado.")

        Log.d("VerAnimesProvider", "loadLinks - Finalizado, links encontrados: $linksFound")
        return linksFound
    }

    private fun parseStatus(statusString: String): ShowStatus {
        return when (statusString.lowercase()) {
            "finalizado" -> ShowStatus.Completed
            "en emisión" -> ShowStatus.Ongoing
            "en curso" -> ShowStatus.Ongoing
            else -> ShowStatus.Ongoing
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) {
            url
        } else if (url.startsWith("./")) { // Maneja URLs como "./ver/..."
            mainUrl + url.substring(1) // Elimina el '.' y lo concatena
        } else if (url.startsWith("/")) {
            mainUrl + url
        } else {
            // Este caso debería ser raro si todas las URLs relativas son con '/' o './'
            mainUrl + "/" + url
        }
    }

    private fun String.hexToString(): String {
        return chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
    }
}