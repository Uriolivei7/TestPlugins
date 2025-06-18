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
class VeronlineProvider : MainAPI() {
    override var mainUrl = "https://www.veronline.cfd"
    override var name = "Veronline" // Nombre más amigable para el usuario
    override val supportedTypes = setOf(
        TvType.TvSeries,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Últimas Series Agregadas", "$mainUrl/series-online.html"),
            // Puedes añadir más si hay secciones específicas en la página principal, por ejemplo:
            // Pair("Series Populares", "$mainUrl/series-populares.html")
        )

        val homePageLists = urls.apmap { (name, url) ->
            // Definir tvType aquí para que esté en el ámbito de este bloque
            val tvType = TvType.TvSeries
            val doc = app.get(url).document
            // Ajuste del selector para los elementos de la página principal (últimas series agregadas)
            val homeItems = doc.select("div.movs article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text()
                val link = it.selectFirst("a")?.attr("href")
                // El atributo de la imagen puede variar. 'data-src' es común para lazyload.
                // Si no es 'data-src', es 'src'. Probar con ambos.
                val img = it.selectFirst("div.poster img")?.attr("data-src")
                    ?: it.selectFirst("div.poster img")?.attr("src")

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

        // Además de las "últimas agregadas", vamos a intentar extraer el "Slider" de la página principal
        // que parece estar en <div id="owl-slider">
        val mainPageDoc = app.get(mainUrl).document
        val sliderItems = mainPageDoc.select("div#owl-slider div.owl-item div.shortstory").mapNotNull {
            val title = it.selectFirst("h4.short-link a")?.text()
            val link = it.selectFirst("h4.short-link a")?.attr("href")
            val img = it.selectFirst("div.short-images a img")?.attr("src")

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
        if (sliderItems.isNotEmpty()) {
            items.add(0, HomePageList("Destacadas", sliderItems))
        }


        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/recherche?q=$query"
        val doc = app.get(url).document
        // Selector para los resultados de búsqueda
        return doc.select("div.result-item").mapNotNull {
            val title = it.selectFirst("h3.title a")?.text()
            val link = it.selectFirst("h3.title a")?.attr("href")
            val img = it.selectFirst("img")?.attr("src")

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
        Log.d("Veronline", "load - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("Veronline", "load - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("Veronline", "load - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("Veronline", "load - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("Veronline", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl).document
        val tvType = TvType.TvSeries
        // Selectores actualizados para los detalles de la serie
        val title = doc.selectFirst("div.fstory-infos h1.fstory-h1")?.text()?.replace("ver serie ", "")?.replace(" Online gratis HD", "") ?: ""
        val poster = doc.selectFirst("div.fstory-poster-in img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.block-infos p")?.text() ?: ""
        val tags = doc.select("div.finfo-block a[href*='/series-online/']").map { it.text() }

        // Extracción de actores y directores
        val actors = doc.select("div.finfo-block:has(span:contains(Actores)) a[href*='/series-online/actor/']").map {
            // CORRECCIÓN CONFIRMADA: Constructor de ActorData solo acepta 'name'
            //ActorData(name = it.text().trim())
        }
        // Para directores, LoadResponse no tiene un campo directo, así que los concatenaremos en el plot si es necesario,
        // o simplemente los ignoramos para evitar el error 'Unresolved reference'.
        // Si CloudStream añade un campo 'director' en el futuro, se podría usar.
        val directors = doc.select("div.finfo-block:has(span:contains(director)) a[href*='/series-online/director/']").map { it.text().trim() }

        // Extracción de Temporadas y Episodios
        val seasons = doc.select("div#full-video div#serie-seasons div.shortstory-in").mapNotNull { seasonElement ->
            val seasonTitle = seasonElement.selectFirst("h4.short-link a")?.text()
            val seasonLink = seasonElement.selectFirst("h4.short-link a")?.attr("href")
            val seasonPoster = seasonElement.selectFirst("div.short-images a img")?.attr("src")

            if (seasonLink != null && seasonTitle != null) {
                // Navegar a la página de la temporada para obtener los episodios
                val seasonDoc = app.get(fixUrl(seasonLink)).document
                val episodesInSeason = seasonDoc.select("div#serie-episodes div.episode-list div.saisian_LI").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("a span")?.text() ?: ""
                    val episodeNumber = epTitle.replace("Capítulo ", "").toIntOrNull()

                    // Extraer el número de temporada del título de la temporada.
                    // Asumimos que el formato es "Merlín Temporada N"
                    val seasonNumber = seasonTitle.replace(Regex(".*Temporada\\s*"), "").toIntOrNull()

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson()
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = seasonPoster
                        }
                    } else null
                }
                episodesInSeason
            } else null
        }.flatten()

        return newTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            type = tvType,
            episodes = seasons,
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.tags = tags
            //this.actors = actors // <-- Esta es la línea 154 (o cercana). La asignación es correcta aquí.
            // No se asigna 'director' directamente porque no es una propiedad de TvSeriesLoadResponse.
            // Si quieres mostrar los directores, podrías añadirlos a la descripción (plot)
            // o a un campo CustomData si CloudStream lo permite y tu UI lo renderiza.
            if (directors.isNotEmpty()) {
                this.plot = this.plot + "\n\nDirectores: " + directors.joinToString(", ")
            }
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
            Log.e("Veronline", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Veronline", "loadLinks - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("Veronline", "loadLinks - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("Veronline", "loadLinks - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Veronline", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("Veronline", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Veronline", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl).document
        // Selector para los iframes principales de los reproductores en la página del episodio
        val iframeSrc = doc.selectFirst("div#player iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("Veronline", "No se encontró iframe del reproductor con el selector específico. Intentando buscar en scripts de la página.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

            // Regex más robusta para encontrar URLs directas de reproductores o embeds en scripts
            val directRegex = """(https?:\/\/[^'"]+?(?:.m3u8|.mp4|embed|player|file|stream)[^'"]*)""".toRegex()
            val directMatches = directRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (directMatches.isNotEmpty()) {
                directMatches.apmap { directUrl ->
                    Log.d("Veronline", "Encontrado enlace directo en script de página: $directUrl")
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }
            Log.d("Veronline", "No se encontraron enlaces directos en scripts de la página.")
            return false
        }

        Log.d("Veronline", "Iframe encontrado: $iframeSrc")

        // La lógica de `loadLinks` para Veronline.cfd se basa en que el `iframeSrc`
        // o los enlaces directos encontrados en los scripts pueden ser manejados por los extractores de CloudStream.
        // No hay necesidad de lógica específica para "xupalace.org", "re.sololatino.net" o "embed69.org" a menos que
        // se identifiquen embeds específicos de Veronline.cfd que requieran un manejo particular.
        Log.d("Veronline", "Cargando extractor para el iframe principal: $iframeSrc")
        return loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
    }
}