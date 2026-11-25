package com.danya.mafia.relay

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Мост к реле на Vercel.
 *
 * Телефон ведущего сам ходит в интернет: забирает очередь запросов от игроков,
 * выполняет их у себя и отправляет ответы обратно. Поэтому белому IP не нужно
 * быть — игроки видят только адрес Vercel.
 */
class RelayClient(
    baseUrl: String,
    private val code: String
) {
    private val base = baseUrl.trim().trimEnd('/')

    fun poll(): List<JSONObject> {
        return try {
            val response = post(
                "/api/relay/poll",
                JSONObject().put("code", code)
            ) ?: return emptyList()
            val array = response.optJSONArray("requests") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun reply(responses: List<JSONObject>) {
        if (responses.isEmpty()) return
        try {
            post(
                "/api/relay/reply",
                JSONObject().apply {
                    put("code", code)
                    put("responses", JSONArray().apply { responses.forEach { put(it) } })
                }
            )
        } catch (_: Exception) {
            // Ответы потеряются, игрок повторит запрос.
        }
    }

    private fun post(path: String, payload: JSONObject): JSONObject? {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (text.isBlank()) return null
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
