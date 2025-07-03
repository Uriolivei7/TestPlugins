package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay

// **Eliminamos la importación de LStatus, ya que tu declaración de LoadResponse no la contiene.**


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

    private val cfKiller = CloudflareKiller()

    private suspend fun safeAppGet(
        url: String,
        retries: Int = 3,
        delayMs: Long = 2000L,
        timeoutMs: Long = 15000L
    ): String? {
        for (i in 0 until retries) {
            try {
                Log.d("OtakusTV", "safeAppGet - Intento ${i + 1}/$retries para URL: $url")
                val res = app.get(url, interceptor = cfKiller, timeout = timeoutMs)
                if (res.isSuccessful) {
                    Log.d("OtakusTV", "safeAppGet - Petición exitosa para URL: $url")
                    return res.text
                } else {
                    Log.w("OtakusTV", "safeAppGet - Petición fallida para URL: $url con código ${res.code}. Error HTTP.")
                }
            } catch (e: Exception) {
                Log.e("OtakusTV", "safeAppGet - Error en intento ${i + 1}/$retries para URL: $url: ${e.message}", e)
            }
            if (i < retries - 1) {
                Log.d("OtakusTV", "safeAppGet - Reintentando en ${delayMs / 1000.0} segundos...")
                delay(delayMs)
            }
        }
        Log.e("OtakusTV", "safeAppGet - Fallaron todos los intentos para URL: $url")
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"

        val html = safeAppGet(url)
        if (html == null) {
            Log.e("OtakusTV", "getMainPage - No se pudo obtener HTML para $url")
            return null
        }
        val doc = Jsoup.parse(html)

        // --- Función auxiliar para extraer datos comunes de los elementos de anime ---
        fun extractAnimeItem(element: Element): AnimeSearchResponse? { // Aseguramos que devuelve AnimeSearchResponse
            val titleElement = element.selectFirst("h2.font-GDSherpa-Bold a")
            val title = titleElement?.text()?.trim()
            val link = titleElement?.attr("href")

            val posterElement = element.selectFirst("img.lazyload")
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

        // --- Extracción de "EPISODIOS ESTRENO" ---
        val latestEpisodes = doc.select("div.reciente .row .pre").mapNotNull { item ->
            // anime es ahora de tipo AnimeSearchResponse
            val anime: AnimeSearchResponse? = extractAnimeItem(item)
            if (anime != null) {
                val episodeNumberText = item.selectFirst("p.font15 span.bog")?.text()?.trim()
                val episodeNumber = Regex("""Episodio\s*(\d+)""").find(episodeNumberText ?: "")?.groupValues?.get(1)?.toIntOrNull()

                // Si quieres incluir el número de episodio en el título de la miniatura de la página principal:
                val displayTitle = if (episodeNumber != null) "${anime.name} - Ep ${episodeNumber}" else anime.name

                // Usamos .copy() ya que AnimeSearchResponse es una data class y debería funcionar.
                // Si el error persiste en esta línea, la única explicación es un problema de IDE/compilación
                // o que AnimeSearchResponse en tu entorno no es una data class o no tiene copy.
                anime.copy(name = displayTitle)
            } else null
        }
        if (latestEpisodes.isNotEmpty()) {
            items.add(HomePageList("Últimos Episodios", latestEpisodes))
        }

        // --- Extracción de "ANIMES FINALIZADOS" ---
        val finishedAnimesContainer = doc.selectFirst("div.reciente:has(h3:contains(ANIMES FINALIZADOS))")
        if (finishedAnimesContainer != null) {
            // **CORRECCIÓN:** Corregido el error de tipografía aquí
            val finishedAnimes = finishedAnimesContainer.select(".carusel_ranking .item").mapNotNull { item ->
                extractAnimeItem(item)
            }
            if (finishedAnimes.isNotEmpty()) {
                items.add(HomePageList("Animes Finalizados", finishedAnimes))
            }
        }

        // --- Extracción de "RANKING" ---
        val rankingContainer = doc.selectFirst("div.ranking:has(h3:contains(RANKING))")
        if (rankingContainer != null) {
            val rankingAnimes = rankingContainer.select(".carusel_ranking .item").mapNotNull { item ->
                extractAnimeItem(item)
            }
            if (rankingAnimes.isNotEmpty()) {
                items.add(HomePageList("Ranking", rankingAnimes))
            }
        }

        // --- Extracción de "SIMULCASTS" ---
        val simulcastsContainer = doc.selectFirst("div.simulcasts:has(h3:contains(SIMULCASTS))")
        if (simulcastsContainer != null) {
            val simulcastAnimes = simulcastsContainer.select(".carusel_simulcast .item").mapNotNull { item ->
                extractAnimeItem(item)
            }
            if (simulcastAnimes.isNotEmpty()) {
                items.add(HomePageList("Simulcasts", simulcastAnimes))
            }
        }

        val hasNextPage = doc.selectFirst("a.next.page-numbers") != null || doc.selectFirst("li.page-item a[rel=\"next\"]") != null

        return newHomePageResponse(items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val html = safeAppGet(url)
        if (html == null) {
            Log.e("OtakusTV", "search - No se pudo obtener HTML para la búsqueda: $url")
            return emptyList()
        }
        val doc = Jsoup.parse(html)

        return doc.select("div.col-6.col-sm-6.col-md-4.col-lg-3.col-xl-2.pre").mapNotNull {
            val titleElement = it.selectFirst("h2.font-GDSherpa-Bold a")
            val title = titleElement?.text()?.trim()
            val link = titleElement?.attr("href")

            val posterElement = it.selectFirst("img.lazyload")
            val img = posterElement?.attr("data-src") ?: posterElement?.attr("src")

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
        Log.d("OtakusTV", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("OtakusTV", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("OtakusTV", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("OtakusTV", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("OtakusTV", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val html = safeAppGet(cleanUrl)
        if (html == null) {
            Log.e("OtakusTV", "load - No se pudo obtener HTML para la URL principal: $cleanUrl")
            return null
        }
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.Title")?.text() ?: ""
        // La imagen del póster para el anime en sí se encuentra generalmente aquí:
        val poster = doc.selectFirst("div.Image img")?.attr("src") ?: ""


        // Extraer descripción, manejando el enlace "Ver más"
        val descriptionElement = doc.selectFirst("p.font14.mb-0.text-white.mt-0.mt-lg-2")
        // Obtener todos los nodos de texto y unirlos, luego recortar, para evitar incluir "Ver más"
        val description = descriptionElement?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""

        val tags = doc.select("ul.Genre li a").map { it.text() }

        // Extraer estado (ej. "Finalizado") - Ya no se usa directamente en LoadResponse
        // val statusText = doc.selectFirst("span.btn-anime-info")?.text()?.trim()

        // Extraer fecha de estreno
        val releaseDateText = doc.selectFirst("span.date")?.text()?.replace("Estreno:", "")?.trim()
        val year = releaseDateText?.substringAfterLast("-")?.toIntOrNull()


        // Extracción de episodios
        val episodes = doc.select("div.col-6.col-sm-6.col-md-4.col-lg-3.col-xl-2.pre.text-white.mb20.text-center").mapNotNull { element ->
            val epUrl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
            val epTitle = element.selectFirst("span.font-GDSherpa-Bold a")?.text()?.trim() ?: ""
            val epPlot = element.selectFirst("p.font14.mb-0.mt-2.text-left span.bog")?.text()?.trim() ?: ""
            val epPoster = element.selectFirst("img.img-fluid")?.attr("src") ?: ""

            val episodeNumber = Regex("""Episodio\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            if (epUrl.isNotBlank() && epTitle.isNotBlank()) {
                newEpisode(
                    EpisodeLoadData(epTitle, epUrl).toJson()
                ) {
                    this.name = epTitle // Usa esto para el título principal del episodio
                    this.season = null // Asumiendo que no hay información de temporada directamente disponible en esta estructura
                    this.episode = episodeNumber
                    this.posterUrl = epPoster
                    // **CORRECCIÓN:** La clase Episode no tiene una propiedad 'plot'.
                    // Si quieres incluir epPlot, podrías añadirlo al nombre:
                    // this.name = if (epPlot.isNotBlank()) "$epTitle - $epPlot" else epTitle
                }
            } else null
        }.reversed() // Los episodios suelen aparecer en orden inverso (los más recientes primero) en la página, así que los invertimos para un orden cronológico.


        return newTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            type = TvType.Anime,
            episodes = episodes,
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description // La trama es para la serie, no para episodios individuales
            this.tags = tags
            // **CORRECCIÓN:** Se eliminó la asignación de status, ya que la propiedad no existe en LoadResponse
            // this.status = parseStatus(statusText)
            this.year = year // Establece el año aquí
        }
    }

    // **CORRECCIÓN:** Se eliminó la función parseStatus, ya que la enumeración LStatus no se encuentra.
    // private fun parseStatus(statusString: String?): LStatus? {
    //     return when (statusString?.lowercase()) {
    //         "finalizado" -> LStatus.Completed
    //         "en emisión" -> LStatus.Ongoing
    //         "próximamente" -> LStatus.ComingSoon
    //         else -> null // O LStatus.Other si existe, o null
    //     }
    // }


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
            Log.e("OtakusTV", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("OtakusTV", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("OtakusTV", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("OtakusTV", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("OtakusTV", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("Otakustv", "loadLinks - URL final (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("OtakusTV", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val initialHtml = safeAppGet(targetUrl)
        if (initialHtml == null) {
            Log.e("OtakusTV", "loadLinks - No se pudo obtener HTML para la URL principal del contenido: $targetUrl")
            return false
        }
        val doc = Jsoup.parse(initialHtml)

        val playerIframeSrc = doc.selectFirst("div.player-container iframe")?.attr("src")

        if (playerIframeSrc.isNullOrBlank()) {
            Log.e("OtakusTV", "loadLinks - No se encontró el iframe del reproductor principal en OtakusTV con el selector: div.player-container iframe")

            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val commonPlayerRegex = """(https?://(?:www\.)?(?:fembed|streamlare|ok.ru|yourupload|mp4upload|doodstream|filelions)\.com[^"'\s)]+)""".toRegex()
            val directMatches = commonPlayerRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                Log.d("OtakusTV", "Encontrados enlaces directos a reproductores comunes en scripts.")
                directMatches.apmap { playerUrl ->
                    Log.d("OtakusTV", "Cargando extractor para: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), targetUrl, subtitleCallback, callback)
                }
                return true
            }

            val dataSrcPlayer = doc.selectFirst("div[data-src]")?.attr("data-src") ?: doc.selectFirst("div[data-url]")?.attr("data-url")
            if (!dataSrcPlayer.isNullOrBlank()) {
                Log.d("OtakusTV", "Encontrado enlace de reproductor en atributo data-src/data-url: $dataSrcPlayer")
                loadExtractor(fixUrl(dataSrcPlayer), targetUrl, subtitleCallback, callback)
                return true
            }

            Log.w("OtakusTV", "loadLinks - No se encontró ningún reproductor de video en el HTML o scripts.")
            return false
        }

        Log.d("OtakusTV", "loadLinks - Iframe del reproductor encontrado: $playerIframeSrc")
        loadExtractor(fixUrl(playerIframeSrc), targetUrl, subtitleCallback, callback)

        return true
    }
}