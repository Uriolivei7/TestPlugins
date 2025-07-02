import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        // *** CAMBIO CLAVE AQUÍ: VOLVER A LA VERSIÓN DE KOTLIN 2.1.0 ***
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

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all Cloudstream classes
        // MUY IMPORTANTE: Asegúrate de que esta dependencia del SDK de CloudStream
        // sea exactamente la que coincide con la versión de CloudStream que tienes instalada
        // y que está compilada con Kotlin 2.1.0.
        // La que tienes ahora (`com.lagradost:cloudstream3:pre-release`) parece ser K2.
        cloudstream("com.lagradost:cloudstream3:pre-release")


        // Dependencias de Kotlin: Vuelve a la configuración de Kotlin 2.0 (K2)
        implementation(kotlin("stdlib")) // Esto usará la versión 2.1.0 del kotlin-stdlib
        // O si quieres ser explícito:
        // implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0") // O la versión exacta de tu kotlin-gradle-plugin

        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.18.3")
        // Jackson: Mantén 2.16.0, es la versión esperada con K2 y CS4.x
        // *** CAMBIO CLAVE AQUÍ: BAJAR LA VERSIÓN DE JACKSON ***
        // Intenta con 2.15.2 primero
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
        implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
        implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")

        // Si 2.15.2 NO funciona, vuelve a este archivo y prueba con 2.14.0
        // implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.0")
        // implementation("com.fasterxml.jackson.core:jackson-databind:2.14.0")
        // implementation("com.fasterxml.jackson.core:jackson-core:2.14.0")
        // implementation("com.fasterxml.jackson.core:jackson-annotations:2.14.0")

        // Y si 2.14.0 tampoco, podrías intentar con 2.13.5 (la última de 2.13.x)
        // implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.5")
        // implementation("com.fasterxml.jackson.core:jackson-databind:2.13.5")
        // implementation("com.fasterxml.jackson.core:jackson-core:2.13.5")
        // implementation("com.fasterxml.jackson.core:jackson-annotations:2.13.5")

        implementation("com.squareup.okhttp3:okhttp:4.12.0")

        // Coroutines: Vuelve a una versión compatible con Kotlin 2.0 (K2)
        // La 1.10.1 es la correcta para Kotlin 2.0
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

        implementation("org.mozilla:rhino:1.8.0")
        implementation("com.google.code.gson:gson:2.11.0")
        implementation("androidx.annotation:annotation:1.8.0")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}