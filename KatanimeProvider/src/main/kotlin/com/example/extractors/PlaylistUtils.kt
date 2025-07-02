package com.example.extractors

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities // Importación para Qualities.
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

import java.io.File
import java.util.regex.Pattern

// EXTENSIONES NECESARIAS: Si estas funciones no están en un archivo global
// de utilidades de CloudStream o en otro lugar accesible, deben estar aquí.
fun Headers.toMap(): Map<String, String> { //
    return this.associate { it.first to it.second }
}

fun Map<String, String>.toOkHttpHeaders(): Headers { //
    val builder = Headers.Builder()
    for ((name, value) in this) {
        builder.add(name, value)
    }
    return builder.build()
}
// FIN DE EXTENSIONES

class PlaylistUtils(private val client: OkHttpClient, private val baseHeaders: Headers) {

    suspend fun extractFromHls(
        playlistUrl: String,
        referer: String = "",
        masterHeaders: Headers? = null,
        videoHeaders: Headers? = null,
        videoNameGen: (String) -> String = { quality -> quality },
        subtitleCallback: (SubtitleFile) -> Unit,
        audioCallback: (ExtractorLink) -> Unit,
    ): List<ExtractorLink> {
        val currentMasterHeadersMap = (masterHeaders ?: generateMasterHeaders(baseHeaders, referer)).toMap() //

        val masterPlaylistResponse = client.newCall(
            Request.Builder()
                .url(playlistUrl)
                .headers(currentMasterHeadersMap.toOkHttpHeaders())
                .build()
        ).execute()

        val masterPlaylist = masterPlaylistResponse.body?.string() ?: return emptyList()

        if (PLAYLIST_SEPARATOR !in masterPlaylist) { //
            val videoUrl = masterPlaylistResponse.request.url.toString()
            return listOf(
                newExtractorLink(
                    source = "PlaylistUtils",
                    name = videoNameGen("Video"),
                    url = videoUrl,
                    type = INFER_TYPE //
                ) {
                    // Propiedades que son 'var' en ExtractorLink
                    this.quality = Qualities.Unknown.value
                    this.referer = referer
                    this.headers = currentMasterHeadersMap //
                    // NO ASIGNAR isM3u8 o isDash aquí, porque son 'val' en ExtractorLink
                    // Se asume que el tipo (INFER_TYPE) o la URL implicarán si es M3U8 o DASH
                }
            )
        }

        val playlistHttpUrl = playlistUrl.toHttpUrl()
        val masterUrlBasePath = playlistHttpUrl.newBuilder().apply {
            removePathSegment(playlistHttpUrl.pathSegments.size - 1)
            addPathSegment("")
            query(null)
            fragment(null)
        }.build().toString()

        SUBTITLE_REGEX.findAll(masterPlaylist).mapNotNull {
            val subtitleUrl = getAbsoluteUrl(it.groupValues[2], playlistUrl, masterUrlBasePath) ?: return@mapNotNull null
            val lang = it.groupValues[1]
            subtitleCallback(SubtitleFile(lang, subtitleUrl))
        }.toList()

        val audioExtractorLinks = mutableListOf<ExtractorLink>()
        AUDIO_REGEX.findAll(masterPlaylist).forEach {
            val audioUrl = getAbsoluteUrl(it.groupValues[2], playlistUrl, masterUrlBasePath) ?: return@forEach
            val lang = it.groupValues[1]
            val link = newExtractorLink(
                source = "PlaylistUtils",
                name = "Audio ($lang)",
                url = audioUrl,
                type = INFER_TYPE //
            ) {
                // Propiedades que son 'var' en ExtractorLink
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = currentMasterHeadersMap //
                // NO ASIGNAR isM3u8 o isDash aquí
            }
            audioExtractorLinks.add(link)
        }
        audioExtractorLinks.forEach { audioCallback(it) }

        val videoExtractorLinks = mutableListOf<ExtractorLink>()
        masterPlaylist.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR).forEach { stream ->
            val codec = Regex("""CODECS=\"([^\"]+)\"""").find(stream)?.groupValues?.get(1)
            if (!codec.isNullOrBlank()) {
                if (codec.startsWith("mp4a")) return@forEach
            }

            val resolution = Regex("""RESOLUTION=([xX\d]+)""").find(stream)
                ?.groupValues?.get(1)
                ?.let { res ->
                    val standardQuality = Regex("""[xX](\d+)""").find(res)
                        ?.groupValues?.get(1)

                    if (!standardQuality.isNullOrBlank()) {
                        "$standardQuality (${res.replace("x", "X")})"
                    } else {
                        res
                    }
                }
            val bandwidthValue = Regex("""BANDWIDTH=(\d+)""").find(stream)
                ?.groupValues?.get(1)
                ?.toLongOrNull()
                ?.let { formatBytes(it) }

            val streamName = listOfNotNull(resolution, bandwidthValue).joinToString(" - ")
                .takeIf { it.isNotBlank() } ?: "Video"

            val videoUrlValue = stream.substringAfter("\n").substringBefore("\n").let { url ->
                getAbsoluteUrl(url, playlistUrl, masterUrlBasePath)?.trimEnd()
            } ?: return@forEach

            val currentVideoHeadersMap = (videoHeaders ?: generateMasterHeaders(baseHeaders, referer)).toMap() //

            val link = newExtractorLink(
                source = "PlaylistUtils",
                name = videoNameGen(streamName),
                url = videoUrlValue,
                type = INFER_TYPE //
            ) {
                // Propiedades que son 'var' en ExtractorLink
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = currentVideoHeadersMap //
                // NO ASIGNAR isM3u8 o isDash aquí
            }
            videoExtractorLinks.add(link)
        }
        return videoExtractorLinks
    }

    private fun getAbsoluteUrl(url: String, playlistUrl: String, masterBase: String): String? {
        return when {
            url.isEmpty() -> null
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            // Se asume que playlistUrl.toHttpUrl().newBuilder().encodedPath("/").build().toString().substringBeforeLast("/")
            // es para obtener la base URL del playlist, por ejemplo, "http://example.com/path/".
            // Luego se le añade 'url' a eso.
            url.startsWith("/") -> playlistUrl.toHttpUrl().newBuilder().encodedPath("/").build().toString()
                .substringBeforeLast("/") + url
            else -> masterBase + url
        }
    }

    fun generateMasterHeaders(baseHeaders: Headers, referer: String): Headers {
        return baseHeaders.newBuilder().apply {
            set("Accept", "*/*")
            if (referer.isNotEmpty()) {
                val originHost = Uri.parse(referer).host ?: referer.toHttpUrl().host
                if (originHost.isNotEmpty()) {
                    set("Origin", "https://$originHost")
                }
                set("Referer", referer)
            }
        }.build()
    }

    suspend fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String, String) -> String,
        referer: String = "",
        mpdHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, referer, videoUrl ->
            generateMasterHeaders(baseHeaders, referer)
        },
        subtitleCallback: (SubtitleFile) -> Unit,
        audioCallback: (ExtractorLink) -> Unit,
    ): List<ExtractorLink> {
        val mpdHeadersMap = mpdHeadersGen(baseHeaders, referer).toMap() //

        val response = client.newCall(
            Request.Builder()
                .url(mpdUrl)
                .headers(mpdHeadersMap.toOkHttpHeaders())
                .build()
        ).execute()

        val doc = Jsoup.parse(response.body?.string() ?: return emptyList())

        val audioExtractorLinks = mutableListOf<ExtractorLink>()
        doc.select("Representation[mimetype~=audio]").forEach { audioSrc ->
            val audioUrl = audioSrc.selectFirst("BaseURL")?.text() ?: audioSrc.selectFirst("Media")?.attr("sd:src") ?: ""
            if(audioUrl.isBlank()) return@forEach
            val lang = audioSrc.attr("lang") ?: "Unknown"

            val link = newExtractorLink(
                source = "PlaylistUtils",
                name = "Audio ($lang)",
                url = audioUrl,
                type = INFER_TYPE //
            ) {
                // Propiedades que son 'var' en ExtractorLink
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = mpdHeadersMap //
                // NO ASIGNAR isM3u8 o isDash aquí
            }
            audioExtractorLinks.add(link)
        }
        audioExtractorLinks.forEach { audioCallback(it) }

        val videoExtractorLinks = mutableListOf<ExtractorLink>()
        doc.select("Representation[mimetype~=video]").forEach { videoSrc ->
            val bandwidth = videoSrc.attr("bandwidth")
            val res = videoSrc.attr("height") + "p"
            val videoUrl = videoSrc.selectFirst("BaseURL")?.text() ?: videoSrc.selectFirst("Media")?.attr("sd:src") ?: ""

            if(videoUrl.isBlank()) return@forEach

            val link = newExtractorLink(
                source = "PlaylistUtils",
                name = videoNameGen(res, bandwidth),
                url = videoUrl,
                type = INFER_TYPE //
            ) {
                // Propiedades que son 'var' en ExtractorLink
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = videoHeadersGen(baseHeaders, referer, videoUrl).toMap() //
                // NO ASIGNAR isM3u8 o isDash aquí
            }
            videoExtractorLinks.add(link)
        }
        return videoExtractorLinks
    }

    private fun formatBytes(bytes: Long?): String {
        return when {
            bytes == null -> ""
            bytes >= 1_000_000_000 -> "%.2f GB/s".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB/s".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB/s".format(bytes / 1_000.0)
            bytes > 1 -> "$bytes bytes/s"
            bytes == 1L -> "$bytes byte/s"
            else -> ""
        }
    }

    @Suppress("unused")
    suspend fun fixSubtitles(subtitleList: List<SubtitleFile>): List<SubtitleFile> {
        return subtitleList.mapNotNull {
            try {
                val subData = client.newCall(
                    Request.Builder()
                        .url(it.url)
                        .headers(baseHeaders)
                        .build()
                ).execute().body?.string() ?: return@mapNotNull null

                val file = File.createTempFile("subs", "vtt")
                    .also(File::deleteOnExit)

                file.writeText(FIX_SUBTITLE_REGEX.replace(subData, ::cleanSubtitleData))
                val uri = Uri.fromFile(file)

                SubtitleFile(it.lang, uri.toString())
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun cleanSubtitleData(matchResult: MatchResult): String {
        val lineCount = matchResult.groupValues[1].count { it == '\n' }
        return "\n" + "&nbsp;\n".repeat(lineCount - 1)
    }

    companion object {
        private val FIX_SUBTITLE_REGEX = Regex("(\\n{2,})(?!(?:\\d+:)*\\d+(?:\\.\\d+)?\\s-+>\\s(?:\\d+:)*\\d+(?:\\.\\d+)?)", RegexOption.MULTILINE)

        private const val PLAYLIST_SEPARATOR = "#EXT-X-STREAM-INF:"

        private val SUBTITLE_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""") }
        private val AUDIO_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""") }
    }
}