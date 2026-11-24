package com.houxinzhe.pib.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.max

data class DraftAttachment(
    val name: String,
    val isImage: Boolean,
    val textContent: String? = null,
    val imageDataUrl: String? = null,
)

object AttachmentLoader {
    const val MAX_TEXT_BYTES = 1_000_000

    suspend fun loadImage(context: Context, uri: Uri): DraftAttachment = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("照片读取失败，请重新选择。")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("照片读取失败，请重新选择。")
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 3200) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("照片读取失败，请重新选择。")
        val maxSide = max(decoded.width, decoded.height)
        val scaled = if (maxSide > 1600) {
            val ratio = 1600f / maxSide
            Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true)
        } else decoded
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        DraftAttachment(displayName(context, uri) ?: "照片.jpg", isImage = true, imageDataUrl = "data:image/jpeg;base64,$b64")
    }

    suspend fun loadText(context: Context, uri: Uri): DraftAttachment = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_TEXT_BYTES) throw IllegalArgumentException("请选用 1 MB 以内的 UTF-8 文本或代码文件。")
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        } ?: throw IllegalArgumentException("请选用 1 MB 以内的 UTF-8 文本或代码文件。")
        val text = decodeStrictUtf8(bytes)
        DraftAttachment(displayName(context, uri) ?: "附件.txt", isImage = false, textContent = text)
    }

    /** Pure validation helper, also covered by unit tests. */
    fun decodeStrictUtf8(bytes: ByteArray, maxBytes: Int = MAX_TEXT_BYTES): String {
        if (bytes.size > maxBytes || bytes.any { it == 0.toByte() }) {
            throw IllegalArgumentException("请选用 1 MB 以内的 UTF-8 文本或代码文件。")
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("请选用 1 MB 以内的 UTF-8 文本或代码文件。")
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
