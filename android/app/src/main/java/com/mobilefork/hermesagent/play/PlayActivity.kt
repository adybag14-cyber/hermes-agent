package com.mobilefork.hermesagent.play

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.mobilefork.hermesagent.ui.chat.ChatViewModel

class PlayActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PlayShell(chat) }
    }

    override fun onStart() {
        super.onStart()
        PlayForegroundLifetime.started()
    }

    override fun onStop() {
        if (!isChangingConfigurations) chat.pauseForBackground()
        PlayForegroundLifetime.stopped()
        super.onStop()
    }
}
