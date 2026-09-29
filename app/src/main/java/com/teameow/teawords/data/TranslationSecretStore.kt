package com.teameow.teawords.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private, encrypted, and excluded from Android backup. Never expose the key in UI state or logs. */
class TranslationSecretStore(context: Context, fileName: String = "translation-secret") {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    fun configured() = file.baseFile.exists()
    fun clear() { file.delete() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun read(): String {
        if (!configured()) return ""
        return try {
            val pieces = String(file.readFully(), Charsets.UTF_8).split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("DeepSeek 密钥无法读取，请在翻译设置中重新填写。") }
    }
    fun save(value: String) {
        if (value.isBlank()) { clear(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write((Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ':' + Base64.encodeToString(encrypted, Base64.NO_WRAP)).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
    private companion object { const val ALIAS = "teawords.translation.api-key" }
}
