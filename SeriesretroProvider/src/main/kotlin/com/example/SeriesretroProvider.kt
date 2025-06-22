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
class SeriesretroProvider : MainAPI() {
    override var mainUrl = "https://seriesretro.com"
    override var name = "SeriesRetro" // Nombre más amigable para el usuario
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime, // Asumo que tienen animes
        TvType.Cartoon, // Asumo que tienen dibujos
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Últimas Películas", "$mainUrl/todas-las-peliculas/"),
            Pair("Últimas Series", "$mainUrl/lista-series/")
            // Si hay URLs directas para Anime o Cartoon, añádelas aquí:
            // Pair("Animes Recientes", "$mainUrl/animes"),
            // Pair("Dibujos Animados", "$mainUrl/dibujos")
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Últimas Películas" -> TvType.Movie
                "Últimas Series" -> TvType.TvSeries
                "Animes Recientes" -> TvType.Anime
                "Dibujos Animados" -> TvType.Cartoon
                else -> TvType.Others
            }
            val doc = app.get(url).document
            val homeItems = doc.select("ul.Movielist li.TPostMv article.TPost").mapNotNull {
                val title = it.selectFirst("h3.Title")?.text()
                val link = it.selectFirst("a")?.attr("href")
                // CAMBIO CLAVE AQUÍ: Selector de imagen corregido
                val img = it.selectFirst("div.Image figure img")?.attr("src") ?: ""
                val fixedImg = fixUrl(img)

                if (title != null && link != null) {
                    Log.d("SeriesRetro", "getMainPage Poster URL for $title: $fixedImg") // Log para depurar
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = fixedImg
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
        return doc.select("ul.Movielist li.TPostMv article.TPost").mapNotNull {
            val title = it.selectFirst("h3.Title")?.text()
            val link = it.selectFirst("a")?.attr("href")
            // CAMBIO CLAVE AQUÍ: Selector de imagen corregido
            val img = it.selectFirst("div.Image figure img")?.attr("src") ?: ""
            val fixedImg = fixUrl(img)

            if (title != null && link != null) {
                Log.d("SeriesRetro", "Search Poster URL for $title: $fixedImg") // Log para depurar
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = when {
                        link.contains("/movie/") -> TvType.Movie
                        link.contains("/serie/") -> TvType.TvSeries
                        link.contains("/anime/") -> TvType.Anime
                        link.contains("/cartoon/") -> TvType.Cartoon
                        else -> TvType.TvSeries
                    }
                    this.posterUrl = fixedImg
                }
            } else null
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("SeriesRetro", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("SeriesRetro", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("SeriesRetro", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("SeriesRetro", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("SeriesRetro", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl).document
        val tvType = when {
            cleanUrl.contains("/movie/") -> TvType.Movie
            cleanUrl.contains("/serie/") -> TvType.TvSeries
            cleanUrl.contains("/anime/") -> TvType.Anime
            cleanUrl.contains("/cartoon/") -> TvType.Cartoon
            else -> TvType.TvSeries
        }

        val title = doc.selectFirst("h1.Title")?.text() ?: ""
        // CAMBIO CLAVE AQUÍ: Selector de póster principal corregido
        val poster = doc.selectFirst("div.Image figure img")?.attr("src") ?: ""
        val fixedPoster = fixUrl(poster)
        Log.d("SeriesRetro", "load - Main Poster URL for $title: $fixedPoster")

        val description = doc.selectFirst("div.Description p")?.text() ?: ""
        val tags = listOf<String>()

        val episodes = if (tvType == TvType.TvSeries || tvType == TvType.Anime || tvType == TvType.Cartoon) {
            val episodesList = ArrayList<Episode>()
            // Selector de temporadas y episodios
            doc.select("div.wdgt.AABox").forEach { seasonElement ->
                val seasonTitle = seasonElement.selectFirst("div.Title.AA-Season span")?.text() ?: ""
                val seasonNumber = seasonTitle.toIntOrNull() ?: 0

                seasonElement.select("table tbody tr").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("td.MvTbItl a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("td.MvTbItl a")?.text() ?: ""
                    Log.d("SeriesRetro", "DEBUG - Processing episode element: ${element.html()}") // <-- AÑADIR ESTA LÍNEA
                    Log.d("SeriesRetro", "DEBUG - Extracted epTitle: '$epTitle'") // <-- AÑADIR ESTA LÍNEA

                    val episodeNumber = element.selectFirst("td span.Num")?.text()?.toIntOrNull()

                    // CAMBIO CLAVE AQUÍ: Selector de imagen de episodio corregido
                    val rawImg = element.selectFirst("td.MvTbImg.B a img")?.attr("src") ?: ""
                    val fixedEpImg = fixUrl(rawImg)
                    Log.d("SeriesRetro", "load - Episode Poster URL for $epTitle: $fixedEpImg")


                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        episodesList.add(
                            newEpisode(
                                EpisodeLoadData(epTitle, epurl).toJson()
                            ) {
                                this.name = epTitle
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.posterUrl = fixedEpImg
                            }
                        )
                    } else null
                }
            }
            episodesList
        } else listOf()

        return when (tvType) {
            TvType.TvSeries, TvType.Anime, TvType.Cartoon -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = cleanUrl,
                    type = tvType,
                    episodes = episodes,
                ) {
                    this.posterUrl = fixedPoster
                    this.backgroundPosterUrl = fixedPoster
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
                    this.posterUrl = fixedPoster
                    this.backgroundPosterUrl = fixedPoster
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
            Log.e("SeriesRetro", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SeriesRetro", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("SeriesRetro", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("SeriesRetro", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("SeriesRetro", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("SeriesRetro", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("SeriesRetro", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl).document
        val iframeSrc = doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("SeriesRetro", "No se encontró iframe del reproductor con el selector específico en SeriesRetro.com. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    Log.d("SeriesRetro", "Encontrado enlace directo en script de página principal: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("SeriesRetro", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("SeriesRetro", "Iframe encontrado: $iframeSrc")

        // --- LÓGICA PRINCIPAL: Manejar diferentes dominios de iframes ---

        // 1. Manejar Xupalace.org
        if (iframeSrc.contains("xupalace.org")) {
            Log.d("SeriesRetro", "loadLinks - Detectado Xupalace.org iframe: $iframeSrc")
            val xupalaceDoc = try {
                app.get(fixUrl(iframeSrc)).document
            } catch (e: Exception) {
                Log.e("SeriesRetro", "Error al obtener el contenido del iframe de Xupalace ($iframeSrc): ${e.message}")
                return false
            }
            val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
            val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")
            val foundXupalaceLinks = mutableListOf<String>()
            for (element in elementsWithOnclick) {
                val onclickAttr = element.attr("onclick")
                val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)
                if (matchPlayerUrl != null) {
                    val videoUrl = matchPlayerUrl.groupValues[1]
                    val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                    Log.d("SeriesRetro", "Xupalace: Encontrado servidor '$serverName' con URL: $videoUrl")
                    if (videoUrl.isNotBlank()) {
                        foundXupalaceLinks.add(videoUrl)
                    }
                } else {
                    Log.w("SeriesRetro", "Xupalace: No se pudo extraer la URL del onclick: $onclickAttr")
                }
            }
            if (foundXupalaceLinks.isNotEmpty()) {
                foundXupalaceLinks.apmap { playerUrl ->
                    Log.d("SeriesRetro", "Cargando extractor para link de Xupalace: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SeriesRetro", "No se encontraron enlaces de video de Xupalace.org.")
                return false
            }
        }
        // 2. Manejar re.sololatino.net/embed.php
        else if (iframeSrc.contains("re.sololatino.net/embed.php")) {
            Log.d("SeriesRetro", "loadLinks - Detectado re.sololatino.net/embed.php iframe: $iframeSrc")
            val embedDoc = try {
                app.get(fixUrl(iframeSrc)).document
            } catch (e: Exception) {
                Log.e("SeriesRetro", "Error al obtener el contenido del iframe de re.sololatino.net ($iframeSrc): ${e.message}")
                return false
            }
            val regexGoToPlayerUrl = Regex("""go_to_player\('([^']+)'\)""")
            val elementsWithOnclick = embedDoc.select("*[onclick*='go_to_player']")
            val foundReSoloLatinoLinks = mutableListOf<String>()
            for (element in elementsWithOnclick) {
                val onclickAttr = element.attr("onclick")
                val matchPlayerUrl = regexGoToPlayerUrl.find(onclickAttr)
                if (matchPlayerUrl != null) {
                    val videoUrl = matchPlayerUrl.groupValues[1]
                    val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                    Log.d("SeriesRetro", "re.sololatino.net: Encontrado servidor '$serverName' con URL: $videoUrl")
                    if (videoUrl.isNotBlank()) {
                        foundReSoloLatinoLinks.add(videoUrl)
                    }
                } else {
                    Log.w("SeriesRetro", "re.sololatino.net: No se pudo extraer la URL del onclick: $onclickAttr")
                }
            }
            if (foundReSoloLatinoLinks.isNotEmpty()) {
                foundReSoloLatinoLinks.apmap { playerUrl ->
                    Log.d("SeriesRetro", "Cargando extractor para link de re.sololatino.net: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SeriesRetro", "No se encontraron enlaces de video de re.sololatino.net/embed.php.")
                return false
            }
        }
        // 3. Manejar embed69.org (Lógica de dataLink existente)
        else if (iframeSrc.contains("embed69.org")) {
            Log.d("SeriesRetro", "loadLinks - Detectado embed69.org iframe: $iframeSrc")
            val frameDoc = try {
                app.get(fixUrl(iframeSrc)).document
            } catch (e: Exception) {
                Log.e("SeriesRetro", "Error al obtener el contenido del iframe ($iframeSrc): ${e.message}")
                return false
            }

            val scriptContent = frameDoc.select("script").map { it.html() }.joinToString("\n")

            val dataLinkRegex = """const dataLink = (\[.*?\]);""".toRegex()
            val dataLinkJsonString = dataLinkRegex.find(scriptContent)?.groupValues?.get(1)

            if (dataLinkJsonString.isNullOrBlank()) {
                Log.e("SeriesRetro", "No se encontró la variable dataLink en el script de embed69.org.")
                return false
            }

            Log.d("SeriesRetro", "dataLink JSON string encontrado: $dataLinkJsonString")

            val dataLinkEntries = tryParseJson<List<DataLinkEntry>>(dataLinkJsonString)

            if (dataLinkEntries.isNullOrEmpty()) {
                Log.e("SeriesRetro", "Error al parsear dataLink JSON o está vacío.")
                return false
            }

            val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE" // Asegúrate que esta sea la clave correcta para SeriesRetro

            val foundEmbed69Links = mutableListOf<String>()
            for (entry in dataLinkEntries) {
                for (embed in entry.sortedEmbeds) {
                    if (embed.type == "video") {
                        val decryptedLink = decryptLink(embed.link, secretKey)
                        if (decryptedLink != null) {
                            Log.d("SeriesRetro", "Link desencriptado para ${embed.servername}: $decryptedLink")
                            foundEmbed69Links.add(decryptedLink)
                        }
                    }
                }
            }

            if (foundEmbed69Links.isNotEmpty()) {
                foundEmbed69Links.apmap { playerUrl ->
                    Log.d("SeriesRetro", "Cargando extractor para link desencriptado (embed69.org): $playerUrl")
                    loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SeriesRetro", "No se encontraron enlaces de video desencriptados de embed69.org.")
                return false
            }
        }
        else {
            Log.w("SeriesRetro", "Tipo de iframe desconocido o no manejado: $iframeSrc")
            return false
        }
    }
}