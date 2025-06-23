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
class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino" // Nombre más amigable para el usuario
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
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes")
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                else -> TvType.Others
            }
            val doc = app.get(url).document
            val homeItems = doc.select("div.items article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text()
                val link = it.selectFirst("a")?.attr("href")
                // Se prefiere data-srcset para imágenes responsivas, si no, se usa src
                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull() ?: it.selectFirst("div.poster img")?.attr("src")

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
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").mapNotNull {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")
            // Se prefiere data-srcset para imágenes responsivas, si no, se usa src
            val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull() ?: it.selectFirst("div.poster img")?.attr("src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries // Esto podría ser TvType.Movie/Anime dependiendo del resultado de búsqueda real
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
        Log.d("SoloLatino", "load - URL de entrada: $url")

        var cleanUrl = url
        // Intenta extraer la URL de un JSON si viene codificada así
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("SoloLatino", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            // Asegura que la URL comience con HTTPS si es necesario
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("SoloLatino", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("SoloLatino", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("SoloLatino", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl).document
        val tvType = if (cleanUrl.contains("peliculas")) TvType.Movie else TvType.TvSeries // Determina el tipo basado en la URL
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { seasonElement ->
                seasonElement.select("ul.episodios li").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.episodiotitle div.epst")?.text() ?: ""

                    // Extrae número de temporada y episodio
                    val numerandoText = element.selectFirst("div.episodiotitle div.numerando")?.text()
                    val seasonNumber = numerandoText?.split("-")?.getOrNull(0)?.trim()?.toIntOrNull()
                    val episodeNumber = numerandoText?.split("-")?.getOrNull(1)?.trim()?.toIntOrNull()

                    val realimg = element.selectFirst("div.imagen img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson() // Almacena el título y la URL del episodio para loadLinks
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realimg
                        }
                    } else null
                }
            }
        } else listOf() // Si es película, no hay episodios

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
                    dataUrl = cleanUrl // Usa la URL de la película como dataUrl para loadLinks
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            else -> null // En caso de tipo desconocido
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

    // Función de desencriptación (se mantiene igual, ya que es correcta)
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
            Log.e("SoloLatino", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SoloLatino", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        // Intenta limpiar la data si viene con comillas extra
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("SoloLatino", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("SoloLatino", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        // Si la data es un JSON de EpisodeLoadData (para episodios de series)
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("SoloLatino", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            // Si no es JSON, es una URL directa (para películas)
            targetUrl = fixUrl(cleanedData)
            Log.d("SoloLatino", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("SoloLatino", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl).document
        val iframeSrc = doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("SoloLatino", "No se encontró iframe del reproductor con el selector específico en SoloLatino.net. Intentando buscar en scripts de la página principal.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            val directRegex = """url:\s*['"](https?:\/\/[^'"]+)['"]""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    Log.d("SoloLatino", "Encontrado enlace directo en script de página principal: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("SoloLatino", "No se encontraron enlaces directos en scripts de la página principal.")
            return false
        }

        Log.d("SoloLatino", "Iframe encontrado: $iframeSrc")

        // --- LÓGICA PRINCIPAL: Manejar diferentes dominios de iframes ---

        // 1. Manejar Xupalace.org
        if (iframeSrc.contains("xupalace.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado Xupalace.org iframe: $iframeSrc")
            val xupalaceDoc = try {
                app.get(fixUrl(iframeSrc)).document
            } catch (e: Exception) {
                Log.e("SoloLatino", "Error al obtener el contenido del iframe de Xupalace ($iframeSrc): ${e.message}")
                return false
            }

            val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
            val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

            if (elementsWithOnclick.isEmpty()) {
                Log.w("SoloLatino", "No se encontraron elementos con 'go_to_playerVast' en xupalace.org con el selector general.")
                return false
            }

            val foundXupalaceLinks = mutableListOf<String>()
            for (element in elementsWithOnclick) {
                val onclickAttr = element.attr("onclick")
                val matchPlayerUrl = regexPlayerUrl.find(onclickAttr)

                if (matchPlayerUrl != null) {
                    val videoUrl = matchPlayerUrl.groupValues[1]
                    val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                    Log.d("SoloLatino", "Xupalace: Encontrado servidor '$serverName' con URL: $videoUrl")
                    if (videoUrl.isNotBlank()) {
                        foundXupalaceLinks.add(videoUrl)
                    }
                } else {
                    Log.w("SoloLatino", "Xupalace: No se pudo extraer la URL del onclick: $onclickAttr")
                }
            }

            if (foundXupalaceLinks.isNotEmpty()) {
                foundXupalaceLinks.apmap { playerUrl ->
                    Log.d("SoloLatino", "Cargando extractor para link de Xupalace: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SoloLatino", "No se encontraron enlaces de video de Xupalace.org.")
                return false
            }
        }
        // 2. Manejar re.sololatino.net/embed.php
        else if (iframeSrc.contains("re.sololatino.net/embed.php")) {
            Log.d("SoloLatino", "loadLinks - Detectado re.sololatino.net/embed.php iframe: $iframeSrc")
            val embedDoc = try {
                app.get(fixUrl(iframeSrc)).document
            } catch (e: Exception) {
                Log.e("SoloLatino", "Error al obtener el contenido del iframe de re.sololatino.net ($iframeSrc): ${e.message}")
                return false
            }

            val regexGoToPlayerUrl = Regex("""go_to_player\('([^']+)'\)""")
            val elementsWithOnclick = embedDoc.select("*[onclick*='go_to_player']")

            if (elementsWithOnclick.isEmpty()) {
                Log.w("SoloLatino", "No se encontraron elementos con 'go_to_player' en re.sololatino.net/embed.php con el selector general.")
                return false
            }

            val foundReSoloLatinoLinks = mutableListOf<String>()
            for (element in elementsWithOnclick) {
                val onclickAttr = element.attr("onclick")
                val matchPlayerUrl = regexGoToPlayerUrl.find(onclickAttr)

                if (matchPlayerUrl != null) {
                    val videoUrl = matchPlayerUrl.groupValues[1]
                    val serverName = element.selectFirst("span")?.text()?.trim() ?: "Desconocido"
                    Log.d("SoloLatino", "re.sololatino.net: Encontrado servidor '$serverName' con URL: $videoUrl")
                    if (videoUrl.isNotBlank()) {
                        foundReSoloLatinoLinks.add(videoUrl)
                    }
                } else {
                    Log.w("SoloLatino", "re.sololatino.net: No se pudo extraer la URL del onclick: $onclickAttr")
                }
            }

            if (foundReSoloLatinoLinks.isNotEmpty()) {
                foundReSoloLatinoLinks.apmap { playerUrl ->
                    Log.d("SoloLatino", "Cargando extractor para link de re.sololatino.net: $playerUrl")
                    loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SoloLatino", "No se encontraron enlaces de video de re.sololatino.net/embed.php.")
                return false
            }
        }
        // 3. Manejar embed69.org (Lógica de dataLink con reintentos)
        else if (iframeSrc.contains("embed69.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado embed69.org iframe: $iframeSrc")

            var frameDoc: Element? = null
            val maxRetries = 3 // Número de intentos
            val retryDelayMs = 2000L // Retraso entre intentos en milisegundos (2 segundos)
            val timeoutMs = 15000L // Timeout para la petición individual (15 segundos)

            for (i in 0 until maxRetries) {
                try {
                    Log.d("SoloLatino", "Intentando obtener contenido de iframe de embed69.org (intento ${i + 1}/$maxRetries): $iframeSrc")
                    frameDoc = app.get(fixUrl(iframeSrc), timeout = timeoutMs).document
                    Log.d("SoloLatino", "Contenido del iframe de embed69.org obtenido con éxito en intento ${i + 1}.")
                    break // Si tiene éxito, sal del bucle
                } catch (e: Exception) {
                    Log.e("SoloLatino", "Error en el intento ${i + 1} al obtener contenido del iframe de embed69.org ($iframeSrc): ${e.message}")
                    if (i < maxRetries - 1) {
                        kotlinx.coroutines.delay(retryDelayMs) // Espera antes de reintentar
                    } else {
                        Log.e("SoloLatino", "Fallaron todos los intentos para obtener contenido de embed69.org.")
                        return false // Después del último intento fallido, retorna false
                    }
                }
            }

            if (frameDoc == null) {
                Log.e("SoloLatino", "No se pudo obtener el contenido del iframe de embed69.org después de varios intentos, frameDoc es nulo.")
                return false
            }

            val scriptContent = frameDoc.select("script").map { it.html() }.joinToString("\n")

            // *** Regex ajustada para dataLink, confirmada con el JS proporcionado ***
            // Busca la declaración completa de 'const dataLink = [...]'
            val dataLinkRegex = """const\s+dataLink\s*=\s*(\[.*?\]);""".toRegex()
            val dataLinkJsonString = dataLinkRegex.find(scriptContent)?.groupValues?.get(1)

            if (dataLinkJsonString.isNullOrBlank()) {
                Log.e("SoloLatino", "No se encontró la variable dataLink en el script de embed69.org con la regex. Contenido del script (primeras 500 chars): ${scriptContent.take(500)}...")
                return false
            }

            Log.d("SoloLatino", "dataLink JSON string encontrado: $dataLinkJsonString")

            val dataLinkEntries = tryParseJson<List<DataLinkEntry>>(dataLinkJsonString)

            if (dataLinkEntries.isNullOrEmpty()) {
                Log.e("SoloLatino", "Error al parsear dataLink JSON o está vacío.")
                return false
            }

            val secretKey = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE" // Clave secreta confirmada del JS

            val foundEmbed69Links = mutableListOf<String>()
            for (entry in dataLinkEntries) {
                // Iterar sobre todos los embeds, no solo los que coinciden con el idioma si no se necesita
                // Por ahora, solo tomamos el primer set de embeds o todos si hay múltiples sets de dataLink
                for (embed in entry.sortedEmbeds) {
                    if (embed.type == "video") { // Asegúrate de que sea un enlace de video
                        val decryptedLink = decryptLink(embed.link, secretKey)
                        if (decryptedLink != null) {
                            Log.d("SoloLatino", "Link desencriptado para ${embed.servername}: $decryptedLink")
                            foundEmbed69Links.add(decryptedLink)
                        } else {
                            Log.e("SoloLatino", "Falló la desencriptación para ${embed.servername} con enlace: ${embed.link}")
                        }
                    } else {
                        Log.d("SoloLatino", "Ignorando embed de tipo no video: ${embed.servername} (${embed.type})")
                    }
                }
            }

            if (foundEmbed69Links.isNotEmpty()) {
                foundEmbed69Links.apmap { playerUrl ->
                    Log.d("SoloLatino", "Cargando extractor para link desencriptado (embed69.org): $playerUrl")
                    loadExtractor(fixUrl(playerUrl), iframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SoloLatino", "No se encontraron enlaces de video desencriptados de embed69.org.")
                return false
            }
        }
        else {
            Log.w("SoloLatino", "Tipo de iframe desconocido o no manejado: $iframeSrc")
            return false
        }
    }
}