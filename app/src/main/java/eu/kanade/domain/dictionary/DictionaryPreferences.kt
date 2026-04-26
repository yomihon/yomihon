package eu.kanade.domain.dictionary

import mihon.domain.dictionary.interactor.ParserLanguage
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

enum class OcrResultPresentation {
    SHEET,
    POPUP,
}

class DictionaryPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /**
     * When set to anything other than [ParserLanguage.AUTO] every search call will use
     * that specific parse pipeline regardless of what script the query text contains.
     */
    fun parserLanguageOverride() = preferenceStore.getEnum(
        key = "pref_dictionary_parser_language_override",
        defaultValue = ParserLanguage.AUTO,
    )

    fun ocrResultPresentation() = preferenceStore.getEnum(
        key = "pref_dictionary_ocr_result_presentation",
        defaultValue = OcrResultPresentation.POPUP,
    )

    fun ocrResultPopupWidthDp() = preferenceStore.getInt(
        key = "pref_dictionary_ocr_result_popup_width_dp",
        defaultValue = 320,
    )

    fun ocrResultPopupHeightDp() = preferenceStore.getInt(
        key = "pref_dictionary_ocr_result_popup_height_dp",
        defaultValue = 350,
    )

    fun ocrResultPopupScalePercent() = preferenceStore.getInt(
        key = "pref_dictionary_ocr_result_popup_scale_percent",
        defaultValue = 95,
    )

    fun ocrResultDimBackground() = preferenceStore.getBoolean(
        key = "pref_dictionary_ocr_result_dim_background",
        defaultValue = false,
    )
}
