// settings.gradle.kts (archivo de la raíz de tu proyecto TestPlugins)

// ESTE BLOQUE ES ABSOLUTAMENTE INDISPENSABLE para que Gradle sepa dónde buscar plugins.
pluginManagement {
    repositories {
        // Repositorio oficial de plugins de Gradle.
        gradlePluginPortal()
        // Repositorio de Maven Central, donde se encuentran muchos plugins y dependencias.
        mavenCentral()
        // SI EL PLUGIN DE CLOUDSTREAM ESTÁ EN JITPACK (ES MUY PROBABLE PARA VER. PRE-RELEASE)
        // DEBE ESTAR AQUÍ PARA QUE GRADLE LO ENCUENTRE.
        maven { url = uri("https://jitpack.io") }
    }
}

// *** ¡¡¡AÑADE ESTE NUEVO BLOQUE ABAJO DE 'pluginManagement' Y ARRIBA DE 'rootProject.name'!!! ***
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // <-- ¡IMPORTANTE! Para las dependencias de los módulos
    }
}
// ************************************************************************************************

rootProject.name = "CloudstreamPlugins" // Asegúrate de que este nombre sea correcto para tu proyecto

// Este archivo establece qué proyectos están incluidos.
// Todos los proyectos nuevos deberían incluirse automáticamente a menos que se especifiquen en la variable "disabled".

val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

// Para incluir un solo proyecto, comenta las líneas anteriores (excepto la primera), y incluye tu plugin así:
// include("PluginName")