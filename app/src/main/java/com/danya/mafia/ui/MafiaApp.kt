package com.danya.mafia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MafiaApp(vm: AppViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val hostState by vm.hostState.collectAsStateWithLifecycle()
    val clientState by vm.clientState.collectAsStateWithLifecycle()
    val roomInfo by vm.roomInfo.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val roomUrl by vm.roomUrl.collectAsStateWithLifecycle()
    val hostIp by vm.hostIp.collectAsStateWithLifecycle()
    val lastAddress by vm.lastAddress.collectAsStateWithLifecycle()
    val connectionLost by vm.connectionLost.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        val message = error
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.clearError()
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        vm.back()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val current = screen) {
                Screen.Home -> HomeScreen(
                    onCreate = vm::goCreate,
                    onJoin = vm::goJoin
                )
                Screen.Create -> CreateRoomScreen(
                    busy = busy,
                    onBack = vm::back,
                    onCreate = vm::createRoom
                )
                Screen.Join -> JoinScreen(
                    busy = busy,
                    lastAddress = lastAddress,
                    roomInfo = roomInfo,
                    onBack = vm::back,
                    onProbe = vm::probeRoom,
                    onJoin = vm::joinRoom
                )
                Screen.Host -> {
                    val state = hostState
                    if (state == null) {
                        LoadingScreen()
                    } else {
                        HostScreen(
                            state = state,
                            roomUrl = roomUrl,
                            hostIp = hostIp,
                            onStart = vm::startHostGame,
                            onAdvance = vm::hostAdvance,
                            onRestart = vm::hostRestart,
                            onClose = vm::goHome,
                            onRefreshUrl = vm::refreshHostUrl
                        )
                    }
                }
                Screen.Player -> {
                    val state = clientState
                    if (state == null) {
                        LoadingScreen()
                    } else {
                        PlayerScreen(
                            state = state,
                            connectionLost = connectionLost,
                            onAction = vm::sendAction,
                            onLeave = vm::leaveClient
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
