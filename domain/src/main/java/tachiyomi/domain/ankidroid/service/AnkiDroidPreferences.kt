package tachiyomi.domain.ankidroid.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class AnkiDroidPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // region Deck & Model Selection

    fun deckId() = preferenceStore.getLong("ankidroid_deck_id", -1L)

    fun modelId() = preferenceStore.getLong("ankidroid_model_id", -1L)

    fun deckName() = preferenceStore.getString("ankidroid_deck_name", "Yomihon")

    fun modelName() = preferenceStore.getString("ankidroid_model_name", "app.yomihon.dictionary")

    // endregion

    // region Field Mappings

    fun fieldReading() = preferenceStore.getString("ankidroid_field_reading", "{reading}")

    fun fieldExpression() = preferenceStore.getString("ankidroid_field_expression", "{expression}")

    fun fieldGlossary() = preferenceStore.getString("ankidroid_field_glossary", "{glossary}")

    fun fieldSentence() = preferenceStore.getString("ankidroid_field_sentence", "{sentence}")

    fun fieldPitchAccent() = preferenceStore.getString("ankidroid_field_pitch_accent", "{pitchAccent}")

    fun fieldFrequency() = preferenceStore.getString("ankidroid_field_frequency", "{frequency}")

    fun fieldPicture() = preferenceStore.getString("ankidroid_field_picture", "{picture}")

    fun fieldMappings(): Preference<Map<String, String>> = preferenceStore.getObjectFromString(
        key = "ankidroid_field_mappings",
        defaultValue = DEFAULT_FIELD_MAPPINGS,
        serializer = { map ->
            map.entries.joinToString(ENTRY_SEPARATOR) { (key, value) ->
                "$key$KEY_VALUE_SEPARATOR$value"
            }
        },
        deserializer = { serialized ->
            if (serialized.isEmpty()) {
                DEFAULT_FIELD_MAPPINGS
            } else {
                serialized.split(ENTRY_SEPARATOR)
                    .mapNotNull { entry ->
                        val parts = entry.split(KEY_VALUE_SEPARATOR, limit = 2)
                        if (parts.size == 2) {
                            parts[0] to parts[1]
                        } else {
                            null
                        }
                    }
                    .toMap()
            }
        },
    )

    // endregion

    companion object {
        private const val ENTRY_SEPARATOR = "||"
        private const val KEY_VALUE_SEPARATOR = "::"

        val DEFAULT_FIELD_MAPPINGS = mapOf(
            "reading" to "{reading}",
            "expression" to "{expression}",
            "glossary" to "{glossary}",
            "sentence" to "{sentence}",
            "pitchAccent" to "{pitchAccent}",
            "frequency" to "{frequency}",
            "picture" to "{picture}",
        )
    }
}
