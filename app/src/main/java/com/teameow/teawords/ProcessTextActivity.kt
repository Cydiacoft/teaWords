package com.teameow.teawords

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.teameow.teawords.ui.screens.LookupScreen
import com.teameow.teawords.ui.screens.ProvidePronunciation
import com.teameow.teawords.ui.theme.TeaWordsTheme

/** Explicit system share / selected-text entry; never watches other apps or the clipboard. */
class ProcessTextActivity : ComponentActivity() {
    private val incoming = mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = extractText(intent)
        setContent { TeaWordsTheme { ProvidePronunciation { LookupScreen(incoming.value, external = true, onBack = { finish() }) } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming.value = extractText(intent) }
    companion object {
        fun extractText(intent: Intent): String = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getCharSequenceExtra(Intent.EXTRA_TEXT) else null
            else -> null
        }?.toString().orEmpty()
    }
}
