package mihon.domain.dictionary.exception

sealed class DictionaryImportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    data object AlreadyImported : DictionaryImportException("Dictionary already imported")

    class InvalidArchive(message: String, cause: Throwable? = null) : DictionaryImportException(message, cause)

    class ImportFailed(message: String, cause: Throwable? = null) : DictionaryImportException(message, cause)
}
