package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher // Ya no necesario para uqload.net directo, pero lo dejamos si otros servidores lo usan
import javax.crypto.spec.IvParameterSpec // Ya no necesario
import javax.crypto.spec.SecretKeySpec // Ya no necesario
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
            val tvType = TvType.TvSeries
            val title = it.selectFirst("h4.short-link a")?.text() ?: ""
            val link = it.selectFirst("h4.short-link a")?.attr("href") ?: ""
            val img = it.selectFirst("div.short-images a img")?.attr("src") ?: ""

            if (title.isNotBlank() && link.isNotBlank()) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
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
            val tvType = TvType.TvSeries
            val title = it.selectFirst("h3.title a")?.text() ?: ""
            val link = it.selectFirst("h3.title a")?.attr("href") ?: ""
            val img = it.selectFirst("img")?.attr("src") ?: ""

            if (title.isNotBlank() && link.isNotBlank()) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
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
        Log.d("Veronline", "LOAD_START - URL de entrada: $url")

        var cleanUrl = url
        // Intento de limpiar la URL si viene con formato JSON (CloudStream a veces lo hace)
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("Veronline", "LOAD_URL - URL limpia por JSON Regex: $cleanUrl")
        } else {
            // Asegura que la URL tenga https:// si no la tiene
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("Veronline", "LOAD_URL - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("Veronline", "LOAD_URL - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("Veronline", "LOAD_ERROR - URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            Log.e("Veronline", "LOAD_ERROR - No se pudo obtener el documento principal de: $cleanUrl. ${e.message}", e)
            return null
        }
        val tvType = TvType.TvSeries

        val title = doc.selectFirst("div.fstory-infos h1.fstory-h1")?.text()?.replace("ver serie ", "")?.replace(" Online gratis HD", "") ?: ""
        val poster = doc.selectFirst("div.fstory-poster-in img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.block-infos p")?.text() ?: ""
        val tags = doc.select("div.finfo-block a[href*='/series-online/']").map { it.text() }

        val actors = doc.select("div.finfo-block:has(span:contains(Actores)) a[href*='/series-online/actor/']").mapNotNull {
            val actorName = it.text().trim()
            if (actorName.isNotBlank()) {
                ActorData(actor = Actor(actorName))
            } else {
                null
            }
        }
        Log.d("Veronline", "LOAD_ACTORS - Extracted ${actors.size} actors.")

        val directors = doc.select("div.finfo-block:has(span:contains(director)) a[href*='/series-online/director/']").mapNotNull {
            it.text().trim()
        }
        Log.d("Veronline", "LOAD_DIRECTORS - Extracted ${directors.size} directors.")

        val allEpisodes = ArrayList<Episode>()

        val seasonElements = doc.select("div#full-video div#serie-seasons div.shortstory-in")
        Log.d("Veronline", "LOAD_EPISODES - Encontradas ${seasonElements.size} temporadas.")

        if (seasonElements.isEmpty() && doc.select("div#serie-episodes").isNotEmpty()) {
            Log.d("Veronline", "LOAD_EPISODES - No se encontraron elementos de temporada, intentando extraer episodios directamente de la página principal.")
            val defaultSeasonNumber = 1
            val mainPageEpisodes = doc.select("div#serie-episodes div.episode-list div.saision_LI2").mapNotNull { element ->
                val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                val epTitle = element.selectFirst("a span")?.text() ?: ""
                val episodeNumber = epTitle.replace(Regex("Capítulo\\s*"), "").toIntOrNull()

                if (epurl.isNotBlank() && epTitle.isNotBlank() && episodeNumber != null) {
                    Log.d("Veronline", "LOAD_EPISODES - Episodio directo: Título='$epTitle', URL='$epurl', Temporada=$defaultSeasonNumber, Episodio=$episodeNumber")
                    newEpisode(
                        EpisodeLoadData(epTitle, epurl).toJson()
                    ) {
                        this.name = epTitle
                        this.season = defaultSeasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = fixUrl(poster)
                    }
                } else {
                    Log.w("Veronline", "LOAD_EPISODES - Saltando episodio directo incompleto. Título: $epTitle, URL: $epurl, Número: $episodeNumber")
                    null
                }
            }
            allEpisodes.addAll(mainPageEpisodes)
            Log.d("Veronline", "LOAD_EPISODES - Total de episodios extraídos directamente: ${mainPageEpisodes.size}")

        } else {
            seasonElements.apmap { seasonElement ->
                val seasonTitle = seasonElement.selectFirst("h4.short-link a")?.text() ?: ""
                val seasonLink = seasonElement.selectFirst("h4.short-link a")?.attr("href") ?: ""
                val seasonPoster = seasonElement.selectFirst("div.short-images a img")?.attr("src") ?: ""

                Log.d("Veronline", "LOAD_SEASON - Procesando elemento de temporada: Título='$seasonTitle', Enlace='$seasonLink'")

                if (seasonLink.isNotBlank() && seasonTitle.isNotBlank()) {
                    val fullSeasonUrl = fixUrl(seasonLink)
                    val seasonNumber = seasonTitle.replace(Regex(".*Temporada\\s*"), "").toIntOrNull()
                    Log.d("Veronline", "LOAD_SEASON - Extracted Season Number: $seasonNumber (from '$seasonTitle')")

                    if (seasonNumber == null) {
                        Log.w("Veronline", "LOAD_SEASON - No se pudo extraer el número de temporada de: $seasonTitle. Saltando esta temporada.")
                        return@apmap
                    }

                    Log.d("Veronline", "LOAD_SEASON - Cargando página de temporada: $fullSeasonUrl")
                    val seasonDoc = try {
                        app.get(fullSeasonUrl).document
                    } catch (e: Exception) {
                        Log.e("Veronline", "LOAD_SEASON_ERROR - No se pudo obtener la página de la temporada $seasonTitle ($fullSeasonUrl): ${e.message}", e)
                        return@apmap
                    }

                    Log.d("Veronline", "LOAD_SEASON_DOC - Contenido de seasonDoc (primeros 500 chars): ${seasonDoc.html().take(500)}")

                    val episodesInSeason = seasonDoc.select("div#serie-episodes div.episode-list div.saision_LI2").mapNotNull { element ->
                        val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                        val epTitle = element.selectFirst("a span")?.text() ?: ""
                        val episodeNumber = epTitle.replace(Regex("Capítulo\\s*"), "").toIntOrNull()

                        if (epurl.isNotBlank() && epTitle.isNotBlank() && episodeNumber != null) {
                            Log.d("Veronline", "LOAD_EPISODE_DETAIL - Episodio encontrado: Título='$epTitle', URL='$epurl', Temporada=$seasonNumber, Episodio=$episodeNumber")
                            newEpisode(
                                EpisodeLoadData(epTitle, epurl).toJson()
                            ) {
                                this.name = epTitle
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.posterUrl = fixUrl(seasonPoster)
                            }
                        } else {
                            Log.w("Veronline", "LOAD_EPISODE_DETAIL - Saltando episodio incompleto. Título: $epTitle, URL: $epurl, T: $seasonNumber, E: $episodeNumber")
                            null
                        }
                    }
                    Log.d("Veronline", "LOAD_SEASON - Temporada $seasonTitle: Encontrados ${episodesInSeason.size} episodios.")
                    allEpisodes.addAll(episodesInSeason)
                } else {
                    Log.w("Veronline", "LOAD_SEASON_WARN - Elemento de temporada incompleto: Título='$seasonTitle', Enlace='$seasonLink'. Saltando.")
                }
            }
        }

        Log.d("Veronline", "LOAD_END - Total de episodios recolectados: ${allEpisodes.size}")


        return newTvSeriesLoadResponse(
            name = title,
            url = cleanUrl,
            type = tvType,
            episodes = allEpisodes,
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = description
            this.tags = tags
            this.actors = actors
            if (directors.isNotEmpty()) {
                this.plot = this.plot + "\n\nDirectores: " + directors.joinToString(", ")
            }
        }
    }

    // `decryptLink` y las clases `DataLinkEntry`, `SortedEmbed`
    // Ya no son necesarias para uqload.net directo.
    // Si otros servidores en Veronline usan AES u otra lógica compleja,
    // deberías mantener estas partes y usarlas condicionalmente.
    // Por ahora, las comento para centrarme en la solución de uqload.net.

    // data class SortedEmbed(val servername: String, val link: String, val type: String)
    // data class DataLinkEntry(val file_id: String, val video_language: String, val sortedEmbeds: List<SortedEmbed>)
    // private fun decryptLink(encryptedLinkBase64: String, secretKey: String): String? { /* ... */ }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Veronline", "LOADLINKS_START - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(data)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("Veronline", "LOADLINKS_URL - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("Veronline", "LOADLINKS_URL - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Veronline", "LOADLINKS_URL - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("Veronline", "LOADLINKS_URL - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Veronline", "LOADLINKS_ERROR - URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("Veronline", "LOADLINKS_ERROR - No se pudo obtener el documento del episodio: $targetUrl. ${e.message}", e)
            return false
        }

        Log.d("Veronline", "LOADLINKS_DOC - Contenido de la página del episodio (primeros 500 chars): ${doc.html().take(500)}")

        // --- INICIO DE LA LÓGICA REVISADA PARA EXTRAER EL VIDEO DE UQLOAD.NET ---

        // Primero, busca el iframe principal del reproductor
        val iframeSrc = doc.selectFirst("div#player iframe")?.attr("src")

        if (!iframeSrc.isNullOrBlank()) {
            Log.d("Veronline", "LOADLINKS_UQLOAD - Iframe principal encontrado: $iframeSrc")

            // Verificar si el iframe es de uqload.net
            if (iframeSrc.contains("uqload.net")) {
                Log.d("Veronline", "LOADLINKS_UQLOAD - Iframe es de uqload.net. Intentando extraer el enlace directo del MP4.")
                val uqloadIframeDoc = try {
                    app.get(fixUrl(iframeSrc)).document // Acceder al contenido del iframe
                } catch (e: Exception) {
                    Log.e("Veronline", "LOADLINKS_UQLOAD_ERROR - No se pudo obtener el documento del iframe de uqload: $iframeSrc. ${e.message}", e)
                    return false
                }

                // Buscar la URL del MP4 dentro del script del iframe de uqload.net
                val scriptContent = uqloadIframeDoc.select("script").map { it.html() }.joinToString("\n")
                Log.d("Veronline", "LOADLINKS_UQLOAD - Script content del iframe (primeros 500 chars): ${scriptContent.take(500)}")

                // REGEX para encontrar el patrón de URL del MP4 de uqload.net
                // m40.uqload.net/CADENA_LARGA/v.mp4
                val uqloadMp4Regex = Regex("""(https?:\/\/[a-z0-9]+\.uqload\.net\/[a-zA-Z0-9]+\/v\.mp4)""")
                val uqloadMp4Match = uqloadMp4Regex.find(scriptContent)

                if (uqloadMp4Match != null) {
                    val directMp4Url = uqloadMp4Match.groupValues[1]
                    Log.d("Veronline", "LOADLINKS_UQLOAD_SUCCESS - ¡URL de MP4 de uqload.net encontrada!: $directMp4Url")

                    // --- CAMBIO AQUÍ: Uso del constructor de ExtractorLink con Int para quality y ExtractorLinkType ---
                    // Estimamos una calidad de 720p (puedes ajustarla si sabes la calidad real)
                    val estimatedQuality = 720

                    callback.invoke(
                        ExtractorLink(
                            source = name, // Nombre de tu proveedor ("Veronline")
                            name = "Uqload MP4", // Un nombre descriptivo para el enlace
                            url = directMp4Url,
                            referer = mainUrl, // O puedes usar iframeSrc como referer si prefieres
                            quality = estimatedQuality, // Calidad como Int
                            headers = mapOf(), // No se necesitan encabezados personalizados por ahora
                            extractorData = null,
                            type = ExtractorLinkType.VIDEO // Tipo de enlace VIDEO para MP4 directo
                        )
                    )
                    return true
                } else {
                    Log.w("Veronline", "LOADLINKS_UQLOAD_WARN - No se encontró la URL de MP4 de uqload.net en los scripts del iframe.")
                }
            } else {
                Log.d("Veronline", "LOADLINKS_IFRAME - El iframe principal no es de uqload.net, intentando cargarlo como un extractor general: $iframeSrc")
                // Si no es uqload.net, se intentará cargar con los extractores predeterminados de CloudStream
                loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
                return true
            }
        } else {
            Log.w("Veronline", "LOADLINKS_IFRAME_WARN - No se encontró iframe del reproductor con el selector 'div#player iframe'.")
        }

        // --- FIN DE LA LÓGICA REVISADA PARA UQLOAD.NET ---

        // Lógica anterior para otros enlaces directos si se encuentran en los scripts de la página principal
        val scriptContentPage = doc.select("script").map { it.html() }.joinToString("\n")

        val directRegex = """(https?:\/\/[^'"]+?(?:.m3u8|.mp4|embed|player|file|stream)[^'"]*)""".toRegex()
        val directMatches = directRegex.findAll(scriptContentPage).map { it.groupValues[1] }.toList()

        if (directMatches.isNotEmpty()) {
            Log.d("Veronline", "LOADLINKS_DIRECT - Encontrados ${directMatches.size} enlaces directos potenciales en scripts de la página principal.")
            directMatches.apmap { directUrl ->
                Log.d("Veronline", "LOADLINKS_DIRECT - Intentando cargar extractor para enlace directo: $directUrl")
                loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
            }
            return true
        }
        Log.d("Veronline", "LOADLINKS_DIRECT - No se encontraron enlaces directos en scripts de la página principal.")

        Log.w("Veronline", "LOADLINKS_WARN - No se encontraron enlaces de video válidos después de todas las comprobaciones.")
        return false
    }
}