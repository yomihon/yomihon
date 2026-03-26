package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.repository.DictionaryRepository

class DictionaryInteractor(
    private val dictionaryRepository: DictionaryRepository,
) {
    suspend fun getAllDictionaries(): List<Dictionary> {
        return dictionaryRepository.getAllDictionaries()
    }

    suspend fun getFreqDictionaryIds(): List<Long> {
        return dictionaryRepository.getFreqDictionaryIds()
    }

    suspend fun getDictionary(dictionaryId: Long): Dictionary? {
        return dictionaryRepository.getDictionary(dictionaryId)
    }

    suspend fun updateDictionary(dictionary: Dictionary) {
        dictionaryRepository.updateDictionary(dictionary)
    }

    suspend fun createDictionary(
        index: DictionaryIndex,
        styles: String? = null,
        backend: DictionaryBackend = DictionaryBackend.LEGACY_DB,
        storagePath: String? = null,
        storageReady: Boolean = false,
    ): Long {
        dictionaryRepository.bumpAllPrioritiesUp()

        return dictionaryRepository.insertDictionary(
            Dictionary(
                title = index.title,
                revision = index.revision,
                version = index.effectiveVersion,
                author = index.author,
                url = index.url,
                description = index.description,
                attribution = index.attribution,
                styles = styles,
                sourceLanguage = index.sourceLanguage,
                targetLanguage = index.targetLanguage,
                priority = 1,
                backend = backend,
                storagePath = storagePath,
                storageReady = storageReady,
            ),
        )
    }

    suspend fun deleteDictionary(dictionaryId: Long) {
        val dictionary = dictionaryRepository.getDictionary(dictionaryId)
        val priorityToAdjust = dictionary?.priority

        dictionaryRepository.deleteDictionary(dictionaryId)

        // Bump down priorities for dictionaries that were above
        if (priorityToAdjust != null) {
            dictionaryRepository.bumpDownPrioritiesAbove(priorityToAdjust)
        }
    }

    /**
     * Swaps the priority of two dictionaries.
     * This is used to move dictionaries up or down in the list.
     */
    suspend fun swapDictionaryPriorities(dict1: Dictionary, dict2: Dictionary) {
        val priority1 = dict1.priority
        val priority2 = dict2.priority
        dictionaryRepository.updateDictionary(dict1.copy(priority = priority2))
        dictionaryRepository.updateDictionary(dict2.copy(priority = priority1))
    }

    /**
     * Checks if a dictionary with the same title and revision already exists.
     */
    suspend fun isDictionaryAlreadyImported(title: String, revision: String): Boolean {
        return dictionaryRepository.getAllDictionaries().any {
            it.title == title && it.revision == revision
        }
    }
}
