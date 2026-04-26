package mihon.domain.dictionary.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DictionaryTermCardTest {

    @Test
    fun `getFieldValue returns audio field`() {
        val card = DictionaryTermCard(
            expression = "日本語",
            reading = "にほんご",
            audio = "C:/cache/audio.mp3",
        )

        assertEquals("C:/cache/audio.mp3", card.getFieldValue("audio"))
    }
}
