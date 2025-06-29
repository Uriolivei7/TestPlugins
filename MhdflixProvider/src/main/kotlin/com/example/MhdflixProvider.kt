package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.APIHolder.unixTime
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
import kotlinx.coroutines.delay

class MhdflixProvider : MainAPI() {
    override var mainUrl = "https://ww1.mhdflix.com"
    override var name = "Mhdflix"
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

    private val cloudflareKiller = CloudflareKiller() // Mantén esto descomentado y úsalo

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("Mhdflix", "getMainPage - Iniciando en página: $page")
        val items = ArrayList<HomePageList>()

        // Obtener el documento de la página principal
        val doc = app.get(mainUrl, interceptor = cloudflareKiller).document
        Log.d("Mhdflix", "getMainPage - Documento obtenido para página principal. HTML size: ${doc.html().length}")

        // Seleccionar cada sección de contenido (ej. "Películas recientes", "Series destacadas")
        // Buscar el div que contiene el carrusel de elementos.
        // El selector busca un h2 seguido por un div con overflow-x-auto, y dentro el div con flex-nowrap.
        val contentRows = doc.select("div[class*=flex-col] > h2.text-2xl.font-bold + div[class*=overflow-x-auto] > div[class*=flex-nowrap]")

        Log.d("Mhdflix", "getMainPage - Secciones de contenido encontradas: ${contentRows.size}")

        for (row in contentRows) {
            val sectionTitleElement = row.parent()?.selectFirst("h2.text-2xl.font-bold")
            val sectionTitle = sectionTitleElement?.text()?.replace(" ver más", "")?.trim()

            if (sectionTitle.isNullOrBlank()) {
                Log.w("Mhdflix", "getMainPage - Título de sección vacío o no encontrado, saltando.")
                continue
            }

            Log.d("Mhdflix", "getMainPage - Procesando sección: '$sectionTitle'")

            // Los elementos individuales son 'a' tags con href que contiene /movies/ o /tvs/
            val homeItems = row.select("a[href*=\"/movies/\"], a[href*=\"/tvs/\"]").mapNotNull { element ->
                val link = element.attr("href")
                val title = element.selectFirst("img")?.attr("alt")
                val img = element.selectFirst("img")?.attr("src")

                Log.d("Mhdflix", "getMainPage - Item detectado en '$sectionTitle': Title: '$title', Link: '$link', Img: '$img'")

                if (title != null && link != null && img != null) {
                    val tvType = if (link.contains("/movies/")) TvType.Movie else TvType.TvSeries
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else {
                    Log.w("Mhdflix", "getMainPage - Item incompleto en '$sectionTitle', saltando: Title: '$title', Link: '$link', Img: '$img'")
                    null
                }
            }
            if (homeItems.isNotEmpty()) {
                items.add(HomePageList(sectionTitle, homeItems))
            } else {
                Log.w("Mhdflix", "getMainPage - No se encontraron items para la sección: '$sectionTitle'")
            }
        }

        if (items.isEmpty()) {
            Log.e("Mhdflix", "getMainPage - No se pudo extraer ninguna lista de la página principal. Posiblemente los selectores están incorrectos o el sitio ha cambiado.")
        }

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("Mhdflix", "search - Iniciando búsqueda para: '$query'")
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url, interceptor = cloudflareKiller).document
        Log.d("Mhdflix", "search - Documento obtenido para búsqueda. HTML size: ${doc.html().length}")

        // El selector para los resultados de búsqueda en la página de búsqueda debería ser el mismo
        // que el de los elementos individuales en la página principal.
        val searchResults = doc.select("a[href*=\"/movies/\"], a[href*=\"/tvs/\"]").mapNotNull { element ->
            val link = element.attr("href")
            val title = element.selectFirst("img")?.attr("alt")
            val img = element.selectFirst("img")?.attr("src")

            Log.d("Mhdflix", "search - Resultado detectado: Title: '$title', Link: '$link', Img: '$img'")

            if (title != null && link != null && img != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    this.type = if (link.contains("/movies/")) TvType.Movie else TvType.TvSeries
                    this.posterUrl = img
                }
            } else {
                Log.w("Mhdflix", "search - Resultado incompleto, saltando: Title: '$title', Link: '$link', Img: '$img'")
                null
            }
        }
        if (searchResults.isEmpty()) {
            Log.e("Mhdflix", "search - No se encontraron resultados para la búsqueda de '$query'. Posiblemente los selectores están incorrectos o el sitio ha cambiado.")
        }
        return searchResults
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String,
        val season: Int?,
        val episode: Int?
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Mhdflix", "load - URL de entrada: $url")

        val cleanUrl = fixUrl(url)

        if (cleanUrl.isBlank()) {
            Log.e("Mhdflix", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl, interceptor = cloudflareKiller).document
        Log.d("Mhdflix", "load - Documento obtenido para '$cleanUrl'. HTML size: ${doc.html().length}")

        // Determinar el tipo de contenido (película o serie)
        val tvType = if (cleanUrl.contains("/movies/")) TvType.Movie else TvType.TvSeries

        // Extraer información general
        val title = doc.selectFirst("h1.font-bold")?.text() ?: doc.selectFirst("title")?.text() ?: ""
        val poster = doc.selectFirst("div.w-[250px] img")?.attr("src") ?: "" // Usar directamente src
        val description = doc.selectFirst("p.text-sm.text-gray-500")?.text() ?: ""
        val tags = doc.select("div.flex.flex-row.gap-2 a.hover\\:text-blue-500").map { it.text().removeSuffix(",") }

        Log.d("Mhdflix", "load - Título: '$title', Poster: '$poster', Descripción: '$description', Tags: $tags")

        val episodes = ArrayList<Episode>()

        if (tvType == TvType.TvSeries) {
            // Mhdflix tiene un h1 "Temporadas" y luego un div con un carrusel de tarjetas de temporadas.
            // Dentro de cada tarjeta de temporada, parece que se listan los episodios en la misma página.
            // Revisa image_5567f5.png, que muestra la estructura de las "Temporadas".
            // Los episodios están dentro de <div id="episodios"> en las páginas de serie.

            val episodeElements = doc.select("div#episodios a[href*=\"/tvs/episodios/\"]")

            if (episodeElements.isEmpty()) {
                Log.w("Mhdflix", "load - No se encontraron elementos de episodios en div#episodios. Revisar selectores o si la carga es dinámica.")
            }

            for (element in episodeElements) {
                val epUrl = fixUrl(element.attr("href") ?: "")
                val epTitle = element.selectFirst("h2")?.text() ?: ""

                // Extraer temporada y episodio del span (ej: T "1" "1" EP "1")
                // image_55639e.png muestra <p class="text-xs text-gray-400 flex justify-between w-full"> <span>"T" "1" "1" "EP" "1"</span>
                val numerandoText = element.selectFirst("p.text-xs.text-gray-400.flex.justify-between.w-full span")?.text()
                val seasonNumber = numerandoText?.substringAfter("T ")?.substringBefore(" ")?.toIntOrNull()
                val episodeNumber = numerandoText?.substringAfter("EP ")?.substringBefore(" ")?.toIntOrNull()

                // La imagen del episodio está dentro de un div.
                val realImg = element.selectFirst("img")?.attr("src")

                Log.d("Mhdflix", "load - Episodio detectado: Title: '$epTitle', URL: '$epUrl', Season: $seasonNumber, Episode: $episodeNumber, Img: '$realImg'")

                if (epUrl.isNotBlank() && epTitle.isNotBlank()) {
                    episodes.add(
                        newEpisode(
                            EpisodeLoadData(epTitle, epUrl, seasonNumber, episodeNumber).toJson()
                        ) {
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = realImg
                        }
                    )
                } else {
                    Log.w("Mhdflix", "load - Episodio incompleto, saltando: Title: '$epTitle', URL: '$epUrl'")
                }
            }
            if (episodes.isEmpty()) {
                Log.e("Mhdflix", "load - No se extrajo ningún episodio para la serie. Revisa los selectores de episodios.")
            }
        }

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    name = title,
                    url = cleanUrl,
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
                    url = cleanUrl,
                    type = tvType,
                    dataUrl = cleanUrl // Usa la URL de la película como dataUrl para loadLinks
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Mhdflix", "loadLinks - Data de entrada: $data")

        var targetUrl: String
        val parsedEpisodeData = tryParseJson<EpisodeLoadData>(data)
        if (parsedEpisodeData != null) {
            targetUrl = parsedEpisodeData.url
            Log.d("Mhdflix", "loadLinks - URL final de episodio (de JSON): $targetUrl")
        } else {
            targetUrl = fixUrl(data)
            Log.d("Mhdflix", "loadLinks - URL final de película (directa o ya limpia y fixUrl-ed): $targetUrl")
        }

        if (targetUrl.isBlank()) {
            Log.e("Mhdflix", "loadLinks - ERROR: URL objetivo está en blanco después de procesar 'data'.")
            return false
        }

        val doc = app.get(targetUrl, interceptor = cloudflareKiller).document
        Log.d("Mhdflix", "loadLinks - Documento obtenido para '$targetUrl'. HTML size: ${doc.html().length}")

        // --- Extracción de enlaces de video de Mhdflix ---
        // Basado en las capturas (ej. image_55541a.png), el video se encuentra directamente en un tag <video> o dentro de un iframe de uqload.cx.

        // Intentar primero el tag <video> directo
        // Asegúrate de que el selector sea preciso.
        val directVideoSrc = doc.selectFirst("video[data-html5-video]")?.attr("src")

        if (!directVideoSrc.isNullOrBlank()) {
            Log.d("Mhdflix", "loadLinks - Encontrado tag <video> directo: $directVideoSrc")
            loadExtractor(fixUrl(directVideoSrc), targetUrl, subtitleCallback, callback)
            return true
        }

        // Si no se encuentra el tag <video> directo, buscar un iframe de uqload.cx
        val uqloadIframeSrc = doc.selectFirst("iframe[src*=\"uqload.cx/embed-\"]")?.attr("src")

        if (!uqloadIframeSrc.isNullOrBlank()) {
            Log.d("Mhdflix", "loadLinks - Encontrado iframe de uqload.cx: $uqloadIframeSrc")
            loadExtractor(fixUrl(uqloadIframeSrc), targetUrl, subtitleCallback, callback)
            return true
        }

        Log.w("Mhdflix", "loadLinks - No se encontraron enlaces de video directos o iframes conocidos en: $targetUrl")
        return false
    }
}