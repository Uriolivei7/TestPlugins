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

// ¡CRÍTICO! Añadir esta anotación para que el plugin sea reconocido por CloudStream
class PelisplusProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.bz"
    override var name = "Pelisplus" // Nombre más amigable para el usuario
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
        val urls = listOf(
            Pair("Películas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Doramas", "$mainUrl/generos/dorama"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            Log.d("Pelisplus", "getMainPage - Procesando categoría: $name desde URL: $url")

            val tvType = when (name) {
                "Películas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Doramas" -> TvType.TvSeries
                else -> TvType.Others
            }
            val doc = try {
                app.get(url).document
            } catch (e: Exception) {
                Log.e("Pelisplus", "getMainPage - ERROR al obtener documento de $url: ${e.message}", e)
                return@apmap null
            }

            Log.d("Pelisplus", "getMainPage - Documento obtenido para $name. Intentando seleccionar posters con nuevo selector.")
            val homeItems = doc.select("div.Posters a.Posters-link").mapNotNull { element ->
                val title = element.attr("data-title")
                val link = element.attr("href")
                val img = element.selectFirst("img.Posters-img")?.attr("src")

                if (title.isNullOrBlank() || link.isNullOrBlank()) {
                    Log.d("Pelisplus", "getMainPage - Elemento de poster sin título o link. HTML: ${element.html()}")
                    null
                } else {
                    val fixedLink = fixUrl(link)
                    val fixedImg = fixUrl(img ?: "")

                    Log.d("Pelisplus", "getMainPage - Encontrado: Título=$title, Link=$fixedLink, Img=$fixedImg")
                    newAnimeSearchResponse(
                        title,
                        fixedLink
                    ) {
                        this.type = tvType
                        this.posterUrl = fixedImg
                    }
                }
            }
            if (homeItems.isEmpty()) {
                Log.w("Pelisplus", "getMainPage - No se encontraron items para la categoría $name en la URL $url con el nuevo selector.")
            } else {
                Log.d("Pelisplus", "getMainPage - Encontrados ${homeItems.size} items para la categoría $name.")
            }
            HomePageList(name, homeItems)
        }

        items.addAll(homePageLists.filterNotNull())

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val doc = app.get(url).document

        return doc.select("div.Posters a.Posters-link").mapNotNull {
            val title = it.attr("data-title")
            val link = it.attr("href")
            val img = it.selectFirst("img.Posters-img")?.attr("src")

            if (title.isNullOrBlank() || link.isNullOrBlank()) {
                Log.d("Pelisplus", "Search - Elemento de poster sin título o link en búsqueda. HTML: ${it.html()}")
                null
            } else {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries
                    this.posterUrl = img
                }
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Pelisplus", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("Pelisplus", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("Pelisplus", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("Pelisplus", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("Pelisplus", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl).document
        val tvType = if (cleanUrl.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("h1.m-b-5")?.text() ?: ""
        val poster = doc.selectFirst("img.img-fluid")?.attr("src") ?: ""
        val description = doc.selectFirst("div.text-large")?.text() ?: ""
        val tags = doc.select("a[title^=Películas del Genero]").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div.tab-content div.tab-pane a.btn.btn-primary.btn-block").mapNotNull { element ->
                val epurl = fixUrl(element.attr("href") ?: "")
                val fullEpTitle = element.text() ?: ""

                var seasonNumber: Int? = null
                var episodeNumber: Int? = null
                var epTitle: String = fullEpTitle

                val regex = Regex("""T(\d+)\s*-\s*E(\d+):\s*(.*)""")
                val match = regex.find(fullEpTitle)

                if (match != null) {
                    seasonNumber = match.groupValues[1].toIntOrNull()
                    episodeNumber = match.groupValues[2].toIntOrNull()
                    epTitle = match.groupValues[3].trim()
                } else {
                    val simpleEpRegex = Regex("""Episodio\s*(\d+)""")
                    val simpleMatch = simpleEpRegex.find(fullEpTitle)
                    if (simpleMatch != null) {
                        episodeNumber = simpleMatch.groupValues[1].toIntOrNull()
                    }
                    if (seasonNumber == null) {
                        val activeSeasonTab = doc.selectFirst("ul.tbVideoNv.nav-tabs li a.nav-link.active")
                        val seasonText = activeSeasonTab?.text()
                        val seasonRegex = Regex("""TEMPORADA\s*(\d+)""")
                        val seasonMatch = seasonRegex.find(seasonText ?: "")
                        seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    }
                }

                val realimg = poster

                if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                    Log.d("Pelisplus", "load (Series) - Encontrado episodio: Título=$epTitle, URL=$epurl, Temporada=$seasonNumber, Episodio=$episodeNumber")
                    newEpisode(
                        EpisodeLoadData(epTitle, epurl).toJson()
                    ) {
                        this.name = epTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = realimg
                    }
                } else {
                    Log.d("Pelisplus", "load (Series) - Elemento de episodio sin URL o título. HTML: ${element.html()}")
                    null
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
            Log.e("Pelisplus", "Error al descifrar link de Embed69: ${e.message}", e)
            return null
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Pelisplus", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("Pelisplus", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("Pelisplus", "loadLinks - Regex inicial no encontró coincidencia. Usando data original/ajustada: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Pelisplus", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("Pelisplus", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Pelisplus", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl).document

        // --- LÓGICA PARA ENCONTRAR LAS URLs DE REPRODUCTOR INICIALES ---
        val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

        var foundPlayerUrl = false

        // Intentar encontrar video[1] y video[2]
        val videoUrls = mutableMapOf<Int, String>()
        val videoVarRegex = Regex("""video\[(\d+)\]\s*=\s*['"](https?:\/\/[^"']+)['"]""")
        videoVarRegex.findAll(scriptContent).forEach { matchResult ->
            val index = matchResult.groupValues[1].toIntOrNull()
            val url = matchResult.groupValues[2]
            if (index != null && url.isNotBlank()) {
                videoUrls[index] = url
                Log.d("Pelisplus", "Found video[$index] URL in script: $url")
            }
        }

        // Priorizar video[1] si existe, luego video[2]
        val playerUrlsToProcess = mutableListOf<String>()

        videoUrls[1]?.let { playerUrlsToProcess.add(it) }
        videoUrls[2]?.let { playerUrlsToProcess.add(it) } // Añadir UQload/uqlink.php

        if (playerUrlsToProcess.isEmpty()) {
            Log.d("Pelisplus", "No se encontraron URLs de reproductor en variables video[1] o video[2].")
            // Intenta encontrar iframe directamente en el HTML como respaldo (menos común para Pelisplus)
            val iframeSrc = doc.selectFirst("iframe")?.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                Log.d("Pelisplus", "Iframe encontrado directamente en el HTML (respaldo): $iframeSrc")
                playerUrlsToProcess.add(iframeSrc)
            }
        }

        if (playerUrlsToProcess.isEmpty()) {
            Log.d("Pelisplus", "FINALMENTE: No se encontró URL del reproductor en Pelisplus con ningún método.")
            return false
        }

        // --- PROCESAR CADA URL DE REPRODUCTOR ENCONTRADA ---
        for (iframeSrc in playerUrlsToProcess) {
            Log.d("Pelisplus", "Procesando URL de reproductor: $iframeSrc")
            foundPlayerUrl = true

            if (iframeSrc.contains("embed69.org")) {
                Log.d("Pelisplus", "loadLinks - Detectado embed69.org URL: $iframeSrc")
                val frameDoc = try {
                    app.get(fixUrl(iframeSrc), referer = targetUrl).document
                } catch (e: Exception) {
                    Log.e("Pelisplus", "Error al obtener el contenido del reproductor de embed69.org ($iframeSrc): ${e.message}")
                    continue // Continúa con el siguiente reproductor si falla este
                }

                val scriptContentEmbed = frameDoc.select("script").map { it.html() }.joinToString("\n")

                val dataLinkRegex = """const dataLink = (\[.*?\]);""".toRegex()
                val dataLinkJsonString = dataLinkRegex.find(scriptContentEmbed)?.groupValues?.get(1)

                if (dataLinkJsonString.isNullOrBlank()) {
                    Log.e("Pelisplus", "No se encontró la variable dataLink en el script de embed69.org.")
                    continue
                }

                Log.d("Pelisplus", "dataLink JSON string encontrado (Embed69): $dataLinkJsonString")

                val dataLinkEntries = tryParseJson<List<DataLinkEntry>>(dataLinkJsonString)

                if (dataLinkEntries.isNullOrEmpty()) {
                    Log.e("Pelisplus", "Error al parsear dataLink JSON o está vacío (Embed69).")
                    continue
                }

                val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"

                var foundEmbed69Links = false
                for (entry in dataLinkEntries) {
                    for (embed in entry.sortedEmbeds) {
                        if (embed.type == "video") {
                            val decryptedLink = decryptLink(embed.link, secretKey)
                            if (decryptedLink != null) {
                                Log.d("Pelisplus", "Link desencriptado para ${embed.servername}: $decryptedLink")
                                foundEmbed69Links = true
                                loadExtractor(fixUrl(decryptedLink), iframeSrc, subtitleCallback, callback)
                            }
                        }
                    }
                }

                if (!foundEmbed69Links) {
                    Log.d("Pelisplus", "No se encontraron enlaces de video desencriptados de embed69.org.")
                }
                // Si encontramos links, consideramos que este reproductor fue exitoso.
                if (foundEmbed69Links) return true

            } else if (iframeSrc.contains("xupalace.org/video/") || iframeSrc.contains("xupalace.org/uqlink.php")) {
                // Bloque para manejar ambas URLs de Xupalace
                Log.d("Pelisplus", "loadLinks - Detectado URL de Xupalace: $iframeSrc")

                val xupalaceDoc = try {
                    app.get(fixUrl(iframeSrc), referer = targetUrl).document
                } catch (e: Exception) {
                    Log.e("Pelisplus", "Error al obtener el contenido del reproductor de Xupalace ($iframeSrc): ${e.message}")
                    continue
                }

                var foundXupalaceVideoLinks = false

                if (iframeSrc.contains("xupalace.org/video/")) {
                    val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
                    val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

                    if (elementsWithOnclick.isEmpty()) {
                        Log.w("Pelisplus", "No se encontraron elementos con 'go_to_playerVast' en xupalace.org/video/.")
                    } else {
                        for (element in elementsWithOnclick) {
                            val onclickAttr = element.attr("onclick")
                            val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)

                            if (matchPlayerUrl != null) {
                                val videoUrl = matchPlayerUrl.groupValues[1]
                                Log.d("Pelisplus", "Xupalace/video: Encontrado URL: $videoUrl")
                                if (videoUrl.isNotBlank()) {
                                    foundXupalaceVideoLinks = true
                                    loadExtractor(fixUrl(videoUrl), iframeSrc, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                }

                if (iframeSrc.contains("xupalace.org/uqlink.php")) {
                    Log.d("Pelisplus", "Xupalace: Intentando extraer UQload link de uqlink.php")
                    // Buscar patrón para UQload en el HTML/JS de uqlink.php
                    // El patrón típico es un iframe o un script con la URL de uqload.com/embed/...
                    val uqloadLinkRegex = Regex("""(https?://uqload\.com/embed/[^"']+)""")
                    val uqloadMatch = uqloadLinkRegex.find(xupalaceDoc.html()) // Buscar en todo el HTML
                    if (uqloadMatch != null) {
                        val uqloadEmbedUrl = uqloadMatch.groupValues[1]
                        Log.d("Pelisplus", "UQload: Encontrado embed URL: $uqloadEmbedUrl")
                        if (uqloadEmbedUrl.isNotBlank()) {
                            foundXupalaceVideoLinks = true
                            loadExtractor(fixUrl(uqloadEmbedUrl), iframeSrc, subtitleCallback, callback)
                        }
                    } else {
                        Log.w("Pelisplus", "UQload: No se encontró URL de embed en uqlink.php.")
                    }
                }

                if (!foundXupalaceVideoLinks) {
                    Log.d("Pelisplus", "No se encontraron enlaces de video de Xupalace/video o UQload en $iframeSrc.")
                }
                // Si encontramos links, consideramos que este reproductor fue exitoso.
                if (foundXupalaceVideoLinks) return true

            } else {
                Log.w("Pelisplus", "Tipo de reproductor desconocido o no manejado directamente en Pelisplus: $iframeSrc. Intentando loadExtractor genérico.")
                // Si se llega aquí, se intenta cargar con un extractor genérico de Cloudstream.
                val success = loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
                if (success) return true // Si el genérico funciona, terminar
            }
        }
        // Si no se encontró ningún reproductor funcional después de iterar por todos
        return false
    }
}