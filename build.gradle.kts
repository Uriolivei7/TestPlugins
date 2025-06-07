// build.gradle.kts (Raíz del proyecto CloudstreamPlugins)

// Bloque buildscript: Define los repositorios y dependencias para los propios plugins de Gradle.
// Aquí es donde se le dice a Gradle dónde encontrar el plugin de CloudStream.
buildscript {
    repositories {
        google() // Repositorio de Google (para plugins de Android)
        mavenCentral() // Repositorio Maven Central
        // Repositorio de JitPack, donde se aloja el plugin de Gradle de CloudStream
        maven("https://jitpack.io")
    }

    dependencies {
        // Plugin de Gradle para Android. Usar una versión compatible con tu Android Studio.
        classpath("com.android.tools.build:gradle:8.7.3")
        // El plugin de Gradle de CloudStream que facilita la construcción de plugins.
        // Asegúrate de que esta versión sea la correcta para el plugin de CloudStream.
        classpath("com.github.recloudstream:gradle:-SNAPSHOT") // O la versión específica que se espera
        // Plugin de Kotlin para Gradle
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0") // Versión de Kotlin para Gradle
    }
}

// NO NECESITAS EL BLOQUE 'allprojects' NI 'subprojects' aquí
// ya que 'dependencyResolutionManagement' en settings.gradle.kts maneja los repositorios globales
// y cada módulo (como ExampleProvider) definirá sus propios plugins y dependencias.

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}