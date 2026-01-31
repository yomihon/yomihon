package mihon.domain.dictionary.model

/**
 * Represents a dictionary term as a minimal flashcard payload.
 */
data class DictionaryTermCard(
    val front: String,
    val back: String,
    val tags: Set<String> = emptySet(),
)

fun DictionaryTerm.toDictionaryTermCard(dictionaryName: String): DictionaryTermCard {
    val front = buildString {
        append(expression)
        if (reading.isNotBlank() && reading != expression) {
            append(" [")
            append(reading)
            append("]")
        }
    }

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

    val back = when {
        definitions.isNotEmpty() -> definitions.joinToString("\n")
        reading.isNotBlank() -> reading
        else -> expression
    }

    val tags = buildSet {
        add("yomihon")
        val dictionaryTag = dictionaryName.toAnkiTag()
        if (dictionaryTag.isNotBlank()) {
            add(dictionaryTag)
        }
    }

    return DictionaryTermCard(
        front = front,
        back = back,
        tags = tags,
    )
}

private fun String.toAnkiTag(): String {
    return trim()
        .replace("\\s+".toRegex(), "_")
        .replace("[^A-Za-z0-9_\\-]".toRegex(), "")
}
