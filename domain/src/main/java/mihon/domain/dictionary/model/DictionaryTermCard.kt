package mihon.domain.dictionary.model

/**
 * Represents a dictionary term with individual fields for flexible Anki field mapping.
 */
data class DictionaryTermCard(
    val expression: String,
    val reading: String,
    val glossary: String,
    val sentence: String = "",
    val pitchAccent: String = "",
    val frequency: String = "",
    val pictureUrl: String = "",
    val tags: Set<String> = emptySet(),
) {
    /**
     * Get the value for a given app field name.
     */
    fun getFieldValue(fieldName: String): String = when (fieldName) {
        "expression" -> expression
        "reading" -> reading
        "glossary" -> glossary
        "sentence" -> sentence
        "pitchAccent" -> pitchAccent
        "frequency" -> frequency
        "picture" -> pictureUrl
        else -> ""
    }
}

fun DictionaryTerm.toDictionaryTermCard(
    dictionaryName: String,
    glossaryHtml: String,
    sentence: String = "",
    pitchAccent: String = "",
    frequency: String = "",
    pictureUrl: String = "",
): DictionaryTermCard {
    val cardTags = buildSet {
        add("yomihon")
        val dictionaryTag = dictionaryName.toAnkiTag()
        if (dictionaryTag.isNotBlank()) {
            add(dictionaryTag)
        }
    }

    return DictionaryTermCard(
        expression = expression,
        reading = reading,
        glossary = glossaryHtml,
        sentence = sentence,
        pitchAccent = pitchAccent,
        frequency = frequency,
        pictureUrl = pictureUrl,
        tags = cardTags,
    )
}

private fun String.toAnkiTag(): String {
    return trim()
        .replace("\\s+".toRegex(), "_")
        .replace("[^A-Za-z0-9_\\-]".toRegex(), "")
}
