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
    /**
     * Parse index.json file.
     */
    fun parseIndex(jsonString: String): DictionaryIndex

    /**
     * Parse tag_bank_*.json files using streaming.
     * Format: [[name, category, order, notes, score], ...]
     */
    fun parseTagBank(stream: InputStream): Sequence<DictionaryTag>

    /**
     * Parse term_bank_*.json files using streaming.
     * V1 format: [[expression, reading, definitionTags, rules, score, ...glossary], ...]
     * V3 format: [[expression, reading, definitionTags, rules, score, glossary[], sequence, termTags], ...]
     */
    fun parseTermBank(stream: InputStream, version: Int): Sequence<DictionaryTerm>

    /**
     * Parse kanji_bank_*.json files using streaming.
     * V1 format: [[character, onyomi, kunyomi, tags, ...meanings], ...]
     * V3 format: [[character, onyomi, kunyomi, tags, meanings, stats], ...]
     */
    fun parseKanjiBank(stream: InputStream, version: Int): Sequence<DictionaryKanji>

    /**
     * Parse term_meta_bank_*.json files using streaming.
     * Format: [[expression, mode, data], ...]
     */
    fun parseTermMetaBank(stream: InputStream): Sequence<DictionaryTermMeta>

    /**
     * Parse kanji_meta_bank_*.json files using streaming.
     * Format: [[character, mode, data], ...]
     */
    fun parseKanjiMetaBank(stream: InputStream): Sequence<DictionaryKanjiMeta>
}

/**
 * Exception thrown when parsing the importing dictionary fails.
 */
class DictionaryParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
