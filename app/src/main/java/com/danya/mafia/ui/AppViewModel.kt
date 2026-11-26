package com.danya.mafia.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danya.mafia.game.GameEngine
import com.danya.mafia.game.HostSnapshot
import com.danya.mafia.game.PlayerPreview
import com.danya.mafia.game.RoomConfig
import com.danya.mafia.server.GameServer
import com.danya.mafia.server.LocalNetwork
import com.danya.mafia.server.MediaLibrary
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
import kotlinx.coroutines.withContext

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
    val config: RoomConfig,
    val media: MediaLibrary
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

    private val _previews = MutableStateFlow<List<PlayerPreview>>(emptyList())
    val previews: StateFlow<List<PlayerPreview>> = _previews.asStateFlow()

    private val _soundTracks = MutableStateFlow<List<MediaLibrary.Info>>(emptyList())
    val soundTracks: StateFlow<List<MediaLibrary.Info>> = _soundTracks.asStateFlow()

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
                val media = MediaLibrary()
                val srv = GameServer(app, engine, media, _serverPort.value)
                server = srv
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                val ip = LocalNetwork.ipv4()
                host = HostSession(engine, srv, scope, config, media)
                _soundTracks.value = emptyList()
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

    /** Играет mp3 из библиотеки комнаты (на всех или на одном игроке). */
    fun playTrack(trackId: String, targetId: String?) {
        val result = host?.engine?.playSound("mp3", targetId, "/media/$trackId")
        if (result != null && !result.ok) _error.value = result.error
    }

    /** Добавляет mp3, выбранный в системном файловом менеджере. */
    fun addSound(uri: Uri) {
        val session = host ?: return
        viewModelScope.launch {
            try {
                val (name, mime, bytes) = withContext(Dispatchers.IO) { readSound(uri) }
                if (bytes.size > MAX_SOUND_BYTES) {
                    _error.value = "Файл больше 15 МБ — выберите mp3 поменьше"
                    return@launch
                }
                session.media.add(name, mime, bytes)
                _soundTracks.value = session.media.list()
            } catch (e: Exception) {
                _error.value = "Не удалось добавить звук: ${e.message}"
            }
        }
    }

    fun removeSound(trackId: String) {
        val session = host ?: return
        session.media.remove(trackId)
        _soundTracks.value = session.media.list()
    }

    private fun readSound(uri: Uri): Triple<String, String, ByteArray> {
        var name = "sound.mp3"
        try {
            app.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
            }
        } catch (_: Exception) {
        }
        val mime = app.contentResolver.getType(uri)
            ?.takeIf { it.startsWith("audio/") }
            ?: "audio/mpeg"
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_SOUND_BYTES) {
                    throw IllegalStateException("файл больше 15 МБ")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: throw IllegalStateException("файл недоступен")
        if (bytes.isEmpty()) throw IllegalStateException("пустой файл")
        return Triple(name.take(60), mime, bytes)
    }

    fun setCatEnabled(enabled: Boolean) {
        val result = host?.engine?.setCatEnabled(enabled)
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
        _soundTracks.value = emptyList()
    }

    companion object {
        private const val MAX_SOUND_BYTES = 15 * 1024 * 1024
    }

    override fun onCleared() {
        closeHost()
        super.onCleared()
    }
}
