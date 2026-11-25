package com.danya.mafia.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danya.mafia.game.ActionPrompt
import com.danya.mafia.game.PlayerView
import com.danya.mafia.game.Role
import com.danya.mafia.game.TargetView
import kotlinx.coroutines.delay

@Composable
fun teamColor(team: String?): Color = when (team) {
    "mafia" -> Color(0xFFFF9A95)
    "maniac" -> Color(0xFFFFD37A)
    "jester" -> Color(0xFFE8B4FF)
    "peaceful" -> Color(0xFF9BE7A8)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun roleColor(roleId: String?): Color {
    val role = Role.byId(roleId) ?: return MaterialTheme.colorScheme.onSurfaceVariant
    return when (role.team) {
        com.danya.mafia.game.Team.MAFIA -> Color(0xFFFF9A95)
        com.danya.mafia.game.Team.MANIAC -> Color(0xFFFFD37A)
        com.danya.mafia.game.Team.JESTER -> Color(0xFFE8B4FF)
        com.danya.mafia.game.Team.PEACEFUL -> Color(0xFF9BE7A8)
    }
}

@Composable
fun DeviceIcon(device: String, modifier: Modifier = Modifier) {
    val icon = when (device) {
        "ios" -> Icons.Filled.PhoneIphone
        "android" -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.Computer
    }
    Icon(
        imageVector = icon,
        contentDescription = when (device) {
            "ios" -> "iPhone"
            "android" -> "Android"
            else -> "Браузер"
        },
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun MafiaWordmark(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎭", fontSize = 44.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "МАФИЯ",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MafiaTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) {
                TextButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun StatusBanner(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun PlayerRow(
    player: PlayerView,
    showRole: Boolean = player.roleTitle != null,
    trailing: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (player.alive) 1f else 0.55f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                DeviceIcon(player.device, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        player.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (player.isYou) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                "вы",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (player.guardian) {
                    Text(
                        "👼 Ангел-Хранитель",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (!player.alive) {
                    Text(
                        "выбыл",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (player.left) {
                    Text(
                        "нет связи",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            if (showRole && player.roleTitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(player.roleIcon ?: "", fontSize = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        player.roleTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = teamColor(player.roleTeam)
                    )
                }
            } else if (trailing != null) {
                trailing()
            }
        }
    }
}

@Composable
fun RoleCard(
    roleId: String?,
    title: String?,
    icon: String?,
    prominent: Boolean = false,
    extra: String? = null
) {
    val role = Role.byId(roleId)
    val color = roleColor(roleId)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(
            containerColor = if (prominent) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (prominent) 24.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon ?: "❔", fontSize = if (prominent) 56.sp else 32.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                title ?: "Роль скрыта",
                style = if (prominent) MaterialTheme.typography.headlineMedium
                else MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (prominent && role != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    role.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (extra != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    extra,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun CountdownLine(
    endsAt: Long?,
    serverNow: Long,
    label: String = "Осталось",
    modifier: Modifier = Modifier
) {
    val offset = remember(serverNow) { serverNow - System.currentTimeMillis() }
    var localNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        if (endsAt != null) {
            while (true) {
                localNow = System.currentTimeMillis()
                delay(250)
            }
        }
    }
    if (endsAt == null) return
    val left = ((endsAt - (localNow + offset)) / 1000L).coerceAtLeast(0L)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text("⏳", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "$label: $left с",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ActionPromptCard(
    prompt: ActionPrompt,
    serverNow: Long,
    onSubmit: (List<String>) -> Unit
) {
    val promptKey = remember(prompt) {
        prompt.kind + "|" + prompt.title + "|" + prompt.endsAt + "|" +
            prompt.targets.joinToString(",") { it.id }
    }
    var selected by remember(promptKey) { mutableStateOf(emptySet<String>()) }
    var sent by remember(promptKey) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                prompt.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (prompt.maxTargets > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Выберите ${prompt.maxTargets} игрока",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            CountdownLine(prompt.endsAt, serverNow, label = "Время")

            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.targets.forEach { target ->
                    TargetRow(
                        target = target,
                        selected = target.id in selected,
                        enabled = !sent,
                        onClick = {
                            selected = if (prompt.maxTargets == 1) {
                                setOf(target.id)
                            } else {
                                if (target.id in selected) selected - target.id
                                else if (selected.size >= prompt.maxTargets) selected
                                else selected + target.id
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (sent) {
                Text(
                    "Выбор отправлен. Ожидайте...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                Button(
                    onClick = {
                        sent = true
                        onSubmit(selected.toList())
                    },
                    enabled = selected.size >= prompt.minTargets,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Подтвердить")
                }
                if (prompt.canSkip) {
                    TextButton(
                        onClick = {
                            sent = true
                            onSubmit(emptyList())
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (prompt.kind == "vote") "Воздержаться" else "Пропустить")
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(
    target: TargetView,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            DeviceIcon(target.device, Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            target.name,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun EventsCard(events: List<String>, modifier: Modifier = Modifier) {
    if (events.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            events.forEach { event ->
                Row {
                    Text("•", color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        event,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AllowedRolesRow(roleIds: List<String>, modifier: Modifier = Modifier) {
    val roles = roleIds.mapNotNull { Role.byId(it) }
    if (roles.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(roles) { role ->
            AssistChip(
                onClick = {},
                label = { Text("${role.icon} ${role.title}") }
            )
        }
    }
}
