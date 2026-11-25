package com.danya.mafia.server

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Читает веб-клиент для iOS из assets и кэширует его в памяти. */
object WebAssets {
    private val cache = ConcurrentHashMap<String, String>()
    private val safeName = Regex("^[A-Za-z0-9._-]+$")

    fun read(context: Context, name: String): String? {
        if (!safeName.matches(name)) return null
        cache[name]?.let { return it }
        return try {
            val text = context.assets.open("web/$name")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            cache[name] = text
            text
        } catch (_: Exception) {
            null
        }
    }

    fun mimeOf(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }
}
