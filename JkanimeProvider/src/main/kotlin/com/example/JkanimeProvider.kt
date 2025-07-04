package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import java.util.*
import kotlin.collections.ArrayList

// Import para logging
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.ExtractorApi
import android.util.Log // Importar Log para los logs de Android


class JkanimeProvider : MainAPI() {
    // TAG para logging
    val TAG = "JKAnimeProvider"

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", ignoreCase = true) || t.contains("Especial", ignoreCase = true) || t.contains("ONA", ignoreCase = true)) TvType.OVA
            else if (t.contains("Pelicula", ignoreCase = true)) TvType.AnimeMovie
            else TvType.Anime
        }
    }

    override var mainUrl = "https://jkanime.net"
    override var name = "JKAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        Log.d(TAG, "getMainPage: Obteniendo la página principal para JKAnime")

        val document = app.get(mainUrl).document

        // === SECCIÓN "ÚLTIMOS EPISODIOS (Home)" ELIMINADA ===
        // Si deseas volver a activarla, descomenta y revisa los selectores
        /*
        items.add(
            HomePageList(
                "Últimos episodios (Home)",
                document.select(".listadoanime-home a.bloqq").mapNotNull {
                    val title = it.selectFirst("h5")?.text()
                    val poster = it.selectFirst(".anime__sidebar__comment__item__pic img")?.attr("src") ?: ""
                    val epRegex = Regex("/(\\d+)/|/especial/|/ova/")
                    val url = it.attr("href").replace(epRegex, "") // Remove episode number from URL
                    val epNum = it.selectFirst("h6")?.text()?.replace("Episodio ", "")?.toIntOrNull()

                    if (title == null || url.isEmpty()) {
                        Log.w(TAG, "getMainPage: Elemento inválido encontrado en Últimos episodios (Home), saltando.")
                        return@mapNotNull null
                    }

                    val dubstat = if (title.contains("Latino") || title.contains("Castellano"))
                        DubStatus.Dubbed else DubStatus.Subbed

                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = poster
                        addDubStatus(dubstat, epNum)
                    }
                }
            )
        )
        Log.d(TAG, "getMainPage: Últimos episodios (Home) procesados. (Sección eliminada/desactivada por el usuario)")
        */


        // Animes (from the new tabbed section)
        document.select("#animes .card").mapNotNull {
            val title = it.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                TvType.Anime,
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("Animes", it))
                Log.d(TAG, "getMainPage: Animes agregados. Cantidad: ${it.size}")
            }
        }

        // Donghuas (from the new tabbed section)
        document.select("#donghuas .card").mapNotNull {
            val title = it.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                TvType.Anime, // Donghuas are generally TvType.Anime
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("Donghuas", it))
                Log.d(TAG, "getMainPage: Donghuas agregados. Cantidad: ${it.size}")
            }
        }

        // OVAs & Películas (from the new tabbed section)
        document.select("#ovas .card").mapNotNull {
            val title = it.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            val typeText = it.selectFirst(".badge.badge-primary")?.text() ?: ""
            val type = when {
                typeText.contains("Pelicula", ignoreCase = true) -> TvType.AnimeMovie
                typeText.contains("OVA", ignoreCase = true) -> TvType.OVA
                typeText.contains("ONA", ignoreCase = true) -> TvType.OVA // ONAs are often grouped with OVAs
                else -> TvType.Anime
            }
            val isDub = title.contains("Latino") || title.contains("Castellano")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                type,
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("OVAs y Películas", it))
                Log.d(TAG, "getMainPage: OVAs y Películas agregados. Cantidad: ${it.size}")
            }
        }

        // Animes Recientes (from the new trending_div section)
        document.select(".trending_div .mode3 .p-3.d-flex").mapNotNull {
            val title = it.selectFirst(".card-body-home h5.card-title a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst(".custom_thumb_home a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst(".custom_thumb_home img")?.attr("src") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            val typeText = it.selectFirst(".card-info .badge:not(.currently):not(.finished):not(.notyet)")?.text() ?: ""
            val type = when {
                typeText.contains("Pelicula", ignoreCase = true) -> TvType.AnimeMovie
                typeText.contains("OVA", ignoreCase = true) || typeText.contains("ONA", ignoreCase = true) -> TvType.OVA
                else -> TvType.Anime
            }

            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                type,
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("Animes Recientes", it, false)) // Explicitly set isHorizontal to false
                Log.d(TAG, "getMainPage: Animes Recientes agregados. Cantidad: ${it.size}")
            }
        }


        // Top Animes (from the new .upto and .lower sections)
        document.select(".row.upto .col.toplist, .row.lower .col.toplist").mapNotNull {
            val title = it.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            AnimeSearchResponse(
                title,
                fixUrl(href),
                this.name,
                TvType.Anime, // Assuming most top animes are TV series
                fixUrl(poster),
                null,
                if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            )
        }.let {
            if (it.isNotEmpty()) {
                items.add(HomePageList("Top Animes", it))
                Log.d(TAG, "getMainPage: Top Animes agregados. Cantidad: ${it.size}")
            }
        }

        if (items.size <= 0) {
            Log.e(TAG, "getMainPage: No se encontraron items para la página principal, lanzando ErrorLoadingException.")
            throw ErrorLoadingException()
        }
        Log.d(TAG, "getMainPage: Se encontraron ${items.size} listas de la página principal.")
        return HomePageResponse(items)
    }

    data class MainSearch(
        @JsonProperty("animes") val animes: List<Animes>,
        @JsonProperty("anime_types") val animeTypes: AnimeTypes
    )

    data class Animes(
        @JsonProperty("id") val id: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("image") val image: String,
        @JsonProperty("synopsis") val synopsis: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("status") val status: String,
        @JsonProperty("thumbnail") val thumbnail: String
    )

    data class AnimeTypes(
        @JsonProperty("TV") val TV: String,
        @JsonProperty("OVA") val OVA: String,
        @JsonProperty("Movie") val Movie: String,
        @JsonProperty("Special") val Special: String,
        @JsonProperty("ONA") val ONA: String,
        @JsonProperty("Music") val Music: String
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val search = ArrayList<SearchResponse>()
        Log.d(TAG, "search: Buscando '$query'")
        val doc = app.get("$mainUrl/buscar/$query").document

        doc.select(".row.page_directorio .col-lg-2.col-md-6.col-sm-6").mapNotNull {
            val title = it.selectFirst(".anime__item__text h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst(".anime__item a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst(".anime__item__pic.set-bg")?.attr("data-setbg") ?: ""

            val typeText = it.selectFirst(".anime__item__text ul li.anime")?.text()?.trim() ?: "Serie"
            val type = when {
                typeText.contains("Pelicula", ignoreCase = true) -> TvType.AnimeMovie
                typeText.contains("OVA", ignoreCase = true) -> TvType.OVA
                typeText.contains("Especial", ignoreCase = true) -> TvType.OVA
                typeText.contains("ONA", ignoreCase = true) -> TvType.OVA
                else -> TvType.Anime
            }

            val isDub = title.contains("Latino", ignoreCase = true) || title.contains("Castellano", ignoreCase = true)

            search.add(
                AnimeSearchResponse(
                    title,
                    fixUrl(href),
                    this.name,
                    type,
                    fixUrl(poster),
                    null,
                    if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
                )
            )
        }
        Log.d(TAG, "search: Se encontraron ${search.size} resultados para '$query'.")
        return search
    }

    // --- NUEVAS DATA CLASSES PARA EL JSON DE EPISODIOS ---
    data class EpisodesResponse(
        @JsonProperty("current_page") val currentPage: Int,
        @JsonProperty("data") val data: List<EpisodeData>,
        @JsonProperty("from") val from: Int?,
        @JsonProperty("last_page") val lastPage: Int,
        @JsonProperty("first_page_url") val firstPageUrl: String?,
        @JsonProperty("last_page_url") val lastPageUrl: String?,
        @JsonProperty("links") val links: List<Link>,
        @JsonProperty("next_page_url") val nextPageUrl: String?,
        @JsonProperty("path") val path: String,
        @JsonProperty("per_page") val perPage: Int,
        @JsonProperty("prev_page_url") val prevPageUrl: String?,
        @JsonProperty("to") val to: Int?,
        @JsonProperty("total") val total: Int
    )

    data class EpisodeData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("number") val number: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("image") val image: String,
        @JsonProperty("timestamp") val timestamp: String
    )

    data class Link(
        @JsonProperty("url") val url: String?,
        @JsonProperty("label") val label: String,
        @JsonProperty("active") val active: Boolean
    )
    // --- FIN DE NUEVAS DATA CLASSES ---

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load: Cargando URL: $url")
        val doc = app.get(url, timeout = 120).document

        // --- Extracción de Título, Descripción y Poster ---
        // Revisar selectores basados en el HTML de Jkanime
        val poster = doc.selectFirst(".anime__details__pic.set-bg")?.attr("data-setbg")?.let { fixUrl(it) } ?: // Nueva estructura
        doc.selectFirst("div.col-lg-3.col-md-4.col-sm-5 img")?.attr("src")?.let { fixUrl(it) } // Antigua o fallback
        Log.d(TAG, "load: Poster URL: $poster")

        val title = doc.selectFirst("div.anime_info h3")?.text()?.trim() ?: // Nueva estructura
        doc.selectFirst("div.anime_info h1")?.text()?.trim() // Antigua o fallback
        Log.d(TAG, "load: Título encontrado: $title")

        val description = doc.selectFirst("div.anime_info p.scroll")?.text()?.trim() ?: // Nueva estructura
        doc.selectFirst("div.col-lg-8.col-md-8.col-sm-8 p.text-white.text-justify")?.text()?.trim() // Antigua o fallback
        Log.d(TAG, "load: Descripción encontrada: ${description?.take(50)}...") // Log solo los primeros 50 caracteres

        val genres = doc.select("div.anime_info ul li a[href*='/genero/']")
            .map { it.text().trim() }
        Log.d(TAG, "load: Géneros encontrados: $genres")

        val statusText = doc.select("div.anime__details__widget ul li")
            .firstOrNull { it.text().contains("Estado:", ignoreCase = true) }
            ?.text()?.substringAfter("Estado:")?.trim()
        Log.d(TAG, "load: Estado encontrado: $statusText")

        val status = when (statusText) {
            "En emisión", "En emision" -> ShowStatus.Ongoing
            "Concluido", "Finalizado" -> ShowStatus.Completed
            else -> null
        }

        val typeText = doc.select("div.anime__details__widget ul li")
            .firstOrNull { it.text().contains("Tipo:", ignoreCase = true) }
            ?.text()?.substringAfter("Tipo:")?.trim()
        Log.d(TAG, "load: Tipo encontrado: $typeText")


        // --- Extracción del Anime ID y Token CSRF para la API de episodios ---
        // El ID está en <div id="guardar-anime" class="guardar_anime" data-anime="2106">
        val animeID = doc.selectFirst("div.guardar_anime[data-anime]")?.attr("data-anime")?.toIntOrNull()
        Log.d(TAG, "load: Anime ID extraído: $animeID")

        // === EXTRAER EL TOKEN CSRF ===
        val csrfToken = doc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content")
        if (csrfToken.isNullOrBlank()) {
            Log.e(TAG, "load: ERROR - No se pudo encontrar el _token CSRF en la página $url.")
            // Puedes decidir lanzar una excepción o retornar null aquí si es crítico.
            // Para depuración, es mejor continuar y ver el siguiente error.
        } else {
            Log.d(TAG, "load: _token CSRF encontrado: ${csrfToken.take(10)}...") // Log parcial por seguridad
        }


        val episodes = ArrayList<Episode>()

        if (animeID != null && !csrfToken.isNullOrBlank()) {
            // Utilizamos la URL de la API de episodios y hacemos un POST
            val episodesApiUrl = "$mainUrl/ajax/episodes/$animeID/1" // Asumiendo temporada 1, página 1
            Log.d(TAG, "load: URL API de episodios: $episodesApiUrl")

            try {
                // === REALIZAR SOLICITUD POST CON TOKEN ===
                val episodesResponseText = app.post(
                    episodesApiUrl,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest", // Simula una solicitud AJAX
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to url // Es buena práctica enviar el Referer
                    ),
                    data = mapOf("_token" to csrfToken) // Envía el token como datos del POST
                ).text

                val episodesResponse = parseJson<EpisodesResponse>(episodesResponseText)
                Log.d(TAG, "load: API de episodios cargada. Total de episodios: ${episodesResponse.total}")

                episodesResponse.data.map { episodeData ->
                    // La URL de la imagen del episodio se construye así:
                    val episodePosterUrl = "https://cdn.jkdesu.com/assets/images/animes/video/image_thumb/${episodeData.image}"

                    // === EL LINK DEL EPISODIO APUNTA A LA URL PRINCIPAL DEL ANIME ===
                    // Esto hará que al hacer clic en un episodio, Cloudstream te lleve de vuelta a la página del anime.
                    val episodeLink = url
                    Log.d(TAG, "load: Creando episodio: ${episodeData.number}, Link (apunta a anime page): $episodeLink")

                    Episode(
                        episodeLink,
                        name = "Capítulo ${episodeData.number} - ${episodeData.title.substringAfter(" - ", episodeData.title).trim()}",
                        posterUrl = fixUrl(episodePosterUrl), // Asegúrate de arreglar la URL si es relativa
                        episode = episodeData.number
                    )
                }.let {
                    episodes.addAll(it)
                }

                Log.d(TAG, "load: ${episodes.size} episodios procesados desde la API.")

                // TODO: Si hay paginación y quieres cargar más, necesitarías un bucle para cargar más páginas
                // if (episodesResponse.lastPage > 1) { /* Lógica para cargar más páginas de episodios */ }

            } catch (e: Exception) {
                Log.e(TAG, "load: Error al cargar episodios desde la API: ${e.message}", e)
                // Aquí puedes loguear la respuesta cruda si quieres para depurar más a fondo
                // Log.e(TAG, "load: Respuesta cruda de la API: $episodesResponseText")
            }
        } else {
            if (animeID == null) Log.e(TAG, "load: No se pudo encontrar el ID del anime para cargar episodios.")
            if (csrfToken.isNullOrBlank()) Log.e(TAG, "load: No se encontró el token CSRF para cargar episodios.")
        }

        episodes.sortBy { it.episode } // Ordenar episodios por número
        Log.d(TAG, "load: Episodios ordenados.")

        // Manejo de títulos nulos
        val finalTitle = title ?: "Título Desconocido"
        if (title == null) Log.w(TAG, "load: El título del anime es null, usando 'Título Desconocido'.")


        return newAnimeLoadResponse(finalTitle, url, getType(typeText ?: "Serie")) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes) // JKAnime parece ser solo subtitulado por defecto
            showStatus = status
            plot = description
            tags = genres
            // Otras propiedades como year, recommendations, etc. si las extraes.
        }
    }


    data class Nozomi(
        @JsonProperty("file") val file: String?
    )

    private suspend fun streamClean(
        name: String,
        url: String,
        referer: String,
        quality: String?, // <--- Este es un String?
        callback: (ExtractorLink) -> Unit,
        m3u8: Boolean
    ): Boolean {
        Log.d(TAG, "streamClean: Procesando link: $url (Referer: $referer)")
        callback(
            newExtractorLink(
                name,
                name,
                url,
                type = if (m3u8) ExtractorLinkType.M3U8 else INFER_TYPE // <-- Esto es correcto
            ) {
                this.quality = getQualityFromName(quality) // <-- Aquí se usa el String 'quality'
                this.headers = mapOf("Referer" to referer)
            }
        )
        return true
    }


    private fun fetchjkanime(text: String?): List<String> {
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "fetchjkanime: Texto de entrada nulo o vacío.")
            return listOf()
        }
        // Regex mejorada para capturar URLs de reproductores/iframes
        val linkRegex = Regex("""src=["'](https?:\/\/(?:www\.)?(?:jkanime\.net|ok\.ru|mixdrop\.co|embedsito\.com)\/[^"']*)["']""")
        val links = linkRegex.findAll(text).map { it.groupValues[1] }.toList()
        Log.d(TAG, "fetchjkanime: Encontrados ${links.size} posibles enlaces iframe.")
        return links
    }


    data class ServersEncoded (
        @JsonProperty("remote" ) val remote : String,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: Cargando links para: $data")

        app.get(data).document.select("script").apmap { script ->
            // --- Carga de servidores codificados (ServersEncoded) ---
            if (script.data().contains(Regex("slug|remote"))) {
                val serversRegex = Regex("\\[\\{.*?\"remote\".*?\"\\}\\]")
                val servers = serversRegex.findAll(script.data()).map { it.value }.toList().firstOrNull()

                if (servers != null) {
                    try {
                        val serJson = parseJson<ArrayList<ServersEncoded>>(servers)
                        Log.d(TAG, "loadLinks: Encontrados ${serJson.size} servidores codificados.")
                        serJson.apmap {
                            val encodedurl = it.remote
                            val urlDecoded = base64Decode(encodedurl) // Asegúrate de que base64Decode esté definida
                            Log.d(TAG, "loadLinks: Servidor decodificado: $urlDecoded")
                            loadExtractor(urlDecoded, mainUrl, subtitleCallback, callback) // Usar mainUrl como referer para extractores
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks: Error al parsear servidores codificados: ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "loadLinks: No se encontraron servidores codificados en el script.")
                }
            }


            // --- Carga de enlaces de video (var video = []) ---
            if (script.data().contains("var video = []")) {
                val videos = script.data()
                Log.d(TAG, "loadLinks: Script contiene 'var video = []'. Procesando enlaces de video.")

                // Mapear y limpiar los enlaces de video. La regex en fetchjkanime debería hacer esto mejor.
                fetchjkanime(videos).distinct().apmap { link ->
                    Log.d(TAG, "loadLinks: Procesando enlace de video (fetchjkanime): $link")

                    // Algunos links podrían ser directamente extractores válidos
                    if (loadExtractor(link, data, subtitleCallback, callback)) {
                        Log.d(TAG, "loadLinks: Enlace $link manejado por extractor existente.")
                    } else {
                        // Lógica específica para um2.php, um.php, jkmedia
                        if (link.contains("um2.php")) {
                            Log.d(TAG, "loadLinks: um2.php link detectado: $link")
                            val doc = app.get(link, referer = data).document
                            val gsplaykey = doc.select("form input[value]").attr("value")
                            if (gsplaykey.isNullOrEmpty()) {
                                Log.w(TAG, "loadLinks: No se encontró gsplaykey para um2.php en $link")
                            } else {
                                app.post(
                                    "$mainUrl/gsplay/redirect_post.php",
                                    headers = mapOf(
                                        "Host" to "jkanime.net",
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                        "Accept-Language" to "en-US,en;q=0.5",
                                        "Referer" to link,
                                        "Content-Type" to "application/x-www-form-urlencoded",
                                        "Origin" to "https://jkanime.net",
                                        "DNT" to "1",
                                        "Connection" to "keep-alive",
                                        "Upgrade-Insecure-Requests" to "1",
                                        "Sec-Fetch-Dest" to "iframe",
                                        "Sec-Fetch-Mode" to "navigate",
                                        "Sec-Fetch-Site" to "same-origin",
                                        "TE" to "trailers",
                                        "Pragma" to "no-cache",
                                        "Cache-Control" to "no-cache",
                                    ),
                                    data = mapOf(Pair("data", gsplaykey)),
                                    allowRedirects = false
                                ).okhttpResponse.headers.values("location").apmap { loc ->
                                    Log.d(TAG, "loadLinks: gsplay redirect location: $loc")
                                    val postkey = loc.replace("/gsplay/player.html#", "")
                                    val nozomitext = app.post(
                                        "$mainUrl/gsplay/api.php",
                                        headers = mapOf(
                                            "Host" to "jkanime.net",
                                            "User-Agent" to USER_AGENT,
                                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                                            "Accept-Language" to "en-US,en;q=0.5",
                                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                            "X-Requested-With" to "XMLHttpRequest",
                                            "Origin" to "https://jkanime.net",
                                            "DNT" to "1",
                                            "Connection" to "keep-alive",
                                            "Sec-Fetch-Dest" to "empty",
                                            "Sec-Fetch-Mode" to "cors",
                                            "Sec-Fetch-Site" to "same-origin",
                                        ),
                                        data = mapOf(Pair("v", postkey)),
                                        allowRedirects = false
                                    ).text
                                    try {
                                        val json = parseJson<Nozomi>(nozomitext)
                                        val nozomiurl = json.file
                                        if (!nozomiurl.isNullOrEmpty()) {
                                            streamClean(
                                                "Nozomi",
                                                nozomiurl,
                                                loc, // Referer debería ser la URL de redirección
                                                null,
                                                callback,
                                                nozomiurl.contains(".m3u8")
                                            )
                                            Log.d(TAG, "loadLinks: Nozomi URL procesada: $nozomiurl")
                                        } else {
                                            Log.w(TAG, "loadLinks: Nozomi URL nula o vacía.")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "loadLinks: Error al parsear JSON Nozomi: ${e.message} - Texto: $nozomitext", e)
                                    }
                                }
                            }
                        } else if (link.contains("um.php")) {
                            Log.d(TAG, "loadLinks: um.php link detectado: $link")
                            val desutext = app.get(link, referer = data).text
                            val desuRegex = Regex("((https:|http:)//.*\\.m3u8)")
                            val file = desuRegex.find(desutext)?.value
                            if (file != null) {
                                val namedesu = "Desu"
                                generateM3u8(
                                    namedesu,
                                    file,
                                    link, // Referer correcto debería ser la URL del um.php
                                ).forEach { desurl ->
                                    streamClean(
                                        namedesu,
                                        desurl.url,
                                        link, // Referer para el m3u8 es la URL del um.php
                                        desurl.quality.toString(),
                                        callback,
                                        true
                                    )
                                }
                                Log.d(TAG, "loadLinks: Desu M3U8 procesado: $file")
                            } else {
                                Log.w(TAG, "loadLinks: No se encontró M3U8 para um.php en $link")
                            }
                        } else if (link.contains("jkmedia")) {
                            Log.d(TAG, "loadLinks: jkmedia link detectado: $link")
                            app.get(
                                link,
                                referer = data,
                                allowRedirects = false
                            ).okhttpResponse.headers.values("location").apmap { xtremeurl ->
                                val namex = "Xtreme S"
                                streamClean(
                                    namex,
                                    xtremeurl,
                                    link, // Referer para Xtreme S es la URL del jkmedia
                                    null,
                                    callback,
                                    xtremeurl.contains(".m3u8")
                                )
                                Log.d(TAG, "loadLinks: Xtreme S URL procesada: $xtremeurl")
                            }
                        }
                    }
                }
            }
        }
        Log.d(TAG, "loadLinks: Finalizando carga de enlaces para $data.")
        return true
    }
}