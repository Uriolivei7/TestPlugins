package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.* // MANTEN ESTA IMPORTACIÓN para ExtractorLink y otros utils
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element // Importación necesaria para Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay

// Importaciones ESENCIALES y correctas:
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse

// ***** CAMBIO CLAVE PARA loadExtractor *****
//import com.lagradost.cloudstream3.extractors.loadExtractor // ¡RE-AÑADE ESTA IMPORTACIÓN!
// ******************************************

// Asegúrate de que NO tengas esta línea si la de arriba es la que funciona:
// import com.lagradost.cloudstream3.utils.ExtractorApi

class OtakustvProvider : MainAPI() {
    override var mainUrl = "https://www1.otakustv.com"
    override var name = "OtakusTV"
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.75 Safari/537.36"

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

    // ***** CAMBIO CLAVE PARA getMainPage: ELIMINA el '?' de 'request' *****
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
            val finishedAnimes = finishedAnimesContainer.select(".carusel_ranking .item").mapNotNull { item: Element -> // Especifica tipo si el error persiste
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
            val rankingAnimes = rankingContainer.select(".carusel_ranking .item").mapNotNull { item: Element -> // Especifica tipo si el error persiste
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
            val simulcastAnimes = simulcastsContainer.select(".carusel_simulcast .item").mapNotNull { item: Element -> // Especifica tipo si el error persiste
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
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
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

        val episodes = doc.select("ul.episodes-list li").mapNotNull { element ->
            val epUrl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
            val epTitle = element.selectFirst("h2")?.text()?.trim() ?: ""
            val epPoster = element.selectFirst("img")?.attr("src") ?: ""
            val episodeNumber = element.selectFirst("a")?.attr("href")?.split("-")?.lastOrNull()?.toIntOrNull()

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

        val recommendations = doc.select(".base-carusel .item").mapNotNull { item: Element -> // Especifica tipo si el error persiste
            extractAnimeItem(item)
        }
        if (recommendations.isEmpty()) {
            Log.w("OtakustvProvider", "load - No se encontraron recomendaciones con el selector '.base-carusel .item'.")
        } else {
            Log.d("OtakustvProvider", "load - Se encontraron ${recommendations.size} recomendaciones.")
        }

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
            //this.status = status // ¡Vuelve a poner 'this.'!
            this.recommendations = recommendations
            //this.dubStatus = DubStatus.Subbed // ¡Vuelve a poner 'this.'!
        }
    }

    data class SortedEmbed(
        val servername: String,
        val link: String,
        val type: String
    )

    data class DataLinkEntry(
        val file_id: String,
        val video_language: String,
        val sortedEmbeds: List<SortedEmbed>
    )

    private fun decryptLink(encryptedLinkBase64: String, secretKey: String): String? {
        try {
            val encryptedBytes = Base64.decode(encryptedLinkBase64, Base64.DEFAULT)

            val ivBytes = encryptedBytes.copyOfRange(0, 16)
            val ivSpec = IvParameterSpec(ivBytes)

            val cipherTextBytes = encryptedBytes.copyOfRange(16, encryptedBytes.size)

            val keySpec = SecretKeySpec(secretKey.toByteArray(UTF_8), "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decryptedBytes = cipher.doFinal(cipherTextBytes)

            return String(decryptedBytes, UTF_8)
        } catch (e: Exception) {
            Log.e("OtakustvProvider", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("OtakustvProvider", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("OtakustvProvider", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("OtakustvProvider", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("OtakustvProvider", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("OtakustvProvider", "loadLinks - URL final (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

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

        val playerIframeSrc = doc.selectFirst("div.st-vid #result_server iframe#ytplayer")?.attr("src")

        if (!playerIframeSrc.isNullOrBlank()) {
            Log.d("OtakustvProvider", "loadLinks - Iframe del reproductor principal encontrado: $playerIframeSrc")

            // Llamada directa a loadExtractor
            loadExtractor(fixUrl(playerIframeSrc), targetUrl, subtitleCallback, callback)
            return true
        } else {
            Log.w("OtakustvProvider", "loadLinks - No se encontró el iframe del reproductor principal. Intentando métodos alternativos.")

            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val commonPlayerRegex = """(https?://(?:www\.)?(?:fembed|streamlare|ok\.ru|yourupload|mp4upload|doodstream|filelions|mixdrop|streamsb|sblongvu|streamtape|gogocdn|filemoon\.to)\.com[^"'\s)]+)""".toRegex()
            val directMatches = commonPlayerRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                Log.d("OtakustvProvider", "loadLinks - Encontrados enlaces directos a reproductores comunes en scripts: ${directMatches.size}")
                directMatches.apmap { playerUrl: String -> // Especifica tipo si el error persiste
                    Log.d("OtakustvProvider", "loadLinks - Cargando extractor para: $playerUrl")
                    // Llamada directa a loadExtractor
                    loadExtractor(fixUrl(playerUrl), targetUrl, subtitleCallback, callback)
                }
                return true
            }

            val dataSrcPlayer = doc.selectFirst("div[data-src]")?.attr("data-src") ?: doc.selectFirst("div[data-url]")?.attr("data-url")
            if (!dataSrcPlayer.isNullOrBlank()) {
                Log.d("OtakustvProvider", "loadLinks - Encontrado enlace de reproductor en atributo data-src/data-url: $dataSrcPlayer")
                // Llamada directa a loadExtractor
                loadExtractor(fixUrl(dataSrcPlayer), targetUrl, subtitleCallback, callback)
                return true
            }

            Log.w("OtakustvProvider", "loadLinks - No se encontró ningún reproductor de video válido en el HTML o scripts.")
            return false
        }
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