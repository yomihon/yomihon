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

internal suspend fun decodeOcrBitmapWithFallback(
    decodeAttempt: suspend ((InputStream) -> Bitmap?) -> Bitmap?,
): Bitmap? {
    return decodeAttempt(::decodeImageDecoderBitmap)
        ?: decodeAttempt(::decodeArchiveBitmap)
}

internal suspend fun decodeOcrRegionWithFallback(
    sourceRect: Rect,
    decodeAttempt: suspend ((InputStream) -> Bitmap?) -> Bitmap?,
): Bitmap? {
    return decodeAttempt { stream -> decodeImageDecoderBitmapRegion(stream, sourceRect) }
        ?: decodeAttempt { stream -> decodeArchiveBitmapRegion(stream, sourceRect) }
}

internal fun buildStreamDecoderOcrInput(
    pageIndex: Int,
    openStream: () -> InputStream?,
): OcrPageInput {
    return buildSuspendStreamDecoderOcrInput(
        pageIndex = pageIndex,
        openStream = { openStream() },
    )
}

internal fun buildSuspendStreamDecoderOcrInput(
    pageIndex: Int,
    openStream: suspend () -> InputStream?,
): OcrPageInput {
    return OcrPageInput(
        pageIndex = pageIndex,
        openBitmap = {
            withIOContext {
                decodeOcrBitmapWithFallback { decode ->
                    openStream()?.use(decode)
                }
            }
        },
        openBitmapRegion = { sourceRect ->
            withIOContext {
                decodeOcrRegionWithFallback(sourceRect) { decode ->
                    openStream()?.use(decode)
                }
            }
        },
    )
}

internal fun buildArchiveDecoderOcrInput(
    pageIndex: Int,
    openStream: () -> InputStream?,
): OcrPageInput {
    return buildStreamDecoderOcrInput(
        pageIndex = pageIndex,
        openStream = openStream,
    )
}

internal fun buildBufferedDecoderOcrInput(
    pageIndex: Int,
    source: BufferedSource,
): OcrPageInput {
    return OcrPageInput(
        pageIndex = pageIndex,
        openBitmap = {
            withIOContext {
                decodeOcrBitmapWithFallback { decode ->
                    source.peek().inputStream().use(decode)
                }
            }
        },
        openBitmapRegion = { sourceRect ->
            withIOContext {
                decodeOcrRegionWithFallback(sourceRect) { decode ->
                    source.peek().inputStream().use(decode)
                }
            }
        },
    )
}
