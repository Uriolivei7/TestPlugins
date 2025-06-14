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

            // Log.d("PlushdProvider", "Contenido completo del script para seasonsJson (primeros 1000 caracteres): ${script.take(1000)}")

            // Regex más precisa para extraer solo el bloque JSON.
            // Buscamos 'seasonsJson = ' seguido de '{' y capturamos todo hasta el ';' final
            // que coincide con la estructura del JSON, y no cualquier ';'.
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
                // Eliminar cualquier escape de barra y comillas, es esencial antes del parseo.
                jsonscript = jsonscript.replace("\\/", "/")
                    .replace("\\\"", "\"")

                // **NUEVA LÓGICA DE SANEAMIENTO**
                // 1. Intentar encontrar el balance de llaves y corchetes.
                // 2. Si no se puede, buscar el último cierre válido y truncar.
                var cleanJson = jsonscript
                var openBraces = 0
                var closedBraces = 0
                var openBrackets = 0
                var closedBrackets = 0
                var lastValidIndex = -1

                // Recorrer el JSON para encontrar el último punto válido de cierre
                for (i in cleanJson.indices) {
                    when (cleanJson[i]) {
                        '{' -> openBraces++
                        '}' -> closedBraces++
                        '[' -> openBrackets++
                        ']' -> closedBrackets++
                    }
                    // Si los balances son iguales en este punto, es un posible final válido.
                    // Priorizamos el balance del array de alto nivel (el que contiene las temporadas).
                    // Pero el error es interno, así que buscamos la última posición balanceada.
                    if (openBraces == closedBraces && openBrackets == closedBrackets) {
                        lastValidIndex = i
                    }
                }

                // Si el JSON no está balanceado al final, intentamos truncarlo.
                if (openBraces != closedBraces || openBrackets != closedBrackets) {
                    if (lastValidIndex != -1) {
                        cleanJson = cleanJson.substring(0, lastValidIndex + 1)
                        Log.w("PlushdProvider", "ADVERTENCIA: JSON truncado para balancear llaves/corchetes. JSON parcial: ${cleanJson.take(500)}...")
                    } else {
                        // Último recurso: si el JSON es demasiado inválido para balancear, intentamos cerrar lo que podamos.
                        // Esto es menos ideal y puede llevar a JSONs incorrectos.
                        Log.e("PlushdProvider", "ERROR: JSON malformado severamente, no se pudo encontrar un punto de balance válido. Intentando cierre forzado.")
                        while (openBraces > closedBraces) { cleanJson += "}"; closedBraces++ }
                        while (openBrackets > closedBrackets) { cleanJson += "]"; closedBrackets++ }
                        Log.w("PlushdProvider", "ADVERTENCIA: Cierre forzado de JSON. JSON parcial: ${cleanJson.take(500)}...")
                    }
                }

                // Eliminar comas que estén inmediatamente antes de un cierre de array o llave.
                // Esto es una causa común de JsonParseException.
                cleanJson = cleanJson.replace(",}", "}")
                cleanJson = cleanJson.replace(",]", "]")

                Log.d("PlushdProvider", "JSON final (seasonsJson) antes de parsear: ${cleanJson.take(500)}...")

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
                                    Log.w("PlushdProvider", "ADVERTENCIA: Error al parsear episodio en temporada $seasonKey: ${episodeNode.toString().take(200)}... Error: ${e.message}")
                                }
                            }
                        } else {
                            Log.w("PlushdProvider", "ADVERTENCIA: seasonNode no es un array para la clave $seasonKey: $seasonNode. Esto puede indicar un formato inesperado.")
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

            val playerDoc = app.get(playerUrl).document

            val link = playerDoc.selectFirst("iframe")?.attr("src")
                ?: playerDoc.select("script").firstOrNull { it.html().contains("player.src") || it.html().contains("source src=") }?.let { scriptTag ->
                    val playerSrcMatch = "player\\.src\\s*=\\s*['\"](.*?)['\"]".toRegex().find(scriptTag.html())?.groupValues?.get(1)
                    if (playerSrcMatch != null) return@let playerSrcMatch

                    val sourceSrcMatch = "source\\s+src=['\"](.*?)['\"]".toRegex().find(scriptTag.html())?.groupValues?.get(1)
                    if (sourceSrcMatch != null) return@let sourceSrcMatch

                    null
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