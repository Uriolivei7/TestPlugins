// build.gradle.kts para tu módulo ExampleProvider

plugins {
    // Aplica el plugin de Android Library, ya que un plugin de CloudStream es esencialmente una librería Android
    id("com.android.library")
    // Aplica el plugin de Kotlin para Android
    kotlin("android")
    // Aplica el plugin específico de Gradle para CloudStream con el ID correcto
    id("com.lagradost.cloudstream3.gradle")
}

android {
    // ESTO ES CRUCIAL: Define el nombre del paquete para tu librería y para BuildConfig.
    // DEBE COINCIDIR con el nombre del paquete de tus archivos Kotlin (por ejemplo, "com.example" en BlankFragment.kt)
    namespace = "com.example" // <--- ¡IMPORTANTE! Asegúrate de que esto coincida con tu paquete

    // Establece las versiones del SDK
    compileSdk = 34 // Usa un nivel de API estable y reciente
    defaultConfig {
        minSdk = 21 // Versión mínima de Android compatible con tu plugin
        // targetSdk generalmente se infiere de compileSdk para módulos de librería
    }

    // Configuración de los tipos de compilación (por ejemplo, para builds de lanzamiento)
    buildTypes {
        release {
            isMinifyEnabled = false // Puedes cambiar a 'true' para compilar con ProGuard en versiones de lanzamiento
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Opciones de compatibilidad de Java
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Opciones del compilador de Kotlin
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Habilitar características de compilación
    buildFeatures {
        buildConfig = true // Esto asegura que BuildConfig.java/kt sea generado
        viewBinding = true // Habilita View Binding si lo utilizas en tus layouts
    }
}

dependencies {
    // Librerías de AndroidX esenciales
    implementation("androidx.core:core-ktx:1.13.1") // Proporciona extensiones de Kotlin
    implementation("androidx.appcompat:appcompat:1.6.1") // Proporciona soporte para AppCompat
    implementation("com.google.android.material:material:1.12.0") // Componentes de Material Design
    implementation("androidx.recyclerview:recyclerview:1.3.2") // RecyclerView para listas

    // Librería CloudStream3 (la versión 'pre-release' que estás utilizando)
    // Se usa 'cloudstream' en lugar de 'implementation' para la dependencia principal de CloudStream.
    val cloudstream by configurations // Declara la configuración 'cloudstream'
    cloudstream("com.lagradost:cloudstream3:pre-release")

    // Jsoup para el parseo de HTML (ya presente en tu SoloLatinoProvider.kt)
    implementation("org.jsoup:jsoup:1.17.2") // Asegúrate de que esta versión sea compatible
}

// Bloque de configuración específico del plugin CloudStream (tu configuración existente)
cloudstream {
    description = "Plugin para SoloLatino.net - Películas y Series en Español" // Descripción corregida
    authors = listOf("Ruth Riveiro") // ¡Tu nombre aquí!

    status = 1

    tvTypes = listOf( // Tipos de contenido que tu plugin soporta
        "Movie",
        "TvSeries",
        "Anime", // Si SoloLatino.net también tiene anime
        "Cartoon" // Si SoloLatino.net también tiene cartoons
    )

    requiresResources = false // Generalmente false para plugins de scraping simples
    language = "es" // ¡Importante! Idioma español

    iconUrl = "https://placehold.co/128x128/FF0000/FFFFFF?text=SL" // Un icono placeholder simple para SoloLatino
}
