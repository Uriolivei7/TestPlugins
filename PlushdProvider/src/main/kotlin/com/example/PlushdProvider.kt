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
            } else {
                // *** INICIO: MEJORAS EN EL SANEAMIENTO DEL JSON ***
                // Intento de sanear el JSON si está incompleto al final.
                // Verifica si el JSON no termina con '}' y si se encuentra una llave de cierre anterior
                if (!jsonscript.endsWith("}")) {
                    val lastBraceIndex = jsonscript.lastIndexOf("}")
                    if (lastBraceIndex != -1) {
                        // Corta la cadena justo después de la última llave de cierre encontrada
                        jsonscript = jsonscript.substring(0, lastBraceIndex + 1)
                        Log.w("PlushdProvider", "ADVERTENCIA: JSON ajustado para terminar con '}'. JSON parcial: ${jsonscript.take(500)}...")
                    } else {
                        Log.e("PlushdProvider", "ERROR: JSON extraído no es válido y no contiene '}'. No se puede parsear. JSON: ${jsonscript.take(500)}...")
                        return null
                    }
                }

                // Limpieza de caracteres de escape y comillas rotas al final.
                // Esto intenta arreglar casos como "...confia" (donde falta el final de la cadena)
                // Se busca una comilla no escapada que no esté al final y se elimina todo después de ella.
                // Esto es un parche, no una solución robusta para JSONs malformados.
                jsonscript = jsonscript.replace("\\/", "/")
                    .replace("\\\"", "\"")

                // Aquí es donde está el problema principal que vemos en los logs:
                // "title": "Cuesti\u00f3n de confia
                // El JSON se corta en medio de un valor de cadena.
                // Intentaremos encontrar la última comilla NO escapada y si después de ella
                // el JSON no está bien formado, cortar ahí.
                // Esto es heurístico y puede romper JSONs válidos en casos raros.
                var lastQuoteIndex = -1
                var i = jsonscript.length - 1
                while (i >= 0) {
                    if (jsonscript[i] == '"' && (i == 0 || jsonscript[i-1] != '\\')) {
                        lastQuoteIndex = i
                        break
                    }
                    i--
                }

                if (lastQuoteIndex != -1 && !jsonscript.substring(lastQuoteIndex).matches(Regex("\".*\"[\\s\\n\\r]*[\\}\\]]"))) {
                    // Si la última comilla no escapada no cierra una cadena correctamente, cortamos.
                    jsonscript = jsonscript.substring(0, lastQuoteIndex)
                    Log.w("PlushdProvider", "ADVERTENCIA: JSON truncado en la última comilla no escapada debido a formato incorrecto. JSON parcial: ${jsonscript.take(500)}...")
                    // Intentamos cerrar la estructura que creemos que está abierta.
                    // Esto es pura heurística, podría ser un array, un objeto, etc.
                    // Por ahora, simplemente intentamos cerrar el objeto si es el caso.
                    // Si el truncamiento ocurrió dentro de un array, esto puede no funcionar.
                    if (jsonscript.endsWith("{") || jsonscript.endsWith("[")) {
                        // Si termina en { o [, es un objeto/array abierto, dejarlo así
                        // porque el parseJson de Jackson ya manejará el EOF.
                        // El `_reportInvalidEOF` indica que esperaba más.
                    } else if (jsonscript.endsWith(",")) {
                        jsonscript = jsonscript.dropLast(1) // Eliminar la última coma si está ahí
                        Log.w("PlushdProvider", "ADVERTENCIA: Última coma eliminada del JSON. JSON parcial: ${jsonscript.take(500)}...")
                    }
                    jsonscript += "}" // Intentamos cerrar el último objeto
                    Log.w("PlushdProvider", "ADVERTENCIA: Se añadió '}' al final del JSON. JSON parcial: ${jsonscript.take(500)}...")
                }

                jsonscript = jsonscript.trim() // Limpiar espacios extra
                // *** FIN: MEJORAS EN EL SANEAMIENTO DEL JSON ***

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
                    // Si el JSON es irrecuperable después de todos los intentos, podemos retornar null aquí.
                    // Esto evita que la aplicación se bloquee y permite que se muestre como "no disponible".
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
                    allEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
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
            val linkRegex = Regex("window\\.location\\.href\\s*=\\s*'(.*)'")
            val playerUrl = "$mainUrl/player/$encodedTwo"
            val text = app.get(playerUrl).text
            val link = linkRegex.find(text)?.destructured?.component1()

            if (link != null) {
                Log.d("PlushdProvider", "Enlace extraído del player ($playerUrl): $link")
                loadExtractor(link, mainUrl, subtitleCallback, callback)
            } else {
                Log.e("PlushdProvider", "ERROR: No se pudo extraer el enlace del player de $playerUrl")
            }
        }
        return true
    }
}