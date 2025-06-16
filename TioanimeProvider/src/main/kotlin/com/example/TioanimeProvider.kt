package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.*
import kotlin.collections.ArrayList
import android.util.Log // Import para Logcat

// NUEVAS IMPORTACIONES PARA JACKSON Y URLDecoder
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLDecoder
import java.nio.charset.StandardCharsets // Para URLEncoder si lo necesitaras en el futuro, aunque en TioAnime no es directo

class TioanimeProvider:MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Película")) TvType.AnimeMovie
            else TvType.Anime
        }
    }
    override var mainUrl = "https://tioanime.com"
    override var name = "TioAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    // Instancia de JsonMapper para el parseo de JSON
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/directorio?year=1950%2C2022&status=2&sort=recent", "Animes"),
            Pair("$mainUrl/directorio?year=1950%2C2022&status=1&sort=recent", "En Emisión"),
            Pair("$mainUrl/directorio?type[]=1&year=1950%2C2022&status=2&sort=recent", "Películas"),
        )
        val items = ArrayList<HomePageList>()
        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl).document.select("ul.episodes li article").map {
                    val title = it.selectFirst("h3.title")?.text()?.replace(Regex("((\\d+)\$)"),"")
                    val poster = it.selectFirst("figure img")?.attr("src")
                    val epRegex = Regex("(-(\\d+)\$)")
                    val url = it.selectFirst("a")?.attr("href")?.replace(epRegex,"")
                        ?.replace("ver/","anime/")
                    val urlepnum = it.selectFirst("a")?.attr("href")
                    val epNum = epRegex.findAll(urlepnum ?: "").map {
                        it.value.replace("-","")
                    }.first().toIntOrNull()
                    val dubstat = if (title!!.contains("Latino") || title.contains("Castellano"))
                        DubStatus.Dubbed
                    else DubStatus.Subbed
                    newAnimeSearchResponse(title, fixUrl(url!!)) {
                        this.posterUrl = fixUrl(poster ?: "")
                        addDubStatus(dubstat, epNum)
                    }
                })
        )
        urls.apmap { (url, name) ->
            val doc = app.get(url).document
            val home = doc.select("ul.animes li article").map {
                val title = it.selectFirst("h3.title")?.text()
                val poster = it.selectFirst("figure img")?.attr("src")
                AnimeSearchResponse(
                    title!!,
                    fixUrl(it.selectFirst("a")?.attr("href") ?: ""),
                    this.name,
                    TvType.Anime,
                    fixUrl(poster ?: ""),
                    null,
                    if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed),
                )
            }

            items.add(HomePageList(name, home))
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    data class SearchObject (
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("last_id") val lastId: String?,
        @JsonProperty("slug") val slug: String
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post("https://tioanime.com/api/search",
            data = mapOf(Pair("value",query))
        ).text
        val json = parseJson<List<SearchObject>>(response)
        return json.map { searchr ->
            val title = searchr.title
            val href = "$mainUrl/anime/${searchr.slug}"
            val image = "$mainUrl/uploads/portadas/${searchr.id}.jpg"
            AnimeSearchResponse(
                title,
                href,
                this.name,
                TvType.Anime,
                fixUrl(image),
                null,
                if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed),
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val episodes = ArrayList<Episode>()
        val title = doc.selectFirst("h1.Title")?.text()
        val poster = doc.selectFirst("div.thumb img")?.attr("src")
        val description = doc.selectFirst("p.sinopsis")?.text()
        val type = doc.selectFirst("span.anime-type-peli")?.text()
        val status = when (doc.selectFirst("div.thumb a.btn.status i")?.text()) {
            "En emision" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val genre = doc.select("p.genres a")
            .map { it?.text()?.trim().toString() }
        val year = doc.selectFirst("span.year")?.text()?.toIntOrNull()

        doc.select("script").map { script ->
            if (script.data().contains("var episodes = [")) {
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                data.split("],").forEach {
                    it.split(",").forEach { epNum ->
                        val link = url.replace("/anime/","/ver/")+"-$epNum"
                        episodes.add( Episode(
                            link,
                            "Capítulo $epNum",
                            posterUrl = null,
                            episode = epNum.toIntOrNull()
                        )
                        )
                    }
                }
            }
        }
        return newAnimeLoadResponse(title!!, url, getType(type!!)) {
            posterUrl = fixUrl(poster ?: "")
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            tags = genre
            this.year = year
        }
    }

    // FUNCIÓN LOADLINKS MODIFICADA
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TioAnime", "loadLinks: Iniciando para URL: $data")
        val doc = app.get(data).document

        // Buscar el script que contiene la variable 'videos'
        val scriptContent = doc.select("script").firstOrNull {
            it.data().contains("var videos =")
        }?.data()

        if (scriptContent.isNullOrEmpty()) {
            Log.e("TioAnime", "loadLinks: No se encontró el script con la variable 'videos'.")
            return false
        }

        // Extraer el JSON de la variable 'videos'
        val jsonString = scriptContent.substringAfter("var videos = ").substringBefore(";")
            .replace("\\'", "'") // Limpiar posibles escapes de comillas simples
            .replace("\\", "") // Quitar barras invertidas de escape si no son necesarias para JSON

        Log.d("TioAnime", "loadLinks: JSON de videos encontrado: ${jsonString.take(500)}") // Log solo una parte

        try {
            // Tioanime usa una lista de listas, donde cada sub-lista es un servidor
            // y dentro tiene objetos con {server, title, code, languaje}
            val videoServers: List<List<TioanimeVideo>> = mapper.readValue(jsonString)

            Log.d("TioAnime", "loadLinks: Se encontraron ${videoServers.size} grupos de servidores.")

            videoServers.apmap { serverGroup ->
                serverGroup.apmap { videoInfo ->
                    val videoCode = videoInfo.code
                    val serverName = videoInfo.title
                    Log.d("TioAnime", "loadLinks: Procesando servidor: $serverName, Code: $videoCode")

                    // Tioanime a veces incrusta links de servicios conocidos o IDs que necesitan ser construidos
                    // Aquí es donde ajustas para los diferentes tipos de servidores que usa Tioanime
                    val urlToExtract = when {
                        videoCode.contains("fembed.com") -> videoCode // Directamente la URL de fembed
                        videoCode.contains("sbani.pro") -> videoCode // Directamente la URL de StreamSB
                        videoCode.contains("ok.ru") -> videoCode // Directamente la URL de Ok.ru
                        // Añade más casos si Tioanime usa otros dominios directamente.
                        // SI ves otros dominios en el Logcat (en 'videoCode'), añádelos aquí.
                        else -> {
                            // Si no es una URL directa, podría ser un ID o una URL codificada o incompleta
                            // Investiga la estructura de 'videoCode' para otros servidores
                            Log.w("TioAnime", "loadLinks: Formato de videoCode no reconocido para extractor directo: $videoCode")
                            null // No procesar si no es un formato esperado
                        }
                    }

                    if (!urlToExtract.isNullOrEmpty()) {
                        Log.d("TioAnime", "loadLinks: Intentando extraer de: $urlToExtract")
                        // Si necesitas decodificar la URL porque viene con entidades HTML o URL-encoded
                        val decodedUrl = URLDecoder.decode(urlToExtract, StandardCharsets.UTF_8.toString())
                            .replace("https://embedsb.com/e/","https://watchsb.com/e/") // Normalizar StreamSB
                            .replace("https://ok.ru","http://ok.ru") // Normalizar Ok.ru

                        loadExtractor(decodedUrl, subtitleCallback, callback)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("TioAnime", "loadLinks: Error al parsear JSON o extraer enlaces: ${e.message}", e)
            return false
        }
    }
}

// Clases de datos para parsear la respuesta JSON de los videos de Tioanime
data class TioanimeVideo(
    @JsonProperty("server") val server: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("code") val code: String, // Esta es la URL o el ID del embed
    @JsonProperty("languaje") val language: String? // Nota: es "languaje" no "language" en el JSON
)

// Opcional: Si el JSON tuviera una clave raíz para los videos, pero parece ser un array directo
// data class TioanimeVideosResponse(
//    @JsonProperty("videos") val videos: List<List<TioanimeVideo>>
// )