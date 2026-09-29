package com.teameow.teawords.data

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface TranslationProvider {
    suspend fun translate(text: String, source: String, target: String, onProgress: (TranslationProgress) -> Unit = {}): String
    suspend fun isAvailable(): Boolean
    suspend fun prepare()
    fun release()
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

class DeviceTranslationProvider : TranslationProvider {
    private val manager = RemoteModelManager.getInstance()
    private val chinese = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
    private var translator: Translator? = null
    private var direction = ""
    override suspend fun isAvailable(): Boolean = manager.isModelDownloaded(chinese).awaitResult()
    override suspend fun prepare() { manager.download(chinese, DownloadConditions.Builder().requireWifi().build()).awaitResult() }
    override suspend fun translate(text: String, source: String, target: String, onProgress: (TranslationProgress) -> Unit): String {
        require(source != target && source in listOf("en", "zh") && target in listOf("en", "zh"))
        check(isAvailable()) { "请先下载中英翻译模型" }
        if (direction != "$source-$target") {
            release()
            translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
            direction = "$source-$target"
        }
        return translator!!.translate(text).awaitResult().also { onProgress(TranslationProgress(1, 1, it)) }
    }
    suspend fun deleteModel() { release(); manager.deleteDownloadedModel(chinese).awaitResult() }
    override fun release() { translator?.close(); translator = null; direction = "" }
}
