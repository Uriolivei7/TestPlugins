package com.example.extractors // Ajusta esto si tu paquete es diferente

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrl

// Importar el JsUnpacker de CloudStream
import com.lagradost.cloudstream3.utils.JsUnpacker

class UnpackerExtractor(private val client: OkHttpClient, private val baseHeaders: Headers) {

    // Mantener SOLO esta función videosFromUrl, la que tiene 3 argumentos.
    // La otra con "quality" como default DEBE ser eliminada para evitar el "Too many arguments"
    suspend fun videosFromUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): List<ExtractorLink> {
        val extractedLinks = mutableListOf<ExtractorLink>()

        val response = client.newCall(
            Request.Builder()
                .url(url)
                .headers(baseHeaders)
                .build()
        ).execute()

        val doc = response.body?.string()?.let { Jsoup.parse(it) } ?: return emptyList()

        // CORREGIDO: JsUnpacker toma el script como argumento y luego llamas a .unpack()
        val script = doc.selectFirst("script:containsData(eval)")
            ?.data()
            ?.let { JsUnpacker(it).unpack() } // Pasa 'it' (que es la cadena de datos del script) a JsUnpacker
            ?: return emptyList()

        val playlistUrl = script.substringAfter("file:\"").substringBefore('"')

        val playlistUtils = PlaylistUtils(client, baseHeaders)

        val hlsLinks = playlistUtils.extractFromHls(
            playlistUrl,
            referer = playlistUrl,
            videoNameGen = { "LuluStream:$it" },
            subtitleCallback = subtitleCallback,
            audioCallback = { audio -> callback(audio) }
        )
        extractedLinks.addAll(hlsLinks)

        return extractedLinks
    }
}