package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

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

// ¡CRÍTICO! Añadir esta anotación para que el plugin sea reconocido por CloudStream
class PlayhubProvider : MainAPI() {
    override var mainUrl = "https://playhublite.com"
    override var name = "PlayHub" // Nombre más amigable para el usuario
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
        val doc = app.get(mainUrl).document // Obtener el documento de la página principal

        // Iterar a través de las secciones de "Estrenos", "Top del día", etc.
        doc.select("div.px-2").forEach { section -> // div.px-2 parece ser el contenedor de cada sección
            val sectionTitle = section.selectFirst("span.text-white.text-2xl.font-bold")?.text()
            if (sectionTitle != null && sectionTitle.isNotBlank()) {
                val homeItems = section.select("div.swiper-wrapper div.swiper-slide").mapNotNull { slide ->
                    // *** ESTA ES LA PARTE CLAVE: NECESITAMOS LA ESTRUCTURA INTERNA DE swiper-slide ***
                    // Asumo que la estructura es similar a la que tenías para búsqueda/categorías
                    val link = slide.selectFirst("a")?.attr("href") // Asumo que hay un <a> directo
                    val title = slide.selectFirst("div.info h2")?.text() // Asumo que hay un div.info h2
                    val img = slide.selectFirst("img")?.attr("data-src") ?: slide.selectFirst("img")?.attr("src") // Asumo que hay un <img>

                    if (title != null && link != null) {
                        newMovieSearchResponse(
                            title,
                            fixUrl(link)
                        ) {
                            // Determinar el tipo (Movie/TvSeries) puede ser más complejo aquí
                            // Sin el HTML interno del slide, lo dejo en TvType.Movie por defecto
                            this.type = TvType.Movie // O podrías inferirlo del link (ej. si contiene /series/)
                            this.posterUrl = img
                        }
                    } else null
                }
                if (homeItems.isNotEmpty()) {
                    items.add(HomePageList(sectionTitle, homeItems))
                }
            }
        }

        // Si aún quieres incluir las secciones /peliculas y /series por separado, puedes mantener la lógica anterior
        // o si la página principal no lista todo el contenido.
        // Por ahora, estoy priorizando la extracción de la página principal.
        // Si no aparecen, podrías volver a la lógica anterior o combinar ambas.

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        // Selector para los elementos de búsqueda en PlayHubLite
        return doc.select("div.peliculas-content div.item-pelicula").mapNotNull {
            val title = it.selectFirst("div.info h2")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")

            if (title != null && link != null) {
                newMovieSearchResponse( // Usamos newMovieSearchResponse por defecto, se ajustará en load
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries // Tipo por defecto para búsqueda, se refina en load
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

        val doc = app.get(cleanUrl).document

        val tvType = if (cleanUrl.contains("/series/") || doc.select("div.seasons-list").isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        val posterDiv = doc.selectFirst("div[style*=\"background-image:\"]")
        val poster = posterDiv?.attr("style")
            ?.let { style -> Regex("""url\('([^']+)'\)""").find(style)?.groupValues?.get(1) }
            ?: ""

        val title = doc.selectFirst("div.movie-info h1")?.text()
            ?: doc.selectFirst("h1.text-white")?.text()
            ?: doc.selectFirst("h3.text-white")?.text()
            ?: ""

        val description = doc.selectFirst("div.synopsis p")?.text() ?: ""
        val tags = doc.select("div.movie-genres a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div.seasons-list li.season-item").flatMap { seasonElement ->
                val seasonNumber = seasonElement.selectFirst("a")?.text()?.replace("Temporada ", "")?.trim()?.toIntOrNull() ?: 1
                Log.d("PlayHubLite", "Procesando temporada: $seasonNumber")

                seasonElement.select("div.episodios ul li").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.info h2")?.text() ?: ""

                    val episodeNumber = element.selectFirst("div.info h2")?.text()
                        ?.lowercase()
                        ?.let { text ->
                            Regex("""episodio\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        } ?: 1

                    val realimg = element.selectFirst("img")?.attr("data-src") ?: element.selectFirst("img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        Log.d("PlayHubLite", "Episodio encontrado: $epTitle, URL: $epurl, Temp: $seasonNumber, Ep: $episodeNumber")
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson()
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realimg
                        }
                    } else null
                }
            }
        } else listOf()

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

        val doc = app.get(targetUrl).document

        val iframeSrc = doc.selectFirst("div.player-frame iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div.player-frame iframe.player-embed")?.attr("src")
            ?: doc.selectFirst("iframe[src*='streamlare.com']")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("PlayHubLite", "No se encontró iframe del reproductor con los selectores específicos en PlayHubLite.com.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val streamlareRegex = """(https?://streamlare\.com/e/[a-zA-Z0-9]+)""".toRegex()
            val streamlareMatches = streamlareRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (streamlareMatches.isNotEmpty()) {
                streamlareMatches.apmap { directUrl ->
                    Log.d("PlayHubLite", "Encontrado enlace directo de Streamlare en script: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }

            Log.d("PlayHubLite", "No se encontraron enlaces de video directos en scripts.")
            return false
        }

        Log.d("PlayHubLite", "Iframe encontrado: $iframeSrc")

        return loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
    }
}