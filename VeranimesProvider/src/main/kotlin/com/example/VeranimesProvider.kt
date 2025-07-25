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
import com.lagradost.cloudstream3.DubStatus

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
        val linkHref = linkElement?.attr("href") // Esta es la URL del anime o episodio

        val titleText = element.selectFirst("h3.h a")?.text()?.trim()
            ?: element.selectFirst("span.n b")?.text()?.trim()

        val posterElement = element.selectFirst("figure.i img")
        val img = fixUrl(posterElement?.attr("data-src") ?: posterElement?.attr("src"))

        val releaseYear = null

        if (titleText != null && linkHref != null) {
            val finalAnimeUrl: String
            val episodeMatch = Regex("""(.+?\/ver\/[^\/]+?)(?:-episodio-\d+)?(?:-\d+)?\/?$""").find(linkHref)
            if (episodeMatch != null) {
                finalAnimeUrl = episodeMatch.groupValues[1]
            } else {
                finalAnimeUrl = linkHref
            }

            Log.d("VerAnimesProvider", "extractAnimeItem - Título: $titleText, Enlace Original: $linkHref, Enlace BASE Anime: $finalAnimeUrl, Póster: $img")

            val fixedFinalAnimeUrl = fixUrl(finalAnimeUrl) ?: return null

            return newAnimeSearchResponse(
                titleText,
                fixedFinalAnimeUrl
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
        Log.d("VerAnimesProvider", "getMainPage - HTML cargado para $url. Extrayendo listas de la página principal.")

        // Nuevos episodios agregados
        // *** MODIFICACIÓN AQUI: Selector más robusto para "Nuevos episodios agregados" ***
        doc.selectFirst("div.th:has(h2.h:contains(Nuevos episodios agregados)) + div.ul.episodes")?.let { container ->
            val animes = container.select("article.li").mapNotNull { extractAnimeItem(it) }
            if (animes.isNotEmpty()) {
                items.add(HomePageList("Nuevos Episodios Agregados", animes))
                Log.d("VerAnimesProvider", "getMainPage - Añadidos ${animes.size} Nuevos Episodios Agregados.")
            } else {
                Log.w("VerAnimesProvider", "getMainPage - No se encontraron animes para 'Nuevos Episodios Agregados'.")
            }
        } ?: Log.w("VerAnimesProvider", "getMainPage - Contenedor para 'Nuevos episodios agregados' (div.ul.episodes) no encontrado.")


        // Nuevos Animes Agregados
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
            val img = fixUrl(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))

            Log.d("VerAnimesProvider", "search - Resultado: Título: $title, Link: $link, Póster: $img")

            if (title != null && link != null) {
                val finalLink = fixUrl(link) ?: return@mapNotNull null
                newAnimeSearchResponse(
                    title,
                    finalLink
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

        val finalUrlToFetch: String? = if (Regex("""(?i)\/(?:ver|anime)\/([^\/]+)(?:-\d+)?\/?$""").find(cleanUrl)?.let { it.groupValues[1] } != null) {
            val slug = Regex("""(?i)\/(?:ver|anime)\/([^\/]+)(?:-\d+)?\/?$""").find(cleanUrl)!!.groupValues[1]
            "${mainUrl}/anime/$slug"
        } else {
            cleanUrl
        }

        Log.d("VerAnimesProvider", "load - URL limpia inicial: $url, URL procesada: $cleanUrl, URL base final a obtener: $finalUrlToFetch")

        val fixedFinalUrlToFetch = fixUrl(finalUrlToFetch)
        if (fixedFinalUrlToFetch.isNullOrBlank()) {
            Log.e("VerAnimesProvider", "load - URL final para obtener está en blanco o es nula después de fixUrl: $finalUrlToFetch")
            return null
        }

        val html = app.get(fixedFinalUrlToFetch).text
        if (html.isNullOrBlank()) {
            Log.e("VerAnimesProvider", "load - Falló la obtención del HTML para: $fixedFinalUrlToFetch")
            return null
        }
        Log.d("VerAnimesProvider", "load - HTML recibido para $fixedFinalUrlToFetch (primeros 500 caracteres): ${html.take(500)}")

        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("div.ti h1 strong")?.text() ?: ""
        val poster = fixUrl(doc.selectFirst("div.sc div.l figure.i img")?.attr("data-src") ?: doc.selectFirst("div.sc div.l figure.i img")?.attr("src"))
        val description = doc.selectFirst("div.tx p")?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""

        Log.d("VerAnimesProvider", "load - Título extraído: '$title', Póster extraído: '$poster', Descripción extraída: '${description.take(50)}...'")
        if (poster.isNullOrBlank()) {
            Log.w("VerAnimesProvider", "load - ¡ADVERTENCIA! No se encontró póster con selector 'div.sc div.l figure.i img' para $fixedFinalUrlToFetch")
        }

        val localTags = doc.select("ul.gn li a").map { it.text().trim() }
        val year = doc.selectFirst("div.ti div span.a")?.text()?.trim()?.toIntOrNull()
        val localStatus = parseStatus(doc.selectFirst("div.ti div span.fi")?.text()?.trim() ?: "")

        Log.d("VerAnimesProvider", "load - Géneros/Tags: $localTags, Año: $year, Estado: $localStatus")

        val allEpisodes = ArrayList<Episode>()

        val dataSl = doc.selectFirst("*[data-sl]")?.attr("data-sl")
        val dataZr = doc.selectFirst("*[data-zr]")?.attr("data-zr")?.toIntOrNull()

        val scriptContent = doc.select("script:contains(var eps)").firstOrNull()?.html()

        if (scriptContent != null && dataSl != null && dataZr != null) {
            val epsRegex = "var eps = \\[([^\\]]+)\\];".toRegex()
            val matchResult = epsRegex.find(scriptContent)

            if (matchResult != null) {
                val epsString = matchResult.groupValues[1]
                val epsArray = epsString.split(",").mapNotNull { it.trim().toIntOrNull() }

                Log.d("VerAnimesProvider", "load - `eps` array extraído: ${epsArray.joinToString(", ")}, data-sl: $dataSl, data-zr: $dataZr")

                val sortedEpsArray = epsArray.sorted()

                for (epn in sortedEpsArray) {
                    val episodeTitle = "Episodio $epn"
                    val episodeUrl = "$mainUrl/ver/$dataSl-$epn"

                    allEpisodes.add(
                        newEpisode(EpisodeLoadData(episodeTitle, episodeUrl).toJson()) {
                        }
                    )
                }
            } else {
                Log.w("VerAnimesProvider", "load - No se pudo encontrar la definición de 'eps' en el script para $fixedFinalUrlToFetch.")
            }
        } else {
            Log.w("VerAnimesProvider", "load - No se encontró el script con 'var eps' o data-sl/data-zr en el HTML para $fixedFinalUrlToFetch.")
        }

        Log.d("VerAnimesProvider", "load - Total de episodios encontrados: ${allEpisodes.size}")

        val episodesMap = mutableMapOf<DubStatus, List<Episode>>()
        if (allEpisodes.isNotEmpty()) {
            episodesMap[DubStatus.Subbed] = allEpisodes
        }

        return newAnimeLoadResponse(
            name = title,
            url = fixedFinalUrlToFetch,
            type = TvType.Anime,
            //year = year // **CORRECCIÓN: Intentamos pasar 'year' aquí directamente como un argumento.**
        ) { // Este bloque es la lambda 'initializer' para configurar AnimeLoadResponse
            episodes = episodesMap
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            tags = localTags
            //status = localStatus
            recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        val targetUrl = parsedEpisodeData?.url ?: fixUrl(data) ?: return false

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
                val fixedIframeSrc = fixUrl(iframeSrc)
                if (fixedIframeSrc != null) {
                    Log.d("VerAnimesProvider", "loadLinks - Extrayendo del iframe principal: $fixedIframeSrc")
                    loadExtractor(fixedIframeSrc, targetUrl, subtitleCallback, callback)
                    linksFound = true
                } else {
                    Log.w("VerAnimesProvider", "loadLinks - URL de iframe principal es nula después de fixUrl.")
                }
            } else {
                Log.w("VerAnimesProvider", "loadLinks - El src del iframe del reproductor principal es nulo/vacío.")
            }
        } else {
            Log.w("VerAnimesProvider", "loadLinks - No se encontró el iframe del reproductor principal con el selector 'div.ply iframe'.")
        }

        doc.select("ul.opt li").forEach { liElement ->
            val encryptedUrlHex = liElement.attr("encrypt")
            val serverName = liElement.attr("title").ifBlank { liElement.selectFirst("span")?.text()?.trim() }
            Log.d("VerAnimesProvider", "loadLinks - Procesando servidor: '$serverName', URL Encriptada: '$encryptedUrlHex'")

            if (encryptedUrlHex.isNotBlank()) {
                try {
                    val decryptedUrl = encryptedUrlHex.chunked(2)
                        .map { it.toInt(16).toChar() }
                        .joinToString("")

                    val fixedDecryptedUrl = fixUrl(decryptedUrl)

                    if (fixedDecryptedUrl != null && fixedDecryptedUrl.isNotBlank()) {
                        Log.d("VerAnimesProvider", "loadLinks - Extractor del servidor desencriptado '$serverName': $fixedDecryptedUrl")
                        loadExtractor(fixedDecryptedUrl, targetUrl, subtitleCallback, callback)
                        linksFound = true
                    } else {
                        Log.w("VerAnimesProvider", "loadLinks - URL desencriptada vacía o nula para servidor '$serverName'.")
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
                        val fixedDownloadUrl = fixUrl(downloadUrl)
                        if (fixedDownloadUrl != null && fixedDownloadUrl.isNotBlank()) {
                            Log.d("VerAnimesProvider", "loadLinks - Extractor del enlace de descarga '$downloadServerName': $fixedDownloadUrl")
                            loadExtractor(fixedDownloadUrl, targetUrl, subtitleCallback, callback)
                            linksFound = true
                        } else {
                            Log.w("VerAnimesProvider", "loadLinks - URL de descarga vacía o nula para servidor '$downloadServerName'.")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VerAnimesProvider", "loadLinks - Error al parsear los datos de descarga: ${e.message}", e)
            }
        } ?: Log.w("VerAnimesProvider", "loadLinks - Botón de descarga no encontrado.")

        Log.d("VerAnimesProvider", "loadLinks - Finalizado, enlaces encontrados: $linksFound")
        return linksFound
    }

    private fun hex2a(hex: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < hex.length) {
            val charCode = hex.substring(i, i + 2).toInt(16)
            sb.append(charCode.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun parseStatus(statusString: String?): Int? {
        return when (statusString?.lowercase()) {
            "En Emisión" -> 1 // Representa "En emisión" o "En curso"
            "finalizado" -> 2 // Representa "Finalizado"
            else -> null // Si el estado no se reconoce, devuelve null
        }
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        return if (url.startsWith("http")) {
            url
        } else if (url.startsWith("./")) {
            mainUrl + url.substring(1)
        } else if (url.startsWith("/")) {
            mainUrl + url
        } else {
            "$mainUrl/$url"
        }
    }

    private fun String.hexToString(): String {
        return chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
    }
}