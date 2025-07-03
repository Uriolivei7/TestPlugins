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

        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/" // Asegúrate que la paginación sea correcta.

        val html = safeAppGet(url)
        if (html == null) {
            Log.e("OtakusTV", "getMainPage - No se pudo obtener HTML para $url")
            return null
        }
        val doc = Jsoup.parse(html)

        // --- Función auxiliar para extraer datos comunes de los elementos de anime ---
        fun extractAnimeItem(element: Element): AnimeSearchResponse? {
            val titleElement = element.selectFirst("h2.font-GDSherpa-Bold a")
                ?: element.selectFirst("div.img-in a") // Selector alternativo para el enlace principal

            val title = titleElement?.attr("title") ?: titleElement?.text()?.trim()
            val link = titleElement?.attr("href")

            val posterElement = element.selectFirst("img.lazyload") // img con lazyload
                ?: element.selectFirst("img.img-fluid") // img normal
            val img = posterElement?.attr("data-src") ?: posterElement?.attr("src")

            if (title != null && link != null) {
                return newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = img
                    // Puedes añadir más campos si los selectores están disponibles aquí
                    // this.quality = Qualities.newValue si hay un indicador visible
                    // this.plot = item.selectFirst("p.some-description-class")?.text() si aplica
                }
            }
            return null
        }

        // --- COMENTADA: Extracción de "EPISODIOS ESTRENO" ---
        // Según tu solicitud, esta sección no se incluirá en la HomePage.
        /*
        val latestEpisodesElements = doc.select("div.reciente .row .pre")
        val latestEpisodesList = latestEpisodesElements.mapNotNull { item ->
            val anime: AnimeSearchResponse? = extractAnimeItem(item)
            if (anime != null) {
                val episodeNumberText = item.selectFirst("p.font15 span.bog")?.text()?.trim()
                val episodeNumber = Regex("""Episodio\s*(\d+)""").find(episodeNumberText ?: "")?.groupValues?.get(1)?.toIntOrNull()

                val displayTitle = if (episodeNumber != null) "${anime.name} - Ep ${episodeNumber}" else anime.name

                newAnimeSearchResponse(
                    displayTitle,
                    anime.url
                ) {
                    this.type = anime.type
                    this.posterUrl = anime.posterUrl
                    this.quality = if (item.selectFirst("span.new_movie") != null) Qualities.newValue else null
                    this.episode = episodeNumber
                }
            } else null
        }
        if (latestEpisodesList.isNotEmpty()) {
            items.add(HomePageList("Últimos Episodios", latestEpisodesList))
            Log.d("OtakusTV", "getMainPage - Añadidos ${latestEpisodesList.size} Últimos Episodios.")
        } else {
            Log.d("OtakusTV", "getMainPage - No se encontraron Últimos Episodios con los selectores actuales.")
        }
        */

        // --- Extracción de "ANIMES FINALIZADOS" ---
        // Selector para el contenedor padre de "Animes Finalizados".
        // Si no funciona, verifica que el h3 con "ANIMES FINALIZADOS" y la clase "reciente" sean correctos.
        val finishedAnimesContainer = doc.selectFirst("div.reciente:has(h3:contains(ANIMES FINALIZADOS))")
        if (finishedAnimesContainer != null) {
            val finishedAnimes = finishedAnimesContainer.select(".carusel_ranking .item").mapNotNull { item ->
                extractAnimeItem(item)
            }
            if (finishedAnimes.isNotEmpty()) {
                items.add(HomePageList("Animes Finalizados", finishedAnimes))
                Log.d("OtakusTV", "getMainPage - Añadidos ${finishedAnimes.size} Animes Finalizados.")
            } else {
                Log.d("OtakusTV", "getMainPage - No se encontraron Animes Finalizados en el carrusel.")
            }
        } else {
            Log.d("OtakusTV", "getMainPage - No se encontró el contenedor de Animes Finalizados.")
        }

        // --- Extracción de "RANKING" ---
        // Selector para el contenedor padre de "Ranking".
        // Si no funciona, verifica que el h3 con "RANKING" y la clase "ranking" sean correctos.
        val rankingContainer = doc.selectFirst("div.ranking:has(h3:contains(RANKING))")
        if (rankingContainer != null) {
            val rankingAnimes = rankingContainer.select(".carusel_ranking .item").mapNotNull { item ->
                extractAnimeItem(item)
            }
            if (rankingAnimes.isNotEmpty()) {
                items.add(HomePageList("Ranking", rankingAnimes))
                Log.d("OtakusTV", "getMainPage - Añadidos ${rankingAnimes.size} Animes en Ranking.")
            } else {
                Log.d("OtakusTV", "getMainPage - No se encontraron Animes en Ranking en el carrusel.")
            }
        } else {
            Log.d("OtakusTV", "getMainPage - No se encontró el contenedor de Ranking.")
        }

        // --- Extracción de "SIMULCASTS" ---
        // Selector para el contenedor padre de "Simulcasts".
        // Si no funciona, verifica que el h3 con "SIMULCASTS" y la clase "simulcasts" sean correctos.
        val simulcastsContainer = doc.selectFirst("div.simulcasts:has(h3:contains(SIMULCASTS))")
        if (simulcastsContainer != null) {
            val simulcastAnimes = simulcastsContainer.select(".carusel_simulcast .item").mapNotNull { item ->
                extractAnimeItem(item)
            }
            if (simulcastAnimes.isNotEmpty()) {
                items.add(HomePageList("Simulcasts", simulcastAnimes))
                Log.d("OtakusTV", "getMainPage - Añadidos ${simulcastAnimes.size} Simulcasts.")
            } else {
                Log.d("OtakusTV", "getMainPage - No se encontraron Simulcasts en el carrusel.")
            }
        } else {
            Log.d("OtakusTV", "getMainPage - No se encontró el contenedor de Simulcasts.")
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

        // Selector para resultados de búsqueda. Si no funciona, inspecciona la página de búsqueda.
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
        // Intenta parsear la URL si viene en formato JSON, útil para episodios
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("OtakusTV", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            // Asegúrate de que la URL comienza con http(s):// y no tiene dobles barras iniciales
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("OtakusTV", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("OtakusTV", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        // Si la URL limpia apunta a un episodio, necesitamos la URL base del anime para cargar la información de la serie
        val episodeRegex = Regex("""(.+)/episodio-\d+/?$""")
        val animeBaseUrlMatch = episodeRegex.find(cleanUrl)
        val finalUrlToFetch = if (animeBaseUrlMatch != null) {
            val base = animeBaseUrlMatch.groupValues[1]
            if (!base.endsWith("/")) "$base/" else base // Asegurar que la URL base termine en /
        } else {
            if (!cleanUrl.endsWith("/")) "$cleanUrl/" else cleanUrl // Asegurar que la URL base termine en /
        }
        Log.d("OtakusTV", "load - URL final para fetch HTML (base del anime): $finalUrlToFetch")


        if (finalUrlToFetch.isBlank()) {
            Log.e("OtakusTV", "load - ERROR: URL base del anime está en blanco después de procesar.")
            return null
        }

        val html = safeAppGet(finalUrlToFetch) // ¡Usamos la URL base del anime!
        if (html == null) {
            Log.e("OtakusTV", "load - No se pudo obtener HTML para la URL principal del anime: $finalUrlToFetch")
            return null
        }
        val doc = Jsoup.parse(html)

        // --- Selectores de Información del Anime (página de detalles) ---
        // Estos selectores son cruciales. Si no funcionan, deberás inspeccionar
        // el HTML de una página de anime en OtakusTV para ajustarlos.
        val title = doc.selectFirst("h1[itemprop=\"name\"]")?.text() ?: doc.selectFirst("h1.tit_ocl")?.text() ?: ""
        Log.d("OtakusTV", "load - Título extraído: $title")

        val poster = doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("data-src")
            ?: doc.selectFirst("div.img-in img[itemprop=\"image\"]")?.attr("src")
            ?: ""
        Log.d("OtakusTV", "load - Póster extraído: $poster")

        val descriptionElement = doc.selectFirst("p.font14.mb-0.text-white.mt-0.mt-lg-2")
        val description = descriptionElement?.textNodes()?.joinToString("") { it.text().trim() }?.trim() ?: ""
        Log.d("OtakusTV", "load - Descripción extraída: $description")

        val tags = doc.select("ul.Genre li a").map { it.text() }
        if (tags.isEmpty()) {
            Log.w("OtakusTV", "load - No se encontraron tags/géneros con el selector 'ul.Genre li a'.")
        } else {
            Log.d("OtakusTV", "load - Tags extraídos: $tags")
        }

        val releaseDateText = doc.selectFirst("span.date")?.text()?.replace("Estreno:", "")?.trim()
        val year = releaseDateText?.substringAfterLast("-")?.toIntOrNull()
        Log.d("OtakusTV", "load - Año extraído: $year")


        // --- Extracción de Episodios ---
        // Este selector para los episodios es muy específico.
        // Si no funciona, inspecciona el HTML de una página de anime para ver el contenedor exacto de cada miniatura de episodio.
        val episodes = doc.select("div.col-6.col-sm-6.col-md-4.col-lg-3.col-xl-2.pre.text-white.mb20.text-center").mapNotNull { element ->
            val epUrl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
            val epTitle = element.selectFirst("span.font-GDSherpa-Bold a")?.text()?.trim() ?: ""
            // Si el plot del episodio no existe o el selector no es correcto, será vacío.
            val epPlot = element.selectFirst("p.font14.mb-0.mt-2.text-left span.bog")?.text()?.trim() ?: ""
            val epPoster = element.selectFirst("img.img-fluid")?.attr("src") ?: ""

            val episodeNumber = Regex("""Episodio\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            if (epUrl.isNotBlank() && epTitle.isNotBlank()) {
                newEpisode(
                    EpisodeLoadData(epTitle, epUrl).toJson() // Serializa a JSON para pasar los datos al loadLinks
                ) {
                    this.name = epTitle
                    this.season = null // Si no hay temporadas claras, dejar null
                    this.episode = episodeNumber
                    this.posterUrl = epPoster
                }
            } else null
        }.reversed() // Los episodios suelen estar listados de más reciente a más antiguo, reversed los pone en orden ascendente.
        Log.d("OtakusTV", "load - Se encontraron ${episodes.size} episodios.")


        return newTvSeriesLoadResponse(
            name = title,
            url = finalUrlToFetch, // La URL base del anime
            type = TvType.Anime,
            episodes = episodes,
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster // A menudo se usa el mismo póster como fondo si no hay uno específico
            this.plot = description
            this.tags = tags
            this.year = year
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
            Log.d("OtakusTV", "loadLinks - URL final (directa o ya limpia y fixUrl-ed): $targetUrl")
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

        // Primero, busca el iframe del reproductor principal, que es el método más fiable.
        val playerIframeSrc = doc.selectFirst("div.player-container iframe")?.attr("src")

        if (!playerIframeSrc.isNullOrBlank()) {
            Log.d("OtakusTV", "loadLinks - Iframe del reproductor principal encontrado: $playerIframeSrc")
            // Carga el extractor para la URL del iframe.
            loadExtractor(fixUrl(playerIframeSrc), targetUrl, subtitleCallback, callback)
            return true
        } else {
            Log.w("OtakusTV", "loadLinks - No se encontró el iframe del reproductor principal. Intentando métodos alternativos.")

            // Alternativa 1: Buscar en los scripts por enlaces directos a reproductores conocidos.
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            // Añade más dominios de extractores comunes si los conoces.
            val commonPlayerRegex = """(https?://(?:www\.)?(?:fembed|streamlare|ok\.ru|yourupload|mp4upload|doodstream|filelions|mixdrop|streamsb|sblongvu|streamtape|gogocdn)\.com[^"'\s)]+)""".toRegex()
            val directMatches = commonPlayerRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                Log.d("OtakusTV", "loadLinks - Encontrados enlaces directos a reproductores comunes en scripts: ${directMatches.size}")
                directMatches.apmap { playerUrl ->
                    Log.d("OtakusTV", "loadLinks - Cargando extractor para: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), targetUrl, subtitleCallback, callback)
                }
                return true
            }

            // Alternativa 2: Buscar URLs en atributos data-src o data-url.
            val dataSrcPlayer = doc.selectFirst("div[data-src]")?.attr("data-src") ?: doc.selectFirst("div[data-url]")?.attr("data-url")
            if (!dataSrcPlayer.isNullOrBlank()) {
                Log.d("OtakusTV", "loadLinks - Encontrado enlace de reproductor en atributo data-src/data-url: $dataSrcPlayer")
                loadExtractor(fixUrl(dataSrcPlayer), targetUrl, subtitleCallback, callback)
                return true
            }

            Log.w("OtakusTV", "loadLinks - No se encontró ningún reproductor de video válido en el HTML o scripts.")
            return false
        }
    }
}