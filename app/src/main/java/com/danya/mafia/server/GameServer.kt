package com.danya.mafia.server

import android.content.Context
import com.danya.mafia.game.GameEngine
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP-сервер комнаты.
 * Android-игроки ходят в те же адреса /api/..., что и веб-клиент для iOS.
 */
class GameServer(
    private val context: Context,
    private val engine: GameEngine,
    private val media: MediaLibrary,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val appContext = context.applicationContext

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, ""))
        }
        return try {
            when {
                session.uri == "/" || session.uri == "/index.html" ->
                    serveAsset("index.html")
                session.uri.startsWith("/assets/") ->
                    serveAsset(session.uri.removePrefix("/assets/"))
                session.uri.startsWith("/media/") ->
                    serveMedia(session.uri.removePrefix("/media/"))
                session.uri == "/api/room" ->
                    cors(json(JSONObject().apply {
                        put("ok", true)
                        put("room", engine.roomInfo().toJson())
                    }))
                session.uri == "/api/join" ->
                    handleJoin(session)
                session.uri == "/api/leave" ->
                    handleLeave(session)
                session.uri == "/api/state" ->
                    handleState(session)
                session.uri == "/api/action" ->
                    handleAction(session)
                session.uri == "/api/chat" ->
                    handleChat(session)
                session.uri == "/api/activity" ->
                    handleActivity(session)
                else -> cors(newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
                ))
            }
        } catch (e: Exception) {
            cors(json(JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "Ошибка сервера")
            }, Response.Status.INTERNAL_ERROR))
        }
    }

    private fun serveAsset(name: String): Response {
        val bytes = WebAssets.readBytes(appContext, name)
            ?: return cors(newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found"
            ))
        val mime = WebAssets.mimeOf(name)
        val binary = WebAssets.isBinary(name)
        val response = if (binary) {
            newFixedLengthResponse(
                Response.Status.OK, mime,
                java.io.ByteArrayInputStream(bytes), bytes.size.toLong()
            )
        } else {
            newFixedLengthResponse(Response.Status.OK, mime, String(bytes, Charsets.UTF_8))
        }
        val cache = if (binary) "public, max-age=86400" else "no-cache"
        return cors(response.also { it.addHeader("Cache-Control", cache) })
    }

    private fun serveMedia(id: String): Response {
        val track = media.get(id)
            ?: return cors(newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Sound not found"
            ))
        val response = newFixedLengthResponse(
            Response.Status.OK,
            track.info.mime,
            java.io.ByteArrayInputStream(track.bytes),
            track.bytes.size.toLong()
        )
        response.addHeader("Cache-Control", "no-cache")
        return cors(response)
    }

    private fun handleJoin(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("Нужен POST")
        val body = readJson(session) ?: return badRequest("Пустой запрос")
        val name = body.optString("name", "")
        val device = body.optString("device", "web")
        val password = if (body.has("password") && !body.isNull("password")) {
            body.optString("password", "")
        } else null

        val result = engine.join(name, device, password)
        val response = JSONObject().apply {
            put("ok", result.ok)
            if (result.ok) {
                put("playerId", result.playerId)
                put("token", result.token)
                put("room", engine.roomInfo().toJson())
            } else {
                put("error", result.error ?: "Не удалось войти")
            }
        }
        return cors(json(response))
    }

    private fun handleLeave(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("Нужен POST")
        val body = readJson(session) ?: return badRequest("Пустой запрос")
        val result = engine.leave(
            body.optString("playerId", ""),
            body.optString("token", "")
        )
        return cors(json(JSONObject().apply {
            put("ok", result.ok)
            if (!result.ok) put("error", result.error ?: "Не удалось")
        }))
    }

    private fun handleState(session: IHTTPSession): Response {
        val pid = session.parameters["pid"]?.firstOrNull()
            ?: return notFound("Нет playerId")
        val token = session.parameters["token"]?.firstOrNull()
            ?: return notFound("Нет token")
        val state = engine.clientState(pid, token)
            ?: return notFound("Сессия не найдена")
        return cors(json(state.toJson()))
    }

    private fun handleAction(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("Нужен POST")
        val body = readJson(session) ?: return badRequest("Пустой запрос")
        val pid = body.optString("playerId", "")
        val token = body.optString("token", "")
        val type = body.optString("type", "")
        val targetId = if (body.has("targetId") && !body.isNull("targetId")) {
            body.optString("targetId", "")
        } else null
        val targetIds = mutableListOf<String>()
        val arr = body.optJSONArray("targetIds")
        if (arr is JSONArray) {
            for (i in 0 until arr.length()) targetIds += arr.optString(i, "")
        }

        val result = engine.handleAction(pid, token, type, targetId, targetIds)
        return cors(json(JSONObject().apply {
            put("ok", result.ok)
            if (!result.ok) put("error", result.error ?: "Не удалось")
        }))
    }

    private fun handleChat(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("Нужен POST")
        val body = readJson(session) ?: return badRequest("Пустой запрос")
        val result = engine.sendChat(
            body.optString("playerId", ""),
            body.optString("token", ""),
            body.optString("text", "")
        )
        return cors(json(JSONObject().apply {
            put("ok", result.ok)
            if (!result.ok) put("error", result.error ?: "Не удалось")
        }))
    }

    private fun handleActivity(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("Нужен POST")
        val body = readJson(session) ?: return badRequest("Пустой запрос")
        val result = engine.recordActivity(
            body.optString("playerId", ""),
            body.optString("token", ""),
            body.optString("screen", ""),
            body.optString("detail", ""),
            body.optInt("scroll", 0)
        )
        return cors(json(JSONObject().apply {
            put("ok", result.ok)
            if (!result.ok) put("error", result.error ?: "Не удалось")
        }))
    }

    /**
     * Читаем тело запроса сами, в UTF-8.
     * Встроенный parseBody() у NanoHTTPD декодирует тело как ASCII,
     * если в Content-Type не указан charset, — из-за этого русские имена
     * превращались в вопросительные знаки.
     */
    private fun readJson(session: IHTTPSession): JSONObject? {
        return try {
            val length = session.headers["content-length"]?.toIntOrNull() ?: 0
            val raw = if (length > 0) {
                val bytes = ByteArray(length)
                var off = 0
                while (off < length) {
                    val n = session.inputStream.read(bytes, off, length - off)
                    if (n <= 0) break
                    off += n
                }
                String(bytes, 0, off, Charsets.UTF_8)
            } else {
                session.inputStream.readBytes().toString(Charsets.UTF_8)
            }
            if (raw.isBlank()) null else JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun badRequest(message: String) = cors(json(
        JSONObject().apply { put("ok", false); put("error", message) },
        Response.Status.BAD_REQUEST
    ))

    private fun notFound(message: String) = cors(json(
        JSONObject().apply { put("ok", false); put("error", message) },
        Response.Status.NOT_FOUND
    ))

    private fun json(obj: JSONObject, status: Response.IStatus = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }
}
