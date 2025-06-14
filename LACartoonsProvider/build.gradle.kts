// build.gradle.kts (dentro del directorio de tu módulo LacartoonsProvider)

// use an integer for version numbers
version = 3

cloudstream {
    language = "es"
    // All of these properties are optional, you can safely remove them

    //description = "Lorem Ipsum"
    authors = listOf("Ranita", "Yeji", "Mina")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Cartoons",
        "TvSeries"
    )

    //iconUrl = "https://www.google.com/s2/favicons?domain=lacartoons.com&sz=%size%"
}

// *** AÑADIR ESTE BLOQUE DE DEPENDENCIAS ***
dependencies {
    implementation("com.github.recloudstream.cloudstream:library:v4.5.2")
    // Estas son las dependencias que el plugin de CloudStream generalmente usa y que proporcionan
    // las clases ExtractorApi, ExtractorLink, SubtitleFile, app, etc.
    // Usaremos "pre-release" si tu setup de CloudStream lo soporta, o si no,
    // tendrás que encontrar la versión exacta en JitPack como discutimos antes.
    // Si tienes problemas para resolver "pre-release", tendrás que ir a jitpack.io/#recloudstream/cloudstream
    // y encontrar el commit hash o la versión más reciente (ej: "3.2.0" o "d1a2b3c").

    // Requerido para ExtractorApi, ExtractorLink, app, etc.
    //implementation("com.lagradost.cloudstream3:app:pre-release")
    //implementation("com.lagradost.cloudstream3:extractors:pre-release")
    //implementation("com.lagradost.cloudstream3:webview:pre-release") // Necesario si usas WebView o loadExtractor


    // Requerido para JsonProperty (parte de Jackson)
    // Asegúrate de que las versiones de Jackson coincidan o sean compatibles.
    // 2.17.1 es la más reciente.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1") // Para el soporte de Kotlin de Jackson

    // Otras dependencias comunes si no están ya en el build.gradle.kts padre y las necesitas:
    implementation(kotlin("stdlib-jdk8")) // O la versión de tu stdlib
    implementation("org.jsoup:jsoup:1.18.3") // Para el parsing HTML si no está ya
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Para las peticiones HTTP si no está ya
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1") // Para las corrutinas
}