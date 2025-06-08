// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // ¡ESTA ES LA LÍNEA QUE DEBES AÑADIR!
        maven("https://jitpack.io") // JitPack es donde se aloja el plugin de CloudStream
        // Asegúrate de que este sea el repositorio correcto para el plugin de CloudStream
        // Si el plugin estuviera en otro lugar, la URL sería diferente.
        // A veces también es: maven { url "https://plugins.gradle.org/m2/" }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // También asegúrate de que el repositorio de la librería de CloudStream esté aquí (jitpack)
        maven("https://jitpack.io")
    }
}
rootProject.name = "TestPlugins" // O "TestPlugins" si así se llama tu carpeta raíz

// Resto del código de auto-inclusión:
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}