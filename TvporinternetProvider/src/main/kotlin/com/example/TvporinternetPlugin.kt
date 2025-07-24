package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin // Importa la anotación CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin // Importa la clase base Plugin

@CloudstreamPlugin
class TvporinternetPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(TvporinternetProvider())
    }
}
