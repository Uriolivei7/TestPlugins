package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VeranimesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VerAnimesProvider())
    }
}
