import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/Uriolivei/TestPlugins")

        authors = listOf("Ranita")
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
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

    // AÑADE ESTE BLOQUE PARA FORZAR LA VERSIÓN DE JACKSON
    configurations.all {
        resolutionStrategy {
            // Forzar una versión específica para todas las librerías de Jackson.
            // Los logs indican que hay una restricción a 2.16.0, así que la forzamos.
            force("com.fasterxml.jackson.core:jackson-databind:2.16.0")
            force("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
            force("com.fasterxml.jackson.core:jackson-annotations:2.16.0") // También es buena idea forzar annotations
            force("com.fasterxml.jackson.core:jackson-core:2.16.0") // Y core
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        val compileOnly by configurations

        // Stubs for all Cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // estas dependencias son para COMPILAR el plugin, pero NO se empaquetan en el .cs3
        // Se asume que Cloudstream ya las provee en tiempo de ejecución.
        // Ahora usamos 2.16.0 para ser consistentes con la fuerza anterior
        compileOnly("com.fasterxml.jackson.core:jackson-databind:2.16.0")
        compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")

        // estas dependencias sí se empaquetarán si son necesarias para tu plugin y Cloudstream no las provee
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        implementation("org.mozilla:rhino:1.8.0")
        implementation("com.google.code.gson:gson:2.11.0")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}