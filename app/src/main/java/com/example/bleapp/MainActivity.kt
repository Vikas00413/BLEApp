package com.example.bleapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bleapp.ui.chat.BleChatScreen
import com.example.bleapp.ui.location.LocationShareIntent
import com.example.bleapp.ui.location.LocationShareScreen
import com.example.bleapp.ui.location.LocationShareViewModel
import com.example.bleapp.ui.theme.BleAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BleAppTheme {
                val viewModel: LocationShareViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                if (state.showChatScreen) {
                    val canSend = state.isChatConnected || state.isGattServerRunning
                    BleChatScreen(
                        modifier = Modifier.fillMaxSize(),
                        peerName = state.chatPeerName,
                        chatStatus = state.chatStatus,
                        canSend = canSend,
                        isPeerTyping = state.isPeerTyping,
                        messages = state.chatMessages,
                        onInputChange = { viewModel.onIntent(LocationShareIntent.ChatInputChanged(it)) },
                        onSend = { viewModel.onIntent(LocationShareIntent.SendChatMessage(it)) },
                        onShareLocation = { viewModel.onIntent(LocationShareIntent.ShareMyLocation) },
                        onShareLink = { viewModel.onIntent(LocationShareIntent.ShareLink(it)) },
                        onSharePhoto = { viewModel.onIntent(LocationShareIntent.SharePhoto(it)) },
                        onShareVideo = { viewModel.onIntent(LocationShareIntent.ShareVideo(it)) },
                        onBack = { viewModel.onIntent(LocationShareIntent.CloseChat) },
                    )
                } else {
                    LocationShareScreen(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}
