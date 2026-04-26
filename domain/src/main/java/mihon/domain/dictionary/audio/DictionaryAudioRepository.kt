package mihon.domain.dictionary.audio

interface DictionaryAudioRepository {
    suspend fun fetchAudio(
        expression: String,
        reading: String,
    ): DictionaryAudioResult
}

sealed interface DictionaryAudioResult {
    data class Success(val audio: DictionaryAudio) : DictionaryAudioResult

    data object NotFound : DictionaryAudioResult

    data class Error(val throwable: Throwable? = null) : DictionaryAudioResult
}
