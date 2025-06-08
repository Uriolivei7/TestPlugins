package com.example // ¡MUY IMPORTANTE! Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo.

import android.util.Log
import com.lagradost.cloudstream3.* // Importa todas las clases de Cloudstream3 necesarias
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64 // Necesario si Gnula.life tuviera su propia desencriptación Base64, pero por ahora no se usa para embed69
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

// ¡CRÍTICO! Esta clase NO LLEVA @CloudstreamPlugin. Esa anotación va en GnulaPlugin.kt
class GnulaProvider : MainAPI() {
    override var mainUrl = "https://gnula.life"
    override var name = "Gnula"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val urls = listOf(
            Pair("Películas Destacadas", "$mainUrl/peliculas"),
            Pair("Series Populares", "$mainUrl/series"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Películas Destacadas" -> TvType.Movie
                "Series Populares" -> TvType.TvSeries
                else -> TvType.Movie
            }
            val doc = app.get(url).document

            // SELECTORES PARA getMainPage (Basado en patrones comunes de gnula.life. ¡CONFIRMA EN EL NAVEGADOR!)
            val homeItems = doc.select("div.items article.item").mapNotNull {
                val title = it.selectFirst("div.title")?.text() // Conjetura: Título dentro de un div.title
                val link = it.selectFirst("a")?.attr("href") // Conjetura: Enlace en un tag <a>
                val img = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src") // Conjetura: Imagen en <img> (data-src o src)

                if (title != null && link != null) {
                    newMovieSearchResponse( // Usar el tipo de respuesta adecuado según la sección
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else null
            }
            HomePageList(name, homeItems)
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document

        // SELECTORES PARA search (Basado en patrones comunes de gnula.life. ¡CONFIRMA EN EL NAVEGADOR!)
        // Generalmente son los mismos selectores que en la página principal para los ítems.
        return doc.select("div.items article.item").mapNotNull {
            val title = it.selectFirst("div.title")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")

            if (title != null && link != null) {
                newMovieSearchResponse( // Puedes usar Movie o TvSeries aquí, o intentar detectarlo
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.Movie // Por defecto, ajusta si hay forma de saber si es serie
                    this.posterUrl = img
                }
            } else null
        }
    }

    // Data class para pasar datos a newEpisode y loadLinks cuando es un episodio
    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("GnulaLife", "load - URL de entrada: $url")

        // Limpieza de URL
        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("GnulaLife", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("GnulaLife", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("GnulaLife", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("GnulaLife", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl).document
        // Determina el tipo de TV (película o serie)
        val tvType = if (cleanUrl.contains("/series/")) TvType.TvSeries else TvType.Movie

        // SELECTORES PARA load (CONFIRMADOS CON TUS IMÁGENES de película y serie)
        val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
        // Utiliza el selector de atributo `class*=` para mayor robustez ante cambios en el hash de la clase
        val poster = doc.selectFirst("div[class*=Info_image_] img")?.attr("data-src") ?: doc.selectFirst("div[class*=Info_image_] img")?.attr("src") ?: ""
        // La descripción está en un div.row bajo un contenedor que termina en _wrapper
        val description = doc.selectFirst("div[class*=description_text_wrapper] div.row")?.text() ?: ""
        // Conjetura para tags/géneros: busca enlaces <a> dentro de un div con clase que contenga "Info_extra_"
        val tags = doc.select("div[class*=Info_extra_] a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            // SELECTORES DE EPISODIOS BASADOS EN image_a7ac5a.png
            // Nota: Jsoup solo verá los episodios de la temporada cargada inicialmente.
            // Para obtener todas las temporadas, se requeriría una lógica de API JS o peticiones adicionales.
            doc.select("div.serieBlockListEpisodes_selector_RwIbM + div.row.row-cols-xl-4.row-cols-lg-3.row-cols-2 div.col").mapNotNull { element ->
                val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "") // Enlace del episodio en <a>
                val epTitle = element.selectFirst("div.title")?.text() ?: "" // Título del episodio en div.title

                // Extraer número de temporada y episodio (conjetura, verifica cómo se formatea en Gnula.life)
                // Puede que necesites ajustar la Regex o buscar en atributos data-
                val seasonEpisodeMatch = Regex("""T(\d+)\s*E(\d+)""").find(epTitle)
                val seasonNumber = seasonEpisodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episodeNumber = seasonEpisodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

                // La imagen del episodio (si existe), asegura que no sea nula
                val realimg = element.selectFirst("img")?.attr("data-src") ?: element.selectFirst("img")?.attr("src") ?: ""

                if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                    newEpisode(
                        EpisodeLoadData(epTitle, epurl).toJson()
                    ) {
                        this.name = epTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = realimg // Asegura que realimg sea String no nulo
                    }
                } else null
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("GnulaLife", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("GnulaLife", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("GnulaLife", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("GnulaLife", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("GnulaLife", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("GnulaLife", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl).document

        // Selector para el iframe del reproductor
        val iframeSrc = doc.selectFirst("div.player_playerInside_ISJ3F iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.e("GnulaLife", "No se encontró el iframe del reproductor en la página de Gnula.life.")
            // Intenta buscar enlaces directos en scripts como fallback
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("GnulaLife", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("GnulaLife", "Iframe del reproductor de Gnula.life encontrado: $iframeSrc")

        val playerFrameDoc = try {
            app.get(fixUrl(iframeSrc)).document
        } catch (e: Exception) {
            Log.e("GnulaLife", "Error al obtener el contenido del iframe del reproductor de Gnula.life ($iframeSrc): ${e.message}")
            return false
        }

        // 1. Búsqueda de enlaces de video directos en <source> tags dentro del iframe
        playerFrameDoc.select("video source").mapNotNull { it.attr("src") }.apmap { videoSrc ->
            Log.d("GnulaLife", "Enlace de video directo encontrado en player.gnula.life: $videoSrc")
            loadExtractor(fixUrl(videoSrc), iframeSrc, subtitleCallback, callback)
        }

        // 2. Búsqueda de enlaces en scripts dentro del iframe (si no están directos en <video>)
        val playerScriptContent = playerFrameDoc.select("script").map { it.html() }.joinToString("\n")
        val videoLinkRegex = """['"](https?:\/\/[^'"]+\.(?:mp4|m3u8|avi|mkv|mov|flv|webm)\b)['"]""".toRegex()
        val videoLinks = videoLinkRegex.findAll(playerScriptContent).map { it.groupValues[1] }.toList()

        if (videoLinks.isNotEmpty()) {
            videoLinks.apmap { videoUrl ->
                Log.d("GnulaLife", "Enlace de video encontrado en script de player.gnula.life: $videoUrl")
                loadExtractor(fixUrl(videoUrl), iframeSrc, subtitleCallback, callback)
            }
            return true
        } else {
            Log.d("GnulaLife", "No se encontraron enlaces de video en player.gnula.life.")
            return false
        }
    }
}