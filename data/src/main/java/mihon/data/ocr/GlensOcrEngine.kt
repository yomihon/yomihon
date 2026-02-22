package mihon.data.ocr

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import kotlin.random.Random

/**
 * OCR engine backed by Google Lens online OCR.
 * Extracts plain text from text-layout boxes in the protobuf response.
 */
internal class GlensOcrEngine : OcrEngine {
    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.IO) {
        require(!image.isRecycled) { "Input bitmap is recycled" }

        val startTime = System.nanoTime()
        try {
            val preparedImage = prepareImage(image)
            val payload = buildRequestPayload(preparedImage)
            val responseBytes = executeRequest(payload)
            val extractedText = parseResponseText(responseBytes)

            val totalTime = (System.nanoTime() - startTime) / 1_000_000
            logcat(LogPriority.INFO) { "OCR(glens) Runtime: recognizeText total time: $totalTime ms" }
            extractedText
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OCR (glens) failed" }
            throw e
        }
    }

    override fun close() = Unit

    private fun prepareImage(image: Bitmap): PreparedImage {
        val maxDim = maxOf(image.width, image.height)
        val resized = if (maxDim > MAX_IMAGE_DIMENSION) {
            val scaleFactor = MAX_IMAGE_DIMENSION.toFloat() / maxDim.toFloat()
            val targetWidth = (image.width * scaleFactor).toInt().coerceAtLeast(1)
            val targetHeight = (image.height * scaleFactor).toInt().coerceAtLeast(1)
            image.scale(targetWidth, targetHeight, filter = true)
        } else {
            null
        }

        val working = resized ?: image
        return try {
            val encoded = ByteArrayOutputStream()
            val success = working.compress(Bitmap.CompressFormat.PNG, 100, encoded)
            if (!success) {
                throw IOException("Failed to encode image for GLens request")
            }

            PreparedImage(
                bytes = encoded.toByteArray(),
                width = working.width,
                height = working.height,
            )
        } finally {
            resized?.recycle()
        }
    }

    private fun buildRequestPayload(image: PreparedImage): ByteArray {
        val requestId = Random.nextLong()
        val timeZone = TimeZone.getDefault().id.ifBlank { DEFAULT_CLIENT_TIME_ZONE }

        return ProtoWriter().apply {
            writeMessage(fieldNumber = SERVER_REQUEST_OBJECTS_REQUEST) { objectsRequest ->
                objectsRequest.writeMessage(fieldNumber = OBJECTS_REQUEST_CONTEXT) { requestContext ->
                    requestContext.writeMessage(fieldNumber = REQUEST_CONTEXT_REQUEST_ID) { requestIdMessage ->
                        requestIdMessage.writeUInt64(fieldNumber = REQUEST_ID_UUID, value = requestId)
                        requestIdMessage.writeInt32(fieldNumber = REQUEST_ID_SEQUENCE_ID, value = 1)
                        requestIdMessage.writeInt32(fieldNumber = REQUEST_ID_IMAGE_SEQUENCE_ID, value = 1)
                    }
                    requestContext.writeMessage(fieldNumber = REQUEST_CONTEXT_CLIENT_CONTEXT) { clientContext ->
                        clientContext.writeInt32(fieldNumber = CLIENT_CONTEXT_PLATFORM, value = PLATFORM_WEB)
                        clientContext.writeInt32(fieldNumber = CLIENT_CONTEXT_SURFACE, value = SURFACE_CHROMIUM)
                        clientContext.writeMessage(fieldNumber = CLIENT_CONTEXT_LOCALE_CONTEXT) { localeContext ->
                            localeContext.writeString(fieldNumber = LOCALE_LANGUAGE, value = DEFAULT_CLIENT_LANGUAGE)
                            localeContext.writeString(fieldNumber = LOCALE_REGION, value = DEFAULT_CLIENT_REGION)
                            localeContext.writeString(fieldNumber = LOCALE_TIME_ZONE, value = timeZone)
                        }
                    }
                }

                objectsRequest.writeMessage(fieldNumber = OBJECTS_REQUEST_IMAGE_DATA) { imageData ->
                    imageData.writeMessage(fieldNumber = IMAGE_DATA_PAYLOAD) { payload ->
                        payload.writeBytes(fieldNumber = IMAGE_PAYLOAD_BYTES, value = image.bytes)
                    }
                    imageData.writeMessage(fieldNumber = IMAGE_DATA_METADATA) { metadata ->
                        metadata.writeInt32(fieldNumber = IMAGE_METADATA_WIDTH, value = image.width)
                        metadata.writeInt32(fieldNumber = IMAGE_METADATA_HEIGHT, value = image.height)
                    }
                }
            }
        }.toByteArray()
    }

    private fun executeRequest(payload: ByteArray): ByteArray {
        val connection = (URL(LENS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", CONTENT_TYPE_PROTOBUF)
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("X-Goog-Api-Key", API_KEY)
            setRequestProperty("Connection", "keep-alive")
        }

        return try {
            connection.outputStream.use { output ->
                output.write(payload)
            }

            val statusCode = connection.responseCode
            val responseBytes = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { input -> input.readBytes() }
                ?: ByteArray(0)

            if (statusCode !in 200..299) {
                val bodyPreview = responseBytes.toString(Charsets.UTF_8).take(256)
                throw IOException("GLens request failed with HTTP $statusCode: $bodyPreview")
            }

            responseBytes
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponseText(responseBytes: ByteArray): String {
        val reader = ProtoReader(responseBytes)
        val paragraphs = mutableListOf<String>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == SERVER_RESPONSE_OBJECTS_RESPONSE && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                paragraphs += parseObjectsResponse(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        return paragraphs
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            // collapse into a single line for viewing and format consistency between engines
            .joinToString(separator = "")
    }

    private fun parseObjectsResponse(objectsBytes: ByteArray): List<String> {
        val reader = ProtoReader(objectsBytes)
        val paragraphs = mutableListOf<String>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == OBJECTS_RESPONSE_TEXT && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                paragraphs += parseText(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        return paragraphs
    }

    private fun parseText(textBytes: ByteArray): List<String> {
        val reader = ProtoReader(textBytes)
        val paragraphs = mutableListOf<String>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == TEXT_LAYOUT && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                paragraphs += parseTextLayout(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        return paragraphs
    }

    private fun parseTextLayout(layoutBytes: ByteArray): List<String> {
        val reader = ProtoReader(layoutBytes)
        val paragraphs = mutableListOf<String>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == TEXT_LAYOUT_PARAGRAPH && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                val paragraphText = parseParagraph(reader.readBytes())
                if (paragraphText.isNotBlank()) {
                    paragraphs += paragraphText
                }
            } else {
                reader.skipField(wireType)
            }
        }

        return paragraphs
    }

    private fun parseParagraph(paragraphBytes: ByteArray): String {
        val reader = ProtoReader(paragraphBytes)
        val lines = mutableListOf<String>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == PARAGRAPH_LINE && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                val lineText = parseLine(reader.readBytes())
                if (lineText.isNotBlank()) {
                    lines += lineText
                }
            } else {
                reader.skipField(wireType)
            }
        }

        return lines.joinToString(separator = "\n")
    }

    private fun parseLine(lineBytes: ByteArray): String {
        val reader = ProtoReader(lineBytes)
        val builder = StringBuilder()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == LINE_WORD && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                builder.append(parseWord(reader.readBytes()))
            } else {
                reader.skipField(wireType)
            }
        }

        return builder.toString().trim()
    }

    private fun parseWord(wordBytes: ByteArray): String {
        val reader = ProtoReader(wordBytes)
        var text = ""
        var separator = ""

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            when (field) {
                WORD_PLAIN_TEXT if wireType == WIRE_TYPE_LENGTH_DELIMITED -> {
                    text = reader.readString()
                }
                WORD_SEPARATOR if wireType == WIRE_TYPE_LENGTH_DELIMITED -> {
                    separator = reader.readString()
                }
                else -> reader.skipField(wireType)
            }
        }

        return text + separator
    }

    private data class PreparedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PreparedImage

            if (width != other.width) return false
            if (height != other.height) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    companion object {
        private const val LENS_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
        private const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"
        private const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private const val DEFAULT_CLIENT_LANGUAGE = "en"
        private const val DEFAULT_CLIENT_REGION = "US"
        private const val DEFAULT_CLIENT_TIME_ZONE = "America/New_York"
        private const val MAX_IMAGE_DIMENSION = 1500

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000

        private const val WIRE_TYPE_MASK = 0x7
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2

        // Request fields
        private const val SERVER_REQUEST_OBJECTS_REQUEST = 1
        private const val OBJECTS_REQUEST_CONTEXT = 1
        private const val OBJECTS_REQUEST_IMAGE_DATA = 3
        private const val REQUEST_CONTEXT_REQUEST_ID = 3
        private const val REQUEST_CONTEXT_CLIENT_CONTEXT = 4
        private const val REQUEST_ID_UUID = 1
        private const val REQUEST_ID_SEQUENCE_ID = 2
        private const val REQUEST_ID_IMAGE_SEQUENCE_ID = 3
        private const val CLIENT_CONTEXT_PLATFORM = 1
        private const val CLIENT_CONTEXT_SURFACE = 2
        private const val CLIENT_CONTEXT_LOCALE_CONTEXT = 4
        private const val LOCALE_LANGUAGE = 1
        private const val LOCALE_REGION = 2
        private const val LOCALE_TIME_ZONE = 3
        private const val IMAGE_DATA_PAYLOAD = 1
        private const val IMAGE_DATA_METADATA = 3
        private const val IMAGE_PAYLOAD_BYTES = 1
        private const val IMAGE_METADATA_WIDTH = 1
        private const val IMAGE_METADATA_HEIGHT = 2
        private const val PLATFORM_WEB = 3
        private const val SURFACE_CHROMIUM = 4

        // Response fields
        private const val SERVER_RESPONSE_OBJECTS_RESPONSE = 2
        private const val OBJECTS_RESPONSE_TEXT = 3
        private const val TEXT_LAYOUT = 1
        private const val TEXT_LAYOUT_PARAGRAPH = 1
        private const val PARAGRAPH_LINE = 2
        private const val LINE_WORD = 1
        private const val WORD_PLAIN_TEXT = 2
        private const val WORD_SEPARATOR = 3
    }
}

private class ProtoWriter {
    private val output = ByteArrayOutputStream()

    fun writeInt32(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, wireType = 0)
        writeVarint32(value)
    }

    fun writeUInt64(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, wireType = 0)
        writeVarint64(value)
    }

    fun writeString(fieldNumber: Int, value: String) {
        writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        writeTag(fieldNumber, wireType = 2)
        writeVarint32(value.size)
        output.write(value)
    }

    fun writeMessage(fieldNumber: Int, block: (ProtoWriter) -> Unit) {
        val nested = ProtoWriter().apply(block).toByteArray()
        writeBytes(fieldNumber, nested)
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint32((fieldNumber shl 3) or wireType)
    }

    private fun writeVarint32(value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v and 0x7F)
    }

    private fun writeVarint64(value: Long) {
        var v = value
        while ((v and -0x80L) != 0L) {
            output.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        output.write((v and 0x7F).toInt())
    }
}

private class ProtoReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    fun hasRemaining(): Boolean = position < bytes.size

    fun readTag(): Int {
        if (!hasRemaining()) return 0
        return readVarint32()
    }

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun readBytes(): ByteArray {
        val length = readVarint32()
        if (length < 0 || position + length > bytes.size) {
            throw IOException("Invalid length-delimited field length: $length")
        }

        val value = bytes.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun skipField(wireType: Int) {
        when (wireType) {
            0 -> readVarint64()
            1 -> skipBytes(8)
            2 -> {
                val length = readVarint32()
                skipBytes(length)
            }

            5 -> skipBytes(4)
            else -> throw IOException("Unsupported protobuf wire type: $wireType")
        }
    }

    private fun skipBytes(count: Int) {
        if (count < 0 || position + count > bytes.size) {
            throw IOException("Invalid skip length: $count")
        }
        position += count
    }

    private fun readVarint32(): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint32")
            val b = bytes[position++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }

        // Read and discard extra bytes for malformed/int64 varints.
        repeat(5) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint32 overflow")
            if ((bytes[position++].toInt() and 0x80) == 0) return result
        }
        throw IOException("Malformed varint32")
    }

    private fun readVarint64(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint64")
            val b = bytes[position++].toLong() and 0xFFL
            result = result or ((b and 0x7FL) shl shift)
            if ((b and 0x80L) == 0L) return result
            shift += 7
        }
        throw IOException("Malformed varint64")
    }
}
