package com.danya.mafia.net

import com.danya.mafia.game.ActionResult
import com.danya.mafia.game.ActionPrompt
import com.danya.mafia.game.ClientState
import com.danya.mafia.game.JoinResponse
import com.danya.mafia.game.RoomInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String) : Exception(message)

/** Клиент к комнате ведущего. Работает и по Wi-Fi, и по точке доступа. */
class ApiClient(baseUrl: String) {

    val baseUrl: String = normalize(baseUrl)

    suspend fun roomInfo(): RoomInfo = withContext(Dispatchers.IO) {
        val o = request("/api/room", "GET")
        val room = o.optJSONObject("room") ?: throw ApiException("Комната не найдена")
        RoomInfo.fromJson(room)
    }

    suspend fun join(name: String, password: String?, device: String = "android"): JoinResponse =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("name", name)
                put("device", device)
                if (!password.isNullOrBlank()) put("password", password)
            }
            val o = request("/api/join", "POST", body)
            if (!o.optBoolean("ok")) {
                throw ApiException(o.optString("error", "Не удалось войти"))
            }
            JoinResponse(
                ok = true,
                playerId = o.optString("playerId"),
                token = o.optString("token"),
                room = o.optJSONObject("room")?.let(RoomInfo::fromJson)
            )
        }

    suspend fun state(playerId: String, token: String): ClientState =
        withContext(Dispatchers.IO) {
            val p = URLEncoder.encode(playerId, "UTF-8")
            val t = URLEncoder.encode(token, "UTF-8")
            ClientState.fromJson(request("/api/state?pid=$p&token=$t", "GET"))
        }

    suspend fun action(
        playerId: String,
        token: String,
        type: String,
        targetId: String? = null,
        targetIds: List<String> = emptyList()
    ): ActionResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("playerId", playerId)
            put("token", token)
            put("type", type)
            if (targetId != null) put("targetId", targetId)
            if (targetIds.isNotEmpty()) {
                put("targetIds", JSONArray().apply { targetIds.forEach { put(it) } })
            }
        }
        val o = request("/api/action", "POST", body)
        ActionResult(
            ok = o.optBoolean("ok"),
            error = if (o.optBoolean("ok")) null else o.optString("error", "Не удалось")
        )
    }

    suspend fun leave(playerId: String, token: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                request("/api/leave", "POST", JSONObject().apply {
                    put("playerId", playerId)
                    put("token", token)
                })
            }
        }
    }

    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299) {
                throw ApiException(json?.optString("error").orEmpty().ifBlank { "HTTP $code" })
            }
            return json ?: throw ApiException("Пустой ответ сервера")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun normalize(input: String): String {
            var raw = input.trim()
            if (raw.isBlank()) throw ApiException("Введите IP ведущего")
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                raw = "http://$raw"
            }
            val uri = try {
                URI(raw)
            } catch (_: Exception) {
                throw ApiException("Неверный адрес")
            }
            val host = uri.host ?: throw ApiException("Неверный адрес")
            val port = if (uri.port > 0) uri.port else 8080
            return "http://$host:$port"
        }
    }
}
