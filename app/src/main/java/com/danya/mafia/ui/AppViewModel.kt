package com.danya.mafia.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danya.mafia.game.ClientState
import com.danya.mafia.game.GameEngine
import com.danya.mafia.game.HostSnapshot
import com.danya.mafia.game.RoomConfig
import com.danya.mafia.game.RoomInfo
import com.danya.mafia.net.ApiClient
import com.danya.mafia.server.GameServer
import com.danya.mafia.server.LocalNetwork
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Create : Screen
    data object Join : Screen
    data object Host : Screen
    data object Player : Screen
}

private class HostSession(
    val engine: GameEngine,
    val server: GameServer,
    val scope: CoroutineScope,
    val config: RoomConfig
)

private class ClientSession(
    val api: ApiClient,
    val playerId: String,
    val token: String,
    val name: String
) {
    var polling: Job? = null
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()
    private val prefs = app.getSharedPreferences("mafia", Context.MODE_PRIVATE)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _hostState = MutableStateFlow<HostSnapshot?>(null)
    val hostState: StateFlow<HostSnapshot?> = _hostState.asStateFlow()

    private val _clientState = MutableStateFlow<ClientState?>(null)
    val clientState: StateFlow<ClientState?> = _clientState.asStateFlow()

    private val _roomInfo = MutableStateFlow<RoomInfo?>(null)
    val roomInfo: StateFlow<RoomInfo?> = _roomInfo.asStateFlow()

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

    private val _lastAddress = MutableStateFlow(
        prefs.getString("last_address", "192.168.43.1:8080") ?: "192.168.43.1:8080"
    )
    val lastAddress: StateFlow<String> = _lastAddress.asStateFlow()

    private val _connectionLost = MutableStateFlow(false)
    val connectionLost: StateFlow<Boolean> = _connectionLost.asStateFlow()

    private var host: HostSession? = null
    private var hostCollect: Job? = null
    private var client: ClientSession? = null

    fun goHome() {
        val leaving = client
        closeClient()
        if (leaving != null) {
            viewModelScope.launch(Dispatchers.IO) {
                leaving.api.leave(leaving.playerId, leaving.token)
            }
        }
        closeHost()
        _clientState.value = null
        _hostState.value = null
        _roomInfo.value = null
        _screen.value = Screen.Home
    }

    fun goCreate() {
        _error.value = null
        _roomInfo.value = null
        _screen.value = Screen.Create
    }

    fun goJoin() {
        _error.value = null
        _roomInfo.value = null
        _screen.value = Screen.Join
    }

    fun back() {
        when (_screen.value) {
            Screen.Create, Screen.Join -> _screen.value = Screen.Home
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
                val srv = GameServer(app, engine, 8080)
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
                _roomUrl.value = ip?.let { "http://$it:8080" }
                _connectionLost.value = false
                _screen.value = Screen.Host
                _error.value = null
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

    fun refreshHostUrl() {
        val ip = LocalNetwork.ipv4()
        _hostIp.value = ip
        _roomUrl.value = ip?.let { "http://$it:8080" }
    }

    fun closeHost() {
        hostCollect?.cancel()
        hostCollect = null
        host?.server?.stop()
        host?.scope?.cancel()
        host = null
        _hostState.value = null
        _roomUrl.value = null
        _hostIp.value = null
    }

    // ── Игрок ──

    fun probeRoom(address: String) {
        if (_busy.value) return
        _busy.value = true
        _roomInfo.value = null
        viewModelScope.launch {
            try {
                val api = ApiClient(address)
                _roomInfo.value = withContext(Dispatchers.IO) { api.roomInfo() }
                _lastAddress.value = api.baseUrl.removePrefix("http://")
                saveAddress(_lastAddress.value)
                _error.value = null
            } catch (e: Exception) {
                _roomInfo.value = null
                _error.value = e.message ?: "Не удалось найти комнату"
            } finally {
                _busy.value = false
            }
        }
    }

    fun joinRoom(address: String, name: String, password: String) {
        if (_busy.value) return
        if (name.isBlank()) {
            _error.value = "Введите имя"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                val api = ApiClient(address)
                val response = withContext(Dispatchers.IO) {
                    api.join(name.trim(), password.ifBlank { null }, "android")
                }
                val pid = response.playerId ?: error("Сервер не вернул playerId")
                val token = response.token ?: error("Сервер не вернул token")
                closeClient()
                val session = ClientSession(api, pid, token, name.trim())
                client = session
                _lastAddress.value = api.baseUrl.removePrefix("http://")
                saveAddress(_lastAddress.value)
                _clientState.value = withContext(Dispatchers.IO) { api.state(pid, token) }
                _connectionLost.value = false
                _screen.value = Screen.Player
                _error.value = null
                startPolling(session)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось подключиться"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun startPolling(session: ClientSession) {
        session.polling?.cancel()
        session.polling = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                try {
                    val state = withContext(Dispatchers.IO) {
                        session.api.state(session.playerId, session.token)
                    }
                    _clientState.value = state
                    failures = 0
                    _connectionLost.value = false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failures += 1
                    if (failures >= 2) _connectionLost.value = true
                }
                delay(1000)
            }
        }
    }

    fun sendAction(type: String, targetId: String? = null, targetIds: List<String> = emptyList()) {
        val session = client ?: return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    session.api.action(session.playerId, session.token, type, targetId, targetIds)
                }
                if (!result.ok) {
                    _error.value = result.error ?: "Не удалось"
                } else {
                    _clientState.value = withContext(Dispatchers.IO) {
                        session.api.state(session.playerId, session.token)
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось отправить выбор"
            }
        }
    }

    fun leaveClient() {
        val leaving = client
        closeClient()
        if (leaving != null) {
            viewModelScope.launch(Dispatchers.IO) {
                leaving.api.leave(leaving.playerId, leaving.token)
            }
        }
        _clientState.value = null
        _screen.value = Screen.Home
    }

    private fun closeClient() {
        client?.polling?.cancel()
        client = null
    }

    private fun saveAddress(address: String) {
        prefs.edit().putString("last_address", address).apply()
    }

    override fun onCleared() {
        closeClient()
        closeHost()
        super.onCleared()
    }
}
