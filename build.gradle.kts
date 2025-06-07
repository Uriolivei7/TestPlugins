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

    // Configuración específica del plugin de CloudStream para todos los subproyectos
    cloudstream {
        // cuando se ejecuta a través de github workflow, GITHUB_REPOSITORY debería contener el nombre del repositorio actual
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/SaurabhKaperwan/CSX")
        // Eliminada la propiedad 'authors' de aquí, ya que se define en el módulo ExampleProvider.
        // description y language tampoco son necesarios aquí si se definen a nivel de módulo.
    }

    // Configuración de Android para el módulo
    android {
        // Namespace de tu módulo. Debe coincidir con el 'package' de tus archivos Kotlin.
        namespace = "com.example" // ¡MUY IMPORTANTE! ASEGÚRATE DE QUE ESTO COINCIDA CON EL PAQUETE REAL (com.example o com.stormunblessed)

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
                jvmTarget.set(JvmTarget.JVM_17)
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
        // La configuración 'cloudstream' ahora SOLO se declara en el build.gradle.kts del módulo individual.
        // NO SE DECLARA AQUÍ para evitar duplicados.
        val implementation by configurations // Necesario para otras dependencias

        // Estas dependencias pueden incluir cualquiera de las añadidas por la aplicación,
        // pero no necesitas incluir ninguna de ellas si no las necesitas.
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts

        implementation(kotlin("stdlib")) // añade características estándar de kotlin
        implementation("com.github.Blatzar:NiceHttp:0.4.13") // librería http
        implementation("org.jsoup:jsoup:1.18.3") // html parser
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        implementation("org.mozilla:rhino:1.8.0") //run JS
        implementation("com.google.code.gson:gson:2.11.0")

    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
