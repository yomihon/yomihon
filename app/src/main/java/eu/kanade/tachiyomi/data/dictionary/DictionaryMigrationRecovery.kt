package eu.kanade.tachiyomi.data.dictionary

import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryLegacyRowCounts
import mihon.domain.dictionary.model.DictionaryMigrationState

internal enum class DictionaryMigrationResumeAction {
    MIGRATE_FROM_LEGACY,
    CLEANUP_LEGACY_ROWS,
    MARK_COMPLETE,
}

internal object DictionaryMigrationRecovery {
    fun resumeAction(
        dictionary: Dictionary,
        counts: DictionaryLegacyRowCounts,
    ): DictionaryMigrationResumeAction {
        if (dictionary.backend != DictionaryBackend.HOSHI || !dictionary.storageReady) {
            return DictionaryMigrationResumeAction.MIGRATE_FROM_LEGACY
        }

        return if (counts.hasLegacyRows) {
            DictionaryMigrationResumeAction.CLEANUP_LEGACY_ROWS
        } else {
            DictionaryMigrationResumeAction.MARK_COMPLETE
        }
    }

    fun hasPendingMigration(hasLegacyDictionaries: Boolean, states: List<DictionaryMigrationState>): Boolean {
        return hasLegacyDictionaries || states.any { it != DictionaryMigrationState.COMPLETE }
    }
}
