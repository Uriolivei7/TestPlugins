package com.example

//Prueba para github

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

class SoloLatinoProvider : MainAPI() { // All providers must be an instance of MainAPI
    override var mainUrl = "https://sololatino.net/"
    override var name = "Solo Latino"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var lang = "es"

    // Enable this when your provider has a main page
    override val hasMainPage = true

    // This function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        return listOf()
    }
}