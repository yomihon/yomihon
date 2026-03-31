package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import tachiyomi.core.common.util.system.ImageUtil
import java.io.ByteArrayInputStream
import java.io.InputStream

internal fun decodeBitmap(stream: InputStream): Bitmap? {
    return BitmapFactory.decodeStream(
        stream,
        null,
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

internal fun decodeArchiveBitmap(stream: InputStream): Bitmap? {
    val bytes = stream.readBytes()
    if (bytes.isEmpty()) {
        return null
    }

    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
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
