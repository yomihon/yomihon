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
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
)
