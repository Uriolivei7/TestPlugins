package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder
import java.net.URI
import org.jsoup.nodes.Document
import android.util.Base64 // Importar Base64 de Android
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.delay

// ##################################################################################
// ########################### INICIO IMPORTACIONES PARA CRIPTOGRAFÍA ###############
// ##################################################################################
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
// ##################################################################################
// ########################### FIN IMPORTACIONES PARA CRIPTOGRAFÍA ##################
// ##################################################################################

class LacartoonsProvider : MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LACartoons"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon
    )

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    // Helper para loggear cadenas largas
    private fun logLongString(tag: String, message: String) {
        val chunkSize = 4000 // Tamaño máximo de línea para logcat
        var i = 0
        while (i < message.length) {
            val endIndex = min(i + chunkSize, message.length)
            println("$tag: ${message.substring(i, endIndex)}")
            i += chunkSize
        }
    }

    private fun Document.toSearchResult(): List<SearchResponse> {
        return this.select(".categorias .conjuntos-series a").map {
            val title = it.selectFirst("p.nombre-serie")?.text()
            val href = fixUrl(it.attr("href"))
            val img = fixUrl(it.selectFirst("img")!!.attr("src"))
            newTvSeriesSearchResponse(title!!, href) {
                this.posterUrl = img
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get(mainUrl).document
        val home = soup.toSearchResult()
        items.add(HomePageList("Series", home))
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?utf8=✓&Titulo=$query").document
        return doc.toSearchResult()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h2.text-center")?.text()
        val description =
            doc.selectFirst(".informacion-serie-seccion p:contains(Reseña)")?.text()
                ?.substringAfter("Reseña:")?.trim()
        val poster = doc.selectFirst(".imagen-serie img")?.attr("src")
        val backposter = doc.selectFirst("img.fondo-serie-seccion")?.attr("src")
        val episodes = doc.select("ul.listas-de-episodion li").map {
            val regexep = Regex("Capitulo.(\\d+)|Capitulo.(\\d+)\\-")
            val href = it.selectFirst("a")?.attr("href")
            val name = it.selectFirst("a")?.text()?.replace(regexep, "")?.replace("-", "")
            val seasonnum = href?.substringAfter("t=")
            val epnum = regexep.find(name.toString())?.destructured?.component1()

            val actualEpnum = epnum?.toIntOrNull()

            Episode(
                fixUrl(href!!),
                name,
                seasonnum?.toIntOrNull(),
                actualEpnum,
            )
        }

        return newTvSeriesLoadResponse(title!!, url, TvType.Cartoon, episodes) {
            this.posterUrl = fixUrl(poster!!)
            this.backgroundPosterUrl = fixUrl(backposter!!)
            this.plot = description
        }
    }

    // ##################################################################################
    // ########################### START DECRYPTION LOGIC ###############################
    // ##################################################################################

    // 1. Array de Cadenas de Xr() - LIMPIADO y CORREGIDO
    private val xrStrings = arrayOf(
        "data-id", "replace", "some", "downloader-toast-container", "media-video-layout", "type", "--video-time-bg", "padStart", "startLoadingPoster", "div", "img", "subtitleFontSize", "--media-resumable-text-color", "--media-menu-text-secondary-color", "removeItem", "6458336UHbLtm", "impression", "logo", "Push Ads", "ended", "is-hidden", "zIndex", "no-download", "core", "uiElements", "backgroundSize", "parse", "href", "sandbox", "top: 0; left: 0; z-index: 3; width: 100%; height: 100%; position: absolute; display: flex; flex-direction: column; justify-content: center; align-items: center; color: white; font-size: 24px; cursor: pointer;", "width", "media-toggle-button", "banner", "provider-setup", "touchstart", "volume", "content", "blur", "displayContainer", "getTime", "CONTENT_PAUSE_REQUESTED", "download", "mute", "10139856JpWTpX", "FULLSCREEN", "--video-brand", "http", "cfStream", "COUNTDOWN", "visibilityState", "hash", "z-index: 2", ":root", "px; width: min-content; max-height: 100%; height: min-content; position: relative; overflow: hidden; cursor: pointer;", "resumeAcceptButton", "tooltipBackground", "playerAds", "px)", "translation", "vds-playlist-tooltip", "&api=", "pathname", "player", "AdsRequest", "offsetHeight", "<span>Play</span>", "Sorry there is no download link for this video", "htmlContainer", "translateX(", "--media-time-font-weight", "defaultSubtitle", "player-loading-text", "closed", "applyDynamicConfig", "Opss! Headless Browser is not allowed", "slot", "disabled", "metric", "Unknown error", "classList", "forEach", "getItem", "floor", "Sorry, this video is not available", "--media-slider-value-color", "vds-tooltip-content", "media-tooltip", "NORMAL", "requestAds", "play", "pop", "UiElements", "paused", "detail", "cover", "thumbnails", "AES-CBC", "media-player", "hidden", "<svg class=\"vds-icon\" viewBox=\"0 0 32 32\" fill=\"none\" aria-hidden=\"true\" xmlns=\"http://www.w3.org/2000/svg\">\n  <path d=\"M12 7.66667C12 7.29848 12.2985 7 12.6666 7H26C26.3682 7 26.6666 7.29848 26.6666 7.66667V9.66667C26.6666 10.0349 26.3682 10.3333 26 10.3333H12.6666C12.2985 10.3333 12 10.0349 12 9.66667V7.66667Z\" fill=\"currentColor\"></path>\n  <path d=\"M12 15C12 14.6318 12.2985 14.3333 12.6666 14.3333H26C26.3682 14.3333 26.6666 14.6318 26.6666 15V17C26.6666 17.3682 26.3682 17.6667 26 17.6667H12.6666C12.2985 17.6667 12 17.3682 12 17V15Z\" fill=\"currentColor\"></path>\n  <path d=\"M5.99998 21.6667C5.63179 21.6667 5.33331 21.9651 5.33331 22.3333V24.3333C5.33331 24.7015 5.63179 25 5.99998 25H7.99998C8.36817 25 8.66665 24.7015 8.66665 24.3333V22.3333C8.66665 21.9651 8.36817 21.6667 7.99998 21.6667H5.99998Z\" fill=\"currentColor\"></path>\n  <path d=\"M12.6666 21.6667C12.2985 21.6667 12 21.9651 12 22.3333V24.3333C12 24.7015 12.2985 25 12.6666 25H26C26.3682 25 26.6666 24.7015 26.6666 24.3333V22.3333C26.6666 21.6667 26.3682 21.6667 26 21.6667H12.6666Z\" fill=\"currentColor\"></path>\n  <path d=\"M5.99998 14.3333C5.63179 14.3333 5.33331 14.6318 5.33331 15V17C5.33331 17.3682 5.63179 17.6667 5.99998 17.6667H7.99998C8.36817 17.6667 8.66665 17.3682 8.66665 17V15C8.66665 14.6318 8.36817 14.3333 7.99998 14.3333H5.99998Z\" fill=\"currentColor\"></path>\n  <path d=\"M5.99998 7C5.63179 7 5.33331 7.29848 5.33331 7.66667V9.66667C5.33331 10.0349 5.63179 10.3333 5.99998 10.3333H7.99998C8.36817 10.3333 8.66665 10.0349 8.66665 9.66667V7.66667Z\" fill=\"currentColor\"></path>\n</svg>", "stop", "No videoId found", "split", "getStatus", "ttStream", "126280RIvQKP", "onClick", "sliderLoadColor", "change", "sandboxed", "--media-tooltip-font-weight", "</button>\n                </div>\n            </div>\n        ", "Opss! Sandboxed our player is not allowed", "position", "true", ".resume-dialog-abort", "decode", "custom", "AD_ERROR", "You were watching this video at {{TIME}}. Do you want to resume?", "<p style=\"font-size: 28px\">", "script", "ads", "click", "iframeApi", "destroy", "onload", "includes", "url", "fontFamily", "ttdata", "/api/v1/download?id=", "media-tooltip-content", "p2pEngine", "AdDisplayContainer", "isPremium", "parent", "resize", "getWidth", "--media-slider-track-bg", "Watch", "black", "100%", "mp4", "unshift", "https://imasdk.googleapis.com/js/sdkloader/ima3.js", "right", "ima", "toISOString", "aria-label", "&w=", "menuSecondary", "contentDocument", "vds-tooltip", "backgroundColor", "vds-quality-button", "AdsManagerLoadedEvent", "value", "--video-controls-color", "ADS_MANAGER_LOADED", "showing", "/api/v1/folder?id=", "linearAdSlotHeight", "config", "test", "removeEventListener", "&r=", "/pproxy/", "querySelectorAll", "--media-cue-font-weight", "/tt/master.", "player-logo", "resumeTextColor", "/api/v1/video?id=", "indexOf", "webdriver", "hostname", "userAgent", "Start from beginning", "pointerdown", "reverse", "match", "onerror", "16yliFNJ", "encrypt", "--media-time-color", "player-button-container", "querySelector", "translations", "thumbnail", "restoreCustomPlaybackStateOnAdBreakComplete", "decrypt", "toString", "--media-tooltip-bg-color", "branding", "No folder found", "current-slide", "</p>\n                <div class=\"resume-dialog-actions\">\n                    <button class=\"resume-dialog-accept\">", "from", "width: 100%; height: 100%; z-index: 2; overflow: hidden;", "duration", "vds-playlist-button", "nextElementSibling", "src", "Direct Link", "adBlock", "poster", "next-slide-2", "193114rxqLzv", "Onclick Ads", "Please use a modern browser to watch this video", "qualities", "map", "left", "tooltipFontSize", "Close Ad", "resumePlayback", ".vds-download-button.vds-button", "encode", "location", "languages", "origin", "AdEvent", "catch", "getAdsManager", "navigator", "Please disable adblock to download this video", "menuPrimary", "target", "menuSection", "backgroundImage", "offsetWidth", "children", "preventDefault", "CLICK", "{{videoId}}", "subtitleColor", "contains", "--media-resumable-reject-button", "crypto", "Ready", "margin: 0px; padding: 0px; display: flex; justify-content: center; align-items: flex-end; height: 100%;", "Video is not ready yet", "superPlayer", "findIndex", "data", "downloadLink", " to download this video", "Please disable AdBlock to watch this video", "pointerup", "downloadButton", "&reportCurrentTime=1", "default_audio", "start", "touchend", "init", "clientX", "--media-user-text-bg", "visibilitychange", "placement", "stringify", "www.", "subtitleBackground", "--media-tooltip-font-size", "hls-error", "iconColor", "createElement", "style", "transparent", "setItem", "adId", "timeFontWeight", "direct", "referrer", "allowExternal", "playing", "currentTime", "Type", "open", "&poster=", "pause", "subtitleFontWeight", "text", "networkError", "span", "create", "Failed to setup player, please try again later.", "1634479IUNzXG", "postMessage", "player-loading", "getAd", "addEventListener", "isLinear", "url(\"", "--media-menu-text-color", "source", "coder", "userId", "innerHTML", "transform", "nonLinearAdSlotHeight", "seek", ".vds-quality-button", "/api/v1/info?id=", "innerWidth", "&subs=", "translateX(-", "language", "video", "sliderTrackColor", "message", "Resume", "\n            <div class=\"resume-dialog-content\">\n                <p>", "https://", "button", "position: absolute; right: calc(50% - 40px); top: 0px; color: white; cursor: pointer; z-index: 5; background-color: black; padding: 0px 6px; border-radius: 4px; display: none", "add", "codePointAt", "document", "maxWidth", "timeFontSize", "restrictCountry", "Download is unavailable", "set", "hls", "time-update", "preload.m3u8", "LOADED", "getHeight", "object", "--media-resumable-accept-button", "AdsLoader", "mode", "resumeRejectButton", "sliderTimeColor", "ancestorOrigins", "top", "assign", "downloadSource", "block", "show", "subtle", "media-tooltip-trigger", "direct://", "tooltipFontWeight", "startsWith", "firstChild", "subtitle", "downloader-button", "reload", "allowDownload", "protocol", "adsLoader", "fromCodePoint", "asset", "playerId", "setProperty", "AdsRenderingSettings", "textContent", "Loading...", "menuTopBar", "replaceChild", "subtitles", "tooltipColor", "loadVideoTimeout", "is-shown", "{{TIME}}", "--video-font-family", "Vast Tag", "--media-time-font-size", "onclick", "then", "name", "swarmId", "Headless Detected", "--media-menu-section-bg", "mouseover", "className", "tagName", "join", "slice", "restrictEmbed", "p2pStream", "visitorCountry", "4625526QfAdwx", "&dl=1", "ALL_ADS_COMPLETED", "innerHeight", "setAttribute", "next-slide-1", "body", "AD_ATTRIBUTION", "audioTracks", "vds-quality-tooltip", "appendChild", "startTime", "auto", "_blank", "/api/v1/player?t=", "torrentTrackers", "Switch quality", "firstElementChild", "screen", "resume:", "14242487VsOnLe", "importKey", "remove", "/api/v1/log?t=", "ViewMode", "aria-pressed", "allow-scripts allow-forms allow-popups allow-same-origin", "provider-change", "requestPointerLock", "height", "Getting download link...", "ready", "string", "defaultAudio", "fullscreen-change", "Banner Ads", "sliderTimeBackground", "downloader-button-container", "httpStream", "ipp", "title", "videoId", "Media source is not playable", ".resume-dialog-accept", "insertBefore", "media-provider", "bottom", "length", "parentNode", "null", "attributes", "--media-tooltip-color", "Quality", "textTracks", "Download", "observe", "getElementById", "shift", "getAttribute", "position: absolute; right: calc(50% - ", "vds-button", "all", "prev-slide-2", "<div class=\"toast\">", "application/x-mpegurl", "format", "iframe", "selected", ".vds-menu-button.vds-button", "mouseout", "startLoading", "px; height: min-content; position: absolute; border: 0px; overflow: hidden;"
    )

    // 2. Función ne(index: Int)
    private fun ne(index: Int): String {
        return xrStrings[index - 369]
    }

    // 3. La cadena `h` (base de caracteres para f)
    private val charSetH = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    // 4. Función f(b: Int, T: Int)
    private fun f(b: Int, T: Int): String {
        var S = StringBuilder()
        var tempB = b
        var tempT = T
        while (tempT >= 0) {
            S.append(charSetH[tempB and 63])
            tempB = tempB ushr 6
            tempT--
        }
        return S.toString()
    }

    // 5. Función h(...m) (String.fromCodePoint en Kotlin)
    // Se asegura que acepta un vararg de Int y lo convierte a String correctamente.
    private fun h_fromCodePoint(vararg m: Int): String {
        if (m.isEmpty()) return ""
        return m.joinToString("") { Character.toChars(it).joinToString("") }
    }

    // 7. Función x(m: String) (String a Bytes - TextEncoder equivalent)
    private fun x_encode(m: String): ByteArray {
        return m.toByteArray(StandardCharsets.UTF_8)
    }

    // 8. Función d(m: String) (Hex String a Bytes - Datos Cifrados)
    private fun d_hexToBytes(m: String): ByteArray {
        val hexPairs = Regex("[\\da-f]{2}", RegexOption.IGNORE_CASE).findAll(m).map { it.value }.toList()
        return ByteArray(hexPairs.size) { i ->
            hexPairs[i].toInt(16).toByte()
        }
    }

    // 9. Función p(m: ByteArray) (Bytes Descifrados a String - TextDecoder equivalent)
    private fun p_decode(m: ByteArray): String {
        return String(m, StandardCharsets.UTF_8)
    }

    // 10. Función b() (Generar Clave)
    private fun b_generateKey(): ByteArray {
        val protocol = URI(mainUrl).scheme + ":"
        val P_const = 10
        val D_const = 110
        val K_const = 1

        var F_str = ""

        println("LACartoons: b_generateKey - Inicio")

        // Corregido: .codePointAt(0) puede ser null si la cadena está vacía
        val fResult_phi_char = "ᵟ".codePointAt(0) ?: 0
        val fResult_phi = f(fResult_phi_char, P_const / 10)
        println("LACartoons: b_generateKey - fResult_phi_char: $fResult_phi_char, fResult_phi: $fResult_phi")

        val dollarArray = fResult_phi.split("").filter { it.isNotEmpty() }
        println("LACartoons: b_generateKey - dollarArray: $dollarArray")

        for (element in dollarArray) {
            if (element.isNotEmpty()) {
                val cp = element.codePointAt(0) ?: 0
                val charToAdd = h_fromCodePoint(P_const + cp)
                F_str += charToAdd
                println("LACartoons: b_generateKey - F_str (después de dollarArray loop): '$F_str' (añadido: '$charToAdd')")
            }
        }

        val protocolCodePoint = protocol.codePointAt(0)
        if (protocolCodePoint != null) {
            val charToAdd = h_fromCodePoint(f(protocolCodePoint, P_const / 10).codePointAt(0) ?: 0)
            F_str += charToAdd
            println("LACartoons: b_generateKey - F_str (después de protocol): '$F_str' (añadido: '$charToAdd')")
        }

        F_str += F_str.substring(1, minOf(F_str.length, 1 + 2))
        println("LACartoons: b_generateKey - F_str (después de substring): '$F_str'")

        val charsToAddD = h_fromCodePoint(D_const, D_const - 1, D_const + 7)
        F_str += charsToAddD
        println("LACartoons: b_generateKey - F_str (después de D_const chars): '$F_str' (añadido: '$charsToAddD')")

        val ieArray = "3579".split("").filter { it.isNotEmpty() } // ["3", "5", "7", "9"]
        println("LACartoons: b_generateKey - ieArray: $ieArray")

        // Corregido: Asegurarse de que los elementos sean Int antes de la operación numérica
        val cp1 = (ieArray[3].toInt() + ieArray[2].toInt()) // 9 + 7 = 16
        val cp2 = (ieArray[1].toInt() + ieArray[2].toInt())  // 5 + 7 = 12
        val charsToAddIE = h_fromCodePoint(cp1, cp2)
        F_str += charsToAddIE
        println("LACartoons: b_generateKey - F_str (después de ieArray cp1,cp2): '$F_str' (añadido: '$charsToAddIE', cp1: $cp1, cp2: $cp2)")


        val cp3 = ieArray[0].toInt() * K_const + K_const + ieArray[3].toInt()
        val cp4 = ieArray[0].toInt() * K_const + K_const + ieArray[3].toInt()
        val charsToAddIE2 = h_fromCodePoint(cp3, cp4)
        F_str += charsToAddIE2
        println("LACartoons: b_generateKey - F_str (después de ieArray cp3,cp4): '$F_str' (añadido: '$charsToAddIE2', cp3: $cp3, cp4: $cp4)")


        // CORRECCIÓN APLICADA AQUÍ: .slice(0, 2) cambiado a .substring(0, 2)
        // Convertir a Int si es necesario para el h_fromCodePoint
        val reversedJoinedSliceInt = ieArray.reversed().joinToString("").substring(0, 2).toInt() // "97" -> 97
        val cp5 = ieArray[3].toInt() * P_const + ieArray[3].toInt() * K_const
        val charsToAddIE3 = h_fromCodePoint(cp5, reversedJoinedSliceInt)
        F_str += charsToAddIE3
        println("LACartoons: b_generateKey - F_str (después de ieArray cp5,reversed): '$F_str' (añadido: '$charsToAddIE3', cp5: $cp5, reversedJoinedSliceInt: $reversedJoinedSliceInt)")

        println("LACartoons: b_generateKey - F_str FINAL antes de x_encode: '$F_str'")

        return x_encode(F_str)
    }

    // 11. Función T() (Generar IV)
    private fun T_generateIv(): ByteArray {
        val origin = URI(mainUrl).scheme + "://" + URI(mainUrl).host
        val hostname = URI(mainUrl).host

        val P_slash = "$origin//"
        val K_val = origin.length * P_slash.length
        val F_const = 1

        var dollarStr = StringBuilder()

        println("LACartoons: T_generateIv - Inicio")
        println("LACartoons: T_generateIv - origin: '$origin', hostname: '$hostname'")
        println("LACartoons: T_generateIv - P_slash: '$P_slash', K_val: $K_val, F_const: $F_const")

        for (pe in F_const until 10) {
            val charToAdd = h_fromCodePoint(pe + K_val)
            dollarStr.append(charToAdd)
            println("LACartoons: T_generateIv - dollarStr (después de loop): '$dollarStr' (añadido: '$charToAdd')")
        }

        var ie_str = ""
        ie_str = F_const.toString() + ie_str + F_const.toString() + ie_str + F_const.toString() // "111"
        println("LACartoons: T_generateIv - ie_str: '$ie_str'")

        val hostnameCodePoint = hostname.codePointAt(0)
        val he_val = if (hostnameCodePoint != null) {
            val f_res_char = f(hostnameCodePoint, F_const).codePointAt(0) ?: 0
            val calc_he_val = ie_str.length * f_res_char
            println("LACartoons: T_generateIv - hostnameCodePoint: $hostnameCodePoint, f(hostnameCodePoint, F_const) result char code: $f_res_char, he_val calculated: $calc_he_val")
            calc_he_val
        } else {
            0
        }

        val Ue_val = ie_str.toInt() * F_const + origin.length
        println("LACartoons: T_generateIv - Ue_val: $Ue_val")
        val R_val = Ue_val + 4
        println("LACartoons: T_generateIv - R_val: $R_val")

        val originCodePoint = origin.codePointAt(0)
        val Z_val = if (originCodePoint != null) {
            val f_res_Z = f(originCodePoint, F_const)
            println("LACartoons: T_generateIv - originCodePoint: $originCodePoint, Z_val (f result): '$f_res_Z'")
            f_res_Z
        } else {
            ""
        }

        val Se_val = Z_val.codePointAt(0)?.let { it * F_const - 2 } ?: 0
        println("LACartoons: T_generateIv - Se_val: $Se_val")

        val argsForH = mutableListOf<Int>()
        argsForH.add(K_val)
        argsForH.add(ie_str.toInt())
        argsForH.add(he_val)
        argsForH.add(Ue_val)
        argsForH.add(R_val)
        Z_val.codePointAt(0)?.let { argsForH.add(it) } ?: argsForH.add(0)
        argsForH.add(Se_val)
        println("LACartoons: T_generateIv - argsForH (code points): $argsForH")

        val charsToAddArgs = h_fromCodePoint(*argsForH.toIntArray())
        dollarStr.append(charsToAddArgs)
        println("LACartoons: T_generateIv - dollarStr (después de argsForH): '$dollarStr' (añadido: '$charsToAddArgs')")


        println("LACartoons: T_generateIv - dollarStr FINAL antes de x_encode: '$dollarStr'")

        return x_encode(dollarStr.toString())
    }

    // Función de descifrado AES-CBC
    private fun decryptContent(encryptedHex: String): String {
        val keyBytes = b_generateKey()
        val keyHash = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val secretKeySpec = SecretKeySpec(keyHash, "AES")

        val ivBytes = T_generateIv()
        val ivHash = MessageDigest.getInstance("MD5").digest(ivBytes)
        val ivSpec = IvParameterSpec(ivHash)

        val encryptedData = d_hexToBytes(encryptedHex)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
        val decryptedBytes = cipher.doFinal(encryptedData)

        println("LACartoons: Key Hash (hex): ${keyHash.joinToString("") { "%02x".format(it) }}")
        println("LACartoons: IV Hash (hex): ${ivHash.joinToString("") { "%02x".format(it) }}")

        return p_decode(decryptedBytes)
    }

    // ##################################################################################
    // ########################### END DECRYPTION LOGIC #################################
    // ##################################################################################

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframeSrc = doc.selectFirst(".serie-video-informacion iframe")?.attr("src")

        if (iframeSrc == null) {
            println("${name}: No se encontró iframe para el episodio: $data")
            return false
        }

        // --- LÓGICA PARA SENDVID.COM ---
        if (iframeSrc.contains("sendvid.com/embed/")) {
            println("${name}: Detectado iframe de sendvid.com. Intentando extracción manual.")
            val sendvidEmbedUrl = iframeSrc

            val embedDoc = try {
                app.get(sendvidEmbedUrl).document
            } catch (e: Exception) {
                println("${name}: SENDVID_ERROR - No se pudo obtener el documento del embed de Sendvid: $sendvidEmbedUrl. ${e.message}")
                return false
            }

            // Intenta obtener la URL del video de la etiqueta <source> dentro de <video>
            val videoUrl = embedDoc.selectFirst("video#video-js-video source#video_source")?.attr("src")
            // Como respaldo, intenta de las meta etiquetas Open Graph
                ?: embedDoc.selectFirst("meta[property=og:video]")?.attr("content")
                ?: embedDoc.selectFirst("meta[property=og:video:secure_url]")?.attr("content")

            if (!videoUrl.isNullOrBlank()) {
                println("${name}: SENDVID_SUCCESS - URL de video encontrada para Sendvid: $videoUrl")
                callback.invoke(
                    ExtractorLink(
                        source = "Sendvid",
                        name = "Sendvid",
                        url = videoUrl,
                        referer = sendvidEmbedUrl, // La URL del embed de Sendvid como referer
                        quality = Qualities.Unknown.value,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        headers = mapOf("User-Agent" to "Mozilla/50 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                    )
                )
                return true
            } else {
                println("${name}: SENDVID_WARN - No se encontró la URL del video en la página de Sendvid: $sendvidEmbedUrl")
                return false
            }
        }
        // --- FIN LÓGICA PARA SENDVID.COM ---

        // --- LÓGICA EXISTENTE PARA CUBEEMBED.RPMVID.COM ---
        else if (iframeSrc.contains("cubeembed.rpmvid.com")) {
            println("${name}: Detectado iframe de cubeembed.rpmvid.com, procesando internamente.")
            val cubembedUrl = iframeSrc
            val embedId = cubembedUrl.substringAfterLast("#")

            if (embedId.isEmpty()) {
                println("${name}: No se pudo extraer el ID del embed de Cubembed de la URL: $cubembedUrl")
                return false
            }

            val commonApiHeaders = mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br, zstd",
                "Accept-Language" to "es-ES,es;q=0.5",
                "Priority" to "u=1, i",
                "Referer" to cubembedUrl.substringBefore("#"),
                "Sec-Ch-Ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Brave\";v=\"138\"",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to "\"Windows\"",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Gpc" to "1",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
            )

            try {
                // --- PASO 1: Simular la llamada 'k' del JS (para /api/v1/info) ---
                val infoApiUrl = "https://cubeembed.rpmvid.com/api/v1/info?id=$embedId"
                println("${name}: Realizando solicitud GET a la API de info: ${infoApiUrl}")
                val infoResponse = app.get(infoApiUrl, headers = commonApiHeaders)
                val infoEncodedHex = infoResponse.text

                logLongString(name, "Cadena HEX recibida de /info (completa): $infoEncodedHex")

                val infoDecryptedContent = decryptContent(infoEncodedHex)
                logLongString(name, "Contenido DESCIFRADO de /info (completa): $infoDecryptedContent")

                delay(1000)

                // --- PASO 2: Simular la llamada 'I' del JS (para /api/v1/video) ---
                val videoApiUrl = "https://cubeembed.rpmvid.com/api/v1/video?id=$embedId&w=1280&h=800&r="
                println("${name}: Realizando solicitud GET a la API de video: ${videoApiUrl}")
                val videoResponse = app.get(videoApiUrl, headers = commonApiHeaders, allowRedirects = true)
                val videoEncodedHex = videoResponse.text

                logLongString(name, "Cadena HEX recibida de /video (completa): $videoEncodedHex")

                val videoDecryptedContent = decryptContent(videoEncodedHex)
                logLongString(name, "Contenido DESCIFRADO de /video (completa): $videoDecryptedContent")

                val videoJson = AppUtils.tryParseJson<CubembedApiResponse>(videoDecryptedContent)
                val m3u8Url = videoJson?.file ?: videoJson?.quality

                if (m3u8Url != null) {
                    println("${name}: ¡Éxito! URL de video M3U8 extraída de la cadena descifrada de /video: $m3u8Url")

                    val finalM3u8Headers = mapOf(
                        "Referer" to "https://cubeembed.rpmvid.com/",
                        "Origin" to "https://cubeembed.rpmvid.com",
                        "User-Agent" to commonApiHeaders["User-Agent"]!!
                    )

                    callback(
                        ExtractorLink(
                            source = "Cubembed",
                            name = "Cubembed",
                            url = m3u8Url,
                            referer = "https://cubeembed.rpmvid.com/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = finalM3u8Headers
                        )
                    )
                    return true
                } else {
                    println("${name}: No se encontró la URL HLS (.m3u8) en el JSON descifrado de /video. Contenido: $videoDecryptedContent")
                    val infoJson = AppUtils.tryParseJson<CubembedApiResponse>(infoDecryptedContent)
                    val m3u8UrlInfo = infoJson?.file ?: infoJson?.quality

                    if (m3u8UrlInfo != null) {
                        println("${name}: ¡Éxito! URL de video M3U8 extraída del JSON descifrado de /info: $m3u8UrlInfo")
                        val finalM3u8Headers = mapOf(
                            "Referer" to "https://cubeembed.rpmvid.com/",
                            "Origin" to "https://cubeembed.rpmvid.com",
                            "User-Agent" to commonApiHeaders["User-Agent"]!!
                        )
                        callback(
                            ExtractorLink(
                                source = "Cubembed",
                                name = "Cubembed",
                                url = m3u8UrlInfo,
                                referer = "https://cubeembed.rpmvid.com/",
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8,
                                headers = finalM3u8Headers
                            )
                        )
                        return true
                    } else {
                        println("${name}: No se encontró la URL HLS (.m3u8) en el JSON descifrado de /info tampoco. Contenido: $infoDecryptedContent")
                    }
                }

            } catch (e: Exception) {
                println("${name}: Error al procesar el embed de Cubembed (info/video API): ${e.message}")
                e.printStackTrace()
            }
            return false

        } else if (iframeSrc.contains("dhtpre.com")) {
            println("${name}: Detectado iframe de dhtpre.com, usando loadExtractor.")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        } else {
            println("${name}: Tipo de iframe desconocido: $iframeSrc. Intentando con loadExtractor por defecto.")
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
    }

    data class CubembedApiResponse(
        @JsonProperty("file")
        val file: String?,
        @JsonProperty("quality")
        val quality: String? = null
    )
}