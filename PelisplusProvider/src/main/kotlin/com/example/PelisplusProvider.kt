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
            val tvType = when (name) {
                "Películas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Doramas" -> TvType.TvSeries
                else -> TvType.Others
            }
            val doc = app.get(url).document
            val homeItems = doc.select("div.Posters article.listing-content").mapNotNull {
                val title = it.selectFirst("p")?.text()
                val link = it.selectFirst("a.Posters-link")?.attr("href")
                val img = it.selectFirst("img.Posters-img")?.attr("src")

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
        val url = "$mainUrl/search?s=$query"
        val doc = app.get(url).document
        return doc.select("div.Posters article.listing-content").mapNotNull {
            val title = it.selectFirst("p")?.text()
            val link = it.selectFirst("a.Posters-link")?.attr("href")
            val img = it.selectFirst("img.Posters-img")?.attr("src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries
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
            doc.select("div#seasons div.se-c").flatMap { seasonElement ->
                seasonElement.select("ul.episodios li").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.episodiotitle div.epst")?.text() ?: ""

                    val seasonNumber = element.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                    val episodeNumber = element.selectFirst("div.episodiotitle div.numerando")?.text()
                        ?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()

                    val realimg = element.selectFirst("div.imagen img")?.attr("src")

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
            Log.e("Pelisplus", "Error al descifrar link: ${e.message}", e)
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
            Log.d("Pelisplus", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
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
        val iframeSrc = doc.selectFirst("div.player iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("Pelisplus", "No se encontró iframe del reproductor con el selector específico en Pelisplus.")
            return false
        }

        Log.d("Pelisplus", "Iframe encontrado: $iframeSrc")

        // --- LÓGICA PRINCIPAL: Manejar diferentes dominios de iframes ---

        if (iframeSrc.contains("embed69.org")) {
            Log.d("Pelisplus", "loadLinks - Detectado embed69.org iframe: $iframeSrc")
            val frameDoc = try {
                app.get(fixUrl(iframeSrc)).document
            } catch (e: Exception) {
                Log.e("Pelisplus", "Error al obtener el contenido del iframe ($iframeSrc): ${e.message}")
                return false
            }

            val scriptContent = frameDoc.select("script").map { it.html() }.joinToString("\n")

            val dataLinkRegex = """const dataLink = (\[.*?\]);""".toRegex()
            val dataLinkJsonString = dataLinkRegex.find(scriptContent)?.groupValues?.get(1)

            if (dataLinkJsonString.isNullOrBlank()) {
                Log.e("Pelisplus", "No se encontró la variable dataLink en el script de embed69.org.")
                return false
            }

            Log.d("Pelisplus", "dataLink JSON string encontrado: $dataLinkJsonString")

            val dataLinkEntries = tryParseJson<List<DataLinkEntry>>(dataLinkJsonString)

            if (dataLinkEntries.isNullOrEmpty()) {
                Log.e("Pelisplus", "Error al parsear dataLink JSON o está vacío.")
                return false
            }

            val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"

            val foundEmbed69Links = mutableListOf<String>()
            for (entry in dataLinkEntries) {
                for (embed in entry.sortedEmbeds) {
                    if (embed.type == "video") {
                        val decryptedLink = decryptLink(embed.link, secretKey)
                        if (decryptedLink != null) {
                            Log.d("Pelisplus", "Link desencriptado para ${embed.servername}: $decryptedLink")
                            foundEmbed69Links.add(decryptedLink)
                        }
                    }
                }
            }

            if (foundEmbed69Links.isNotEmpty()) {
                foundEmbed69Links.apmap { playerUrl ->
                    Log.d("Pelisplus", "Cargando extractor para link desencriptado (embed69.org): $playerUrl")
                    loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("Pelisplus", "No se encontraron enlaces de video desencriptados de embed69.org.")
                return false
            }
        } else if (iframeSrc.contains("xupalace.org")) {
            Log.d("Pelisplus", "loadLinks - Detectado xupalace.org iframe: $iframeSrc")
            val xupalaceDoc = try {
                app.get(fixUrl(iframeSrc)).document
            } catch (e: Exception) {
                Log.e("Pelisplus", "Error al obtener el contenido del iframe de Xupalace ($iframeSrc): ${e.message}")
                return false
            }

            val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
            val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

            if (elementsWithOnclick.isEmpty()) {
                Log.w("Pelisplus", "No se encontraron elementos con 'go_to_playerVast' en xupalace.org.")
                return false
            }

            val foundXupalaceLinks = mutableListOf<String>()
            for (element in elementsWithOnclick) {
                val onclickAttr = element.attr("onclick")
                val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)

                if (matchPlayerUrl != null) {
                    val videoUrl = matchPlayerUrl.groupValues[1]
                    Log.d("Pelisplus", "Xupalace: Encontrado URL: $videoUrl")
                    if (videoUrl.isNotBlank()) {
                        foundXupalaceLinks.add(videoUrl)
                    }
                }
            }

            if (foundXupalaceLinks.isNotEmpty()) {
                foundXupalaceLinks.apmap { playerUrl ->
                    Log.d("Pelisplus", "Cargando extractor para link de Xupalace: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("Pelisplus", "No se encontraron enlaces de video de Xupalace.org.")
                return false
            }
        } else {
            Log.w("Pelisplus", "Tipo de iframe desconocido o no manejado: $iframeSrc")
            return false
        }
    }
}