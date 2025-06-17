package com.lagradost

import com.lagradost.nicehttp.Requests
import okhttp3.*
// ELIMINA ESTA IMPORTACIÓN: import okhttp3.internal.parseCookie // 'parseCookie' es interna

/**
 * Un gestor de sesión HTTP.
 *
 * Esta clase simplemente mantiene las cookies a través de las solicitudes. No hay seguridad sobre qué sitio debe usar qué cookies.
 *
 */

class CustomSession(
    client: OkHttpClient
) : Requests() {
    // Las cookies serán manejadas por CustomCookieJar. No necesitamos un mapa aquí en CustomSession.
    // var cookies = mutableMapOf<String, Cookie>() // ¡Eliminar esta línea!

    // Instancia de CustomCookieJar que manejará el almacenamiento de cookies
    private val customCookieJar = CustomCookieJar() // Hacerla private si solo se usa internamente

    init {
        this.baseClient = client
            .newBuilder()
            .cookieJar(customCookieJar) // Configura tu CustomCookieJar aquí para OkHttp
            // ELIMINA TODO EL BLOQUE addInterceptor QUE INTENTABA PARSEAR COOKIES MANUALMENTE
            // OkHttp y CookieJar ya manejan esto automáticamente.
            /*
            .addInterceptor {
                val time = System.currentTimeMillis()
                val request = it.request()
                request.headers.forEach { header ->
                    if (header.first.equals("cookie", ignoreCase = true)) {
                        val cookie = parseCookie(time, request.url, header.second) ?: return@forEach
                        cookies += cookie.name to cookie
                    }
                }
                it.proceed(request)
            }
            */
            .build()
    }

    // Tu CustomCookieJar es donde OkHttp guardará y cargará las cookies
    inner class CustomCookieJar : CookieJar {
        // Almacenamiento real de las cookies
        // Usar un ConcurrentHashMap puede ser más seguro si se accede desde múltiples hilos,
        // pero para un uso simple, un MutableMap es suficiente.
        private val storedCookies = mutableMapOf<String, Cookie>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            // Aquí, devuelve solo las cookies relevantes para la URL.
            // Para la mayoría de los casos de uso básico de "mantener todas las cookies",
            // filtrar por 'it.matches(url)' es una buena práctica.
            return storedCookies.values.filter { it.matches(url) }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            // Guarda las cookies recibidas en la respuesta.
            // Sobreescribirá las cookies existentes con el mismo nombre.
            this.storedCookies += cookies.map { it.name to it }
        }

        // Opcional: Métodos para acceder o manipular las cookies desde CustomSession
        fun getAllCookies(): List<Cookie> {
            return storedCookies.values.toList()
        }

        fun clearCookies() {
            storedCookies.clear()
        }
    }
}