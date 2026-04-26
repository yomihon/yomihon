package mihon.domain.dictionary.audio

import java.io.File

data class DictionaryAudio(
    val file: File,
    val mediaType: String?,
    val source: DictionaryAudioSource,
)

enum class DictionaryAudioSource {
    JPOD101,
    WIKTIONARY,
}
