package com.tea.teawords.ui.helper

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

fun playAudio(context: Context, url: String) {
    if (url.isEmpty() || url.startsWith("ipa_audio_")) return
    try {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mediaPlayer.setDataSource(url)
        mediaPlayer.prepareAsync()
        mediaPlayer.setOnPreparedListener { mp ->
            try {
                mp.start()
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Failed to start playback", e)
                mp.release()
            }
        }
        mediaPlayer.setOnCompletionListener { mp -> mp.release() }
        mediaPlayer.setOnErrorListener { mp, _, _ ->
            mp.release()
            true
        }
    } catch (e: Exception) {
        Log.e("AudioPlayer", "Error: ${e.message}", e)
    }
}
