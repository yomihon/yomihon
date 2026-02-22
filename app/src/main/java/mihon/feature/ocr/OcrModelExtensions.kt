package mihon.feature.ocr

import dev.icerock.moko.resources.StringResource
import mihon.domain.ocr.model.OcrModel
import tachiyomi.i18n.MR

val OcrModel.titleRes: StringResource
    get() = when (this) {
        OcrModel.LEGACY -> MR.strings.ocr_model_legacy
        OcrModel.FAST -> MR.strings.ocr_model_fast
        OcrModel.GLENS -> MR.strings.ocr_model_glens
    }
