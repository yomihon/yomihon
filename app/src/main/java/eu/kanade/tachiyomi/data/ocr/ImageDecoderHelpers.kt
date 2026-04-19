package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import okio.BufferedSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.decoder.ImageDecoder
import java.io.InputStream

internal data class ImageDecoderBounds(
    val width: Int,
    val height: Int,
)

internal fun decodeImageDecoderBounds(stream: InputStream): ImageDecoderBounds? {
    val decoder = ImageDecoder.newInstance(stream) ?: return null
    return try {
        ImageDecoderBounds(
            width = decoder.width,
            height = decoder.height,
        )
    } catch (_: IllegalStateException) {
        null
    } finally {
        decoder.recycle()
    }
}

internal fun decodeImageDecoderBitmap(
    stream: InputStream,
    sampleSize: Int = 1,
): Bitmap? {
    val decoder = ImageDecoder.newInstance(stream) ?: return null
    return try {
        decoder.decode(sampleSize = sampleSize)
    } catch (_: IllegalStateException) {
        null
    } finally {
        decoder.recycle()
    }
}

internal fun decodeImageDecoderBitmapRegion(
    stream: InputStream,
    region: Rect,
    sampleSize: Int = 1,
): Bitmap? {
    val decoder = ImageDecoder.newInstance(stream) ?: return null
    return try {
        val boundedRegion = Rect(region)
        val intersects = boundedRegion.intersect(0, 0, decoder.width, decoder.height)
        if (!intersects || boundedRegion.width() <= 0 || boundedRegion.height() <= 0) {
            null
        } else {
            decoder.decode(region = boundedRegion, sampleSize = sampleSize)
        }
    } catch (_: IllegalStateException) {
        null
    } finally {
        decoder.recycle()
    }
}

internal fun buildImageDecoderStreamOcrPageInput(
    pageIndex: Int,
    openStream: () -> InputStream?,
): OcrPageInput {
    return OcrPageInput(
        pageIndex = pageIndex,
        openBitmap = {
            withIOContext {
                openStream()?.use(::decodeImageDecoderBitmap)
                    ?: openStream()?.use(::decodeArchiveBitmap)
            }
        },
        openBitmapRegion = { sourceRect ->
            withIOContext {
                openStream()?.use { stream ->
                    decodeImageDecoderBitmapRegion(stream, sourceRect)
                } ?: openStream()?.use { stream ->
                    decodeArchiveBitmapRegion(stream, sourceRect)
                }
            }
        },
    )
}

internal fun buildImageDecoderArchiveStreamOcrPageInput(
    pageIndex: Int,
    openStream: () -> InputStream?,
): OcrPageInput {
    return buildImageDecoderStreamOcrPageInput(
        pageIndex = pageIndex,
        openStream = openStream,
    )
}

internal fun buildImageDecoderBufferedOcrPageInput(
    pageIndex: Int,
    source: BufferedSource,
): OcrPageInput {
    return OcrPageInput(
        pageIndex = pageIndex,
        openBitmap = {
            withIOContext {
                source.peek().inputStream().use(::decodeImageDecoderBitmap)
                    ?: source.peek().inputStream().use(::decodeArchiveBitmap)
            }
        },
        openBitmapRegion = { sourceRect ->
            withIOContext {
                source.peek().inputStream().use { stream ->
                    decodeImageDecoderBitmapRegion(stream, sourceRect)
                } ?: source.peek().inputStream().use { stream ->
                    decodeArchiveBitmapRegion(stream, sourceRect)
                }
            }
        },
    )
}
