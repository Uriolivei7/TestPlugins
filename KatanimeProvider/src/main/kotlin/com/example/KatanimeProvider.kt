package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.delay

import okhttp3.RequestBody
import okhttp3.FormBody

class KatanimeProvider : MainAPI() {

    init {
        Log.d("KatanimeProviderInit", "KatanimeProvider ha sido inicializado.")
    }

    override var mainUrl = "https://katanime.net"
    override var name = "Katanime"
    override val supportedTypes = setOf(
        TvType.Anime
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val targetUrl = if (page <= 1) {
            mainUrl
        } else {
            "$mainUrl/page/$page/"
        }

        Log.d("Katanime", "getMainPage - Intentando obtener documento de $targetUrl")
        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("Katanime", "Error al obtener el documento para la página principal: ${e.message}", e)
            return null
        }
        Log.d("Katanime", "getMainPage - Documento de la página principal obtenido. Título del HTML: ${doc.selectFirst("title")?.text()} ...")
        Log.d("Katanime", "getMainPage - Primeros 1000 caracteres del HTML: ${doc.outerHtml().take(1000)}...")

        val items = ArrayList<HomePageList>()

        Log.d("Katanime", "getMainPage - Procesando 'Animes Recientes'")
        val animesRecientesH3 = doc.selectFirst("h3[class*=\"carousel\"]:containsOwn(Animes recientes)")
        Log.d("Katanime", "Animes Recientes - h3 encontrado: ${animesRecientesH3?.outerHtml()?.take(100)}...")

        val animesRecientesContainer = animesRecientesH3?.nextElementSibling()
        Log.d("Katanime", "Animes Recientes - Contenedor nextElementSibling: ${animesRecientesContainer?.tagName()}#${animesRecientesContainer?.id()} ${animesRecientesContainer?.classNames()} - ${animesRecientesContainer?.outerHtml()?.take(500)}...")

        val homeItemsAnimes = animesRecientesContainer?.select("div[class*=\"extra\"][class*=\"_2mJki\"]")?.mapNotNull { itemDiv ->
            val anchor = itemDiv.selectFirst("a._1A2Dc._38LRT")
            val link = anchor?.attr("href") ?: ""
            val img = itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("data-src")
                ?: itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("src") ?: ""
            val title = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text() ?: ""

            if (title.isNotBlank() && link.isNotBlank()) {
                Log.d("Katanime", "Anime Reciente - Título: $title, Link: $link, Img: $img")
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = fixUrl(img)
                }
            } else {
                Log.w("Katanime", "Anime Reciente - Elemento incompleto encontrado. Title: $title, Link: $link")
                null
            }
        } ?: emptyList()

        items.add(HomePageList("Animes Recientes", homeItemsAnimes))
        Log.d("Katanime", "Animes Recientes - Total de ítems encontrados: ${homeItemsAnimes.size}")

        Log.d("Katanime", "getMainPage - Procesando 'Capítulos Recientes'")
        val capitulosRecientesH3 = doc.selectFirst("h3.carousel:containsOwn(Capítulos recientes)")
        Log.d("Katanime", "Capítulos Recientes - h3 encontrado: ${capitulosRecientesH3?.outerHtml()?.take(100)}...")

        val capitulosRecientesContainer = capitulosRecientesH3?.nextElementSibling()
        Log.d("Katanime", "Capítulos Recientes - Contenedor nextElementSibling: ${capitulosRecientesContainer?.tagName()}#${capitulosRecientesContainer?.id()} ${capitulosRecientesContainer?.classNames()} - ${capitulosRecientesContainer?.outerHtml()?.take(500)}...")

        val homeItemsCapitulos = capitulosRecientesContainer?.select("div#article-div div[class*=\"chap\"][class*=\"_2mJki\"]")?.mapNotNull { itemDiv ->
            val anchor = itemDiv.selectFirst("a._1A2Dc._38LRT")
            val link = anchor?.attr("href") ?: ""
            val img = itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("data-src")
                ?: itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("src") ?: ""
            val chapterTitle = itemDiv.selectFirst("span[class*=\"_2y8kd\"][class*=\"etag\"]")?.text() ?: ""
            val seriesTitle = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text() ?: ""

            if (link.isNotBlank() && chapterTitle.isNotBlank() && seriesTitle.isNotBlank()) {
                Log.d("Katanime", "Capítulo Reciente - Serie: $seriesTitle, Capítulo: $chapterTitle, Link: $link, Img: $img")
                newAnimeSearchResponse(
                    "$seriesTitle - $chapterTitle",
                    fixUrl(link)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = fixUrl(img)
                }
            } else {
                Log.w("Katanime", "Capítulo Reciente - Elemento incompleto encontrado. Link: $link, ChapterTitle: $chapterTitle, SeriesTitle: $seriesTitle")
                null
            }
        } ?: emptyList()

        items.add(HomePageList("Capítulos Recientes", homeItemsCapitulos))
        Log.d("Katanime", "Capítulos Recientes - Total de ítems encontrados: ${homeItemsCapitulos.size}")

        val hasNextPage = if (page <= 1) {
            true
        } else {
            false
        }

        return newHomePageResponse(items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        Log.d("Katanime", "search - URL de búsqueda: $url")

        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e("Katanime", "Error al obtener el documento para la búsqueda $url: ${e.message}", e)
            return emptyList()
        }

        val selectedItems = doc.select("div#article-div div[class*=\"full\"][class*=\"_2mJki\"]")
        Log.d("Katanime", "Buscador - Items encontrados: ${selectedItems.size}")

        return selectedItems.mapNotNull { itemDiv ->
            val anchor = itemDiv.selectFirst("a._1A2Dc._38LRT")
            val title = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text() ?: ""
            val link = anchor?.attr("href") ?: ""
            val img = itemDiv.selectFirst("img[data-src]")?.attr("data-src")
                ?: itemDiv.selectFirst("img[src]")?.attr("src")
                ?: ""

            Log.d("Katanime", "Buscador - Imagen obtenida para '$title': $img")

            val typeTag = itemDiv.selectFirst("span[class*=\"_2y8kd\"][class*=\"etag\"][class*=\"tag\"]")?.text() ?: ""
            val tvType = when {
                typeTag.contains("Pelicula", ignoreCase = true) == true -> TvType.Movie
                else -> TvType.Anime
            }

            if (title.isNotBlank() && link.isNotBlank()) {
                Log.d("Katanime", "Buscador - Título: $title, Link: $link, Img: $img")
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = fixUrl(img)
                }
            } else {
                Log.w("Katanime", "Buscador - Elemento incompleto encontrado. Title: $title, Link: $link")
                null
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Katanime", "load - URL de entrada: $url")

        val cleanUrl = fixUrl(url)

        if (cleanUrl.isBlank()) {
            Log.e("Katanime", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            Log.e("Katanime", "Error al obtener el documento para la carga de $cleanUrl: ${e.message}", e)
            return null
        }
        val htmlContent = doc.outerHtml()
        Log.d("Katanime", "load - HTML de la página (primeros 5000 caracteres): ${htmlContent.take(5000)}")

        val tvType = TvType.Anime

        val title = doc.selectFirst("h1[class*=\"comics-title\"]")?.text() ?: ""
        Log.d("Katanime", "load - Título del anime: $title")

        val poster = doc.selectFirst("div#animeinfo img[class*=\"lozad\"]")?.attr("data-src")
            ?: doc.selectFirst("div#animeinfo img[class*=\"lozad\"]")?.attr("src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: ""
        Log.d("Katanime", "load - URL del póster: $poster")

        val description = doc.selectFirst("div#sinopsis")?.text() ?: ""
        Log.d("Katanime", "load - Descripción: ${description.take(50)}...")

        val tags = doc.select("span[class*=\"_2y8kd\"][class*=\"etag\"][class*=\"tag\"]").map { it.text() }
        Log.d("Katanime", "load - Tags: $tags")

        val episodesContainerDiv = doc.selectFirst("div#c_list")
        val episodesDataUrl = episodesContainerDiv?.attr("data-url")
        Log.d("Katanime", "load - URL de datos de episodios (data-url): $episodesDataUrl")

        var episodesDoc: org.jsoup.nodes.Document? = null
        if (!episodesDataUrl.isNullOrBlank()) {
            try {
                // Obtener el CSRF token justo antes de la solicitud POST
                val csrfToken = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content")
                    ?: throw ErrorLoadingException("No se encontró el token CSRF para cargar los episodios.")
                Log.d("Katanime", "load - Token CSRF encontrado (antes de POST): $csrfToken")


                // Se construye el cuerpo de la solicitud con el token y la página.
                val requestBody = FormBody.Builder()
                    .add("_token", csrfToken)
                    .add("pagina", "1")
                    .build()

                val headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to cleanUrl, // Asegurarse que el Referer es la URL de la página del anime.
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0"
                )

                episodesDoc = app.post(
                    fixUrl(episodesDataUrl),
                    requestBody = requestBody,
                    headers = headers
                ).document
                Log.d("Katanime", "load - Documento de episodios obtenido de $episodesDataUrl (POST).")
                Log.d("Katanime", "load - HTML de episodios (primeros 1000 caracteres): ${episodesDoc.outerHtml().take(1000)}")
            } catch (e: Exception) {
                Log.e("Katanime", "Error al obtener el documento de episodios de $episodesDataUrl (POST): ${e.message}", e)
                Log.e("Katanime", "load - Error completo de la excepción POST: ${Log.getStackTraceString(e)}")
            }
        }
        val documentToParseEpisodes = episodesDoc ?: doc // Fallback to original doc if POST failed.

        val episodeElements = documentToParseEpisodes.select("a.cap_list")
        Log.d("Katanime", "load - Numero de elementos 'a.cap_list' encontrados: ${episodeElements.size}")

        val episodes = episodeElements.mapNotNull { element ->
            val epurl = fixUrl(element.attr("href") ?: "")
            val epTitleElement = element.selectFirst("h3.entry-title-h2")
            val epTitle = epTitleElement?.text()?.trim() ?: ""

            Log.d("Katanime", "load - Procesando elemento episodio. href: '$epurl', h3.entry-title-h2 encontrado: ${epTitleElement != null}, Título extraído: '$epTitle'")

            val episodeNumberRegex = Regex("""Capítulo\s*(\d+)""")
            val episodeNumber = episodeNumberRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            val realimg = poster

            if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                Log.d("Katanime", "load - Episodio válido añadido: $epTitle, URL: $epurl, Número: $episodeNumber")
                newEpisode(
                    EpisodeLoadData(epTitle, epurl).toJson()
                ) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = episodeNumber
                    this.posterUrl = realimg
                }
            } else {
                Log.w("Katanime", "load - Episodio incompleto o inválido encontrado. Title: '$epTitle', URL: '$epurl'")
                null
            }
        }
        Log.d("Katanime", "load - Total de episodios encontrados: ${episodes.size}")

        return newTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            type = tvType,
            episodes = episodes,
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    private fun decodeBase64(encodedString: String): String? {
        return try {
            val cleanEncodedString = encodedString.replace("=", "")
            String(Base64.decode(cleanEncodedString, Base64.DEFAULT), UTF_8)
        } catch (e: IllegalArgumentException) {
            Log.e("Katanime", "Error al decodificar Base64: ${e.message}", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Katanime", "loadLinks - Data de entrada: $data")

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Katanime", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(data)
            Log.d("Katanime", "loadLinks - URL final (directa): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Katanime", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("Katanime", "Error al obtener el documento para loadLinks $targetUrl: ${e.message}", e)
            return false
        }

        val playerOptions = doc.select("ul#ul-drop-dropcaps li a")
        Log.d("Katanime", "loadLinks - Opciones de reproductor encontradas: ${playerOptions.size}")


        if (playerOptions.isEmpty()) {
            Log.e("Katanime", "No se encontraron opciones de reproductor en la página: $targetUrl")
            val fallbackIframeSrc = doc.selectFirst("section#player_section div iframe[class*=\"embed-responsive-item\"]")?.attr("src")
                ?: doc.selectFirst("div.elementor-widget-container iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*=\"player.\"]")?.attr("src")
                ?: ""

            if (fallbackIframeSrc.isNotBlank()) {
                Log.d("Katanime", "Usando iframe de fallback: $fallbackIframeSrc")
                if (fallbackIframeSrc.contains("katanime.net/reproductor?url=")) {
                    val encodedInnerUrl = fallbackIframeSrc.substringAfter("url=")
                    val decodedInnerUrl = decodeBase64(encodedInnerUrl)
                    if (decodedInnerUrl != null) {
                        Log.d("Katanime", "Iframe de Katanime.net encontrado, URL interna decodificada: $decodedInnerUrl")
                        loadExtractor(fixUrl(decodedInnerUrl), targetUrl, subtitleCallback, callback)
                    } else {
                        Log.e("Katanime", "No se pudo decodificar la URL interna del iframe de Katanime.net: $encodedInnerUrl")
                    }
                } else {
                    loadExtractor(fixUrl(fallbackIframeSrc), targetUrl, subtitleCallback, callback)
                }
                return true
            }

            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            val directVideoRegex = Regex("""["'](https?:\/\/[^"']+\.(?:mp4|m3u8|avi|mkv|mov|flv|webm))["']""")
            val directVideoMatches = directVideoRegex.findAll(scriptContent)

            if (directVideoMatches.any()) {
                Log.d("Katanime", "Encontrados posibles enlaces de video directos en scripts como fallback.")
                directVideoMatches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    Log.d("Katanime", "Cargando extractor para enlace directo de script (fallback): $videoUrl")
                    loadExtractor(fixUrl(videoUrl), targetUrl, subtitleCallback, callback)
                }
                return true
            }

            return false
        }

        for (option in playerOptions) {
            val encodedUrl = option.attr("data-player") ?: ""
            val serverName = option.attr("data-player-name") ?: ""
            Log.d("Katanime", "loadLinks - Procesando opción de reproductor: $serverName, encoded: $encodedUrl")

            if (encodedUrl.isNotBlank()) {
                val decodedUrl = decodeBase64(encodedUrl)
                if (decodedUrl != null) {
                    Log.d("Katanime", "loadLinks - Servidor: $serverName, URL decodificada: $decodedUrl")
                    loadExtractor(fixUrl(decodedUrl), targetUrl, subtitleCallback, callback)
                } else {
                    Log.e("Katanime", "loadLinks - No se pudo decodificar la URL para el servidor: $serverName, encoded: $encodedUrl")
                }
            } else {
                Log.w("Katanime", "loadLinks - La opción de reproductor '$serverName' no tiene un atributo 'data-player'.")
            }
        }

        return true
    }
}