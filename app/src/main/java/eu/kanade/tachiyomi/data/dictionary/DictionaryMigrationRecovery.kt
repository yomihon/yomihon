package eu.kanade.tachiyomi.data.dictionary

import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryLegacyRowCounts
import mihon.domain.dictionary.model.DictionaryMigrationState
import mihon.domain.dictionary.model.DictionaryMigrationStatus

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

    fun hasPendingMigration(
        legacyDictionaries: List<Dictionary>,
        statuses: List<DictionaryMigrationStatus>,
    ): Boolean {
        val statusesByDictionaryId = statuses.associateBy { it.dictionaryId }
        val hasPendingLegacy = legacyDictionaries.any { dictionary ->
            statusesByDictionaryId[dictionary.id]?.state != DictionaryMigrationState.ERROR
        }
        val hasPendingStatuses = statuses.any { status ->
            status.state != DictionaryMigrationState.COMPLETE &&
                status.state != DictionaryMigrationState.ERROR
        }
        return hasPendingLegacy || hasPendingStatuses
    }
}
