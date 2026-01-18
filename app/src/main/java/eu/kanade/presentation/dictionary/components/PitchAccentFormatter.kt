package eu.kanade.presentation.dictionary.components

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.TermMetaMode
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority

/**
 * Represents pitch level for a mora.
 */
enum class PitchLevel {
    HIGH,
    LOW,
}

/**
 * Represents a single mora with its pitch and special attributes.
 */
data class MoraPitch(
    val mora: String,
    val pitch: PitchLevel,
    val isNasal: Boolean = false,
    val isDevoiced: Boolean = false,
)

/**
 * Represents a single pitch accent pattern for a word.
 */
data class PitchPattern(
    val morae: List<MoraPitch>,
    val tags: List<String> = emptyList(),
    val particlePitch: PitchLevel = PitchLevel.LOW,
)

/**
 * Represents pitch accent data for a term/reading combination.
 */
data class PitchAccentData(
    val reading: String,
    val patterns: List<PitchPattern>,
    val dictionaryId: Long,
)

internal object PitchAccentFormatter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Small kana that combine with preceding mora
    private val SMALL_KANA = setOf(
        'ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ',
        'ャ', 'ュ', 'ョ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ',
        'ゎ', 'ヮ',
    )

    /**
     * Parse pitch accent data from term meta entries.
     */
    fun parsePitchAccents(termMetaList: List<DictionaryTermMeta>): List<PitchAccentData> {
        return termMetaList
            .filter { it.mode == TermMetaMode.PITCH }
            .mapNotNull { parsePitchAccent(it) }
    }

    /**
     * Parse a single pitch accent entry.
     */
    private fun parsePitchAccent(termMeta: DictionaryTermMeta): PitchAccentData? {
        return try {
            val element = json.parseToJsonElement(termMeta.data)

            if (element !is JsonObject) return null

            val reading = element["reading"]?.jsonPrimitive?.content ?: return null
            val pitchesArray = element["pitches"]?.jsonArray ?: return null

            val patterns = pitchesArray.mapNotNull { pitchElement ->
                parsePitchPattern(pitchElement.jsonObject, reading)
            }

            if (patterns.isEmpty()) return null

            PitchAccentData(
                reading = reading,
                patterns = patterns,
                dictionaryId = termMeta.dictionaryId,
            )
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to parse pitch accent data: ${termMeta.data}" }
            null
        }
    }

    /**
     * Parse a single pitch pattern from the pitches array.
     */
    private fun parsePitchPattern(obj: JsonObject, reading: String): PitchPattern? {
        val positionElement = obj["position"] ?: return null

        val nasalPositions = parsePositionList(obj["nasal"])
        val devoicedPositions = parsePositionList(obj["devoice"])

        val tags = obj["tags"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.content
        } ?: emptyList()

        val morae = splitToMorae(reading)
        if (morae.isEmpty()) return null

        // Determine pitch levels based on position format
        val (pitchLevels, particlePitch) = when (positionElement) {
            is JsonPrimitive -> {
                if (positionElement.isString) {
                    // HL string format (e.g., "LHHLL")
                    parseHLString(positionElement.content, morae.size) to PitchLevel.LOW
                } else {
                    // Integer downstep position
                    val downstepPosition = positionElement.intOrNull ?: return null
                    calculatePitchLevelsFromDownstep(downstepPosition, morae.size)
                }
            }
            else -> return null
        }

        if (pitchLevels.size != morae.size) return null

        // Build mora pitch list with attributes
        val moraPitches = morae.mapIndexed { index, mora ->
            MoraPitch(
                mora = mora,
                pitch = pitchLevels[index],
                isNasal = (index + 1) in nasalPositions, // positions are 1-based
                isDevoiced = (index + 1) in devoicedPositions,
            )
        }

        return PitchPattern(
            morae = moraPitches,
            tags = tags,
            particlePitch = particlePitch,
        )
    }

    /**
     * Parse position list from nasal/devoice field.
     * Can be an integer or array of integers.
     */
    private fun parsePositionList(element: kotlinx.serialization.json.JsonElement?): Set<Int> {
        if (element == null) return emptySet()

        return when (element) {
            is JsonPrimitive -> {
                val pos = element.intOrNull
                if (pos != null) setOf(pos) else emptySet()
            }
            is JsonArray -> {
                element.mapNotNull { it.jsonPrimitive.intOrNull }.toSet()
            }
            else -> emptySet()
        }
    }

    /**
     * Parse HL string format (e.g., "LHHLL") into pitch levels.
     */
    private fun parseHLString(hlString: String, moraCount: Int): List<PitchLevel> {
        val pattern = hlString.uppercase()
        return (0 until moraCount).map { index ->
            if (index < pattern.length && pattern[index] == 'H') {
                PitchLevel.HIGH
            } else {
                PitchLevel.LOW
            }
        }
    }

    /**
     * Calculate pitch levels from downstep position.
     *
     * Japanese pitch accent rules:
     * - Position 0 (heiban/flat): First mora is LOW, all subsequent are HIGH. Particle is HIGH.
     * - Position 1 (atamadaka): First mora is HIGH, all subsequent are LOW. Particle is LOW.
     * - Position N (nakadaka/odaka): First mora is LOW, morae 2 to N are HIGH, rest are LOW.
     *   If N == moraCount (odaka), particle is LOW.
     */
    private fun calculatePitchLevelsFromDownstep(downstep: Int, moraCount: Int): Pair<List<PitchLevel>, PitchLevel> {
        if (moraCount == 0) return emptyList<PitchLevel>() to PitchLevel.LOW

        val moraePitches = (1..moraCount).map { position ->
            when {
                // Heiban (flat) - no downstep: L H H H H ...
                downstep == 0 -> {
                    if (position == 1) PitchLevel.LOW else PitchLevel.HIGH
                }
                // Atamadaka - downstep after first: H L L L L ...
                downstep == 1 -> {
                    if (position == 1) PitchLevel.HIGH else PitchLevel.LOW
                }
                // Nakadaka/Odaka - downstep after position N: L H H ... H L L ...
                else -> {
                    when {
                        position == 1 -> PitchLevel.LOW
                        position <= downstep -> PitchLevel.HIGH
                        else -> PitchLevel.LOW
                    }
                }
            }
        }

        val particlePitch = when {
            downstep == 0 -> PitchLevel.HIGH
            downstep == moraCount -> PitchLevel.LOW
            else -> PitchLevel.LOW
        }

        return moraePitches to particlePitch
    }

    /**
     * Split a Japanese reading (hiragana/katakana) into morae.
     *
     * Morae are the rhythmic units of Japanese:
     * - Most kana = 1 mora
     * - Kana + small kana (e.g., きゃ) = 1 mora
     * - Long vowel mark (ー) = 1 mora
     * - Small tsu (っ/ッ) = 1 mora
     * - N (ん/ン) = 1 mora
     */
    private fun splitToMorae(reading: String): List<String> {
        val morae = mutableListOf<String>()
        var i = 0

        while (i < reading.length) {
            val currentChar = reading[i]

            // Check if next character is a small kana that combines with current
            if (i + 1 < reading.length && reading[i + 1] in SMALL_KANA) {
                morae.add(reading.substring(i, i + 2))
                i += 2
            } else {
                morae.add(currentChar.toString())
                i++
            }
        }

        return morae
    }
}
