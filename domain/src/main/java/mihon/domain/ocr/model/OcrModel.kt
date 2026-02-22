package mihon.domain.ocr.model

/**
 * Represents the available OCR models.
 */
enum class OcrModel {
    /**
     * Legacy and slower model, supports GPU/CPU.
     */
    LEGACY,

    /**
     * Faster model designed for ARM CPU.
     */
    FAST,

    /**
     * Online Google Lens OCR model.
     */
    GLENS,
}
