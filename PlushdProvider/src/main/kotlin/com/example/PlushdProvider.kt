package com.example // Corregido 'com.exampe' a 'com.example'

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log // **Añadido para logs**
import android.util.Base64 // **Añadido para base64Encode**

class PlushdProvider :MainAPI() {
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

    // Necesitas esta función para base64Encode en loadLinks
    private fun base64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("PlushdProvider", "DEBUG: Iniciando getMainPage, página: $page, solicitud: ${request.name}") // **Log**
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Doramas", "$mainUrl/doramas"),

            )

        try { // **Bloque try-catch para atrapar errores**
            urls.apmap { (name, url) ->
                Log.d("PlushdProvider", "DEBUG: Obteniendo datos para la lista: $name de $url") // **Log**
                val doc = app.get(url).document
                val home = doc.select(".articlesList article").map { article -> // Renombrado a 'article' para claridad
                    val title = article.selectFirst("a h2")?.text()
                    val link = article.selectFirst("a.itemA")?.attr("href")
                    val img = article.selectFirst("picture img")?.attr("data-src")

                    Log.d("PlushdProvider", "DEBUG: Elemento principal - Título: $title, Link: $link, Imagen: $img") // **Log**

                    // Nota: Aquí se usaban '!!' que pueden causar NPE. Se mantiene como está por tu petición.
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
            Log.d("PlushdProvider", "DEBUG: getMainPage finalizado. ${items.size} listas añadidas.") // **Log**
            return HomePageResponse(items)
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR en getMainPage: ${e.message}", e) // **Log de error**
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("PlushdProvider", "DEBUG: Iniciando search para query: $query") // **Log**
        val url = "$mainUrl/api/search/$query"
        try { // **Bloque try-catch para atrapar errores**
            val doc = app.get(url).document
            Log.d("PlushdProvider", "DEBUG: Documento de búsqueda obtenido para query: $query") // **Log**
            return doc.select("article.item").map { article -> // Renombrado a 'article' para claridad
                val title = article.selectFirst("a h2")?.text()
                val link = article.selectFirst("a.itemA")?.attr("href")
                val img = article.selectFirst("picture img")?.attr("data-src")

                Log.d("PlushdProvider", "DEBUG: Resultado de búsqueda - Título: $title, Link: $link, Imagen: $img") // **Log**

                // Nota: Aquí se usaban '!!' que pueden causar NPE. Se mantiene como está por tu petición.
                TvSeriesSearchResponse(
                    title!!,
                    link!!,
                    this.name,
                    TvType.TvSeries,
                    img,
                )
            }
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR en search para query '$query': ${e.message}", e) // **Log de error**
            return emptyList()
        }
    }

    class MainTemporada(elements: Map<String, List<MainTemporadaElement>>) : HashMap<String, List<MainTemporadaElement>>(elements)
    data class MainTemporadaElement (
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )
    override suspend fun load(url: String): LoadResponse? {
        Log.d("PlushdProvider", "DEBUG: Iniciando load para URL: $url") // **Log**
        try { // **Bloque try-catch para atrapar errores**
            val doc = app.get(url).document
            Log.d("PlushdProvider", "DEBUG: Documento obtenido para load() de URL: $url") // **Log**
            val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
            Log.d("PlushdProvider", "DEBUG: Tipo detectado para URL $url: $tvType") // **Log**

            val title = doc.selectFirst(".slugh1")?.text() ?: ""
            Log.d("PlushdProvider", "DEBUG: Título extraído: $title") // **Log**

            // Nota: Aquí se usaba '!!' que puede causar NPE. Se mantiene como está por tu petición.
            val backimage = doc.selectFirst("head meta[property=og:image]")!!.attr("content")
            Log.d("PlushdProvider", "DEBUG: Imagen de fondo extraída: $backimage") // **Log**

            val poster = backimage.replace("original", "w500")
            Log.d("PlushdProvider", "DEBUG: Póster derivado: $poster") // **Log**

            // Nota: Aquí se usaba '!!' que puede causar NPE. Se mantiene como está por tu petición.
            val description = doc.selectFirst("div.description")!!.text()
            Log.d("PlushdProvider", "DEBUG: Descripción extraída (primeros 100 chars): ${description.take(100)}") // **Log**

            val tags = doc.select("div.home__slider .genres:contains(Generos) a").map { it.text() }
            Log.d("PlushdProvider", "DEBUG: Tags extraídos: $tags") // **Log**

            val epi = ArrayList<Episode>()
            if (tvType == TvType.TvSeries) {
                Log.d("PlushdProvider", "DEBUG: Contenido es TvSeries. Buscando temporadas/episodios.") // **Log**
                val script = doc.select("script").firstOrNull { it.html().contains("seasonsJson = ") }?.html()
                if(!script.isNullOrEmpty()){
                    Log.d("PlushdProvider", "DEBUG: Script 'seasonsJson' encontrado.") // **Log**
                    val jsonscript = script.substringAfter("seasonsJson = ").substringBefore(";")
                    Log.d("PlushdProvider", "DEBUG: JSON de temporadas antes de parsear: ${jsonscript.take(100)}") // **Log**
                    val json = parseJson<MainTemporada>(jsonscript)
                    Log.d("PlushdProvider", "DEBUG: JSON de temporadas parseado exitosamente.") // **Log**
                    json.values.map { list ->
                        list.map { info ->
                            val epTitle = info.title
                            val seasonNum = info.season
                            val epNum = info.episode
                            val img = info.image
                            val realimg = if (img == null) null else if (img.isEmpty() == true) null else "https://image.tmdb.org/t/p/w342${img.replace("\\/", "/")}"
                            val epurl = "$url/season/$seasonNum/episode/$epNum"
                            Log.d("PlushdProvider", "DEBUG: Añadiendo episodio: S:$seasonNum E:$epNum Título: $epTitle, URL: $epurl, Imagen: $realimg") // **Log**
                            epi.add(
                                Episode(
                                    epurl,
                                    epTitle,
                                    seasonNum,
                                    epNum,
                                    realimg,
                                ))
                        }
                    }
                    Log.d("PlushdProvider", "DEBUG: Total de episodios añadidos: ${epi.size}") // **Log**
                } else {
                    Log.w("PlushdProvider", "ADVERTENCIA: Script 'seasonsJson' no encontrado o vacío para TvSeries en URL: $url") // **Log de advertencia**
                }
            }

            Log.d("PlushdProvider", "DEBUG: Devolviendo LoadResponse para tipo: $tvType") // **Log**
            return when(tvType)
            {
                TvType.TvSeries -> {
                    newTvSeriesLoadResponse(title,
                        url, tvType, epi,){
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backimage
                        this.plot = description
                        this.tags = tags
                    }
                }
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, tvType, url){
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backimage
                        this.plot = description
                        this.tags = tags
                    }
                }
                else -> {
                    Log.e("PlushdProvider", "ERROR: Tipo de contenido no soportado o desconocido para URL: $url") // **Log de error**
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR GENERAL en load() para URL $url: ${e.message}", e) // **Log de error**
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("PlushdProvider", "DEBUG: Iniciando loadLinks para data: $data") // **Log**
        try { // **Bloque try-catch para atrapar errores**
            val doc = app.get(data).document
            Log.d("PlushdProvider", "DEBUG: Documento obtenido para loadLinks de data: $data") // **Log**
            val serversFound = doc.select("div ul.subselect li")
            if (serversFound.isEmpty()) {
                Log.w("PlushdProvider", "ADVERTENCIA: No se encontraron elementos 'div ul.subselect li' en loadLinks para data: $data") // **Log de advertencia**
            } else {
                Log.d("PlushdProvider", "DEBUG: Se encontraron ${serversFound.size} servidores.") // **Log**
            }

            serversFound.apmap { serverLi ->
                val serverData = serverLi.attr("data-server")
                Log.d("PlushdProvider", "DEBUG: Procesando servidor con data-server: $serverData") // **Log**
                val encodedOne = serverData.toByteArray()
                val encodedTwo = base64Encode(encodedOne)
                val playerUrl = "$mainUrl/player/$encodedTwo"
                Log.d("PlushdProvider", "DEBUG: URL del reproductor generada: $playerUrl") // **Log**

                try {
                    val text = app.get(playerUrl).text
                    val linkRegex = Regex("window\\.location\\.href\\s*=\\s*'(.*)'")
                    val link = linkRegex.find(text)?.destructured?.component1()

                    if (link != null) {
                        Log.d("PlushdProvider", "DEBUG: Enlace extraído del reproductor: $link") // **Log**
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                        Log.d("PlushdProvider", "DEBUG: loadExtractor llamado para: $link") // **Log**
                    } else {
                        Log.w("PlushdProvider", "ADVERTENCIA: No se pudo extraer el enlace del reproductor de $playerUrl. Contenido (primeros 200 chars): ${text.take(200)}") // **Log de advertencia**
                    }
                } catch (e: Exception) {
                    Log.e("PlushdProvider", "ERROR al procesar URL del reproductor $playerUrl: ${e.message}", e) // **Log de error**
                }
            }
            Log.d("PlushdProvider", "DEBUG: loadLinks finalizado.") // **Log**
            return true
        } catch (e: Exception) {
            Log.e("PlushdProvider", "ERROR GENERAL en loadLinks para data '$data': ${e.message}", e) // **Log de error**
            return false
        }
    }
}