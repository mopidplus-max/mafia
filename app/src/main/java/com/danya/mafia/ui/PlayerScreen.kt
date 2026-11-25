package com.danya.mafia.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.danya.mafia.game.ClientState
import com.danya.mafia.game.VoteStatus

@Composable
fun PlayerScreen(
    state: ClientState,
    connectionLost: Boolean,
    onAction: (String, String?, List<String>) -> Unit,
    onLeave: () -> Unit
) {
    var confirmLeave by remember { mutableStateOf(false) }
    val requestLeave: () -> Unit = { confirmLeave = true }

    Column(modifier = Modifier.fillMaxSize()) {
        MafiaTopBar(
            title = state.roomName,
            onBack = requestLeave,
            actions = {
                TextButton(onClick = requestLeave) {
                    Icon(Icons.Filled.Close, contentDescription = "Выйти")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connectionLost) {
                item {
                    StatusBanner("Связь с ведущим потеряна. Пробуем переподключиться...", error = true)
                }
            }

            if (state.phase == "lobby") {
                item { PlayerLobbyCard(state) }
                item { SectionTitle("Участники (${state.playerCount})") }
                if (state.players.isEmpty()) {
                    item { WaitingPlayerCard("Ждём ведущего и других игроков...") }
                } else {
                    items(state.players, key = { it.id }) { player ->
                        PlayerRow(player = player, showRole = false)
                    }
                }
                item { WaitingPlayerCard("Ведущий начнёт игру, когда все соберутся.") }
            } else {
                val winner = state.winner
                if (winner != null) {
                    item { WinnerCard(winner) }
                }

                item {
                    RoleCard(
                        roleId = state.you.roleId,
                        title = state.you.roleTitle,
                        icon = state.you.roleIcon,
                        prominent = state.phase == "starting"
                    )
                }

                item {
                    PlayerPhaseCard(state)
                }

                if (!state.you.alive && state.phase != "game_over") {
                    item {
                        StatusBanner("Вы выбыли из игры. Можно наблюдать за партией.", error = false)
                    }
                }

                val prompt = state.prompt
                if (prompt != null) {
                    item {
                        ActionPromptCard(
                            prompt = prompt,
                            serverNow = state.serverNow,
                            onSubmit = { targets ->
                                when {
                                    targets.isEmpty() -> onAction("skip", null, emptyList())
                                    prompt.kind == "vote" ->
                                        onAction("vote", targets.first(), emptyList())
                                    prompt.kind == "sniper" ->
                                        onAction("sniper", targets.first(), emptyList())
                                    else -> onAction("night", null, targets)
                                }
                            }
                        )
                    }
                }

                val privateMessage = state.privateMessage
                if (privateMessage != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            )
                        ) {
                            Text(
                                privateMessage,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                val vote = state.vote
                if (vote != null) {
                    item { PlayerVoteCard(vote, state.serverNow) }
                }

                if (state.events.isNotEmpty()) {
                    item { EventsCard(state.events) }
                }

                item { SectionTitle("Игроки") }
                items(state.players, key = { it.id }) { player ->
                    PlayerRow(player = player, showRole = player.roleTitle != null)
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = requestLeave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Выйти из комнаты")
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Выйти из комнаты?") },
            text = {
                Text(
                    if (state.phase == "lobby") "Вы покинете комнату и сможете зайти снова."
                    else "Вы останетесь в партии, но ваши ходы и голос будет пропущены."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeave()
                }) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun PlayerLobbyCard(state: ClientState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Вы в комнате",
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceIcon(state.you.device, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.you.name} • игроков: ${state.playerCount}" +
                        (if (state.maxPlayers > 0) " из ${state.maxPlayers}" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.hasPassword) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "🔒 Комната с паролем",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun PlayerPhaseCard(state: ClientState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.phaseLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            if (state.message.isNotBlank()) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            CountdownLine(
                endsAt = state.phaseEndsAt,
                serverNow = state.serverNow,
                label = "Осталось"
            )
            val nightTitle = state.nightRoleTitle
            if (nightTitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Просыпается: ${state.nightRoleIcon ?: ""} $nightTitle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun PlayerVoteCard(vote: VoteStatus, serverNow: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            val voterName = vote.voterName
            Text(
                if (voterName != null) "Голосует: $voterName"
                else "Голосование завершено",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
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
            if (voterName != null) {
                Spacer(Modifier.height(6.dp))
                CountdownLine(vote.endsAt, serverNow, label = "На голос")
            }
        }
    }
}

@Composable
private fun WaitingPlayerCard(text: String) {
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
private fun WinnerCard(winner: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            when (winner) {
                "mafia" -> "🔴 Мафия победила!"
                "maniac" -> "🔪 Маньяк победил!"
                "jester" -> "🃏 Шут победил!"
                "peaceful" -> "✅ Мирные победили!"
                else -> "Игра окончена"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        )
    }
}
