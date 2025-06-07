// ExampleProvider/build.gradle.kts (MÓDULO de tu plugin)

plugins {
    id("com.android.library")
    kotlin("android")
    // ¡IMPORTANTE! Aplicar el ID del plugin de CloudStream
    id("com.lagradost.cloudstream3.gradle")
}

// YA NO NECESITAS UN BLOQUE 'repositories' AQUÍ si lo tienes en settings.gradle.kts
// repositories {
//     google()
//     mavenCentral()
//     maven("https://jitpack.io")
// }

android {
    // Namespace de tu módulo. Debe coincidir con el 'package' de tus archivos Kotlin.
    // EJEMPLO: si tu SoloLatinoProvider.kt empieza con 'package com.example', el namespace es 'com.example'
    namespace = "com.example" // ¡MUY IMPORTANTE! AJUSTA ESTO A TU PAQUETE REAL (e.g., com.lagradost.provider.sololatino)

    compileSdk = 35 // Versión de compilación de SDK

    defaultConfig {
        minSdk = 21
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions"
        )
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    // Dependencias comunes de Android y Kotlin
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.1")

    // ¡¡¡LA LIBRERÍA DE CLOUDSTREAM!!! ESTA ES LA CLAVE PARA EL ERROR ANTERIOR
    implementation("com.github.LagradostZ:CloudStream3:pre-release")// ¡Esta es la librería que tu código usa!

    // Otras dependencias que tu plugin necesita
    implementation("com.github.Blatzar:NiceHttp:0.4.13") // librería http
    implementation("org.jsoup:jsoup:1.18.3") // html parser
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.mozilla:rhino:1.8.0") //run JS
    implementation("com.google.code.gson:gson:2.11.0")

    // La configuración 'cloudstream' ahora va dentro del módulo, no en las dependencias como antes.
    // YA NO VA AQUÍ LA CONFIGURACIÓN cloudstream { ... }
}

// Configuración específica de CloudStream para este módulo.
// Esta sección se usará para generar la información de tu plugin.
cloudstream {
    description = "Plugin para SoloLatino.net"
    authors = listOf("Ranita")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon")
    requiresResources = true
    language = "es"
    iconUrl = "https://placehold.co/128x128/FF0000/FFFFFF?text=SL"
    // setRepo NO VA AQUÍ, eso es del plugin global, no del módulo individual
}