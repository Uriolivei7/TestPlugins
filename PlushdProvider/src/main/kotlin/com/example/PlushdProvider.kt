package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import kotlin.random.Random
import kotlinx.coroutines.delay

class PlushdProvider : MainAPI() {
    override var mainUrl = "https://ww3.pelisplus.to"
    override var name = "PlusHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Doramas", "$mainUrl/doramas")
        )

        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select(".articlesList article").map {
                val title = it.selectFirst("a h2")?.text()
                val link = it.selectFirst("a.itemA")?.attr("href")
                val img = it.selectFirst("picture img")?.attr("data-src")
                TvSeriesSearchResponse(
                    title!!,
                    link!!,
                    this.name,
                    TvType.TvSeries,
                    img,
                )
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search/$query"
        val doc = app.get(url).document
        return doc.select("article.item").map {
            val title = it.selectFirst("a h2")?.text()
            val link = it.selectFirst("a.itemA")?.attr("href")
            val img = it.selectFirst("picture img")?.attr("data-src")
            TvSeriesSearchResponse(
                title!!,
                link!!,
                this.name,
                TvType.TvSeries,
                img,
            )
        }
    }

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst(".slugh1")?.text() ?: ""
        val backimage = doc.selectFirst("head meta[property=og:image]")!!.attr("content")
        val poster = backimage.replace("original", "w500")
        val description = doc.selectFirst("div.description")!!.text()
        val tags = doc.select("div.home__slider .genres:contains(Generos) a").map { it.text() }

        val allEpisodes = ArrayList<Episode>()

        if (tvType == TvType.TvSeries) {
            val script = doc.select("script").firstOrNull { it.html().contains("seasonsJson = ") }?.html()

            if (script.isNullOrEmpty()) {
                Log.e("PlushdProvider", "ERROR: seasonsJson script no encontrado o está vacío para la URL: $url")
                return null
            }

            val jsonRegex = "seasonsJson\\s*=\\s*(\\{[^;]*\\});?".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = jsonRegex.find(script)

            var jsonscript: String? = null
            if (match != null && match.groupValues.size > 1) {
                jsonscript = match.groupValues[1].trim()
            }

            if (jsonscript.isNullOrEmpty()) {
                Log.e("PlushdProvider", "ERROR: No se pudo extraer el JSON de seasonsJson después de 'seasonsJson = ' para la URL: $url")
                return null
            } else {
                jsonscript = jsonscript.replace("\\/", "/").replace("\\\"", "\"")

                var cleanJson = jsonscript
                var openBraces = 0
                var closedBraces = 0
                var openBrackets = 0
                var closedBrackets = 0
                var lastValidIndex = -1

                for (i in cleanJson.indices) {
                    when (cleanJson[i]) {
                        '{' -> openBraces++
                        '}' -> closedBraces++
                        '[' -> openBrackets++
                        ']' -> closedBrackets++
                    }
                    if (openBraces == closedBraces && openBrackets == closedBrackets) {
                        lastValidIndex = i
                    }
                }

                if (openBraces != closedBraces || openBrackets != closedBrackets) {
                    if (lastValidIndex != -1) {
                        cleanJson = cleanJson.substring(0, lastValidIndex + 1)
                    } else {
                        Log.e("PlushdProvider", "ERROR: JSON malformado severamente, no se pudo encontrar un punto de balance válido. Intentando cierre forzado.")
                        while (openBraces > closedBraces) { cleanJson += "}"; closedBraces++ }
                        while (openBrackets > closedBrackets) { cleanJson += "]"; closedBrackets++ }
                    }
                }

                cleanJson = cleanJson.replace(",}", "}")
                cleanJson = cleanJson.replace(",]", "]")

                try {
                    val jsonNodeMap = parseJson<Map<String, JsonNode>>(cleanJson)

                    jsonNodeMap.forEach { (seasonKey, seasonNode) ->
                        if (seasonNode.isArray) {
                            seasonNode.forEach { episodeNode ->
                                try {
                                    val info = parseJson<MainTemporadaElement>(episodeNode.toString())
                                    val epTitle = info.title?.takeIf { it.isNotBlank() } ?: "Episodio sin título"
                                    val seasonNum = info.season ?: -1
                                    val epNum = info.episode ?: -1
                                    val img = info.image

                                    val realimg = if (img.isNullOrBlank()) null else "https://image.tmdb.org/t/p/w342${img}"

                                    if (seasonNum >= 0 && epNum >= 0) {
                                        val episode = Episode(
                                            data = "$url/season/$seasonNum/episode/$epNum",
                                            name = epTitle,
                                            season = seasonNum,
                                            episode = epNum,
                                            posterUrl = realimg
                                        )
                                        allEpisodes.add(episode)
                                    }
                                } catch (e: Exception) {
                                    Log.w("PlushdProvider", "ADVERTENCIA: Error al parsear episodio en temporada $seasonKey: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PlushdProvider", "ERROR: Error general al parsear seasonsJson. JSON que causó el error: ${cleanJson.take(500)}... Error: ${e.message}", e)
                    return null
                }
            }
        }

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    allEpisodes
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(
                    title,
                    url,
                    tvType,
                    url
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                }
            }
            else -> null
        }
    }

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to data
        )
        Log.d("PlushdProvider", "Iniciando loadLinks para: $data")

        val doc = app.get(data, headers = headers).document
        val serversFound = doc.select("div ul.subselect li")
        if (serversFound.isEmpty()) {
            Log.e("PlushdProvider", "ERROR: No se encontraron servidores de video en la página: $data")
            return false
        }

        var foundLinks = false
        serversFound.apmap {
            val encodedOne = it.attr("data-server").toByteArray()
            val encodedTwo = base64Encode(encodedOne)
            val playerUrl = "$mainUrl/player/$encodedTwo"
            Log.d("PlushdProvider", "Procesando servidor con player URL: $playerUrl")

            // Retardo para evitar bloqueos
            delay(Random.nextLong(1000, 3000))

            try {
                val playerDoc = app.get(playerUrl, headers = headers, allowRedirects = true).document
                var link: String? = null

                // Intentar extraer desde iframe
                val iframe = playerDoc.selectFirst("iframe")
                if (iframe != null) {
                    link = iframe.attr("src")
                } else {
                    // Intentar extraer desde script
                    val scriptTag = playerDoc.select("script").firstOrNull { it.html().contains("player.src") || it.html().contains("source src=") }
                    if (scriptTag != null) {
                        val scriptHtml = scriptTag.html()
                        val playerSrcMatch = "player\\.src\\s*=\\s*['\"](.*?)['\"]".toRegex().find(scriptHtml)?.groupValues?.get(1)
                        if (playerSrcMatch != null) {
                            link = playerSrcMatch
                        } else {
                            val sourceSrcMatch = "source\\s+src=['\"](.*?)['\"]".toRegex().find(scriptHtml)?.groupValues?.get(1)
                            link = sourceSrcMatch ?: ""
                        }
                    }
                }
                link = link ?: ""

                if (link.isNotEmpty()) {
                    Log.d("PlushdProvider", "Enlace extraído del player ($playerUrl): $link")
                    val extractorResult = loadExtractor(link, playerUrl, subtitleCallback) { link ->
                        callback(link)
                        foundLinks = true
                    }
                    Log.d("PlushdProvider", "Resultado de loadExtractor para $link: $extractorResult")
                    // Verificar el resultado del extractor
                    if (!extractorResult) {
                        Log.w("PlushdProvider", "ADVERTENCIA: loadExtractor falló para $link. Verificando bloqueo...")
                        var errorMessage = ""
                        if (playerDoc.text().contains("bloqueo temporal", ignoreCase = true) || playerDoc.html().contains("cargando")) {
                            errorMessage = "ERROR: Bloqueo temporal o carga dinámica detectada en $playerUrl"
                            Log.e("PlushdProvider", errorMessage)
                        } else {
                            errorMessage = "ERROR: Posible dependencia de JavaScript, no soportado en CloudStream3 para $playerUrl"
                            Log.e("PlushdProvider", errorMessage)
                        }
                    } else {
                        // No hacer nada si extractorResult es true
                    }
                } else {
                    Log.e("PlushdProvider", "ERROR: No se pudo extraer el enlace del reproductor de $playerUrl. HTML (parcial) del reproductor: ${playerDoc.html().take(500)}...")
                }
            } catch (e: Exception) {
                Log.e("PlushdProvider", "ERROR: Excepción al acceder a $playerUrl: ${e.message}")
            }
        }
        return foundLinks
    }
}