package com.danya.mafia.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.danya.mafia.game.Role
import com.danya.mafia.game.RoomConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateRoomScreen(
    busy: Boolean,
    onBack: () -> Unit,
    onCreate: (RoomConfig) -> Unit
) {
    var roomName by rememberSaveable { mutableStateOf("Мафия") }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var maxPlayers by rememberSaveable { mutableStateOf("0") }
    var selectedRoles by remember { mutableStateOf(Role.defaultAllowed) }

    val maxValue = maxPlayers.toIntOrNull()
    val maxError = maxValue == null || maxValue < 0
    val passwordError = usePassword && password.isBlank()
    val canCreate = roomName.isNotBlank() && !maxError && !passwordError && !busy

    Column(modifier = Modifier.fillMaxSize()) {
        MafiaTopBar(title = "Создать комнату", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = roomName,
                onValueChange = { roomName = it.take(40) },
                label = { Text("Название комнаты") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))

            RowSwitch(
                title = "Ставить пароль?",
                checked = usePassword,
                onCheckedChange = { usePassword = it }
            )

            if (usePassword) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(32) },
                    label = { Text("Пароль комнаты") },
                    singleLine = true,
                    isError = passwordError,
                    supportingText = if (passwordError) {
                        { Text("Введите пароль или выключите пароль") }
                    } else null,
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "Скрыть" else "Показать"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(22.dp))
            SectionTitle("Какие роли могут быть?")
            Text(
                "Роли выбираются случайно из отмеченных. Мафия добавляется всегда.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Role.selectable.forEach { role ->
                    FilterChip(
                        selected = role.id in selectedRoles,
                        onClick = {
                            selectedRoles = if (role.id in selectedRoles) {
                                selectedRoles - role.id
                            } else {
                                selectedRoles + role.id
                            }
                        },
                        label = { Text("${role.icon} ${role.title}") }
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            OutlinedTextField(
                value = maxPlayers,
                onValueChange = { value ->
                    if (value.length <= 2 && value.all { it.isDigit() }) maxPlayers = value
                },
                label = { Text("Максимум участников") },
                supportingText = {
                    Text(if (maxError) "Введите 0 или больше" else "0 — без ограничения")
                },
                isError = maxError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))
            Text(
                "Минимум 4 игрока. Ведущий не играет и не входит в список участников.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onCreate(
                        RoomConfig(
                            name = roomName.trim(),
                            password = if (usePassword) password else null,
                            allowedRoles = selectedRoles,
                            maxPlayers = maxValue ?: 0
                        )
                    )
                },
                enabled = canCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("Создать")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RowSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
