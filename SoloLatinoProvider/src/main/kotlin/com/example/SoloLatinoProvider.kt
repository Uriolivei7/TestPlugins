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
import kotlinx.coroutines.delay // Importar para usar delay en los reintentos

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

        // *** MODIFICACIÓN AQUÍ: Mejorar la selección del iframe principal ***
        // Prioriza el iframe con id="iframePlayer"
        val initialIframeSrc = doc.selectFirst("iframe#iframePlayer")?.attr("src")
        // Mantiene el selector antiguo como fallback
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe.metaframe")?.attr("src")
            // Mantiene el otro selector antiguo como fallback
            ?: doc.selectFirst("div[id*=\"dooplay_player_response\"] iframe")?.attr("src")

        if (initialIframeSrc.isNullOrBlank()) {
            Log.d("SoloLatino", "No se encontró iframe del reproductor principal con el selector específico en SoloLatino.net. Intentando buscar en scripts de la página principal.")
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

        Log.d("SoloLatino", "Iframe principal encontrado: $initialIframeSrc")

        // Variable para almacenar la URL del iframe final (puede ser initialIframeSrc o el anidado)
        var finalIframeSrc: String = initialIframeSrc

        // *** LÓGICA GHBRISK.COM: Manejar ghbrisk.com como intermediario de iframe anidado ***
        if (initialIframeSrc.contains("ghbrisk.com")) {
            Log.d("SoloLatino", "loadLinks - Detectado ghbrisk.com iframe intermediario: $initialIframeSrc. Buscando iframe anidado.")
            val ghbriskDoc = try {
                app.get(fixUrl(initialIframeSrc)).document // Obtener el contenido del iframe de ghbrisk.com
            } catch (e: Exception) {
                Log.e("SoloLatino", "Error al obtener el contenido del iframe de ghbrisk.com ($initialIframeSrc): ${e.message}")
                return false
            }

            // Buscar el iframe de embed69.org dentro del iframe de ghbrisk.com
            val nestedIframeSrc = ghbriskDoc.selectFirst("iframe.metaframe.rptss")?.attr("src")
                ?: ghbriskDoc.selectFirst("iframe")?.attr("src") // Fallback por si no tiene la clase exacta

            if (nestedIframeSrc.isNullOrBlank()) {
                Log.e("SoloLatino", "No se encontró un iframe anidado (posiblemente embed69.org) dentro de ghbrisk.com.")
                return false
            }
            Log.d("SoloLatino", "Iframe anidado encontrado en ghbrisk.com: $nestedIframeSrc")
            finalIframeSrc = nestedIframeSrc // Actualizar la URL del iframe final a la de embed69.org
        }
        // *** MODIFICACIÓN ADICIONAL: Mejorado el manejo de Xupalace.org ***
        // Este bloque ahora maneja tanto el caso de iframe anidado (playerwish.com)
        // como los enlaces directos 'go_to_playerVast' dentro de Xupalace.org.
        else if (initialIframeSrc.contains("xupalace.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado Xupalace.org iframe intermediario/directo: $initialIframeSrc.")
            val xupalaceDoc = try {
                app.get(fixUrl(initialIframeSrc)).document // Obtener el contenido del iframe de Xupalace
            } catch (e: Exception) {
                Log.e("SoloLatino", "Error al obtener el contenido del iframe de Xupalace ($initialIframeSrc): ${e.message}")
                return false
            }

            // Primero, intenta buscar el iframe con id="IFR" (playerwish.com)
            val nestedIframeSrc = xupalaceDoc.selectFirst("iframe#IFR")?.attr("src")

            if (!nestedIframeSrc.isNullOrBlank()) {
                Log.d("SoloLatino", "Iframe anidado (playerwish.com) encontrado en Xupalace.org: $nestedIframeSrc")
                finalIframeSrc = nestedIframeSrc // Actualizar a la URL de playerwish.com
                // El flujo continuará al bloque de playerwish.com en el siguiente 'if'.
            } else {
                Log.w("SoloLatino", "No se encontró un iframe anidado (playerwish.com) dentro de Xupalace.org. Intentando buscar enlaces directos 'go_to_playerVast'.")
                // Si no se encuentra playerwish.com, busca los enlaces go_to_playerVast directamente en Xupalace.org
                val regexPlayerUrl = Regex("""go_to_playerVast\('([^']+)'""")
                val elementsWithOnclick = xupalaceDoc.select("*[onclick*='go_to_playerVast']")

                if (elementsWithOnclick.isEmpty()) {
                    Log.e("SoloLatino", "No se encontraron elementos con 'go_to_playerVast' ni iframe 'IFR' en xupalace.org.")
                    return false // No se encontró ninguna opción de video viable en Xupalace
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
                        Log.d("SoloLatino", "Cargando extractor para link de Xupalace (go_to_playerVast): $playerUrl")
                        loadExtractor(fixUrl(playerUrl), initialIframeSrc, subtitleCallback, callback)
                    }
                    return true // Se encontraron y procesaron enlaces directos de Xupalace
                } else {
                    Log.d("SoloLatino", "No se encontraron enlaces de video de Xupalace.org (go_to_playerVast).")
                    return false
                }
            }
        }


        // --- LÓGICA PRINCIPAL: Ahora usamos finalIframeSrc para los dominios conocidos ---

        // El orden de estos 'else if' es importante para que el flujo sea lógico.

        // 1. Manejar PlayerWish.com (el más profundo en el caso de Xupalace)
        if (finalIframeSrc.contains("playerwish.com")) {
            Log.d("SoloLatino", "loadLinks - Detectado playerwish.com iframe: $finalIframeSrc")

            val playerwishDoc = try {
                app.get(fixUrl(finalIframeSrc)).document
            } catch (e: Exception) {
                Log.e("SoloLatino", "Error al obtener el contenido del iframe de playerwish.com ($finalIframeSrc): ${e.message}")
                return false
            }

            // La URL de VAST es constante según los scripts proporcionados
            val vastUrl = "https://1wincdn.b-cdn.net/vast_1win_v2.xml"
            val vastXmlDoc = try {
                app.get(vastUrl).document
            } catch (e: Exception) {
                Log.e("SoloLatino", "Error al obtener el XML VAST de $vastUrl: ${e.message}")
                return false
            }

            // Buscar la URL del MP4 dentro del XML VAST
            val videoUrlElement = vastXmlDoc.selectFirst("MediaFile[type=\"video/mp4\"]")
            val finalVideoLink = videoUrlElement?.text()?.trim()

            if (finalVideoLink.isNullOrBlank()) {
                Log.e("SoloLatino", "No se encontró un enlace de video MP4 en el XML VAST de playerwish.com.")
                return false
            }
            Log.d("SoloLatino", "URL de video obtenida del XML VAST en playerwish.com: $finalVideoLink")


            if (!finalVideoLink.isNullOrBlank()) {
                loadExtractor(fixUrl(finalVideoLink), finalIframeSrc, subtitleCallback, callback)
                return true
            } else {
                Log.e("SoloLatino", "No se pudo obtener el enlace final de video de playerwish.com.")
                return false
            }
        }
        // 2. Manejar Xupalace.org (Este bloque ahora manejará la lógica de su botón de "player" si no lleva a playerwish.com)
        // ESTE BLOQUE YA NO ES NECESARIO AQUÍ YA QUE LA LÓGICA SE MOVIÓ AL MANEJO INICIAL DE XUPALACE.ORG
        // Si finalIframeSrc llega a este punto y contiene xupalace.org, significa que NO SE ENCONTRÓ PLAYERWISH.COM
        // EN EL BLOQUE INICIAL DE XUPALACE.ORG, Y POR LO TANTO, YA DEBIÓ HABER INTENTADO CARGAR
        // LOS ENLACES 'go_to_playerVast' DIRECTAMENTE.
        // MANTENGO EL COMENTARIO PARA CLARIDAD.
        /*
        else if (finalIframeSrc.contains("xupalace.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado Xupalace.org iframe directo o secundario: $finalIframeSrc")
            // ... lógica anterior de Xupalace.org ...
        }
        */
        // 3. Manejar re.sololatino.net/embed.php
        else if (finalIframeSrc.contains("re.sololatino.net/embed.php")) {
            Log.d("SoloLatino", "loadLinks - Detectado re.sololatino.net/embed.php iframe: $finalIframeSrc")
            val embedDoc = try {
                app.get(fixUrl(finalIframeSrc)).document
            } catch (e: Exception) {
                Log.e("SoloLatino", "Error al obtener el contenido del iframe de re.sololatino.net ($finalIframeSrc): ${e.message}")
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
                    loadExtractor(fixUrl(playerUrl), initialIframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SoloLatino", "No se encontraron enlaces de video de re.sololatino.net/embed.php.")
                return false
            }
        }
        // 4. Manejar embed69.org (Lógica de dataLink con reintentos)
        else if (finalIframeSrc.contains("embed69.org")) {
            Log.d("SoloLatino", "loadLinks - Detectado embed69.org iframe: $finalIframeSrc")

            var frameDoc: Element? = null
            val maxRetries = 3
            val retryDelayMs = 2000L
            val timeoutMs = 15000L

            for (i in 0 until maxRetries) {
                try {
                    Log.d("SoloLatino", "Intentando obtener contenido de iframe de embed69.org (intento ${i + 1}/$maxRetries): $finalIframeSrc")
                    frameDoc = app.get(fixUrl(finalIframeSrc), timeout = timeoutMs).document
                    Log.d("SoloLatino", "Contenido del iframe de embed69.org obtenido con éxito en intento ${i + 1}.")
                    break
                } catch (e: Exception) {
                    Log.e("SoloLatino", "Error en el intento ${i + 1} al obtener contenido del iframe de embed69.org ($finalIframeSrc): ${e.message}")
                    if (i < maxRetries - 1) {
                        delay(retryDelayMs)
                    } else {
                        Log.e("SoloLatino", "Fallaron todos los intentos para obtener contenido de embed69.org.")
                        return false
                    }
                }
            }

            if (frameDoc == null) {
                Log.e("SoloLatino", "No se pudo obtener el contenido del iframe de embed69.org después de varios intentos, frameDoc es nulo.")
                return false
            }

            val scriptContent = frameDoc.select("script").map { it.html() }.joinToString("\n")

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
                for (embed in entry.sortedEmbeds) {
                    if (embed.type == "video") {
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
                    loadExtractor(fixUrl(playerUrl), initialIframeSrc, subtitleCallback, callback)
                }
                return true
            } else {
                Log.d("SoloLatino", "No se encontraron enlaces de video desencriptados de embed69.org.")
                return false
            }
        }
        // Manejar otros dominios comunes de reproductores directos (ej. Fembed, Streamlare, etc.)
        else if (finalIframeSrc.contains("fembed.com") || finalIframeSrc.contains("streamlare.com") || finalIframeSrc.contains("player.sololatino.net")) {
            Log.d("SoloLatino", "loadLinks - Detectado reproductor directo (Fembed/Streamlare/player.sololatino.net): $finalIframeSrc")
            loadExtractor(fixUrl(finalIframeSrc), targetUrl, subtitleCallback, callback)
            return true
        }
        else {
            Log.w("SoloLatino", "Tipo de iframe desconocido o no manejado: $finalIframeSrc")
            return false
        }
    }
}