package com.example // Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller // Posiblemente necesario si Mhdflix usa Cloudflare
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

// ¡CRÍTICO! Añadir esta anotación para que el plugin sea reconocido por CloudStream
class MhdflixProvider : MainAPI() {
    override var mainUrl = "https://ww1.mhdflix.com" // URL principal de Mhdflix
    override var name = "Mhdflix" // Nombre más amigable para el usuario
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

    // private val cloudflareKiller = CloudflareKiller() // Descomentar si el sitio usa Cloudflare, y usarlo en app.get

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        // Ajustar las URLs y selectores para Mhdflix
        val urls = listOf(
            Pair("Películas", "$mainUrl/movies"),
            Pair("Series", "$mainUrl/tvs"),
            Pair("Animes", "$mainUrl/tvs/category/anime")
            // Puedes añadir más si quieres, como "Dramas"
        )

        val homePageLists = urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Películas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                else -> TvType.Others
            }
            val doc = app.get(url /*, interceptor = cloudflareKiller */).document // Posiblemente necesites el interceptor
            val homeItems = doc.select("div.slick-slide").mapNotNull {
                val title = it.selectFirst("p.line-clamp-1")?.text() // Ajustado a la clase de Mhdflix
                val link = it.selectFirst("a")?.attr("href")
                // Mhdflix usa /_next/image?url=... en srcset, extraer la URL real
                val img = it.selectFirst("img")?.attr("data-nimg-image-url") ?: it.selectFirst("img")?.attr("src")

                if (title != null && link != null) {
                    newAnimeSearchResponse(
                        title,
                        fixUrl(link)
                    ) {
                        this.type = tvType
                        this.posterUrl = img
                    }
                } else null
            }
            HomePageList(name, homeItems)
        }

        items.addAll(homePageLists)

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query" // URL de búsqueda para Mhdflix
        val doc = app.get(url /*, interceptor = cloudflareKiller */).document
        // Selector para resultados de búsqueda en Mhdflix, asumiendo una estructura similar a la página principal
        return doc.select("div.slick-slide").mapNotNull {
            val title = it.selectFirst("p.line-clamp-1")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("img")?.attr("data-nimg-image-url") ?: it.selectFirst("img")?.attr("src")

            if (title != null && link != null) {
                newAnimeSearchResponse(
                    title,
                    fixUrl(link)
                ) {
                    // Mhdflix usa /movies/ y /tvs/, por lo que podemos inferir el tipo
                    this.type = if (link.contains("/movies/")) TvType.Movie else TvType.TvSeries
                    this.posterUrl = img
                }
            } else null
        }
    }

    data class EpisodeLoadData(
        val title: String,
        val url: String,
        val season: Int?,
        val episode: Int?
    )

    override suspend fun load(url: String): LoadResponse? {
        Log.d("Mhdflix", "load - URL de entrada: $url")

        val cleanUrl = fixUrl(url) // Asegura que la URL sea absoluta

        if (cleanUrl.isBlank()) {
            Log.e("Mhdflix", "load - ERROR: URL limpia está en blanco.")
            return null
        }

        val doc = app.get(cleanUrl /*, interceptor = cloudflareKiller */).document

        // Determinar el tipo de contenido (película o serie)
        val tvType = if (cleanUrl.contains("/movies/")) TvType.Movie else TvType.TvSeries

        // Extraer información general
        val title = doc.selectFirst("h1.font-bold")?.text() ?: doc.selectFirst("title")?.text() ?: ""
        val poster = doc.selectFirst("div.w-[250px] img")?.attr("data-nimg-image-url") ?: doc.selectFirst("div.w-[250px] img")?.attr("src") ?: ""
        val description = doc.selectFirst("p.text-sm.text-gray-500")?.text() ?: ""
        val tags = doc.select("div.flex.flex-row.gap-2 a.hover\\:text-blue-500").map { it.text().removeSuffix(",") }

        val episodes = ArrayList<Episode>()

        if (tvType == TvType.TvSeries) {
            // Mhdflix carga dinámicamente las temporadas y episodios.
            // La solución más robusta sería inspeccionar las llamadas de API que hacen para cargar esto.
            // Para una aproximación, si no podemos ver la API, tendremos que asumir que todos los episodios
            // están disponibles directamente en el HTML de la página de la serie o cargados mediante una única llamada de API.
            // Basado en tus capturas de "episodios", el HTML de los episodios está en la misma página de la serie,
            // pero el "Temporadas" hace pensar en una carga dinámica.

            // Asumiendo que los episodios están disponibles directamente en la página de la serie después de la carga inicial
            // (Si no, esto requerirá una llamada adicional a la API que lista los episodios/temporadas)

            // Busca el div de temporadas y dentro los elementos que representan episodios
            // El selector original de SoloLatino (div#seasons div.se-c ul.episodios li) no funciona aquí.
            // Basado en tus capturas, los episodios están en <div id="episodios">.
            // Y cada episodio es un 'a' tag.

            // Iterar sobre los contenedores de episodios, si existen
            val episodeElements = doc.select("div#episodios a[href*=\"/tvs/episodios/\"]")

            if (episodeElements.isEmpty()) {
                Log.w("Mhdflix", "load - No se encontraron elementos de episodios con el selector inicial. Intentando selectores alternativos para TV Series.")
                // Esto es un placeholder. Si los episodios se cargan con JS, esto fallará.
                // Necesitaríamos interceptar la llamada JS si es el caso.
                // Por ahora, asumimos que están en el HTML directo.

                // Si la estructura de episodios está anidada en temporadas, necesitaríamos un loop como el de SoloLatino
                // Las capturas sugieren que los episodios están en un solo div id="episodios", no por temporadas explícitas en el HTML estático.
                // Sin embargo, si al clickear una temporada se muestra un nuevo HTML, tendríamos que re-evaluar.
            }

            for (element in episodeElements) {
                val epUrl = fixUrl(element.attr("href") ?: "")
                val epTitle = element.selectFirst("h2")?.text() ?: ""

                // Extraer temporada y episodio del span (ej: T "1" "1" EP "1")
                val numerandoText = element.selectFirst("p.text-xs.text-gray-400.flex.justify-between.w-full span")?.text()
                val seasonNumber = numerandoText?.substringAfter("T ")?.substringBefore(" ")?.toIntOrNull()
                val episodeNumber = numerandoText?.substringAfter("EP ")?.substringBefore(" ")?.toIntOrNull()

                val realImg = element.selectFirst("img")?.attr("data-nimg-image-url") ?: element.selectFirst("img")?.attr("src")

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
                }
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

    // Mantén tus data classes y funciones de desencriptación si son usadas por otros hosts.
    // Aunque para Mhdflix, parece que uqload.cx es directo.

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

        // Obtener el HTML de la página del episodio/película
        val doc = app.get(targetUrl /*, interceptor = cloudflareKiller */).document

        // --- Extracción de enlaces de video de Mhdflix ---
        // Basado en las capturas, el video se encuentra directamente en un tag <video> o dentro de un iframe de uqload.cx.

        // Intentar primero el tag <video> directo
        val directVideoSrc = doc.selectFirst("video[data-html5-video]")?.attr("src")

        if (!directVideoSrc.isNullOrBlank()) {
            Log.d("Mhdflix", "loadLinks - Encontrado tag <video> directo: $directVideoSrc")
            // Asumimos que es un extractor de video general que CloudStream puede manejar
            loadExtractor(fixUrl(directVideoSrc), targetUrl, subtitleCallback, callback)
            return true
        }

        // Si no se encuentra el tag <video> directo, buscar un iframe de uqload.cx
        // El iframe de uqload.cx puede contener a su vez el tag <video>
        val uqloadIframeSrc = doc.selectFirst("iframe[src*=\"uqload.cx/embed-\"]")?.attr("src")

        if (!uqloadIframeSrc.isNullOrBlank()) {
            Log.d("Mhdflix", "loadLinks - Encontrado iframe de uqload.cx: $uqloadIframeSrc")
            // Pasamos el iframe de uqload.cx al extractor general.
            // CloudStream debería tener un extractor para uqload o lo manejará como un reproductor directo.
            loadExtractor(fixUrl(uqloadIframeSrc), targetUrl, subtitleCallback, callback)
            return true
        }

        // --- Aquí puedes mantener la lógica para otros dominios si Mhdflix también los usa,
        // aunque las capturas sugieren que Uqload es el principal. ---

        // La lógica GHBRISK.COM, XUPALACE.ORG, RE.SOLOLATINO.NET, EMBED69.ORG, PLAYERWISH.COM
        // etc., la mantendría por si acaso, pero la prioridad será Uqload.cx.
        // Si no encuentras estos dominios en Mhdflix, puedes eliminarlos para simplificar el código.

        // Ejemplo: Si Mhdflix usara otros embeds, podrías mantener tu lógica existente:
        /*
        else if (initialIframeSrc.contains("ghbrisk.com")) {
            // ... tu lógica existente para ghbrisk
        }
        // ... y así sucesivamente para otros dominios que encuentres
        */

        Log.w("Mhdflix", "loadLinks - No se encontraron enlaces de video directos o iframes conocidos en: $targetUrl")
        return false // No se encontró ningún video que se pudiera extraer
    }
}