package mihon.domain.ocr.exception

sealed class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InitializationError(cause: Throwable? = null) : OcrException("OCR engine failed to initialize", cause)
    class DetectionUnavailable(cause: Throwable? = null) : OcrException("OCR detection model is unavailable", cause)
    class ConnectionError(cause: Throwable? = null) : OcrException("Failed to process: unable to connect", cause)
}
