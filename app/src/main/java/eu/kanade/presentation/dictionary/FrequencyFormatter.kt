package eu.kanade.presentation.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.TermMetaMode
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority

/**
 * Parsed frequency data from a dictionary term meta entry
 */
data class FrequencyData(
    val reading: String,
    val frequency: String,
    val numericFrequency: Int?,
    val dictionaryId: Long,
)

/**
 * Grouped frequency data for display, with multiple frequencies from the same dictionary combined
 */
data class GroupedFrequencyData(
    val frequencies: String,
    val dictionaryId: Long,
)

object FrequencyFormatter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse frequency data from term meta entries.
     */
    fun parseFrequencies(termMetaList: List<DictionaryTermMeta>): List<FrequencyData> {
        val frequencies = termMetaList
            .filter { it.mode == TermMetaMode.FREQUENCY }
            .mapNotNull { parseFrequency(it) }

        // Return all frequencies sorted by numeric value (lowest first)
        return frequencies.sortedBy { it.numericFrequency ?: Int.MAX_VALUE }
    }

    /**
     * Parse and group frequency data by dictionary.
     * Frequencies from the same dictionary are combined with "|" separator.
     */
    fun parseGroupedFrequencies(termMetaList: List<DictionaryTermMeta>): List<GroupedFrequencyData> {
        val frequencies = parseFrequencies(termMetaList)

        // Group by dictionary ID and combine frequencies
        return frequencies
            .groupBy { it.dictionaryId }
            .map { (dictionaryId, freqList) ->
                val combinedFrequencies = freqList
                    .map { it.frequency }
                    .distinct()
                    .joinToString(" | ")
                val minNumeric = freqList.mapNotNull { it.numericFrequency }.minOrNull()
                GroupedFrequencyData(
                    frequencies = combinedFrequencies,
                    dictionaryId = dictionaryId,
                ) to minNumeric
            }
            .sortedBy { it.second ?: Int.MAX_VALUE }
            .map { it.first }
    }

    /**
     * Parse a single frequency entry.
     */
    private fun parseFrequency(termMeta: DictionaryTermMeta): FrequencyData? {
        return try {
            val element = json.parseToJsonElement(termMeta.data)

            when {
                element is JsonPrimitive && element.isString.not() -> {
                    val freq = element.intOrNull ?: return null
                    FrequencyData(
                        reading = "",
                        frequency = freq.toString(),
                        numericFrequency = freq,
                        dictionaryId = termMeta.dictionaryId,
                    )
                }

                element is JsonPrimitive && element.isString -> {
                    val freqStr = element.content
                    FrequencyData(
                        reading = "",
                        frequency = freqStr,
                        numericFrequency = null,
                        dictionaryId = termMeta.dictionaryId,
                    )
                }

                element is JsonObject -> {
                    parseFrequencyObject(element, termMeta.dictionaryId)
                }

                else -> null
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to parse frequency data: ${termMeta.data}" }
            null
        }
    }

    private fun parseFrequencyObject(obj: JsonObject, dictionaryId: Long): FrequencyData? {
        val reading = obj["reading"]?.jsonPrimitive?.content ?: ""
        val frequencyElement = obj["frequency"]

        return when {
            // Structure with a nested frequency object
            frequencyElement is JsonObject -> {
                val (displayValue, numericValue) = extractFrequencyFromObject(frequencyElement)
                FrequencyData(
                    reading = reading,
                    frequency = displayValue,
                    numericFrequency = numericValue,
                    dictionaryId = dictionaryId,
                )
            }

            // Structure with a simple frequency number
            frequencyElement is JsonPrimitive -> {
                val freq = frequencyElement.intOrNull ?: frequencyElement.content.toIntOrNull()
                FrequencyData(
                    reading = reading,
                    frequency = freq?.toString() ?: frequencyElement.content,
                    numericFrequency = freq,
                    dictionaryId = dictionaryId,
                )
            }

            // Structure with a value/displayValue
            obj.containsKey("value") || obj.containsKey("displayValue") -> {
                val (displayValue, numericValue) = extractFrequencyFromObject(obj)
                FrequencyData(
                    reading = reading,
                    frequency = displayValue,
                    numericFrequency = numericValue,
                    dictionaryId = dictionaryId,
                )
            }

            else -> null
        }
    }

    /**
     * Extract frequency display and numeric values from an object with "value" and/or "displayValue"
     */
    private fun extractFrequencyFromObject(obj: JsonObject): Pair<String, Int?> {
        val displayValue = obj["displayValue"]?.jsonPrimitive?.content
        val numericValue = obj["value"]?.jsonPrimitive?.intOrNull

        // Prefer displayValue if it exists, otherwise use numeric value
        val display = displayValue ?: numericValue?.toString() ?: "Unknown"

        return Pair(display, numericValue)
    }
}
