package com.example // ¡MUY IMPORTANTE! Asegúrate de que este paquete coincida EXACTAMENTE con la ubicación real de tu archivo en el sistema de archivos.

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.MainAPI

/**
 * Esta es la clase principal de tu plugin para CloudStream.
 * Debe estar anotada con @CloudstreamPlugin.
 * Aquí es donde registrarás todos tus proveedores (MainAPI).
 */
@CloudstreamPlugin
class GnulaPlugin : Plugin() {
    /**
     * Aquí debes registrar todos tus proveedores (MainAPI).
     *
     * @param context El contexto de la aplicación.
     */
    override fun load(context: Context) {
        // Registra tu GnulaProvider.
        // GnulaProvider NO DEBE tener la anotación @CloudstreamPlugin.
        registerMainAPI(GnulaProvider())
    }
}