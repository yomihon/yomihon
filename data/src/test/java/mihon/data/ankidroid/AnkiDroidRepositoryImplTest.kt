package mihon.data.ankidroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnkiDroidRepositoryImplTest {

    @Test
    fun `formatFurigana only applies ruby to kanji spans`() {
        val formatted = formatFurigana("食べる", "たべる")

        assertEquals("<ruby>食<rt>た</rt></ruby>べる", formatted)
    }

    @Test
    fun `formatFurigana preserves kana around kanji`() {
        val formatted = formatFurigana("お兄さん", "おにいさん")

        assertEquals("お<ruby>兄<rt>にい</rt></ruby>さん", formatted)
    }

    @Test
    fun `formatFurigana handles multiple kanji segments`() {
        val formatted = formatFurigana("取り扱い", "とりあつかい")

        assertEquals("<ruby>取<rt>と</rt></ruby>り<ruby>扱<rt>あつか</rt></ruby>い", formatted)
    }

    @Test
    fun `formatFurigana keeps all-kanji terms as a single ruby block`() {
        val formatted = formatFurigana("日本語", "にほんご")

        assertEquals("<ruby>日本語<rt>にほんご</rt></ruby>", formatted)
    }
}
