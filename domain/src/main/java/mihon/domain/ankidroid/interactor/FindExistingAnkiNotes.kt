package mihon.domain.ankidroid.interactor

import mihon.domain.ankidroid.repository.AnkiDroidRepository

class FindExistingAnkiNotes(
    private val repository: AnkiDroidRepository,
) {
    /**
     * Returns the subset of [expressions] that already have a matching note in AnkiDroid.
     */
    suspend operator fun invoke(expressions: List<String>): Set<String> {
        return repository.findExistingNotes(expressions)
    }
}
