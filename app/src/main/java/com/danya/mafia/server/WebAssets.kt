package com.danya.mafia.server

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Читает веб-клиент из assets и кэширует его в памяти. */
object WebAssets {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val safeName = Regex("^[A-Za-z0-9._-]+$")

    fun read(context: Context, name: String): String? =
        readBytes(context, name)?.toString(Charsets.UTF_8)

    fun readBytes(context: Context, name: String): ByteArray? {
        if (!safeName.matches(name)) return null
        cache[name]?.let { return it }
        return try {
            val bytes = context.assets.open("web/$name").use { it.readBytes() }
            cache[name] = bytes
            bytes
        } catch (_: Exception) {
            null
        }
    }

    fun isBinary(name: String): Boolean = when {
        name.endsWith(".png") -> true
        name.endsWith(".jpg") -> true
        name.endsWith(".jpeg") -> true
        name.endsWith(".ico") -> true
        else -> false
    }

    fun mimeOf(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }
}
