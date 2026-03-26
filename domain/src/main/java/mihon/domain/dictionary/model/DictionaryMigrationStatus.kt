package mihon.domain.dictionary.model

data class DictionaryMigrationStatus(
    val dictionaryId: Long,
    val state: DictionaryMigrationState,
    val stage: DictionaryMigrationStage,
    val progressText: String? = null,
    val completedDictionaries: Int = 0,
    val totalDictionaries: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class DictionaryMigrationState {
    QUEUED,
    RUNNING,
    COMPLETE,
    ERROR,
    ;

    fun toDbValue(): String {
        return when (this) {
            QUEUED -> "queued"
            RUNNING -> "running"
            COMPLETE -> "complete"
            ERROR -> "error"
        }
    }

    companion object {
        fun fromDbValue(value: String?): DictionaryMigrationState {
            return when (value?.lowercase()) {
                "running" -> RUNNING
                "complete" -> COMPLETE
                "error" -> ERROR
                else -> QUEUED
            }
        }
    }
}

enum class DictionaryMigrationStage {
    QUEUED,
    BUILDING_ARCHIVE,
    IMPORTING,
    VALIDATING,
    REBUILDING_SESSION,
    CLEANING_UP,
    COMPLETE,
    ERROR,
    ;

    fun toDbValue(): String {
        return when (this) {
            QUEUED -> "queued"
            BUILDING_ARCHIVE -> "building_archive"
            IMPORTING -> "importing"
            VALIDATING -> "validating"
            REBUILDING_SESSION -> "rebuilding_session"
            CLEANING_UP -> "cleaning_up"
            COMPLETE -> "complete"
            ERROR -> "error"
        }
    }

    companion object {
        fun fromDbValue(value: String?): DictionaryMigrationStage {
            return when (value?.lowercase()) {
                "building_archive" -> BUILDING_ARCHIVE
                "importing" -> IMPORTING
                "validating" -> VALIDATING
                "rebuilding_session" -> REBUILDING_SESSION
                "cleaning_up" -> CLEANING_UP
                "complete" -> COMPLETE
                "error" -> ERROR
                else -> QUEUED
            }
        }
    }
}
