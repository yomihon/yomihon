package mihon.domain.dictionary.model

data class DictionaryTermExport(
    val expression: String,
    val reading: String,
    val definitionTags: String?,
    val rules: String?,
    val score: Int,
    val glossaryJson: String,
    val sequence: Long?,
    val termTags: String?,
)

data class DictionaryTermMetaExport(
    val expression: String,
    val mode: String,
    val dataJson: String,
)

data class DictionaryKanjiExport(
    val character: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String?,
    val meaningsJson: String,
    val statsJson: String?,
)

data class DictionaryKanjiMetaExport(
    val character: String,
    val mode: String,
    val dataJson: String,
)

data class DictionaryLegacyRowCounts(
    val tagCount: Long,
    val termCount: Long,
    val termMetaCount: Long,
    val kanjiCount: Long,
    val kanjiMetaCount: Long,
) {
    val hasLegacyRows: Boolean
        get() = tagCount > 0L || termCount > 0L || termMetaCount > 0L || kanjiCount > 0L || kanjiMetaCount > 0L
}
