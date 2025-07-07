package com.example // ¡MUY IMPORTANTE! Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo.

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin // Importa la anotación CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // Importa la clase base Plugin

@CloudstreamPlugin
class VerpelishdPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(VerpelishdProvider())
    }
}
