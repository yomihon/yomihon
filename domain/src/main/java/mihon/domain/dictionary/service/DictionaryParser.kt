package mihon.domain.dictionary.service

import java.io.InputStream
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.DictionaryIndex

/**
 * Service for parsing dictionary files.
 * This handles the JSON structure of dictionary bank files.
 * Uses streaming parsing for memory efficiency with large dictionaries.
 */
interface DictionaryParser {
    fun parseIndex(jsonString: String): DictionaryIndex
    fun parseTagBank(stream: InputStream): Sequence<DictionaryTag>
    fun parseTermBank(stream: InputStream, version: Int): Sequence<DictionaryTerm>
    fun parseKanjiBank(stream: InputStream, version: Int): Sequence<DictionaryKanji>
    fun parseTermMetaBank(stream: InputStream): Sequence<DictionaryTermMeta>
    fun parseKanjiMetaBank(stream: InputStream): Sequence<DictionaryKanjiMeta>
}

class DictionaryParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
