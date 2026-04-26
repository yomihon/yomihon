package mihon.domain.dictionary.model

/**
 * Represents a dictionary term with individual fields for flexible Anki field mapping.
 *
 * Glossary fields support multiple variants for flexible Anki card authoring:
 * - `glossary-first`: HTML from only the first/primary dictionary (default)
 * - `glossary-all`: Combined HTML from all dictionaries in the group
 * - `glossary-{dictName}`: HTML from a specific dictionary (e.g. `glossary-JMdict`)
 *
 * For backward compatibility, the bare `glossary` key maps to `glossary-first`.
 */
data class DictionaryTermCard(
    val expression: String,
    val reading: String,
    val sentence: String = "",
    val audio: String = "",
    val pitchAccent: String = "",
    val frequency: String = "",
    val pictureUrl: String = "",
    val freqAvgValue: String = "",
    val freqLowestValue: String = "",
    val singleFreqValues: Map<Long, String> = emptyMap(),
    val tags: Set<String> = emptySet(),
    /** HTML from the first/primary dictionary in the group */
    val glossaryFirst: String = "",
    /** Combined HTML from all dictionaries in the group */
    val glossaryAll: String = "",
    /** Per-dictionary glossary HTML, keyed by dictionary title */
    val glossaryByDictionary: Map<String, String> = emptyMap(),
) {
    /**
     * Get the value for a given app field name.
     *
     * Glossary resolution order:
     * - `glossary` or `glossary-first` → [glossaryFirst]
     * - `glossary-all`                 → [glossaryAll]
     * - `glossary-{dictName}`          → [glossaryByDictionary] lookup
     */
    fun getFieldValue(fieldName: String): String = when {
        fieldName == "expression" -> expression
        fieldName == "reading" -> reading
        fieldName == "glossary" || fieldName == "glossary-first" -> glossaryFirst
        fieldName == "glossary-all" -> glossaryAll
        fieldName == "sentence" -> sentence
        fieldName == "audio" -> audio
        fieldName == "pitchAccent" -> pitchAccent
        fieldName == "frequency" -> frequency
        fieldName == "picture" -> pictureUrl
        fieldName == "freqAvgValue" -> freqAvgValue
        fieldName == "freqLowestValue" -> freqLowestValue
        fieldName.startsWith("freqSingleValue_") -> {
            val dictId = fieldName.substringAfter("freqSingleValue_").toLongOrNull()
            singleFreqValues[dictId] ?: ""
        }
        fieldName.startsWith("glossary-") -> {
            val dictKey = fieldName.removePrefix("glossary-")
            glossaryByDictionary[dictKey] ?: ""
        }
        else -> ""
    }
}

/**
 * Creates a [DictionaryTermCard] from a group of terms sharing the same expression+reading.
 *
 * @param terms       All [DictionaryTerm]s in the group (possibly from multiple dictionaries)
 * @param dictionaries Full dictionary list used to resolve titles and styles
 * @param glossaryHtml Pre-built map of HTML per dictionary title, plus `"__all__"` and `"__first__"` keys
 */
fun createGroupedTermCard(
    expression: String,
    reading: String,
    terms: List<DictionaryTerm>,
    dictionaries: List<Dictionary>,
    glossaryHtml: GlossaryHtmlBundle,
    sentence: String = "",
    audio: String = "",
    pitchAccent: String = "",
    frequency: String = "",
    pictureUrl: String = "",
    freqAvgValue: String = "",
    freqLowestValue: String = "",
    singleFreqValues: Map<Long, String> = emptyMap(),
): DictionaryTermCard {
    val cardTags = buildSet {
        add("yomihon")
        terms.forEach { term ->
            val dictName = dictionaries.find { it.id == term.dictionaryId }?.title.orEmpty()
            val tag = dictName.toAnkiTag()
            if (tag.isNotBlank()) add(tag)
        }
    }

    return DictionaryTermCard(
        expression = expression,
        reading = reading,
        glossaryFirst = glossaryHtml.first,
        glossaryAll = glossaryHtml.all,
        glossaryByDictionary = glossaryHtml.byDictionary,
        sentence = sentence,
        audio = audio,
        pitchAccent = pitchAccent,
        frequency = frequency,
        pictureUrl = pictureUrl,
        freqAvgValue = freqAvgValue,
        freqLowestValue = freqLowestValue,
        singleFreqValues = singleFreqValues,
        tags = cardTags,
    )
}

/**
 * Bundle of pre-built glossary HTML variants for Anki export.
 */
data class GlossaryHtmlBundle(
    /** HTML from the first/primary dictionary only */
    val first: String,
    /** Combined HTML from all dictionaries */
    val all: String,
    /** Per-dictionary HTML, keyed by dictionary title */
    val byDictionary: Map<String, String>,
)

/**
 * Builds a [GlossaryHtmlBundle] from a group of terms.
 *
 * @param terms       Terms grouped by the same expression+reading
 * @param dictionaries Full dictionary list for title/style resolution
 * @param toHtml      Lambda that converts a [DictionaryTerm]'s glossary to HTML given optional CSS styles
 */
fun buildGlossaryHtmlBundle(
    terms: List<DictionaryTerm>,
    dictionaries: List<Dictionary>,
    toHtml: (term: DictionaryTerm, styles: String?) -> String,
): GlossaryHtmlBundle {
    val byDictionary = mutableMapOf<String, String>()
    val allParts = mutableListOf<String>()
    var firstHtml: String? = null

    terms.groupBy { it.dictionaryId }.forEach { (dictId, dictTerms) ->
        val dictionary = dictionaries.find { it.id == dictId }
        val dictName = dictionary?.title?.trim().orEmpty()
        val styles = dictionary?.styles

        val html = dictTerms.joinToString("") { term -> toHtml(term, styles) }

        if (firstHtml == null) firstHtml = html
        allParts.add(html)
        if (dictName.isNotBlank()) {
            byDictionary[dictName] = html
        }
    }

    return GlossaryHtmlBundle(
        first = firstHtml.orEmpty(),
        all = allParts.joinToString(""),
        byDictionary = byDictionary,
    )
}

private fun String.toAnkiTag(): String {
    return trim()
        .replace("\\s+".toRegex(), "_")
        .replace("[^A-Za-z0-9_\\-]".toRegex(), "")
}
