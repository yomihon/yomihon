package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.repository.DictionaryRepository

class DictionaryInteractor(
    private val dictionaryRepository: DictionaryRepository,
) {
    suspend fun getAllDictionaries(): List<Dictionary> {
        return dictionaryRepository.getAllDictionaries()
    }

    suspend fun getDictionary(dictionaryId: Long): Dictionary? {
        return dictionaryRepository.getDictionary(dictionaryId)
    }

    suspend fun updateDictionary(dictionary: Dictionary) {
        dictionaryRepository.updateDictionary(dictionary)
    }

    suspend fun deleteDictionary(dictionaryId: Long) {
        dictionaryRepository.deleteDictionary(dictionaryId)
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
}
