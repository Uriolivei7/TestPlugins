package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller // Posiblemente no necesites CloudflareKiller si el sitio no lo usa
import com.lagradost.cloudstream3.utils.* // Esto importa ExtractorLink, SubtitleFile, etc.
// NO NECESITARÍAS AppUtils.toJson / tryParseJson con Jackson
import com.lagradost.cloudstream3.APIHolder.unixTime // Solo si realmente lo usas, si no, puedes quitarlo

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document // Importante para la variable 'doc'

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayList
import kotlin.text.Charsets.UTF_8

// Jackson imports para JSON
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue // Extensión para readValue

class PlayhubProvider : MainAPI() {
    override var mainUrl = "https://playhublite.com"
    override var name = "PlayHub"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // Nueva URL base para la API
    private val apiBaseUrl = "http://v3.playhublite.com/api"
    // URL base para las imágenes de TheMovieDB (comunes para poster_path)
    private val tmdbImageBaseUrl = "https://image.tmdb.org/t/p/w500" // w500 es un buen tamaño

    // Instancia de Jackson ObjectMapper - ¡Inicializar una sola vez!
    private val jacksonMapper = ObjectMapper().registerModule(KotlinModule())

    // Data class para parsear la respuesta JSON de la API
    data class ApiListResponse(
        @JsonProperty("current_page") val current_page: Int,
        @JsonProperty("data") val data: List<ApiListItem>,
        @JsonProperty("first_page_url") val first_page_url: String?,
        @JsonProperty("from") val from: Int?,
        @JsonProperty("next_page_url") val next_page_url: String?,
        @JsonProperty("path") val path: String?,
        @JsonProperty("per_page") val per_page: Int,
        @JsonProperty("prev_page_url") val prev_page_url: String?,
        @JsonProperty("to") val to: Int?
    )

    // Data class más genérica para manejar tanto 'title' (películas) como 'name' (series)
    data class ApiListItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?, // Puede ser nulo para series
        @JsonProperty("name") val name: String?, // Puede ser nulo para películas
        @JsonProperty("poster_path") val poster_path: String,
        @JsonProperty("backdrop_path") val backdrop_path: String? // Puede ser nulo
    ) {
        // Propiedad calculada para obtener el título, priorizando 'title'
        val displayTitle: String
            get() = title ?: name ?: "Título Desconocido" // Si ambos son nulos, un fallback
    }

    // Data class para pasar información de episodio en JSON (SERIALIZABLE CON JACKSON)
    data class EpisodeLoadData(
        @JsonProperty("title") val title: String,
        @JsonProperty("url") val url: String
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val apiUrls = listOf(
            Pair("Películas", "$apiBaseUrl/movies"),
            Pair("Series", "$apiBaseUrl/series"),
        )

        val homePageLists = apiUrls.apmap { (name, apiUrl) ->
            Log.d("PlayHubLite", "getMainPage: Intentando obtener la categoría '$name' de API URL: $apiUrl?page=$page")
            val tvType = when (name) {
                "Películas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                else -> TvType.Others
            }

            val response = try {
                app.get("$apiUrl?page=$page").parsed<ApiListResponse>()
            } catch (e: Exception) {
                Log.e("PlayHubLite", "getMainPage: ERROR al obtener la respuesta JSON de $apiUrl para '$name': ${e.message}", e)
                return@apmap null // Continuar con la siguiente URL si hay un error
            }

            if (response == null || response.data.isEmpty()) {
                Log.w("PlayHubLite", "getMainPage: La respuesta JSON para '$name' está vacía o es nula de $apiUrl. No se encontraron datos.")
                return@apmap null
            }
            Log.d("PlayHubLite", "getMainPage: Respuesta JSON obtenida para '$name'. Items encontrados: ${response.data.size}")

            val homeItems = response.data.mapNotNull { apiItem ->
                val actualTitle = apiItem.displayTitle
                if (actualTitle == "Título Desconocido") {
                    Log.w("PlayHubLite", "getMainPage: Categoría '$name' - Item API con título desconocido. ID: ${apiItem.id}")
                    return@mapNotNull null
                }

                // Es crucial que esta URL coincida con la que "load" puede procesar.
                // Basado en "https://playhublite.com/series/85077/The-Chosen-(Los-elegidos)"
                val link = "$mainUrl/${if (tvType == TvType.Movie) "movies" else "series"}/${apiItem.id}/${actualTitle.toSlug()}"

                val posterUrl = "$tmdbImageBaseUrl${apiItem.poster_path}"

                Log.d("PlayHubLite", "getMainPage: Categoría '$name' - Item API: Título='$actualTitle', Link='$link', Poster='$posterUrl'")

                newMovieSearchResponse(
                    actualTitle,
                    fixUrl(link)
                ) {
                    this.type = tvType
                    this.posterUrl = posterUrl
                }
            }

            if (homeItems.isNotEmpty()) {
                Log.d("PlayHubLite", "getMainPage: Añadida lista '$name' con ${homeItems.size} items desde API.")
                HomePageList(name, homeItems)
            } else {
                Log.w("PlayHubLite", "getMainPage: La lista '$name' está vacía después de procesar la API en $apiUrl.")
                null
            }
        }.filterNotNull() // Filtra cualquier resultado nulo de apmap

        items.addAll(homePageLists)

        Log.d("PlayHubLite", "getMainPage: Total de HomePageLists finales creadas: ${items.size}")
        return newHomePageResponse(items, homePageLists.any { it.list.isNotEmpty() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchApiUrl = "$apiBaseUrl/search?query=$query"
        Log.d("PlayHubLite", "search: Buscando en API URL: $searchApiUrl")

        val response = try {
            app.get(searchApiUrl).parsed<ApiListResponse>()
        } catch (e: Exception) {
            Log.e("PlayHubLite", "search: ERROR al obtener la respuesta JSON de búsqueda: ${e.message}", e)
            return emptyList()
        }

        if (response == null || response.data.isEmpty()) {
            Log.w("PlayHubLite", "search: La respuesta JSON de búsqueda está vacía o es nula de $searchApiUrl. No se encontraron resultados.")
            return emptyList()
        }
        Log.d("PlayHubLite", "search: Resultados de búsqueda API encontrados: ${response.data.size}")

        return response.data.mapNotNull { apiItem ->
            val actualTitle = apiItem.displayTitle
            if (actualTitle == "Título Desconocido") {
                Log.w("PlayHubLite", "search: Item API de búsqueda con título desconocido. ID: ${apiItem.id}")
                return@mapNotNull null
            }

            // Para la búsqueda, construimos una URL genérica para que 'load' la resuelva.
            // Usamos "auto" para que 'load' intente determinar si es película o serie.
            val link = "$mainUrl/auto/${apiItem.id}/${actualTitle.toSlug()}"

            val posterUrl = "$tmdbImageBaseUrl${apiItem.poster_path}"

            Log.d("PlayHubLite", "search: Item API: Título='$actualTitle', Link='$link', Poster='$posterUrl'")
            newMovieSearchResponse(
                actualTitle,
                fixUrl(link)
            ) {
                // Este tvType es provisional para la búsqueda, 'load' lo determinará
                this.type = TvType.TvSeries
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("PlayHubLite", "load - URL de entrada: $url")

        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://" + url.removePrefix("//")
        } else {
            url
        }
        Log.d("PlayHubLite", "load - URL limpia/ajustada: $cleanUrl")

        if (cleanUrl.isBlank()) {
            Log.e("PlayHubLite", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        // --- NUEVA LÓGICA DE PARSEO DE URL PARA LA FUNCIÓN LOAD ---
        // Extrae el ID y el tipo (movies/series/auto) de la URL
        val regex = Regex("""/(movies|series|auto)/(\d+)/(.+)""")
        val matchResult = regex.find(cleanUrl)

        val itemId = matchResult?.groupValues?.getOrNull(2)?.toIntOrNull()
        val itemTypeString = matchResult?.groupValues?.getOrNull(1) // "movies", "series", "auto"
        val itemSlug = matchResult?.groupValues?.getOrNull(3) // El slug de la URL

        if (itemId == null || itemSlug == null) {
            Log.e("PlayHubLite", "load - ERROR: No se pudo extraer ID o slug de la URL: $cleanUrl")
            return null
        }

        // Declara 'doc' aquí para que sea accesible en todo el scope de la función
        val doc: Document
        val detectedTvType: TvType // Usaremos este para construir la respuesta final

        if (itemTypeString == "auto") {
            // Si viene de búsqueda ("auto"), intentamos resolver si es película o serie
            val movieUrlCandidate = "$mainUrl/movies/$itemId/$itemSlug"
            val seriesUrlCandidate = "$mainUrl/series/$itemId/$itemSlug"

            var tempDoc = try {
                app.get(movieUrlCandidate).document
            } catch (e: Exception) {
                Log.e("PlayHubLite", "load - ERROR al intentar cargar URL de película ($movieUrlCandidate): ${e.message}", e)
                null
            }

            // Un criterio de éxito: tamaño del HTML y si encontramos el selector de info de película
            if (tempDoc != null && tempDoc.html().length > 1500 && tempDoc.selectFirst("div.movie-info h1, h1.text-white") != null) {
                doc = tempDoc
                detectedTvType = TvType.Movie
                Log.d("PlayHubLite", "load - 'auto' resuelto a Película: $movieUrlCandidate")
            } else {
                // Si la URL de película no funcionó o el contenido es muy pequeño, intenta la de serie
                tempDoc = try {
                    app.get(seriesUrlCandidate).document
                } catch (e: Exception) {
                    Log.e("PlayHubLite", "load - ERROR al intentar cargar URL de serie ($seriesUrlCandidate): ${e.message}", e)
                    null
                }

                // Un criterio de éxito: tamaño del HTML y si encontramos el selector de lista de temporadas
                if (tempDoc != null && tempDoc.html().length > 1500 && tempDoc.selectFirst("div.seasons-list") != null) {
                    doc = tempDoc
                    detectedTvType = TvType.TvSeries
                    Log.d("PlayHubLite", "load - 'auto' resuelto a Serie: $seriesUrlCandidate")
                } else {
                    Log.e("PlayHubLite", "load - ERROR: 'auto' no pudo resolverse a película o serie válida para ID $itemId. Probadas: $movieUrlCandidate, $seriesUrlCandidate")
                    return null // No se pudo determinar el tipo ni cargar la página.
                }
            }
        } else {
            // Si la URL ya es /movies/ID/SLUG o /series/ID/SLUG
            val finalDetailPageUrl = cleanUrl // Usa cleanUrl, que ya es la URL de detalle
            detectedTvType = if (itemTypeString == "series") TvType.TvSeries else TvType.Movie
            Log.d("PlayHubLite", "load - URL ya es de tipo específico: $finalDetailPageUrl, Tipo: $detectedTvType")
            // Volvemos a obtener el documento para la URL limpia/ajustada
            doc = try {
                app.get(finalDetailPageUrl).document
            } catch (e: Exception) {
                Log.e("PlayHubLite", "load - ERROR al obtener el documento de detalle (específico): ${e.message}", e)
                return null
            }
        }
        // --- FIN NUEVA LÓGICA DE PARSEO ---

        Log.d("PlayHubLite", "load - Documento de detalle obtenido. Longitud: ${doc.html().length}")

        // Aquí usamos detectedTvType para la lógica de obtención de episodios
        val tvType = detectedTvType
        Log.d("PlayHubLite", "load - Tipo detectado: $tvType")

        val posterDiv = doc.selectFirst("div[style*=\"background-image:\"]")
        val poster = posterDiv?.attr("style")
            ?.let { style -> Regex("""url\(['"]?([^'"]+)['"]?\)""").find(style)?.groupValues?.get(1) }
            ?: ""
        Log.d("PlayHubLite", "load - Póster URL: $poster")


        val title = doc.selectFirst("div.movie-info h1")?.text()
            ?: doc.selectFirst("h1.text-white")?.text()
            ?: doc.selectFirst("h3.text-white")?.text()
            ?: ""
        Log.d("PlayHubLite", "load - Título: $title")


        val description = doc.selectFirst("div.synopsis p")?.text() ?: ""
        Log.d("PlayHubLite", "load - Descripción: $description")

        val tags = doc.select("div.movie-genres a").map { it.text() }
        Log.d("PlayHubLite", "load - Géneros/Tags: $tags")


        val episodes = if (tvType == TvType.TvSeries) {
            Log.d("PlayHubLite", "load - Buscando episodios para serie.")
            val seasonElements = doc.select("div.seasons-list li.season-item")
            Log.d("PlayHubLite", "load - Temporadas encontradas: ${seasonElements.size}")

            seasonElements.flatMap { seasonElement ->
                // Intenta obtener el número de la temporada del texto del enlace o del atributo data-season
                val seasonNumber = seasonElement.selectFirst("a")?.attr("data-season")?.toIntOrNull()
                    ?: seasonElement.selectFirst("a")?.text()?.replace("Temporada ", "")?.trim()?.toIntOrNull()
                    ?: 1 // Fallback a 1 si no se encuentra
                Log.d("PlayHubLite", "load - Procesando temporada: $seasonNumber")

                val episodeElements = seasonElement.select("div.episodios ul li")
                Log.d("PlayHubLite", "load - Episodios encontrados en temp $seasonNumber: ${episodeElements.size}")

                episodeElements.mapNotNull { element ->
                    val epurl = fixUrl(element.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = element.selectFirst("div.info h2")?.text() ?: ""

                    // Intenta extraer el número de episodio del atributo data-episode o del texto del título
                    val episodeNumber = element.selectFirst("a")?.attr("data-episode")?.toIntOrNull()
                        ?: element.selectFirst("div.info h2")?.text()
                            ?.lowercase()
                            ?.let { text ->
                                Regex("""episodio\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                            } ?: 1

                    val realimg = element.selectFirst("img")?.attr("data-src") ?: element.selectFirst("img")?.attr("src")

                    if (epurl.isNotBlank() && epTitle.isNotBlank()) {
                        Log.d("PlayHubLite", "load - Episodio válido: $epTitle (T$seasonNumber E$episodeNumber), URL: $epurl")
                        newEpisode(
                            // Serializa EpisodeLoadData a JSON usando Jackson
                            jacksonMapper.writeValueAsString(EpisodeLoadData(epTitle, epurl))
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realimg
                        }
                    } else {
                        Log.w("PlayHubLite", "load - Episodio inválido (título/link nulo) en T$seasonNumber. HTML: ${element.outerHtml()}")
                        null
                    }
                }
            }
        } else listOf()
        Log.d("PlayHubLite", "load - Total de episodios encontrados: ${episodes.size}")


        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = cleanUrl, // Mantén la URL original para el historial
                    type = tvType,
                    episodes = episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(
                    name = title,
                    url = cleanUrl, // Mantén la URL original para el historial
                    type = tvType,
                    dataUrl = cleanUrl
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            else -> null
        }
    }

    // Estas data classes y funciones de desencriptación las mantienes si las usas.
    // Parecen no ser usadas en el código proporcionado directamente, pero si tu sitio las necesita, son válidas.
    data class SortedEmbed(
        @JsonProperty("servername") val servername: String,
        @JsonProperty("link") val link: String,
        @JsonProperty("type") val type: String
    )

    data class DataLinkEntry(
        @JsonProperty("file_id") val file_id: String,
        @JsonProperty("video_language") val video_language: String,
        @JsonProperty("sortedEmbeds") val sortedEmbeds: List<SortedEmbed>
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
            Log.e("PlayHubLite", "Error al descifrar link: ${e.message}", e)
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("PlayHubLite", "loadLinks - Data de entrada: $data")

        val targetUrl: String
        // Deserializa 'data' usando Jackson
        val parsedEpisodeData = try {
            jacksonMapper.readValue<EpisodeLoadData>(data)
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks - ERROR: No se pudo parsear 'data' como EpisodeLoadData con Jackson: ${e.message}", e)
            null // Si falla el parseo, asumimos que 'data' es una URL directa
        }

        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("PlayHubLite", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(data) // Si no se pudo parsear, 'data' es la URL directa
            Log.d("PlayHubLite", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("PlayHubLite", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = try {
            app.get(targetUrl).document
        } catch (e: Exception) {
            Log.e("PlayHubLite", "loadLinks - ERROR al obtener el documento para cargar enlaces: ${e.message}", e)
            return false
        }
        Log.d("PlayHubLite", "loadLinks - Documento de enlaces obtenido. Longitud: ${doc.html().length}")

        // Selectores de iframe, buscando Streamlare o reproductores genéricos
        val iframeSrc = doc.selectFirst("div.player-frame iframe.metaframe")?.attr("src")
            ?: doc.selectFirst("div.player-frame iframe.player-embed")?.attr("src")
            ?: doc.selectFirst("iframe[src*='streamlare.com']")?.attr("src")
            ?: doc.selectFirst("iframe[src*='/player/']")?.attr("src") // Selector más genérico para jugadores internos

        if (iframeSrc.isNullOrBlank()) {
            Log.d("PlayHubLite", "loadLinks - No se encontró iframe del reproductor con los selectores específicos en PlayHubLite.com.")
            val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
            Log.d("PlayHubLite", "loadLinks - Buscando Streamlare en scripts. Longitud de scriptContent: ${scriptContent.length}")

            val streamlareRegex = """(https?://streamlare\.com/e/[a-zA-Z0-9]+)""".toRegex()
            val streamlareMatches = streamlareRegex.findAll(scriptContent).map { it.groupValues[1] }.toList()

            if (streamlareMatches.isNotEmpty()) {
                Log.d("PlayHubLite", "loadLinks - Encontrados ${streamlareMatches.size} enlaces directos de Streamlare en script.")
                streamlareMatches.apmap { directUrl ->
                    Log.d("PlayHubLite", "loadLinks - Intentando cargar extractor para Streamlare: $directUrl")
                    // Se usa targetUrl como referer en loadExtractor
                    loadExtractor(directUrl, targetUrl, subtitleCallback, callback)
                }
                return true
            }

            Log.d("PlayHubLite", "loadLinks - No se encontraron enlaces de video directos en scripts ni iframes.")
            return false
        }

        Log.d("PlayHubLite", "loadLinks - Iframe encontrado: $iframeSrc")

        // Se usa targetUrl como referer en loadExtractor
        return loadExtractor(fixUrl(iframeSrc), targetUrl, subtitleCallback, callback)
    }

    fun String.toSlug(): String {
        return this.lowercase()
            .replace("[^a-z0-9\\s-]".toRegex(), "") // Elimina caracteres especiales
            .replace("\\s+".toRegex(), "-") // Reemplaza espacios con guiones
            .replace("-+".toRegex(), "-") // Reemplaza múltiples guiones con uno solo
            .replace("^-|-$".toRegex(), "") // Elimina guiones al inicio o final
    }
}

// Función de extensión para convertir una cadena a un slug
// Necesitas añadir esto al final de tu archivo .kt o en un archivo de utilidad si tienes uno.

