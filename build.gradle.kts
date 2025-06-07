// build.gradle.kts (Raíz del proyecto TestPlugins)

import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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
        classpath("com.github.recloudstream:gradle:-SNAPSHOT") // Importante: -SNAPSHOT es crucial para versiones pre-release
        // Plugin de Kotlin para Gradle
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0") // Versión de Kotlin para Gradle
    }
}

// Bloque allprojects: Define los repositorios para las dependencias de todos los submódulos.
allprojects {
    repositories {
        google() // Repositorio de Google (para dependencias de AndroidX, etc.)
        mavenCentral() // Repositorio Maven Central
        maven("https://jitpack.io") // JitPack para otras dependencias o forks
    }
}

// Funciones de extensión para CloudStream y Android (para un DSL más limpio)
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()
fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

// Bloque subprojects: Configuración que se aplica automáticamente a todos los módulos hijos.
subprojects {
    // Aplicar plugins comunes a todos los módulos de CloudStream
    apply(plugin = "com.android.library") // Indica que es un módulo de librería Android
    apply(plugin = "kotlin-android") // Habilita Kotlin para Android
    apply(plugin = "com.lagradost.cloudstream3.gradle") // ¡IMPORTANTE! Este es el ID CORRECTO del plugin de CloudStream

    // Configuración específica del plugin de CloudStream
    cloudstream {
        // Cuando se ejecuta a través de GitHub Actions, GITHUB_REPOSITORY contiene el nombre del repositorio actual.
        // Si no está disponible, se usa una URL por defecto.
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/SaurabhKaperwan/CSX")
        authors = listOf("megix") // Autor(es) de la extensión. Puedes añadirte aquí si eres el autor.
    }

    // Configuración de Android para el módulo
    android {
        // Namespace de tu módulo. Debe coincidir con el 'package' de tus archivos Kotlin.
        namespace = "com.example" // <--- ¡IMPORTANTE! Asegúrate de que esto coincida con tu paquete real (com.example o com.stormunblessed)

        defaultConfig {
            minSdk = 21 // Versión mínima de SDK compatible
            compileSdkVersion(35) // Versión de compilación de SDK (usa el número directamente)
            targetSdk = 35 // Versión de SDK objetivo
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    // Dependencias comunes para todos los módulos
    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs para todas las clases de Cloudstream (la librería principal)
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // Estas dependencias pueden incluir cualquiera de las añadidas por la aplicación,
        // pero no necesitas incluir ninguna de ellas si no las necesitas.
        implementation(kotlin("stdlib")) // Añade características estándar de Kotlin
        implementation("com.github.Blatzar:NiceHttp:0.4.13") // Librería HTTP
        implementation("org.jsoup:jsoup:1.18.3") // Parser HTML
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0") // Soporte JSON para Kotlin
        implementation("com.squareup.okhttp3:okhttp:4.12.0") // Cliente HTTP
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1") // Corrutinas Kotlin
        implementation("org.mozilla:rhino:1.8.0") // Para ejecutar JS (si es necesario)
        implementation("com.google.code.gson:gson:2.11.0") // Librería GSON para JSON
    }
}

// Tarea clean para limpiar el directorio de compilación del proyecto
task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
