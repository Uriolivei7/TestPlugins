package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

class VeronlineProvider : MainAPI() {
    override var mainUrl = "https://www.veronline.cfd"
    override var name = "Veronline"
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
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = TvType.TvSeries
            val doc = app.get(url).document
            val homeItems = doc.select("div.movs article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text() ?: ""
                val link = it.selectFirst("a")?.attr("href") ?: ""
                val img = it.selectFirst("div.poster img")?.attr("data-src")
                    ?: it.selectFirst("div.poster img")?.attr("src") ?: ""

                if (title.isNotBlank() && link.isNotBlank()) {
                    Log.d("Veronline", "Home Item found: $title, Link: $link, Img: $img")
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = fixUrl(img)
                    }
                } else {
                    Log.w("Veronline", "Missing title or link for a home item. Title: $title, Link: $link")
                    null
                }
            }
            Log.d("Veronline", "Total Home Items for $name: ${homeItems.size}")
            HomePageList(name, homeItems)
        }

        val mainPageDoc = app.get(mainUrl).document
        val sliderItems = mainPageDoc.select("div#owl-slider div.owl-item div.shortstory").mapNotNull {
            val title = it.selectFirst("h4.short-link a")?.text() ?: ""
            val link = it.selectFirst("h4.short-link a")?.attr("href") ?: ""
            val img = it.selectFirst("div.short-images a img")?.attr("src") ?: ""

            if (title.isNotBlank() && link.isNotBlank()) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries
                    this.posterUrl = fixUrl(img)
                }
            } else {
                Log.w("Veronline", "Missing title or link for a slider item. Title: $title, Link: $link")
                null
            }
        }
        Log.d("Veronline", "Total Slider Items: ${sliderItems.size}")

        if (sliderItems.isNotEmpty()) {
            items.add(0, HomePageList("Destacadas", sliderItems))
        }
        items.addAll(homePageLists)

        Log.d("Veronline", "Final number of HomePageLists: ${items.size}")
        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/recherche?q=$query"
        val doc = app.get(url).document
        return doc.select("div.result-item").mapNotNull {
            val title = it.selectFirst("h3.title a")?.text() ?: ""
            val link = it.selectFirst("h3.title a")?.attr("href") ?: ""
            val img = it.selectFirst("img")?.attr("src") ?: ""

            if (title.isNotBlank() && link.isNotBlank()) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = TvType.TvSeries
                    this.posterUrl = fixUrl(img)
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

        val title = doc.selectFirst("div.fstory-infos h1.fstory-h1")?.text()?.replace("ver serie ", "")?.replace(" Online gratis HD", "") ?: ""
        val poster = doc.selectFirst("div.fstory-poster-in img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.block-infos p")?.text() ?: ""
        val tags = doc.select("div.finfo-block a[href*='/series-online/']").map { it.text() }

        // ***** SECCIÓN DE ACTORES CON LA POSIBLE CORRECCIÓN *****
        val actors = doc.select("div.finfo-block:has(span:contains(Actores)) a[href*='/series-online/actor/']").mapNotNull {
            val actorName = it.text().trim()
            if (actorName.isNotBlank()) {
                // PRIMERA CORRECCIÓN: Crea un objeto Actor primero, asumiendo Actor(name = String)
                // Esto es provisional hasta que me des la definición de 'Actor'
                val actorObject = Actor(name = actorName)
                ActorData(actor = actorObject) // Usa el parámetro nombrado 'actor'
            } else {
                null
            }
        }

        val directors = doc.select("div.finfo-block:has(span:contains(director)) a[href*='/series-online/director/']").map { it.text().trim() }

        val seasons = doc.select("div#full-video div#serie-seasons div.shortstory-in").mapNotNull { seasonElement ->
            val seasonTitle = seasonElement.selectFirst("h4.short-link a")?.text() ?: ""
            val seasonLink = seasonElement.selectFirst("h4.short-link a")?.attr("href") ?: ""
            val seasonPoster = seasonElement.selectFirst("div.short-images a img")?.attr("src") ?: ""

            if (seasonLink.isNotBlank() && seasonTitle.isNotBlank()) {
                val seasonDoc = app.get(fixUrl(seasonLink)).document
                val episodesInSeason = seasonDoc.select("div#serie-episodes div.episode-list div.saisian_LI").mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("a span")?.text() ?: ""
                    val episodeNumber = epTitle.replace(Regex("Capítulo\\s*"), "").toIntOrNull()

                    val seasonNumber = seasonTitle.replace(Regex(".*Temporada\\s*"), "").toIntOrNull()

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        newEpisode(
                            EpisodeLoadData(epTitle, epurl).toJson()
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = fixUrl(seasonPoster)
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
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = description
            this.tags = tags
            this.actors = actors // Esta línea espera List<ActorData>
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
        val iframeSrc = doc.selectFirst("div#player iframe")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.d("Veronline", "No se encontró iframe del reproductor con el selector específico. Intentando buscar en scripts de la página.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")

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

        Log.d("Veronline", "Cargando extractor para el iframe principal: $iframeSrc")
        return loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
    }
}