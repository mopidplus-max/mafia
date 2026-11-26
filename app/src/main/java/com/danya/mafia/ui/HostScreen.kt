package com.danya.mafia.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.danya.mafia.game.ChatMessage
import com.danya.mafia.game.GameEngine
import com.danya.mafia.game.HostSnapshot
import com.danya.mafia.game.PlayerActivity
import com.danya.mafia.game.PlayerPreview
import com.danya.mafia.game.PlayerView
import com.danya.mafia.game.VoteStatus
import com.danya.mafia.server.MediaLibrary

@Composable
fun HostScreen(
    state: HostSnapshot,
    previews: List<PlayerPreview>,
    soundTracks: List<MediaLibrary.Info>,
    roomUrl: String?,
    hostIp: String?,
    onStart: () -> Unit,
    onAdvance: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
    onRefreshUrl: () -> Unit,
    onToggleAutoHost: (Boolean) -> Unit,
    onToggleCat: (Boolean) -> Unit,
    onPlaySound: (String, String?) -> Unit,
    onPlayTrack: (String, String?) -> Unit,
    onAddSound: (Uri) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onRenamePlayer: (String, String) -> Unit,
    onKickPlayer: (String) -> Unit
) {
    KeepScreenOn()
    var confirmClose by remember { mutableStateOf(false) }
    var showScreens by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlayerView?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var kickTarget by remember { mutableStateOf<PlayerView?>(null) }
    val requestClose: () -> Unit = { confirmClose = true }
    val askRename: (PlayerView) -> Unit = { player ->
        renameTarget = player
        renameValue = player.name
    }
    val askKick: (PlayerView) -> Unit = { player -> kickTarget = player }
    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onAddSound(uri) }
    val pickSound: () -> Unit = { soundPicker.launch(arrayOf("audio/*")) }

    if (state.phase == "lobby") {
        HostLobby(
            state = state,
            previews = previews,
            soundTracks = soundTracks,
            roomUrl = roomUrl,
            hostIp = hostIp,
            onStart = onStart,
            onClose = requestClose,
            onRefreshUrl = onRefreshUrl,
            onToggleAutoHost = onToggleAutoHost,
            onToggleCat = onToggleCat,
            onPlayTrack = onPlayTrack,
            onPickSound = pickSound,
            onRemoveTrack = onRemoveTrack,
            onOpenScreens = { showScreens = true },
            onRename = askRename,
            onKick = askKick
        )
    } else {
        HostGame(
            state = state,
            previews = previews,
            soundTracks = soundTracks,
            roomUrl = roomUrl,
            onAdvance = onAdvance,
            onRestart = onRestart,
            onClose = requestClose,
            onToggleAutoHost = onToggleAutoHost,
            onPlaySound = onPlaySound,
            onPlayTrack = onPlayTrack,
            onPickSound = pickSound,
            onRemoveTrack = onRemoveTrack,
            onOpenScreens = { showScreens = true },
            onRename = askRename,
            onKick = askKick
        )
    }

    if (showScreens) {
        ScreensDialog(
            previews = previews,
            roomUrl = roomUrl,
            onClose = { showScreens = false }
        )
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Новое имя") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(20) },
                    singleLine = true,
                    label = { Text("Имя игрока") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = renameTarget
                        renameTarget = null
                        if (target != null) onRenamePlayer(target.id, renameValue)
                    },
                    enabled = renameValue.trim().length >= 2
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Отмена") }
            }
        )
    }

    if (kickTarget != null) {
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            title = { Text("Убрать игрока?") },
            text = {
                Text("${kickTarget?.name ?: ""} потеряет доступ к комнате. В партии он сразу выбывает.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = kickTarget
                    kickTarget = null
                    if (target != null) onKickPlayer(target.id)
                }) { Text("Убрать") }
            },
            dismissButton = {
                TextButton(onClick = { kickTarget = null }) { Text("Отмена") }
            }
        )
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Закрыть комнату?") },
            text = {
                Text(
                    if (state.phase == "lobby") "Все участники потеряют подключение."
                    else "Игра будет прервана для всех участников."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    onClose()
                }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun HostLobby(
    state: HostSnapshot,
    previews: List<PlayerPreview>,
    soundTracks: List<MediaLibrary.Info>,
    roomUrl: String?,
    hostIp: String?,
    onStart: () -> Unit,
    onClose: () -> Unit,
    onRefreshUrl: () -> Unit,
    onToggleAutoHost: (Boolean) -> Unit,
    onToggleCat: (Boolean) -> Unit,
    onPlayTrack: (String, String?) -> Unit,
    onPickSound: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    onOpenScreens: () -> Unit,
    onRename: (PlayerView) -> Unit,
    onKick: (PlayerView) -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        MafiaTopBar(
            title = state.roomName,
            onBack = onClose,
            actions = {
                TextButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть комнату")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                RoomHeroCard(
                    state = state,
                    roomUrl = roomUrl,
                    hostIp = hostIp,
                    onRefreshUrl = onRefreshUrl,
                    onCopy = { text ->
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("room", text))
                        Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item {
                AutoHostRow(enabled = state.autoHost, onChange = onToggleAutoHost)
            }

            item {
                CatRow(enabled = state.catEnabled, onChange = onToggleCat)
            }

            item {
                ScreensCard(previews = previews, onOpen = onOpenScreens)
            }

            item {
                Mp3Card(
                    tracks = soundTracks,
                    players = state.players,
                    onPick = onPickSound,
                    onPlay = onPlayTrack,
                    onRemove = onRemoveTrack
                )
            }

            item { SectionTitle("Участники (${state.players.size})") }
            if (state.players.isEmpty()) {
                item { WaitingCard("Ждём игроков. Пока никого нет.") }
            } else {
                items(state.players, key = { it.id }) { player ->
                    PlayerRow(
                        player = player,
                        showRole = false,
                        trailing = { PlayerActions(player, onRename, onKick) }
                    )
                }
            }

            item { SectionTitle("Возможные роли") }
            item { AllowedRolesRow(state.allowedRoles) }

            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Начать игру", style = MaterialTheme.typography.titleMedium)
                }
                if (!state.canStart) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Нужно минимум ${state.minPlayers} игрока. Сейчас: ${state.players.size}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                Text(
                    "Ведущий может играть на сайте вместе со всеми — включите автоведущего выше.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RoomHeroCard(
    state: HostSnapshot,
    roomUrl: String?,
    hostIp: String?,
    onRefreshUrl: () -> Unit,
    onCopy: (String) -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "КОМНАТА",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.roomName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("Игроков: ${state.players.size}")
                    if (state.maxPlayers > 0) append(" из ${state.maxPlayers}")
                    else append(" (без ограничения)")
                    append(" • Пароль: ")
                    append(if (state.hasPassword) "да" else "нет")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            if (roomUrl != null) {
                Text(
                    "Откройте в браузере на любом телефоне:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        roomUrl,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onCopy(roomUrl) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Скопировать")
                    }
                }
                Text(
                    "Все играют на сайте — ведущий тоже может зайти по этой ссылке.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { openUrl(context, roomUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Открыть сайт и играть")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (hostIp == null) "Не вижу IP. Включите Wi-Fi или точку доступа."
                        else "IP: $hostIp",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRefreshUrl) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Обновить адрес")
                }
            }
        }
    }
}

@Composable
private fun WaitingCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HostGame(
    state: HostSnapshot,
    previews: List<PlayerPreview>,
    soundTracks: List<MediaLibrary.Info>,
    roomUrl: String?,
    onAdvance: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
    onToggleAutoHost: (Boolean) -> Unit,
    onPlaySound: (String, String?) -> Unit,
    onPlayTrack: (String, String?) -> Unit,
    onPickSound: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    onOpenScreens: () -> Unit,
    onRename: (PlayerView) -> Unit,
    onKick: (PlayerView) -> Unit
) {
    val context = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    val nightTitle = state.nightTitle
    val nightBody = state.nightBody
    val winner = state.winner
    val vote = state.vote

    Column(modifier = Modifier.fillMaxSize()) {
        MafiaTopBar(
            title = state.roomName,
            onBack = onClose,
            actions = {
                if (roomUrl != null) {
                    TextButton(onClick = { openUrl(context, roomUrl) }) {
                        Text("🌐")
                    }
                }
                TextButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PhaseCard(
                    state = state,
                    onAdvance = onAdvance,
                    onRestart = onRestart
                )
            }

            item {
                AutoHostRow(enabled = state.autoHost, onChange = onToggleAutoHost)
            }

            item {
                ScreensCard(previews = previews, onOpen = onOpenScreens)
            }

            item {
                SoundCard(players = state.players, onPlay = onPlaySound)
            }

            item {
                Mp3Card(
                    tracks = soundTracks,
                    players = state.players,
                    onPick = onPickSound,
                    onPlay = onPlayTrack,
                    onRemove = onRemoveTrack
                )
            }

            if (nightTitle != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                nightTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (!nightBody.isNullOrBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    nightBody,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!state.actionProgress.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    state.actionProgress,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }

            if (winner != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(
                            winnerTitle(winner),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                        )
                    }
                }
            }

            item { SectionTitle("Роли игроков") }
            items(state.players, key = { it.id }) { player ->
                PlayerRow(
                    player = player,
                    showRole = true,
                    trailing = { PlayerActions(player, onRename, onKick) }
                )
            }

            if (vote != null) {
                item { VoteCard(vote, state.serverNow) }
            }

            if (state.events.isNotEmpty()) {
                item { EventsCard(state.events) }
            }

            item { ActivityCard(state.activity) }
            item { ChatCard(state.chat) }

            item {
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "Скрыть журнал" else "Показать журнал")
                }
            }
            if (showLog) {
                item { EventsCard(state.log.takeLast(25)) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PhaseCard(state: HostSnapshot, onAdvance: () -> Unit, onRestart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.phaseLabel,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            CountdownLine(
                endsAt = state.phaseEndsAt,
                serverNow = state.serverNow,
                label = "Осталось"
            )
            if (!state.actionProgress.isNullOrBlank() && state.phase != "night_step") {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.actionProgress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (state.pistolHolder != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "🔫 Пистолет у ${state.pistolHolder}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (state.guardianNames.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "👼 Ангелы-Хранители: ${state.guardianNames.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(Modifier.height(14.dp))
            if (state.canAdvance) {
                Button(
                    onClick = onAdvance,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(state.advanceLabel)
                }
            }
            if (state.canRestart) {
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Вернуться в лобби")
                }
            }
        }
    }
}

@Composable
private fun VoteCard(vote: VoteStatus, serverNow: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Голосование",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (vote.voterName != null) "Сейчас голосует: ${vote.voterName}"
                else "Голосование завершено",
                style = MaterialTheme.typography.bodyMedium
            )
            if (vote.total > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { vote.done.toFloat() / vote.total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Проголосовали: ${vote.done} из ${vote.total}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (vote.voterName != null) {
                Spacer(Modifier.height(6.dp))
                CountdownLine(vote.endsAt, serverNow, label = "На голос")
            }
        }
    }
}

@Composable
private fun ScreensCard(previews: List<PlayerPreview>, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "👁 Живые экраны игроков",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Настоящий сайт глазами игрока. Листать — стрелками, обновляется сам.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpen,
                enabled = previews.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (previews.isEmpty()) "Пока никого нет" else "Открыть экраны (${previews.size})")
            }
        }
    }
}

@Composable
private fun PlayerActions(
    player: PlayerView,
    onRename: (PlayerView) -> Unit,
    onKick: (PlayerView) -> Unit
) {
    IconButton(onClick = { onRename(player) }, modifier = Modifier.size(36.dp)) {
        Text("✏️", fontSize = 15.sp)
    }
    IconButton(onClick = { onKick(player) }, modifier = Modifier.size(36.dp)) {
        Text("🚪", fontSize = 15.sp)
    }
}

@Composable
private fun ScreensDialog(
    previews: List<PlayerPreview>,
    roomUrl: String?,
    onClose: () -> Unit
) {
    if (previews.isEmpty()) return
    var index by remember { mutableStateOf(0) }
    val safe = index.coerceIn(0, previews.lastIndex)
    val preview = previews[safe]

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        index = (safe - 1 + previews.size) % previews.size
                    }) {
                        Text("‹", fontSize = 30.sp)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            preview.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            "${safe + 1} из ${previews.size} · ${preview.state.phaseLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        index = (safe + 1) % previews.size
                    }) {
                        Text("›", fontSize = 30.sp)
                    }
                }
                PlayerWebScreen(
                    url = watchUrl(roomUrl, preview),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Закрыть")
                }
            }
        }
    }
}

private fun watchUrl(roomUrl: String?, preview: PlayerPreview): String {
    // Телефон ведущего смотрит свой же сервер по loopback — так надёжнее.
    val port = roomUrl?.let { Uri.parse(it).port }?.takeIf { it in 1..65535 } ?: 8080
    return "http://127.0.0.1:$port/?watch=1" +
        "&pid=${Uri.encode(preview.id)}&token=${Uri.encode(preview.token)}"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlayerWebScreen(url: String, modifier: Modifier = Modifier) {
    var progress by remember(url) { mutableStateOf(0) }
    Column(modifier = modifier.fillMaxSize()) {
        if (progress < 100) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(4.dp))
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = false
                    settings.useWideViewPort = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            progress = 100
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { view ->
                if (view.url != url) view.loadUrl(url)
            }
        )
    }
}

@Composable
private fun SoundCard(players: List<PlayerView>, onPlay: (String, String?) -> Unit) {
    var targetId by remember { mutableStateOf<String?>(null) }
    val devices = players.filter { !it.left }
    val targetName = devices.firstOrNull { it.id == targetId }?.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "🔊 Звук на телефонах",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (targetName == null) "Прозвучит на всех устройствах"
                else "Прозвучит только у $targetName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = targetId == null,
                    onClick = { targetId = null },
                    label = { Text("Все") }
                )
                devices.forEach { p ->
                    FilterChip(
                        selected = targetId == p.id,
                        onClick = { targetId = p.id },
                        label = { Text(p.name) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            GameEngine.SOUND_KINDS.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { kind ->
                        OutlinedButton(
                            onClick = { onPlay(kind, targetId) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "${GameEngine.SOUND_ICONS[kind] ?: ""} ${GameEngine.SOUND_LABELS[kind] ?: kind}",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Mp3Card(
    tracks: List<MediaLibrary.Info>,
    players: List<PlayerView>,
    onPick: () -> Unit,
    onPlay: (String, String?) -> Unit,
    onRemove: (String) -> Unit
) {
    var targetId by remember { mutableStateOf<String?>(null) }
    val devices = players.filter { !it.left }
    val targetName = devices.firstOrNull { it.id == targetId }?.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "🎵 Свои mp3",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (targetName == null) "Файлы играют на всех телефонах"
                else "Файлы играют только у $targetName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = targetId == null,
                    onClick = { targetId = null },
                    label = { Text("Все") }
                )
                devices.forEach { player ->
                    FilterChip(
                        selected = targetId == player.id,
                        onClick = { targetId = player.id },
                        label = { Text(player.name) }
                    )
                }
            }
            if (tracks.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Добавьте mp3 с телефона — он зазвучит на телефонах игроков.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tracks.forEach { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatSize(track.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onPlay(track.id, targetId) }) {
                            Text("▶")
                        }
                        IconButton(onClick = { onRemove(track.id) }) {
                            Text("✕")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text("Добавить mp3")
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f МБ", bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}

@Composable
private fun AutoHostRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Автоведущий",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (enabled) "Сам переключает ночь и итоги — можно играть на сайте"
                    else "Вы ведёте вручную кнопкой «Далее»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun CatRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "🐱 Котость",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (enabled) "Может спасти игрока от первого удара мафии"
                    else "Выключена — защиты от мафии не будет ни у кого",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun ActivityCard(activity: List<PlayerActivity>) {
    if (activity.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "👀 Экраны игроков",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Что сейчас происходит на сайте у каждого.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            activity.forEach { a ->
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(10.dp))
                Text(
                    "${a.playerName} — ${a.screen}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (a.detail.isNotBlank()) {
                    Text(
                        a.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "прокрутка: ${a.scroll}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChatCard(chat: List<ChatMessage>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "💬 Чат мафии",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Пишут только мафия и Адвокат. Остальные игроки этого не видят.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (chat.isEmpty()) {
                Text(
                    "Пока тихо.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            chat.takeLast(14).forEach { m ->
                Text(
                    buildString {
                        if (m.dead) append("💀 ")
                        append(m.authorName)
                        append(": ")
                        append(m.text)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (m.dead) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
}

private fun winnerTitle(winner: String?): String = when (winner) {
    "mafia" -> "🔴 Мафия победила!"
    "maniac" -> "🔪 Маньяк победил!"
    "jester" -> "🃏 Шут победил!"
    "peaceful" -> "✅ Мирные победили!"
    else -> "Игра окончена"
}

@Composable
private fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
