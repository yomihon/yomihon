package mihon.domain.dictionary.service

import mihon.domain.dictionary.model.Dictionary

interface DictionaryArchiveBuilder {
    suspend fun buildArchive(
        dictionary: Dictionary,
        destinationPath: String,
        onProgress: suspend (DictionaryArchiveProgress) -> Unit = {},
    ): DictionaryArchiveBuildResult
}

data class DictionaryArchiveBuildResult(
    val archivePath: String,
    val sampleExpression: String?,
    val tagCount: Long,
    val termCount: Long,
    val termMetaCount: Long,
    val kanjiCount: Long,
    val kanjiMetaCount: Long,
)

data class DictionaryArchiveProgress(
    val dictionaryId: Long,
    val writtenEntries: Long,
    val totalEntries: Long,
    val message: String,
)
