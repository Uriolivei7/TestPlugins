package com.example // ¡MUY IMPORTANTE! Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.util.Log
import com.lagradost.cloudstream3.*
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

// ¡CRÍTICO! Añadir esta anotación para que el plugin sea reconocido por CloudStream
class GnulaProvider : MainAPI() {
    override var mainUrl = "https://gnula.life"
    override var name = "Gnula" // Nombre más amigable para el usuario
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        // TvType.Anime, //
        // TvType.Cartoon,
    )

    override var lang = "es" // Asumiendo que es español

    override val hasMainPage = true
    override val hasChromecastSupport = true //
    override val hasDownloadSupport = true //

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        //
        // y los nombres de las secciones (ej. "/peliculas", "/series").
        val urls = listOf(
            Pair("Películas Destacadas", "$mainUrl/peliculas"),
            Pair("Series Populares", "$mainUrl/series"),
            //
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Películas Destacadas" -> TvType.Movie
                "Series Populares" -> TvType.TvSeries
                else -> TvType.Movie // Por defecto
            }
            val doc = app.get(url).document

            //
            // Los selectores de SoloLatino (div.items article.item) PROBABLEMENTE NO FUNCIONARÁN AQUÍ.
            val homeItems = doc.select("TU_SELECTOR_PARA_CADA_ITEM").mapNotNull {
                val title = it.selectFirst("TU_SELECTOR_PARA_EL_TITULO")?.text()
                val link = it.selectFirst("TU_SELECTOR_PARA_EL_ENLACE_DEL_ITEM")?.attr("href")
                val img = it.selectFirst("TU_SELECTOR_PARA_LA_IMAGEN_DEL_POSTER")?.attr("data-src") // O "src", depende del lazyload

                if (title != null && link != null) {
                    newAnimeSearchResponse(
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
        //
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document

        //
        // Los selectores de SoloLatino (div.items article.item) PROBABLEMENTE NO FUNCIONARÁN AQUÍ.
        return doc.select("TU_SELECTOR_PARA_LOS_RESULTADOS_DE_BUSQUEDA").mapNotNull {
            val title = it.selectFirst("TU_SELECTOR_PARA_EL_TITULO_EN_BUSQUEDA")?.text()
            val link = it.selectFirst("TU_SELECTOR_PARA_EL_ENLACE_EN_BUSQUEDA")?.attr("href")
            val img = it.selectFirst("TU_SELECTOR_PARA_LA_IMAGEN_EN_BUSQUEDA")?.attr("data-src") ?: it.selectFirst("TU_SELECTOR_PARA_LA_IMAGEN_EN_BUSQUEDA")?.attr("src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries //
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

        // La lógica de limpieza de URL de SoloLatino puede ser relevante aquí, si Gnula.life usa JSON
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
        //
        val tvType = if (cleanUrl.contains("/series/")) TvType.TvSeries else TvType.Movie // Ejemplo: asume TvSeries si la URL contiene "/series/"

        //
        val title = doc.selectFirst("TU_SELECTOR_PARA_EL_TITULO_EN_LOAD")?.text() ?: ""
        val poster = doc.selectFirst("TU_SELECTOR_PARA_EL_POSTER_EN_LOAD")?.attr("src") ?: ""
        val description = doc.selectFirst("TU_SELECTOR_PARA_LA_DESCRIPCION_EN_LOAD")?.text() ?: ""
        val tags = doc.select("TU_SELECTOR_PARA_LAS_ETIQUETAS_O_GENEROS a").map { it.text() } // Si existen

        val episodes = if (tvType == TvType.TvSeries) {
            //
            // La estructura de temporadas y episodios de gnula.life será muy diferente a la de SoloLatino.
            // Debes inspeccionar a fondo una página de serie en gnula.life para encontrar:
            // 1. Un selector para cada elemento de temporada.
            // 2. Dentro de cada temporada, un selector para cada elemento de episodio.
            // 3. Dentro de cada episodio, selectores para el enlace, título, número de temporada/episodio, y póster (si lo hay).
            doc.select("TU_SELECTOR_PARA_CADA_TEMPORADA").flatMap { seasonElement ->
                seasonElement.select("TU_SELECTOR_PARA_CADA_EPISODIO_DENTRO_DE_TEMPORADA").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("TU_SELECTOR_PARA_ENLACE_EPISODIO")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("TU_SELECTOR_PARA_TITULO_EPISODIO")?.text() ?: ""

                    //
                    // Busca texto como "S1 E1", "T1:E1", o atributos data-season/data-episode.
                    val seasonNumber = element.selectFirst("TU_SELECTOR_PARA_NUMERO_TEMPORADA")?.text()?.toIntOrNull() // O extraer con regex
                    val episodeNumber = element.selectFirst("TU_SELECTOR_PARA_NUMERO_EPISODIO")?.text()?.toIntOrNull() // O extraer con regex

                    val realimg = element.selectFirst("TU_SELECTOR_PARA_POSTER_EPISODIO")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
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

    // Las Data Classes SortedEmbed y DataLinkEntry y la función decryptLink
    // NO son necesarias para Gnula.life si no usa embed69.org o una lógica de encriptación similar.
    // Las dejo comentadas o eliminadas para evitar confusión, ya que el iframe de Gnula es diferente.
    /*
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
        // ... (Tu código de desencriptación de SoloLatino, no aplicable aquí)
        return null
    }
    */

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

        // TODO: Basado en image_b5c890.png, el selector para el iframe es diferente.
        // Aquí buscamos el iframe del reproductor de gnula.life
        val iframeSrc = doc.selectFirst("div.player_playerInside_ISJ3F iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.e("GnulaLife", "No se encontró el iframe del reproductor en la página de Gnula.life.")
            // TODO: Si hay reproductores directos en scripts, búscalos aquí.
            // Ejemplo de búsqueda en scripts (como en SoloLatino, pero la regex puede ser diferente):
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex() // Revisa si esta regex es adecuada para gnula.life
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

        // 2. Hacer una petición al src del iframe (player.gnula.life) para obtener su contenido
        val playerFrameDoc = try {
            app.get(fixUrl(iframeSrc)).document
        } catch (e: Exception) {
            Log.e("GnulaLife", "Error al obtener el contenido del iframe del reproductor de Gnula.life ($iframeSrc): ${e.message}")
            return false
        }

        //
        // NO ES embed69.org, así que la lógica de desencriptación NO SE APLICA AQUÍ.
        // Debes inspeccionar el HTML y los scripts de la página cargada por el iframeSrc
        // (por ejemplo: https://player.gnula.life/player.php?h=...)
        // Busca si los enlaces de video están:
        // A) Directamente en un tag <source> dentro de un <video>.
        // B) En un script, quizás en una variable JavaScript (URL directa o URL encriptada con otra lógica).
        // C) Generados por un JavaScript más complejo (como el vast-player-v4.js de embed69, pero sin encriptación AES si no la usa).

        // Ejemplo de búsqueda de enlaces de video directos en <source> tags:
        playerFrameDoc.select("video source").mapNotNull { it.attr("src") }.apmap { videoSrc ->
            Log.d("GnulaLife", "Enlace de video directo encontrado en player.gnula.life: $videoSrc")
            // Asume que CloudStream puede manejar directamente estas URLs si son de un extractor conocido
            loadExtractor(fixUrl(videoSrc), iframeSrc, subtitleCallback, callback)
        }

        // Ejemplo de búsqueda de enlaces en scripts (si no están directos en <video>):
        val playerScriptContent = playerFrameDoc.select("script").map { it.html() }.joinToString("\n")
        //
        // Podría ser algo como 'file: "https://ejemplo.com/video.mp4"', 'source: "https://ejemplo.com/video.m3u8"'
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