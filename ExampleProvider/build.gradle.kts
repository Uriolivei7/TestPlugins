// build.gradle.kts (Módulo ExampleProvider)

plugins {
    // Aplica el plugin de Android Library
    id("com.android.library")
    // Aplica el plugin de Kotlin para Android
    kotlin("android")
    // ¡IMPORTANTE! Aplicar el ID del plugin de CloudStream que se define en el build.gradle.kts de la raíz
    id("com.lagradost.cloudstream3.gradle")
}

// AÑADIDO: Bloque repositories directamente en el módulo para resolver dependencias específicas.
repositories {
    google() // Repositorio de Google (para dependencias de AndroidX, etc.)
    mavenCentral() // Repositorio Maven Central
    maven("https://jitpack.io") // JitPack para cloudstream3:pre-release y otras dependencias específicas
}

// Configuración específica de CloudStream para este módulo.
// Esta sección se usará para generar la información de tu plugin.
cloudstream {
    // Todas estas propiedades son opcionales, puedes eliminarlas con seguridad si no las necesitas.

    description = "Plugin para SoloLatino.net" // Descripción de tu plugin
    authors = listOf("Ranita") // TU NOMBRE AQUÍ

    /**
     * Status int como uno de siguiente:
     * 0: Caído
     * 1: Ok
     * 2: Lento
     * 3: Solo Beta
     **/
    status = 1 // Será 3 si no se especifica

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon") // Tipos de contenido que soporta tu plugin

    requiresResources = true // Si tu plugin usa recursos (layouts, strings, drawables)
    language = "es" // Idioma principal del contenido que extrae

    // URL de un ícono para tu plugin. Puedes usar una imagen pública o un ícono SVG incrustado.
    iconUrl = "https://placehold.co/128x128/FF0000/FFFFFF?text=SL" // Placeholder, puedes cambiarlo
}

android {
    // Namespace de tu módulo. Debe coincidir con el 'package' de tus archivos Kotlin.
    // EJEMPLO: si tu SoloLatinoProvider.kt empieza con 'package com.example', el namespace es 'com.example'
    namespace = "com.example" // ¡MUY IMPORTANTE! ASEGÚRATE DE QUE ESTO COINCIDA CON EL PAQUETE DE TUS ARCHIVOS KOTLIN

    // Versiones del SDK
    compileSdk = 34 // Ya definido en el build.gradle.kts de la raíz, pero puedes especificarlo aquí también
    defaultConfig {
        minSdk = 21
        // targetSdk se infiere del compileSdk para módulos de librería
    }

    // Opciones de compilación de Java
    compileOptions {
        // --- MODIFICADO AQUÍ: Actualizado a Java 17 para evitar la advertencia ---
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Opciones del compilador de Kotlin
    kotlinOptions {
        // --- MODIFICADO AQUÍ: Actualizado a JVM 17 para que coincida con Java ---
        jvmTarget = "17"
    }

    // Habilitar características de compilación necesarias
    buildFeatures {
        buildConfig = true // Asegura que BuildConfig.java/kt sea generado
        viewBinding = true // Si usas View Binding en tus layouts
    }
}

// Dependencias específicas para este módulo.
// Las dependencias comunes ya están en el build.gradle.kts de la raíz.
dependencies {
    // Dependencias necesarias para BlankFragment.kt
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.1") // Necesario para Fragment y BottomSheetDialogFragment

    // Librería CloudStream3 (ahora con versión específica)
    // Se usa 'cloudstream' para la dependencia principal de CloudStream.
    val cloudstream by configurations // Declara la configuración 'cloudstream'
    cloudstream("com.lagradost:cloudstream3:pre-release") // <--- ¡VERSIÓN ESPECÍFICA!

    // Jsoup para el parseo de HTML (ya presente en tu SoloLatinoProvider.kt)
    implementation("org.jsoup:jsoup:1.17.2") // Asegúrate de que esta versión sea compatible
}