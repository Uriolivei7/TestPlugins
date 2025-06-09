package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.util.Log
import com.lagradost.cloudstream3.*
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

class VerOnlineProvider : MainAPI() {
    override var mainUrl = "https://www.veronline.cfd"
    override var name = "VerOnline"
    // Modificado: Solo soporta TvSeries y Anime/Cartoon si la página es exclusivamente de series.
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime, // Mantener si el sitio tiene animes también
        TvType.Cartoon, // Mantener si el sitio tiene cartoons también
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        // URLs actualizadas basadas en tu observación y HTML.
        // Se asume que solo hay "series-online.html".
        // Si hay una URL diferente para "Estrenos de Series", deberías investigarla y añadirla.
        val urls = listOf(
            Pair("Últimas Series", "$mainUrl/series-online.html"),
            // Eliminadas las URLs de películas
            // Si hay una URL específica para "Estrenos de Series", añádela aquí
            // Ejemplo: Pair("Estrenos de Series", "$mainUrl/estrenos-series.html"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when {
                name.contains("Series") -> TvType.TvSeries
                else -> TvType.Others // TvType.Others en caso de que añadas algo que no sea directamente serie
            }
            try {
                Log.d("VerOnline", "getMainPage - Intentando obtener URL: $url")
                val doc = app.get(url).document
                Log.d("VerOnline", "getMainPage - HTML recibido para $url (primeros 1000 chars): ${doc.html().take(1000)}")
                // Selector CSS actualizado basado en image_1006dc.jpg
                val homeItems = doc.select("div.shortstory.radius-3").mapNotNull { articleElement ->
                    val aElement = articleElement.selectFirst("a")
                    val title = aElement?.attr("title")
                    val link = aElement?.attr("href")
                    val img = aElement?.selectFirst("img")?.attr("src")

                    if (title != null && link != null) {
                        newTvSeriesSearchResponse( // Usar newTvSeriesSearchResponse ya que solo son series
                            title,
                            fixUrl(link)
                        ) {
                            this.type = tvType
                            this.posterUrl = img
                        }
                    } else null
                }
                Log.d("VerOnline", "getMainPage - Encontrados ${homeItems.size} ítems para $url")
                HomePageList(name, homeItems)
            } catch (e: Exception) {
                Log.e("VerOnline", "Error al obtener la página principal para $url: ${e.message} - ${e.stackTraceToString()}", e)
                null
            }
        }.filterNotNull()

        items.addAll(homePageLists)
        return newHomePageResponse(items.toList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/recherche?q=$query"
        Log.d("VerOnline", "search - Intentando buscar en URL: $url")
        try {
            val doc = app.get(url).document
            Log.d("VerOnline", "search - HTML recibido para $url (primeros 1000 chars): ${doc.html().take(1000)}")
            return doc.select("div.shortstory.radius-3").mapNotNull { articleElement ->
                val aElement = articleElement.selectFirst("a")
                val title = aElement?.attr("title")
                val link = aElement?.attr("href")
                val img = aElement?.selectFirst("img")?.attr("src")

                if (title != null && link != null) {
                    newTvSeriesSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = TvType.TvSeries
                        this.posterUrl = img
                    }
                } else null
            }
        } catch (e: Exception) {
            Log.e("VerOnline", "Error en la b??squeda para '$query' en URL $url: ${e.message} - ${e.stackTraceToString()}", e)
            return emptyList()
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("VerOnline", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("VerOnline", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("VerOnline", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("VerOnline", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("VerOnline", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            Log.e("VerOnline", "load - ERROR al obtener el documento para URL: $cleanUrl - ${e.message} - ${e.stackTraceToString()}", e)
            return null
        }

        val tvType = TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("src")
            ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""
        val description = doc.selectFirst("div.entry-content p")?.text()
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content") ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = doc.select("div#serie-episodes div.episode-list div.saisoin_LI2").mapNotNull { episodeElement ->
            val aElement = episodeElement.selectFirst("a")
            val epurl = fixUrl(aElement?.attr("href") ?: "")
            val epTitleText = aElement?.selectFirst("span")?.text() ?: ""

            val episodeNumber = Regex("""Capítulo\s*(\d+)""").find(epTitleText)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNumber = Regex("""temporada-(\d+)""").find(epurl)?.groupValues?.get(1)?.toIntOrNull()

            val realimg = poster

            if (epurl.isNotBlank() && epTitleText.isNotBlank()) {
                newEpisode(
                    EpisodeLoadData(epTitleText, epurl).toJson()
                ) {
                    this.name = epTitleText
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.posterUrl = realimg
                }
            } else {
                Log.w("VerOnline", "load - Episodio incompleto encontrado: URL=$epurl, Título=$epTitleText")
                null
            }
        }

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
            Log.e("VerOnline", "Error al descifrar link: ${e.message} - ${e.stackTraceToString()}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("VerOnline", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("VerOnline", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("VerOnline", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("VerOnline", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("VerOnline", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("VerOnline", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("VerOnline", "loadLinks - ERROR al obtener el documento para URL: $targetUrl - ${e.message} - ${e.stackTraceToString()}", e)
            return false
        }

        // --- INICIO DEL CAMBIO CLAVE PARA loadLinks ---

        val streamerElements = doc.select("li.streamer")

        if (streamerElements.isEmpty()) {
            Log.w("VerOnline", "loadLinks - No se encontraron elementos 'li.streamer' en la página del episodio. No se pudieron extraer enlaces.")
            // Tu lógica anterior para iframes o scripts directos podría seguir siendo útil como fallback
            // si algunos episodios usan otro método, pero para este caso particular, esto es lo que necesitamos.
            return false // O true si quieres intentar los fallbacks, pero por ahora, indica que no se encontraron links específicos.
        }

        var foundLinks = false
        streamerElements.apmap { streamerElement ->
            val encodedUrl = streamerElement.attr("data-url")
            val serverName = streamerElement.selectFirst("span[id*='player_V_DIV_5']")?.text() // Para obtener Vidoza, Doodstream, etc.
                ?: streamerElement.selectFirst("span")?.text()?.replace("OPCIÓN ", "Opción ")?.trim() // Para "Opción 2", "Opción 3", etc.

            if (encodedUrl.isNotBlank()) {
                // El atributo data-url ya contiene la ruta /streamer/...
                // Necesitamos decodificar la parte de Base64 que viene después de /streamer/
                val base64Part = encodedUrl.substringAfter("/streamer/")

                try {
                    val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                    val decodedUrl = String(decodedBytes, UTF_8)
                    Log.d("VerOnline", "loadLinks - Decodificado URL para $serverName: $decodedUrl")

                    // Ahora pasamos la URL decodificada al loadExtractor
                    val extracted = loadExtractor(fixUrl(decodedUrl), targetUrl, subtitleCallback, callback)
                    if (extracted) foundLinks = true

                } catch (e: IllegalArgumentException) {
                    Log.e("VerOnline", "loadLinks - Error al decodificar Base64 de $encodedUrl: ${e.message}")
                } catch (e: Exception) {
                    Log.e("VerOnline", "loadLinks - Error general al procesar link de $serverName ($encodedUrl): ${e.message} - ${e.stackTraceToString()}", e)
                }
            }
        }
        return foundLinks

        // --- FIN DEL CAMBIO CLAVE PARA loadLinks ---
    }
}