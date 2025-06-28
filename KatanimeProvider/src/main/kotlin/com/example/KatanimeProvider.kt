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
        // CAMBIO: Ambas listas ahora provienen de la página principal (mainUrl)
        // La distinción se hará por los contenedores HTML
        val doc = try {
            app.get("$mainUrl/page/$page/").document // Obtener el documento de la página principal (o paginada)
        } catch (e: Exception) {
            Log.e("Katanime", "Error al obtener el documento para la página principal: ${e.message}", e)
            return null
        }

        val items = ArrayList<HomePageList>()

        // Seccion de "Capítulos Recientes"
        // Buscamos el h3 con "Capítulos recientes" y luego su div hermano con id="content-left"
        val capitulosRecientesContainer = doc.selectFirst("h3.carousel:contains(Capítulos recientes)")
            ?.nextElementSibling() // Esto debería darnos div#content-left

        Log.d("Katanime", "getMainPage - Procesando 'Capítulos Recientes'")

        val homeItemsCapitulos = capitulosRecientesContainer?.select("div[class*=\"chap_2MjKi\"]")?.mapNotNull { itemDiv -> // CAMBIO en el selector
            val anchor = itemDiv.selectFirst("a[itemprop=\"url\"][class*=\"_1A2Dc__38LRT\"]")
            val link = anchor?.attr("href")
            val img = itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("data-src") // CAMBIO en el selector
                ?: itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("src") // CAMBIO en el selector
            val chapterTitle = itemDiv.selectFirst("span[class*=\"_2y8kd\"][class*=\"etag\"]")?.text()
            val seriesTitle = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text()

            if (link != null && chapterTitle != null && seriesTitle != null) {
                Log.d("Katanime", "Capítulo: $seriesTitle - $chapterTitle, Link: $link, Img: $img")
                newAnimeSearchResponse(
                    "$seriesTitle - $chapterTitle",
                    fixUrl(link)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = img
                }
            } else {
                Log.w("Katanime", "Capítulo Reciente - Elemento incompleto encontrado. Link: $link, ChapterTitle: $chapterTitle, SeriesTitle: $seriesTitle")
                null
            }
        } ?: emptyList() // Si no se encuentra el contenedor o los ítems, que sea una lista vacía

        items.add(HomePageList("Capítulos Recientes", homeItemsCapitulos))
        Log.d("Katanime", "Capítulos Recientes - Items encontrados: ${homeItemsCapitulos.size}")


        // Sección de "Animes Recientes"
        // Buscamos el h3 con "Animes recientes" y luego su div hermano con id="content-full" y clase "recientes"
        val animesRecientesContainer = doc.selectFirst("h3.carousel:contains(Animes recientes)")
            ?.nextElementSibling() // Esto debería darnos div#content-full

        Log.d("Katanime", "getMainPage - Procesando 'Animes Recientes'")

        val homeItemsAnimes = animesRecientesContainer?.select("div[class*=\"extra_2MjKi\"]")?.mapNotNull { itemDiv -> // CAMBIO en el selector
            val anchor = itemDiv.selectFirst("a[itemprop=\"url\"][class*=\"_1A2Dc__38LRT\"]")
            val link = anchor?.attr("href")
            val img = itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("data-src") // CAMBIO en el selector
                ?: itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("src") // CAMBIO en el selector
            val title = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text()

            if (title != null && link != null) {
                Log.d("Katanime", "Anime: $title, Link: $link, Img: $img")
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.Anime
                    this.posterUrl = img
                }
            } else {
                Log.w("Katanime", "Anime Reciente - Elemento incompleto encontrado. Title: $title, Link: $link")
                null
            }
        } ?: emptyList()

        items.add(HomePageList("Animes Recientes", homeItemsAnimes))
        Log.d("Katanime", "Animes Recientes - Items encontrados: ${homeItemsAnimes.size}")


        return newHomePageResponse(items, false)
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

        // El selector para resultados de búsqueda parece ser el mismo que antes, pero verificamos.
        // Las imágenes muestran div class="135yj _2FQAt full_2MjKi"
        val selectedItems = doc.select("div#article-div div[class*=\"full_2MjKi\"]") // CAMBIO en el selector
        Log.d("Katanime", "Buscador - Items encontrados: ${selectedItems.size}")

        return selectedItems.mapNotNull { itemDiv ->
            val anchor = itemDiv.selectFirst("a[itemprop=\"url\"][class*=\"_1A2Dc__38LRT\"]")
            val title = itemDiv.selectFirst("div[class*=\"_2NNxg\"] a[class*=\"_2uHIS\"]")?.text()
            val link = anchor?.attr("href")
            val img = itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("data-src") // CAMBIO en el selector
                ?: itemDiv.selectFirst("div[class*=\"_1-8M9\"] img")?.attr("src") // CAMBIO en el selector

            val typeTag = itemDiv.selectFirst("span[class*=\"_2y8kd\"][class*=\"etag\"][class*=\"tag\"]")?.text()
            val tvType = when {
                typeTag?.contains("Pelicula", ignoreCase = true) == true -> TvType.Movie
                else -> TvType.Anime
            }

            if (title != null && link != null) {
                Log.d("Katanime", "Buscador - Título: $title, Link: $link, Img: $img")
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = img
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

        // Episodes:
        // El selector div#c_list li a.cap_list y h3.entry-title-h2 parecen correctos.
        val episodes = doc.select("div#c_list li a.cap_list").mapNotNull { element ->
            val epurl = fixUrl(element.attr("href") ?: "")
            val epTitle = element.selectFirst("h3[class*=\"entry-title-h2\"]")?.text() ?: ""

            val episodeNumberRegex = Regex("""Capítulo\s*(\d+)""")
            val episodeNumber = episodeNumberRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            val realimg = poster

            if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                Log.d("Katanime", "load - Episodio encontrado: $epTitle, URL: $epurl")
                newEpisode(
                    EpisodeLoadData(epTitle, epurl).toJson()
                ) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = episodeNumber
                    this.posterUrl = realimg
                }
            } else {
                Log.w("Katanime", "load - Episodio incompleto encontrado. Title: $epTitle, URL: $epurl")
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

        // Selectores basados en las imágenes (image_070643.png, image_070600.png)
        val playerOptions = doc.select("ul#ul-drop-dropcaps li a")
        Log.d("Katanime", "loadLinks - Opciones de reproductor encontradas: ${playerOptions.size}")


        if (playerOptions.isEmpty()) {
            Log.e("Katanime", "No se encontraron opciones de reproductor en la página: $targetUrl")
            // Fallback: Buscar iframe principal directamente
            // Selectores basados en las imágenes (image_0702e1.png, image_070281.png)
            val fallbackIframeSrc = doc.selectFirst("section#player_section div iframe[class*=\"embed-responsive-item\"]")?.attr("src")
                ?: doc.selectFirst("div.elementor-widget-container iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*=\"player.\"]")?.attr("src")

            if (!fallbackIframeSrc.isNullOrBlank()) {
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
            val encodedUrl = option.attr("data-player")
            val serverName = option.attr("data-player-name")
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