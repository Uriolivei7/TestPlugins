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

class PlayhubProvider : MainAPI() {
    override var mainUrl = "https://playhublite.com"
    override var name = "PlayHubLite"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        // === USANDO LAS URLs ESPECÍFICAS DE CATEGORÍAS (como /movies, /series, /animes) ===
        val urls = listOf(
            Pair("Películas", "$mainUrl/movies"), // Ajustado a /movies según tu ejemplo
            Pair("Series", "$mainUrl/series"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            Log.d("PlayHubLite", "getMainPage: Intentando obtener la categoría '$name' de URL: $url")
            val tvType = when (name) {
                "Películas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                else -> TvType.Others
            }

            val doc = try {
                app.get(url).document
            } catch (e: Exception) {
                Log.e("PlayHubLite", "getMainPage: ERROR al obtener el documento de $url para '$name': ${e.message}", e)
                return@apmap null // Continuar con la siguiente URL si hay un error
            }
            Log.d("PlayHubLite", "getMainPage: Documento obtenido para '$name'. Longitud: ${doc.html().length}")

            // Selector para los elementos de la lista en PlayHubLite
            // => CRÍTICO: Este selector debe coincidir con la estructura de /movies, /series, /animes
            val homeItems = doc.select("div.peliculas-content div.item-pelicula").mapNotNull { itemElement ->
                val title = itemElement.selectFirst("div.info h2")?.text()
                val link = itemElement.selectFirst("a")?.attr("href")
                val img = itemElement.selectFirst("img")?.attr("data-src") ?: itemElement.selectFirst("img")?.attr("src")

                if (title != null && link != null) {
                    Log.d("PlayHubLite", "getMainPage: Categoría '$name' - Item encontrado: Título='$title', Link='$link', Img='$img'")
                    newMovieSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else {
                    Log.w("PlayHubLite", "getMainPage: Categoría '$name' - Item en elemento no válido (título o link nulo). HTML: ${itemElement.outerHtml()}")
                    null
                }
            }

            if (homeItems.isNotEmpty()) {
                Log.d("PlayHubLite", "getMainPage: Añadida lista '$name' con ${homeItems.size} items.")
                HomePageList(name, homeItems)
            } else {
                Log.w("PlayHubLite", "getMainPage: La lista '$name' está vacía después de la extracción en $url. Selectores pueden estar incorrectos o la página está vacía.")
                null // No añadir una lista vacía si no se encontró nada
            }
        }.filterNotNull() // Filtra cualquier resultado nulo de apmap

        items.addAll(homePageLists)

        Log.d("PlayHubLite", "getMainPage: Total de HomePageLists finales creadas: ${items.size}")
        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=$query" // Asumo que la URL de búsqueda es /search?query=
        Log.d("PlayHubLite", "search: Buscando en URL: $url")
        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e("PlayHubLite", "search: ERROR al obtener el documento de búsqueda: ${e.message}", e)
            return emptyList()
        }

        // Selector para los elementos de búsqueda en PlayHubLite (debería ser consistente con categorías)
        val searchResults = doc.select("div.peliculas-content div.item-pelicula").mapNotNull {
            val title = it.selectFirst("div.info h2")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")

            if (title != null && link != null) {
                Log.d("PlayHubLite", "search: Item encontrado: Título='$title', Link='$link', Img='$img'")
                newMovieSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries // Tipo por defecto para búsqueda, se refina en load
                    this.posterUrl = img
                }
            } else {
                Log.w("PlayHubLite", "search: Item en resultado de búsqueda no válido (título o link nulo). HTML: ${it.outerHtml()}")
                null
            }
        }
        Log.d("PlayHubLite", "search: Total de resultados de búsqueda encontrados: ${searchResults.size}")
        return searchResults
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("PlayHubLite", "load - URL de entrada: $url")

        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://" + url.removePrefix("//")
        } else {
            url
        }
        Log.d("PlayHubLite", "load - URL limpia/ajustada: $cleanUrl")

        if (cleanUrl.isBlank()) {
            Log.e("PlayHubLite", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            Log.e("PlayHubLite", "load - ERROR al obtener el documento de detalle: ${e.message}", e)
            return null
        }
        Log.d("PlayHubLite", "load - Documento de detalle obtenido. Longitud: ${doc.html().length}")


        val tvType = if (cleanUrl.contains("/series/") || doc.select("div.seasons-list").isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        Log.d("PlayHubLite", "load - Tipo detectado: $tvType")

        val posterDiv = doc.selectFirst("div[style*=\"background-image:\"]")
        val poster = posterDiv?.attr("style")
            ?.let { style -> Regex("""url\('([^']+)'\)""").find(style)?.groupValues?.get(1) }
            ?: ""
        Log.d("PlayHubLite", "load - Póster URL: $poster")


        val title = doc.selectFirst("div.movie-info h1")?.text()
            ?: doc.selectFirst("h1.text-white")?.text()
            ?: doc.selectFirst("h3.text-white")?.text()
            ?: ""
        Log.d("PlayHubLite", "load - Título: $title")


        val description = doc.selectFirst("div.synopsis p")?.text() ?: ""
        Log.d("PlayHubLite", "load - Descripción: $description")

        val tags = doc.select("div.movie-genres a").map { it.text() }
        Log.d("PlayHubLite", "load - Géneros/Tags: $tags")


        val episodes = if (tvType == TvType.TvSeries) {
            Log.d("PlayHubLite", "load - Buscando episodios para serie.")
            val seasonElements = doc.select("div.seasons-list li.season-item")
            Log.d("PlayHubLite", "load - Temporadas encontradas: ${seasonElements.size}")

            seasonElements.flatMap { seasonElement ->
                val seasonNumber = seasonElement.selectFirst("a")?.text()?.replace("Temporada ", "")?.trim()?.toIntOrNull() ?: 1
                Log.d("PlayHubLite", "load - Procesando temporada: $seasonNumber")

                val episodeElements = seasonElement.select("div.episodios ul li")
                Log.d("PlayHubLite", "load - Episodios encontrados en temp $seasonNumber: ${episodeElements.size}")

                episodeElements.mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.info h2")?.text() ?: ""

                    val episodeNumber = element.selectFirst("div.info h2")?.text()
                        ?.lowercase()
                        ?.let { text ->
                            Regex("""episodio\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        } ?: 1

                    val realimg = element.selectFirst("img")?.attr("data-src") ?: element.selectFirst("img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        Log.d("PlayHubLite", "load - Episodio válido: $epTitle (T$seasonNumber E$episodeNumber), URL: $epurl")
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson()
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realimg
                        }
                    } else {
                        Log.w("PlayHubLite", "load - Episodio inválido (título/link nulo) en T$seasonNumber. HTML: ${element.outerHtml()}")
                        null
                    }
                }
            }
        } else listOf()
        Log.d("PlayHubLite", "load - Total de episodios encontrados: ${episodes.size}")


        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
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

            TvType.Movie -> {
                newMovieLoadResponse(
                    name = title,
                    url = cleanUrl,
                    type = tvType,
                    dataUrl = cleanUrl
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            else -> null
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
            Log.e("PlayHubLite", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("PlayHubLite", "loadLinks - Data de entrada: $data")

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("PlayHubLite", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(data)
            Log.d("PlayHubLite", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("PlayHubLite", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks - ERROR al obtener el documento para cargar enlaces: ${e.message}", e)
            return false
        }
        Log.d("PlayHubLite", "loadLinks - Documento de enlaces obtenido. Longitud: ${doc.html().length}")

        val iframeSrc = doc.selectFirst("div.player-frame iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div.player-frame iframe.player-embed")?.attr("src")
            ?: doc.selectFirst("iframe[src*='streamlare.com']")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("PlayHubLite", "loadLinks - No se encontró iframe del reproductor con los selectores específicos en PlayHubLite.com.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            Log.d("PlayHubLite", "loadLinks - Buscando Streamlare en scripts. Longitud de scriptContent: ${scriptContent.length}")

            val streamlareRegex = """(https?://streamlare\.com/e/[a-zA-Z0-9]+)""".toRegex()
            val streamlareMatches = streamlareRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (streamlareMatches.isNotEmpty()) {
                Log.d("PlayHubLite", "loadLinks - Encontrados ${streamlareMatches.size} enlaces directos de Streamlare en script.")
                streamlareMatches.apmap { directUrl ->
                    Log.d("PlayHubLite", "loadLinks - Intentando cargar extractor para Streamlare: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }

            Log.d("PlayHubLite", "loadLinks - No se encontraron enlaces de video directos en scripts ni iframes.")
            return false
        }

        Log.d("PlayHubLite", "loadLinks - Iframe encontrado: $iframeSrc")

        return loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
    }
}