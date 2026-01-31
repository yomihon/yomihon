package mihon.domain.ankidroid.interactor

import mihon.domain.ankidroid.repository.AnkiDroidRepository
import mihon.domain.dictionary.model.DictionaryTermCard

class AddDictionaryCard(
    private val repository: AnkiDroidRepository,
) {
    suspend operator fun invoke(card: DictionaryTermCard): AnkiDroidRepository.Result {
        return repository.addCard(card)
    }
}
