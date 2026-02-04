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
    val picture: String = "",
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
        "picture" -> picture
        else -> ""
    }
}

fun DictionaryTerm.toDictionaryTermCard(dictionaryName: String): DictionaryTermCard {
    val definitions = glossary.mapNotNull { entry ->
        when (entry) {
            is GlossaryEntry.TextDefinition -> entry.text
            is GlossaryEntry.StructuredContent -> entry.nodes.collectText()
            is GlossaryEntry.Deinflection -> null
            is GlossaryEntry.ImageDefinition -> null
            is GlossaryEntry.Unknown -> null
        }
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val glossaryText = when {
        definitions.isNotEmpty() -> definitions.joinToString("\n")
        reading.isNotBlank() -> reading
        else -> expression
    }

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
        glossary = glossaryText,
        tags = cardTags,
    )
}

private fun String.toAnkiTag(): String {
    return trim()
        .replace("\\s+".toRegex(), "_")
        .replace("[^A-Za-z0-9_\\-]".toRegex(), "")
}
