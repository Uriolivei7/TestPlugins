@file:Suppress("UnstableApiUsage")

// use an integer for version numbers
version = 12

cloudstream {
    description = "Videos de YouTube"
    authors = listOf("Ranita")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf("Others")

    requiresResources = true

    iconUrl = "https://www.youtube.com/s/desktop/711fd789/img/logos/favicon_144x144.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.24.5")
    // Línea corregida: Se cambió _nio a _libs y se usó una versión común que funciona
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3") // <--- CAMBIO AQUÍ
}