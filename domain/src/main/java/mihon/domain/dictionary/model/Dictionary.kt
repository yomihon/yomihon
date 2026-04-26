package mihon.domain.dictionary.model

/**
 * Represents a dictionary with all its metadata.
 */
data class Dictionary(
    val id: Long = 0L,
    val title: String,
    val revision: String,
    val version: Int,
    val author: String? = null,
    val url: String? = null,
    val description: String? = null,
    val attribution: String? = null,
    val styles: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val backend: DictionaryBackend = DictionaryBackend.HOSHI,
    val storagePath: String? = null,
    val storageReady: Boolean = false,
)

enum class DictionaryBackend {
    LEGACY_DB,
    HOSHI,
    ;

    fun toDbValue(): String {
        return when (this) {
            LEGACY_DB -> "legacy_db"
            HOSHI -> "hoshi"
        }
    }

    companion object {
        fun fromDbValue(value: String?): DictionaryBackend {
            return when (value?.lowercase()) {
                "hoshi" -> HOSHI
                else -> LEGACY_DB
            }
        }
    }
}
