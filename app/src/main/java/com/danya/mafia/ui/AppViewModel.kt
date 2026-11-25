package com.danya.mafia.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danya.mafia.game.GameEngine
import com.danya.mafia.game.HostSnapshot
import com.danya.mafia.game.RoomConfig
import com.danya.mafia.relay.RelayClient
import com.danya.mafia.server.GameServer
import com.danya.mafia.server.LocalNetwork
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Приложение — только пульт ведущего.
 * Играют все (включая самого ведущего) на сайте комнаты в браузере.
 */
sealed interface Screen {
    data object Home : Screen
    data object Create : Screen
    data object Host : Screen
}

private class HostSession(
    val engine: GameEngine,
    val server: GameServer,
    val scope: CoroutineScope,
    val config: RoomConfig
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()
    private val prefs = app.getSharedPreferences("mafia", Context.MODE_PRIVATE)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _hostState = MutableStateFlow<HostSnapshot?>(null)
    val hostState: StateFlow<HostSnapshot?> = _hostState.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hostIp = MutableStateFlow<String?>(null)
    val hostIp: StateFlow<String?> = _hostIp.asStateFlow()

    private val _roomUrl = MutableStateFlow<String?>(null)
    val roomUrl: StateFlow<String?> = _roomUrl.asStateFlow()

    private val _serverPort = MutableStateFlow(8080)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _relayUrl = MutableStateFlow(prefs.getString("relay_url", "") ?: "")
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    private val _relayCode = MutableStateFlow(prefs.getString("relay_code", "") ?: "")
    val relayCode: StateFlow<String> = _relayCode.asStateFlow()

    private val _relayEnabled = MutableStateFlow(prefs.getBoolean("relay_enabled", false))
    val relayEnabled: StateFlow<Boolean> = _relayEnabled.asStateFlow()

    private var host: HostSession? = null
    private var hostCollect: Job? = null
    private var relayJob: Job? = null

    fun goHome() {
        closeHost()
        _hostState.value = null
        _screen.value = Screen.Home
    }

    fun goCreate() {
        _error.value = null
        _screen.value = Screen.Create
    }

    fun back() {
        when (_screen.value) {
            Screen.Create -> _screen.value = Screen.Home
            else -> goHome()
        }
    }

    fun clearError() {
        _error.value = null
    }

    // ── Ведущий ──

    fun createRoom(config: RoomConfig) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            var engineScope: CoroutineScope? = null
            var server: GameServer? = null
            try {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                engineScope = scope
                val engine = GameEngine(config, scope)
                val srv = GameServer(app, engine, _serverPort.value)
                server = srv
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                val ip = LocalNetwork.ipv4()
                host = HostSession(engine, srv, scope, config)
                _hostState.value = engine.hostSnapshot.value
                hostCollect?.cancel()
                hostCollect = viewModelScope.launch {
                    engine.hostSnapshot.collect { _hostState.value = it }
                }
                _hostIp.value = ip
                _roomUrl.value = ip?.let { "http://$it:${_serverPort.value}" }
                _screen.value = Screen.Host
                _error.value = null
                if (_relayEnabled.value) startRelay()
            } catch (e: Exception) {
                server?.stop()
                engineScope?.cancel()
                _error.value = "Не удалось создать комнату: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun startHostGame() {
        val result = host?.engine?.startGame()
        if (result != null && !result.ok) _error.value = result.error
    }

    fun hostAdvance() {
        host?.engine?.advance()
    }

    fun hostRestart() {
        val result = host?.engine?.restartToLobby()
        if (result != null && !result.ok) _error.value = result.error
    }

    fun setAutoHost(enabled: Boolean) {
        host?.engine?.setAutoHost(enabled)
    }

    fun refreshHostUrl() {
        val ip = LocalNetwork.ipv4()
        _hostIp.value = ip
        _roomUrl.value = ip?.let { "http://$it:${_serverPort.value}" }
    }

    fun closeHost() {
        stopRelay()
        hostCollect?.cancel()
        hostCollect = null
        host?.server?.stop()
        host?.scope?.cancel()
        host = null
        _roomUrl.value = null
        _hostIp.value = null
    }

    // ── Реле Vercel ──

    fun setRelay(url: String, code: String, enabled: Boolean) {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanCode = code.trim().uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .take(8)
        _relayUrl.value = cleanUrl
        _relayCode.value = cleanCode
        _relayEnabled.value = enabled
        prefs.edit()
            .putString("relay_url", cleanUrl)
            .putString("relay_code", cleanCode)
            .putBoolean("relay_enabled", enabled)
            .apply()
        if (enabled) startRelay() else stopRelay()
    }

    private fun startRelay() {
        stopRelay()
        val url = _relayUrl.value
        val code = _relayCode.value
        if (url.isBlank() || code.isBlank()) {
            _relayEnabled.value = false
            _error.value = "Для игры на расстоянии нужны адрес реле и код комнаты"
            return
        }
        if (host == null) return
        val client = RelayClient(url, code)
        relayJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val requests = client.poll()
                    if (requests.isNotEmpty()) {
                        client.reply(requests.map { forwardToLocal(it) })
                    }
                } catch (_: Exception) {
                    // Следующая попытка через мгновение.
                }
                delay(700)
            }
        }
    }

    private fun stopRelay() {
        relayJob?.cancel()
        relayJob = null
    }

    /** Выполняет запрос игрока на локальном сервере комнаты. */
    private fun forwardToLocal(request: JSONObject): JSONObject {
        val id = request.optString("id")
        val method = request.optString("method", "GET")
        val path = request.optString("path", "/")
        val body = request.optString("body", "")
        return try {
            val connection =
                (URL("http://127.0.0.1:${_serverPort.value}$path").openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = method
                        connectTimeout = 5_000
                        readTimeout = 10_000
                        if (body.isNotBlank()) {
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        }
                    }
            if (body.isNotBlank()) {
                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            connection.disconnect()
            JSONObject()
                .put("id", id)
                .put("status", status)
                .put("body", text)
        } catch (e: Exception) {
            JSONObject()
                .put("id", id)
                .put("status", 502)
                .put(
                    "body",
                    JSONObject()
                        .put("ok", false)
                        .put("error", e.message ?: "Локальный сервер недоступен")
                        .toString()
                )
        }
    }

    override fun onCleared() {
        closeHost()
        super.onCleared()
    }
}
