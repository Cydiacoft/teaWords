package com.teameow.teawords.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps the chosen picture in app storage so it survives restarts and picker permission changes. */
object CustomWallpaperStore {
    private const val MAX_BYTES = 30L * 1024L * 1024L

    suspend fun import(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val imageStream = resolver.openInputStream(source) ?: error("无法打开图片")
        imageStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是可用的图片" }

        val destination = File.createTempFile("home-wallpaper-", ".img", context.filesDir)
        try {
            resolver.openInputStream(source)?.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= MAX_BYTES) { "图片超过 30 MB，请选择较小的图片" }
                        output.write(buffer, 0, read)
                    }
                    require(copied > 0) { "图片为空" }
                }
            } ?: error("无法读取图片")
            Uri.fromFile(destination).toString()
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun remove(context: Context, savedUri: String?) {
        val uri = savedUri?.let(Uri::parse) ?: return
        if (uri.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        if (file.parentFile?.canonicalFile == context.filesDir.canonicalFile &&
            file.name.startsWith("home-wallpaper-")) file.delete()
    }
}
