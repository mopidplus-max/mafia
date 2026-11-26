package com.danya.mafia.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danya.mafia.game.GameEngine
import com.danya.mafia.game.ClientState
import com.danya.mafia.game.HostSnapshot
import com.danya.mafia.game.RoomConfig
import com.danya.mafia.server.GameServer
import com.danya.mafia.server.LocalNetwork
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _previews = MutableStateFlow<List<ClientState>>(emptyList())
    val previews: StateFlow<List<ClientState>> = _previews.asStateFlow()

    private var host: HostSession? = null
    private var hostCollect: Job? = null

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
                    engine.hostSnapshot.collect {
                        _hostState.value = it
                        _previews.value = engine.playerPreviews()
                    }
                }
                _hostIp.value = ip
                _roomUrl.value = ip?.let { "http://$it:${_serverPort.value}" }
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

    fun setAutoHost(enabled: Boolean) {
        host?.engine?.setAutoHost(enabled)
    }

    fun playSound(kind: String, targetId: String?) {
        val result = host?.engine?.playSound(kind, targetId)
        if (result != null && !result.ok) _error.value = result.error
    }

    fun renamePlayer(playerId: String, name: String) {
        val result = host?.engine?.renamePlayer(playerId, name)
        if (result != null && !result.ok) _error.value = result.error
    }

    fun kickPlayer(playerId: String) {
        val result = host?.engine?.kickPlayer(playerId)
        if (result != null && !result.ok) _error.value = result.error
    }

    fun refreshHostUrl() {
        val ip = LocalNetwork.ipv4()
        _hostIp.value = ip
        _roomUrl.value = ip?.let { "http://$it:${_serverPort.value}" }
    }

    fun closeHost() {
        hostCollect?.cancel()
        hostCollect = null
        host?.server?.stop()
        host?.scope?.cancel()
        host = null
        _roomUrl.value = null
        _hostIp.value = null
        _previews.value = emptyList()
    }

    override fun onCleared() {
        closeHost()
        super.onCleared()
    }
}
