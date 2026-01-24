package mihon.domain.ocr.exception

sealed class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InitializationError(cause: Throwable? = null) : OcrException("OCR engine failed to initialize", cause)
}
