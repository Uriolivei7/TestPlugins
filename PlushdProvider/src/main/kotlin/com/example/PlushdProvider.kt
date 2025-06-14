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
            }

            if (!script.isNullOrEmpty()) {
                val jsonRegex = "seasonsJson\\s*=\\s*(\\{[^;]*\\});".toRegex(RegexOption.DOT_MATCHES_ALL)
                val match = jsonRegex.find(script)

                var jsonscript: String? = null
                if (match != null && match.groupValues.size > 1) {
                    jsonscript = match.groupValues[1]
                }

                if (jsonscript.isNullOrEmpty()) {
                    Log.e("PlushdProvider", "ERROR: No se pudo extraer el JSON de seasonsJson después de 'seasonsJson = ' para la URL: $url")
                } else {
                    jsonscript = jsonscript.replace("\\/", "/")
                        .replace("\\\"", "\"")
                        .replace(Regex("(?<!\\\\)\"[^\"]*$"), "")
                        .replace(Regex(",\\s*\\}"), "}")
                        .trim()

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
                    }
                }
            }
        }

        return when (tvType) {
            TvType.TvSeries -> {
                // CORRECCIÓN AQUÍ: newTvSeriesLoadResponse(title, url, type, episodes)
                newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries, // <-- Este es el TvType correcto para series
                    allEpisodes.sortedBy { it.season } .sortedBy { it.episode } // <-- La lista de episodios
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
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
            val text = app.get("$mainUrl/player/$encodedTwo").text
            val link = linkRegex.find(text)?.destructured?.component1()
            if (link != null) {
                loadExtractor(link, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}