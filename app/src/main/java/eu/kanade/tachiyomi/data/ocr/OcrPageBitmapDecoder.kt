package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import tachiyomi.core.common.util.system.ImageUtil
import java.io.ByteArrayInputStream
import java.io.InputStream

internal fun decodeArchiveBitmap(stream: InputStream): Bitmap? {
    val bytes = stream.readBytes()
    if (bytes.isEmpty()) {
        return null
    }

    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        bitmapFactoryOptions(),
    )
}

internal fun decodeArchiveBitmapRegion(
    stream: InputStream,
    sourceRect: Rect,
): Bitmap? {
    return try {
        val bytes = stream.readBytes()
        if (bytes.isEmpty()) {
            return null
        }

        val decoder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }
        decoder.decodeBitmapRegion(sourceRect)
    } catch (_: Exception) {
        null
    }
}

internal suspend fun OcrPageInput.openCroppedBitmap(sourceRect: Rect): Bitmap? {
    openBitmapRegion(sourceRect)?.let { return it }

    val fullBitmap = openBitmap() ?: return null
    return try {
        cropDecodedBitmap(fullBitmap, sourceRect)
    } catch (_: Exception) {
        if (!fullBitmap.isRecycled) {
            fullBitmap.recycle()
        }
        null
    }
}

internal fun isArchiveImageEntry(
    name: String,
    stream: InputStream,
): Boolean {
    if (hasKnownImageExtension(name)) {
        return true
    }

    val header = ByteArray(32)
    val length = stream.read(header)
    if (length <= 0) {
        return false
    }

    return ImageUtil.findImageType(ByteArrayInputStream(header, 0, length)) != null
}

private fun hasKnownImageExtension(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension == "jpeg" || ImageUtil.ImageType.entries.any { it.extension == extension }
}

private fun bitmapFactoryOptions(): BitmapFactory.Options {
    return BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
}

private fun cropDecodedBitmap(
    fullBitmap: Bitmap,
    sourceRect: Rect,
): Bitmap {
    var keepFullBitmap = false
    try {
        val safeRect = Rect(
            sourceRect.left.coerceIn(0, fullBitmap.width),
            sourceRect.top.coerceIn(0, fullBitmap.height),
            sourceRect.right.coerceIn(0, fullBitmap.width),
            sourceRect.bottom.coerceIn(0, fullBitmap.height),
        )
        if (safeRect.width() <= 0 || safeRect.height() <= 0) {
            throw IllegalStateException("Invalid crop rectangle")
        }

        val croppedBitmap = Bitmap.createBitmap(
            fullBitmap,
            safeRect.left,
            safeRect.top,
            safeRect.width(),
            safeRect.height(),
        )
        if (croppedBitmap === fullBitmap) {
            keepFullBitmap = true
        }
        return croppedBitmap
    } finally {
        if (!keepFullBitmap && !fullBitmap.isRecycled) {
            fullBitmap.recycle()
        }
    }
}

private fun BitmapRegionDecoder.decodeBitmapRegion(sourceRect: Rect): Bitmap? {
    return try {
        val safeRect = Rect(
            sourceRect.left.coerceIn(0, width),
            sourceRect.top.coerceIn(0, height),
            sourceRect.right.coerceIn(0, width),
            sourceRect.bottom.coerceIn(0, height),
        )
        if (safeRect.width() <= 0 || safeRect.height() <= 0) {
            null
        } else {
            decodeRegion(safeRect, bitmapFactoryOptions())
        }
    } finally {
        recycle()
    }
}
