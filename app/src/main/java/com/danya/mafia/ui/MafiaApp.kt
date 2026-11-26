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
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val roomUrl by vm.roomUrl.collectAsStateWithLifecycle()
    val hostIp by vm.hostIp.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val soundTracks by vm.soundTracks.collectAsStateWithLifecycle()

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
            when (screen) {
                Screen.Home -> HomeScreen(
                    onCreate = vm::goCreate
                )
                Screen.Create -> CreateRoomScreen(
                    busy = busy,
                    onBack = vm::back,
                    onCreate = vm::createRoom
                )
                Screen.Host -> {
                    val state = hostState
                    if (state == null) {
                        LoadingScreen()
                    } else {
                        HostScreen(
                            state = state,
                            previews = previews,
                            soundTracks = soundTracks,
                            roomUrl = roomUrl,
                            hostIp = hostIp,
                            onStart = vm::startHostGame,
                            onAdvance = vm::hostAdvance,
                            onRestart = vm::hostRestart,
                            onClose = vm::goHome,
                            onRefreshUrl = vm::refreshHostUrl,
                            onToggleAutoHost = vm::setAutoHost,
                            onToggleCat = vm::setCatEnabled,
                            onPlaySound = vm::playSound,
                            onPlayTrack = vm::playTrack,
                            onAddSound = vm::addSound,
                            onRemoveTrack = vm::removeSound,
                            onRenamePlayer = vm::renamePlayer,
                            onKickPlayer = vm::kickPlayer
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
