package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import kotlin.random.Random
import kotlinx.coroutines.delay

// QUITAMOS LA IMPORTACIÓN DE Qualities, ya que no se resuelve
// import com.lagradost.cloudstream3.Qualities // ELIMINAR O COMENTAR ESTA LÍNEA
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class PlushdProvider : MainAPI() {
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

    // ... (Mantén getMainPage, search, MainTemporadaElement, load como están)

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to data
        )
        Log.d("PlushdProvider", "Iniciando loadLinks para: $data")

        val doc = app.get(data, headers = baseHeaders).document
        val serversFound = doc.select("div ul.subselect li")
        if (serversFound.isEmpty()) {
            Log.e("PlushdProvider", "ERROR: No se encontraron servidores de video en la página: $data")
            return false
        }

        var foundLinks = false
        serversFound.apmap { serverLi ->
            val encodedServerId = serverLi.attr("data-server").toByteArray()
            val encodedTwo = base64Encode(encodedServerId)
            val playerUrl = "$mainUrl/player/$encodedTwo"

            Log.d("PlushdProvider", "Procesando servidor con player URL: $playerUrl")

            delay(Random.nextLong(1000, 3000))

            try {
                val plushdPlayerDoc = app.get(playerUrl, headers = baseHeaders, allowRedirects = true).document

                val iframe = plushdPlayerDoc.selectFirst("iframe")
                val seraphinaIframeSrc = iframe?.attr("src") ?: iframe?.attr("data-src")

                // **ÚLTIMA CORRECCIÓN: Inicialización garantizada de fullSeraphinaUrl**
                // Declaramos 'fullSeraphinaUrl' como lateinit var o hacemos la asignación nula inicial.
                // Usaremos una asignación directa aquí para evitar el error de "Unresolved reference".
                val fullSeraphinaUrl: String = if (seraphinaIframeSrc.isNullOrEmpty()) {
                    Log.e("PlushdProvider", "ERROR: No se encontró iframe en la página del reproductor de Plushd: $playerUrl")
                    // Aquí no podemos simplemente retornar, porque Kotlin necesita que fullSeraphinaUrl sea asignada
                    // en todas las ramas. Entonces, si no hay iframe, le damos un valor vacío
                    // y nos aseguramos de que el código posterior lo maneje.
                    // O, la mejor opción es usar un 'return@apmap' antes de la asignación de fullSeraphinaUrl.
                    // Vamos a mover el log y el return para que la asignación sea siempre válida.
                    return@apmap // Si no hay iframe, salimos de este 'apmap' para este servidor
                } else if (seraphinaIframeSrc.startsWith("/")) {
                    val seraphinaBase = "https://seraphinapl.com"
                    "$seraphinaBase$seraphinaIframeSrc"
                } else {
                    seraphinaIframeSrc
                }


                Log.d("PlushdProvider", "URL de Seraphina Iframe: $fullSeraphinaUrl")

                val seraphinaHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to playerUrl
                )

                val seraphinaDoc = app.get(fullSeraphinaUrl, headers = seraphinaHeaders, allowRedirects = true).document
                Log.d("PlushdProvider", "HTML de Seraphina: ${seraphinaDoc.html().take(500)}...")

                val scriptContent = seraphinaDoc.select("script").firstOrNull { it.html().contains(".m3u8") }?.html()

                var finalM3u8Link: String? = null
                if (scriptContent != null) {
                    val regex = "(https?://[^\"']+\\.m3u8(?:\\?[^\"']*)?)".toRegex()
                    val match = regex.find(scriptContent)
                    finalM3u8Link = match?.groupValues?.get(1)
                }

                if (finalM3u8Link.isNullOrEmpty()) {
                    Log.e("PlushdProvider", "ADVERTENCIA: No se encontró un enlace M3U8 obvio en los scripts de Seraphina. Puede requerir análisis de XHR/Fetch.")
                    val fallbackMatch = "https?://[^\"']+\\.acek-cdn\\.com/[^\"']*\\.m3u8(?:\\?[^\"']*)?".toRegex().find(seraphinaDoc.html())
                    finalM3u8Link = fallbackMatch?.groupValues?.get(0)
                }

                if (!finalM3u8Link.isNullOrEmpty()) {
                    Log.d("PlushdProvider", "¡Enlace M3U8 Final Encontrado en Seraphina!: $finalM3u8Link")

                    callback(
                        ExtractorLink(
                            source = "Seraphina",
                            name = "Seraphina - CDN",
                            url = finalM3u8Link,
                            referer = fullSeraphinaUrl,
                            quality = 0,
                            headers = emptyMap(),
                            extractorData = null,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    foundLinks = true
                } else {
                    Log.e("PlushdProvider", "ERROR: No se pudo extraer el enlace del reproductor de Seraphina de $fullSeraphinaUrl.")
                }

            } catch (e: Exception) {
                // Aquí, fullSeraphinaUrl podría no estar inicializada si la excepción ocurre muy temprano.
                // Lo mejor es manejarla con un mensaje más genérico o revisar la pila de llamadas.
                Log.e("PlushdProvider", "ERROR: Excepción al procesar URL del reproductor. Mensaje: ${e.message}", e)
            }
        }
        return foundLinks
    }
}