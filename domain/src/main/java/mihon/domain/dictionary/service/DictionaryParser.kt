package mihon.domain.dictionary.service

import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.model.GlossaryEntry

/**
 * Service for parsing dictionary files.
 * This handles the JSON structure of dictionary bank files.
 * Uses streaming parsing for memory efficiency with large dictionaries.
 */
interface DictionaryParser {
    fun parseIndex(jsonString: String): DictionaryIndex
    fun parseGlossary(rawGlossary: String): List<GlossaryEntry>
}

class DictionaryParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
