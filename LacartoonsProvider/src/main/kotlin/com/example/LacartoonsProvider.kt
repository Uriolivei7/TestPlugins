package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

class LacartoonsProvider : MainAPI() { // Asegúrate de que el nombre de la clase coincida con el internalName del plugin
    override var mainUrl = "https://www.lacartoons.com" // Asegúrate de que esta URL sea la correcta
    override var name = "LACartoons" // Nombre visible en CloudStream
    override val supportedTypes = setOf(
        TvType.Cartoon, // Tipo de contenido principal
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val doc = app.get(mainUrl).document // Obtener el documento de la página principal

        // --- Categorías Dinámicas basadas en los botones de la homepage ---
        // Selectores para extraer las categorías (Nickelodeon, Cartoon Network, etc.)
        val categoryForms = doc.select("ul.botontes-categorias li form.button_to")
        val categoryLists = categoryForms.mapNotNull { formElement ->
            val categoryName = formElement.selectFirst("input[type=submit]")?.attr("value")?.trim().orEmpty()
            val categoryId = formElement.selectFirst("input[name=Categoria_id]")?.attr("value")?.trim().orEmpty()

            if (categoryName.isNotBlank() && categoryId.isNotBlank()) {
                Log.d("LACartoons", "Categoría encontrada: $categoryName con ID: $categoryId")

                // Para cada categoría, realizamos una "búsqueda" simulada usando la ID de categoría.
                // El método search se encargará de procesar esta ID.
                // Aquí solo necesitamos un SearchResponse con la URL correcta para la búsqueda.
                // Como no hay una URL de página de categoría "limpia", usaremos la URL de búsqueda.
                val categorySearchUrl = "$mainUrl/recherche?Categoria_id=$categoryId"

                // Opcional: Para mostrar algunos ítems de la categoría en la página principal,
                // podríamos cargar la URL de búsqueda de la categoría aquí.
                // Esto podría hacer la carga de la página principal más lenta si hay muchas categorías.
                // Por simplicidad y rendimiento, por ahora solo crearemos una lista vacía,
                // y los elementos reales se cargarán cuando el usuario haga clic en la categoría.
                // Si quieres que muestre elementos directamente, necesitarías otra solicitud
                // a categorySearchUrl y extraer los elementos como en la función search().

                // Para que CloudStream muestre algo, podemos cargar la primera página de esa "categoría"
                val categoryDocForList = app.get(categorySearchUrl).document
                val categoryItems = categoryDocForList.select("div.shortstory-in").mapNotNull {
                    val titleElement = it.selectFirst("h4.short-link a")
                    val title = titleElement?.text()?.trim().orEmpty()
                    val link = titleElement?.attr("href")?.trim().orEmpty()
                    val img = it.selectFirst("div.short-images img")?.attr("src")?.trim().orEmpty()

                    if (title.isNotBlank() && link.isNotBlank()) {
                        newTvSeriesSearchResponse(
                            title,
                            fixUrl(link)
                        ) {
                            this.type = TvType.Cartoon
                            this.posterUrl = fixUrl(img)
                        }
                    } else {
                        null
                    }
                }
                HomePageList(categoryName, categoryItems)
            } else {
                Log.w("LACartoons", "Saltando categoría incompleta. Nombre: $categoryName, ID: $categoryId")
                null
            }
        }
        items.addAll(categoryLists)
        // --- Fin de Categorías Dinámicas ---

        // Tu lógica existente para "Últimas Caricaturas Agregadas"
        val urls = listOf(
            Pair("Últimas Caricaturas Agregadas", "$mainUrl/lista-de-caricaturas.html"),
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = TvType.Cartoon
            val docList = app.get(url).document
            val homeItems = docList.select("div.movs article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text()?.trim().orEmpty()
                val link = it.selectFirst("a")?.attr("href")?.trim().orEmpty()
                val img = it.selectFirst("div.poster img")?.attr("data-src")
                    ?: it.selectFirst("div.poster img")?.attr("src")?.trim().orEmpty()

                if (title.isNotBlank() && link.isNotBlank()) {
                    newTvSeriesSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = fixUrl(img)
                    }
                } else {
                    null
                }
            }
            HomePageList(name, homeItems)
        }
        items.addAll(homePageLists)

        // Lógica para el slider de Destacadas
        val sliderItems = doc.select("div#owl-slider div.owl-item div.shortstory").mapNotNull {
            val tvType = TvType.Cartoon
            val title = it.selectFirst("h4.short-link a")?.text()?.trim().orEmpty()
            val link = it.selectFirst("h4.short-link a")?.attr("href")?.trim().orEmpty()
            val img = it.selectFirst("div.short-images a img")?.attr("src")?.trim().orEmpty()

            if (title.isNotBlank() && link.isNotBlank()) {
                newTvSeriesSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = fixUrl(img)
                }
            } else {
                null
            }
        }
        if (sliderItems.isNotEmpty()) {
            items.add(0, HomePageList("Destacadas", sliderItems))
        }

        Log.d("LACartoons", "Final number of HomePageLists: ${items.size}")
        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // La URL de búsqueda puede venir de dos maneras:
        // 1. Una búsqueda de texto normal: ?q=texto
        // 2. Una búsqueda por categoría: ?Categoria_id=X
        val url = if (query.startsWith("Categoria_id=")) {
            "$mainUrl/recherche?$query"
        } else {
            "$mainUrl/recherche?q=$query"
        }

        val doc = app.get(url).document
        Log.d("LACartoons", "SEARCH_DOC_HTML - (Primeros 1000 chars) ${doc.html().take(1000)}")

        return doc.select("div.shortstory-in").mapNotNull {
            val tvType = TvType.Cartoon // Ajusta el tipo de TV según el contenido

            val titleElement = it.selectFirst("h4.short-link a")
            val title = titleElement?.text()?.trim().orEmpty()
            val link = titleElement?.attr("href")?.trim().orEmpty()

            val img = it.selectFirst("div.short-images img")?.attr("src")?.trim().orEmpty()

            if (title.isNotBlank() && link.isNotBlank()) {
                Log.d("LACartoons", "SEARCH_ITEM_FOUND: Title=$title, Link=$link, Img=$img")
                newTvSeriesSearchResponse( // O newMovieSearchResponse si son películas
                    title,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = fixUrl(img)
                }
            } else {
                Log.w("LACartoons", "SEARCH_ITEM_SKIPPED: Missing info. Title=$title, Link=$link")
                null
            }
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("LACartoons", "LOAD_START - URL de entrada: $url")

        var cleanUrl = url
        val urlJsonMatch = Regex("""\{"url":"(https?:\/\/[^"]+)"\}""").find(url)
        if (urlJsonMatch != null) {
            cleanUrl = urlJsonMatch.groupValues[1]
            Log.d("LACartoons", "LOAD_URL - URL limpia por JSON Regex: $cleanUrl")
        } else {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://" + cleanUrl.removePrefix("//")
                Log.d("LACartoons", "LOAD_URL - URL limpiada con HTTPS: $cleanUrl")
            }
            Log.d("LACartoons", "LOAD_URL - URL no necesitaba limpieza JSON Regex, usando original/ajustada: $cleanUrl")
        }

        if (cleanUrl.isBlank()) {
            Log.e("LACartoons", "LOAD_ERROR - URL limpia está en blanco.")
            return null
        }

        val doc = try {
            app.get(cleanUrl).document
        } catch (e: Exception) {
            Log.e("LACartoons", "LOAD_ERROR - No se pudo obtener el documento principal de: $cleanUrl. ${e.message}", e)
            return null
        }
        val tvType = TvType.Cartoon // Ajusta el tipo de TV según el contenido

        // Los selectores de load también pueden necesitar ser ajustados si la estructura de detalle
        // de lacartoons.com es diferente a la de veronline.cfd

        val title = doc.selectFirst("div.fstory-infos h1.fstory-h1")?.text()?.replace("ver serie ", "")?.replace(" Online gratis HD", "")?.trim().orEmpty()
            .ifBlank { doc.selectFirst("h1.fstory-h1")?.text()?.trim().orEmpty() } // Fallback si el primero no funciona
        val poster = doc.selectFirst("div.fstory-poster-in img")?.attr("src")?.trim().orEmpty()
            .ifBlank { doc.selectFirst("div.short-images img")?.attr("src")?.trim().orEmpty() } // Fallback para poster
        val description = doc.selectFirst("div.block-infos p")?.text()?.trim().orEmpty()
        val tags = doc.select("div.finfo-block a[href*='/series-online/']").map { it.text().trim().orEmpty() }

        val actors = doc.select("div.finfo-block:has(span:contains(Actores)) a[href*='/series-online/actor/']").mapNotNull {
            val actorName = it.text().trim()
            if (actorName.isNotBlank()) {
                ActorData(actor = Actor(actorName))
            } else {
                null
            }
        }
        Log.d("LACartoons", "LOAD_ACTORS - Extracted ${actors.size} actors.")

        val directors = doc.select("div.finfo-block:has(span:contains(director)) a[href*='/series-online/director/']").mapNotNull {
            it.text().trim().orEmpty()
        }
        Log.d("LACartoons", "LOAD_DIRECTORS - Extracted ${directors.size} directors.")

        val allEpisodes = ArrayList<Episode>()

        // Lógica de episodios (si son series/caricaturas con episodios)
        val episodeElements = doc.select("div#serie-episodes div.episode-list div.saision_LI2")
        Log.d("LACartoons", "LOAD_EPISODES - Encontrados ${episodeElements.size} elementos de episodio en la página principal.")

        if (episodeElements.isNotEmpty()) {
            val defaultSeasonNumber = 1 // Si no hay temporadas explícitas, asumir temporada 1
            val mainPageEpisodes = episodeElements.mapNotNull { element ->
                val epurl = fixUrl(element.selectFirst("a")?.attr("href")?.trim().orEmpty())
                val epTitle = element.selectFirst("a span")?.text()?.trim().orEmpty()
                val episodeNumber = epTitle.replace(Regex("Capítulo\\s*"), "").toIntOrNull()

                if (epurl.isNotBlank() && epTitle.isNotBlank() && episodeNumber != null) {
                    newEpisode(
                        EpisodeLoadData(epTitle, epurl).toJson()
                    ) {
                        this.name = epTitle
                        this.season = defaultSeasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = fixUrl(poster)
                    }
                } else {
                    Log.w("LACartoons", "LOAD_EPISODES - Saltando episodio directo incompleto. Título: $epTitle, URL: $epurl, Número: $episodeNumber")
                    null
                }
            }
            allEpisodes.addAll(mainPageEpisodes)
            Log.d("LACartoons", "LOAD_EPISODES - Total de episodios extraídos directamente: ${mainPageEpisodes.size}")
        } else {
            Log.d("LACartoons", "LOAD_EPISODES - No se encontraron episodios directos. Podría ser una película o una estructura diferente.")
            // Considerar lógica para películas o un solo video aquí si aplica
        }


        Log.d("LACartoons", "LOAD_END - Total de episodios/videos recolectados: ${allEpisodes.size}")

        return newTvSeriesLoadResponse( // O newMovieLoadResponse si es una película
            name = title,
            url = cleanUrl,
            type = tvType,
            episodes = allEpisodes, // Si es una película, esto puede ser una lista con un solo episodio
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = description
            this.tags = tags
            this.actors = actors
            if (directors.isNotEmpty()) {
                this.plot = (this.plot ?: "") + "\n\nDirectores: " + directors.joinToString(", ")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("LACartoons", "LOADLINKS_START - Data de entrada: $data")

        var cleanedData = data
        val regexExtractUrl = Regex("""(https?:\/\/[^"'\s)]+)""")
        val match = regexExtractUrl.find(cleanedData)

        if (match != null) {
            cleanedData = match.groupValues[1]
            Log.d("LACartoons", "LOADLINKS_URL - Data limpia por Regex (primer intento): $cleanedData")
        } else {
            Log.d("LACartoons", "LOADLINKS_URL - Regex inicial no encontró coincidencia. Usando data original: $cleanedData")
        }

        val targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(cleanedData)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("LACartoons", "LOADLINKS_URL - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(cleanedData)
            Log.d("LACartoons", "LOADLINKS_URL - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("LACartoons", "LOADLINKS_ERROR - URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("LACartoons", "LOADLINKS_ERROR - No se pudo obtener el documento del episodio: $targetUrl. ${e.message}", e)
            return false
        }

        Log.d("LACartoons", "LOADLINKS_DOC - Contenido de la página del episodio (primeros 500 chars): ${doc.html().take(500)}")

        val playerDivs = doc.select("div.player[data-url]")
        Log.d("LACartoons", "LOADLINKS_PLAYERS - Encontrados ${playerDivs.size} elementos 'div.player' con 'data-url'.")

        val results = playerDivs.apmap { playerDivElement ->
            val encodedUrl = playerDivElement.attr("data-url").orEmpty()
            val serverName = playerDivElement.selectFirst("span[id^=player_v_DIV_5]")?.text()?.trim().orEmpty()
            Log.d("LACartoons", "LOADLINKS_PLAYERS - Procesando servidor: $serverName, encodedUrl: $encodedUrl")

            if (encodedUrl.isNotBlank() && (encodedUrl.startsWith("/streamer/") || encodedUrl.startsWith("/telecharger/"))) {
                try {
                    val base64Part = if (encodedUrl.startsWith("/streamer/")) {
                        encodedUrl.substringAfter("/streamer/")
                    } else {
                        encodedUrl.substringAfter("/telecharger/")
                    }

                    val decodedBytes = Base64.decode(base64Part, Base64.DEFAULT)
                    val decodedUrl = String(decodedBytes, UTF_8)

                    val fullIframeUrl = if (decodedUrl.startsWith("http")) {
                        decodedUrl
                    } else {
                        "$mainUrl$decodedUrl"
                    }

                    Log.d("LACartoons", "LOADLINKS_PLAYERS - Decoded URL for $serverName: $fullIframeUrl")

                    // *** MODIFICACIÓN PARA DELEGAR SIEMPRE A loadExtractor ***
                    // Se elimina cualquier lógica específica de host y se confía en CloudStream
                    Log.d("LACartoons", "LOADLINKS_DELEGATING - Delegando a CloudStream's loadExtractor: $fullIframeUrl")
                    loadExtractor(fullIframeUrl, targetUrl, subtitleCallback, callback)

                } catch (e: Exception) {
                    Log.e("LACartoons", "LOADLINKS_PLAYERS_ERROR - Error al decodificar o procesar data-url para $serverName ($encodedUrl): ${e.message}", e)
                    false
                }
            } else {
                Log.w("LACartoons", "LOADLINKS_PLAYERS_WARN - data-url vacía o no comienza con '/streamer/' o '/telecharger/' para servidor $serverName: $encodedUrl")
                false
            }
        }

        val finalLinksFound = results.any { it }

        if (finalLinksFound) {
            Log.d("LACartoons", "LOADLINKS_END - Se encontraron y procesaron enlaces de video.")
            return true
        }

        Log.w("LACartoons", "LOADLINKS_WARN - No se encontraron enlaces de video válidos después de todas las comprobaciones.")
        return false
    }
}