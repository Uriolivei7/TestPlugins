package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import java.util.regex.Pattern

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
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

            Log.d("PlushdProvider", "Contenido completo del script para seasonsJson (primeros 1000 caracteres): ${script.take(1000)}")

            val jsonRegex = "seasonsJson\\s*=\\s*([^;]+);".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = jsonRegex.find(script)

            var jsonscript: String? = null
            if (match != null && match.groupValues.size > 1) {
                jsonscript = match.groupValues[1].trim()
            }

            if (jsonscript.isNullOrEmpty()) {
                Log.e("PlushdProvider", "ERROR: No se pudo extraer el JSON de seasonsJson después de 'seasonsJson = ' para la URL: $url")
                return null
            } else {
                jsonscript = jsonscript.replace("\\/", "/")
                    .replace("\\\"", "\"")

                if (!jsonscript.endsWith("}")) {
                    val lastBraceIndex = jsonscript.lastIndexOf("}")
                    if (lastBraceIndex != -1) {
                        jsonscript = jsonscript.substring(0, lastBraceIndex + 1)
                        Log.w("PlushdProvider", "ADVERTENCIA: JSON ajustado para terminar con '}'. JSON parcial: ${jsonscript.take(500)}...")
                    } else {
                        Log.e("PlushdProvider", "ERROR: JSON extraído no contiene '}'. Intentando recuperación drástica. JSON: ${jsonscript.take(500)}...")
                    }
                }

                val lastValidEndingIndex = jsonscript.indexOfLast { it == '}' || it == ']' }
                val lastCommaIndex = jsonscript.lastIndexOf(",")

                if (lastCommaIndex != -1 && (lastValidEndingIndex == -1 || lastCommaIndex > lastValidEndingIndex)) {
                    var cutIndex = lastCommaIndex
                    var tempJson = jsonscript.substring(0, lastCommaIndex)
                    var lastQuoteBeforeComma = -1
                    var k = tempJson.length - 1
                    while (k >= 0) {
                        if (tempJson[k] == '"' && (k == 0 || tempJson[k-1] != '\\')) {
                            lastQuoteBeforeComma = k
                            break
                        }
                        k--
                    }

                    if (lastQuoteBeforeComma != -1) {
                        cutIndex = lastQuoteBeforeComma + 1
                    }

                    jsonscript = jsonscript.substring(0, cutIndex)
                    Log.w("PlushdProvider", "ADVERTENCIA: JSON truncado para sanear un campo o valor incompleto. JSON parcial: ${jsonscript.take(500)}...")
                }

                var openBraces = jsonscript.count { it == '{' }
                var closedBraces = jsonscript.count { it == '}' }
                var openBrackets = jsonscript.count { it == '[' }
                var closedBrackets = jsonscript.count { it == ']' }

                while (openBraces > closedBraces) {
                    jsonscript += "}"
                    closedBraces++
                    Log.w("PlushdProvider", "ADVERTENCIA: Se a??adi?? '}' al final del JSON para cerrar objeto. JSON parcial: ${jsonscript.take(500)}...")
                }
                while (openBrackets > closedBrackets) {
                    jsonscript += "]"
                    closedBrackets++
                    Log.w("PlushdProvider", "ADVERTENCIA: Se a??adi?? ']' al final del JSON para cerrar array. JSON parcial: ${jsonscript.take(500)}...")
                }

                jsonscript = jsonscript.trim()

                Log.d("PlushdProvider", "JSON final (seasonsJson) antes de parsear: ${jsonscript.take(500)}...")

                try {
                    val jsonNodeMap = parseJson<Map<String, JsonNode>>(jsonscript)

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
                                    Log.w("PlushdProvider", "ADVERTENCIA: Error al parsear episodio en temporada $seasonKey: ${episodeNode.toString().take(200)}... Error: ${e.message}")
                                }
                            }
                        } else {
                            Log.w("PlushdProvider", "ADVERTENCIA: seasonNode no es un array para la clave $seasonKey: $seasonNode. Esto puede indicar un formato inesperado.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PlushdProvider", "ERROR: Error general al parsear seasonsJson. JSON que causó el error: ${jsonscript.take(500)}... Error: ${e.message}", e)
                    return null
                }
            }
        }

        // --- INICIO: Corrección de la llamada a newTvSeriesLoadResponse y newMovieLoadResponse ---
        return when (tvType) {
            TvType.TvSeries -> {
                // La firma correcta para tu versión de CloudStream parece ser:
                // newTvSeriesLoadResponse(name: String, url: String, type: TvType, episodes: List<Episode>) { /* builder block */ }
                // donde 'apiName' y otras propiedades van dentro del bloque.
                newTvSeriesLoadResponse(
                    title, // name
                    url, // url
                    TvType.TvSeries, // type
                    allEpisodes // episodes
                ) {
                    // Propiedades adicionales asignadas dentro del bloque
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    // apiName ya es una propiedad de la clase MainAPI, no se pasa aquí.
                    // Si te da error de "Unresolved reference: apiName" aquí, es redundante.
                    // apiName es una propiedad de la clase, así que ya está disponible para el Loader
                }
            }
            TvType.Movie -> {
                // La firma correcta para películas parece ser similar:
                // newMovieLoadResponse(name: String, url: String, type: TvType, dataUrl: String) { /* builder block */ }
                newMovieLoadResponse(
                    title, // name
                    url, // url
                    tvType, // type
                    url // dataUrl
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    // apiName también es una propiedad de la clase.
                }
            }
            else -> null
        }
        // --- FIN: Corrección de la llamada ---
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("div ul.subselect li").apmap {
            val encodedOne = it.attr("data-server").toByteArray()
            val encodedTwo = base64Encode(encodedOne)
            val playerUrl = "$mainUrl/player/$encodedTwo"

            // 'val' es correcto aquí, ya que 'playerDoc' no se reasigna.
            val playerDoc = app.get(playerUrl).document

            // Buscar un iframe o un script que contenga una URL de reproductor
            val link = playerDoc.selectFirst("iframe")?.attr("src") // Podría ser un iframe
                ?: playerDoc.select("script").firstOrNull { it.html().contains("player.src") || it.html().contains("source src=") }?.let { scriptTag ->
                    // Intenta extraer el src del reproductor de un script si existe
                    // Regex para player.src
                    val playerSrcMatch = "player\\.src\\s*=\\s*['\"](.*?)['\"]".toRegex().find(scriptTag.html())?.groupValues?.get(1)
                    if (playerSrcMatch != null) return@let playerSrcMatch

                    // Regex para source src=
                    val sourceSrcMatch = "source\\s+src=['\"](.*?)['\"]".toRegex().find(scriptTag.html())?.groupValues?.get(1)
                    if (sourceSrcMatch != null) return@let sourceSrcMatch

                    null // Si no se encuentra ninguno
                }

            if (link != null) {
                Log.d("PlushdProvider", "Enlace extraído del player ($playerUrl): $link")
                loadExtractor(link, mainUrl, subtitleCallback, callback)
            } else {
                Log.e("PlushdProvider", "ERROR: No se pudo extraer el enlace del player de $playerUrl. HTML completo del player: ${playerDoc.html().take(500)}...")
            }
        }
        return true
    }
}