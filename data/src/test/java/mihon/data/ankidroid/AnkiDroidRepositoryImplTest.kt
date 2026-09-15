package mihon.data.ankidroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnkiDroidRepositoryImplTest {

    @Test
    fun `addAdditionalAnkiTag preserves existing tags and adds configured tag`() {
        val result = addAdditionalAnkiTag(setOf("yomihon", "jmdict"), "  my-tag  ")

        assertEquals(setOf("yomihon", "jmdict", "my-tag"), result)
    }

    @Test
    fun `addAdditionalAnkiTag splits tags by whitespace`() {
        val result = addAdditionalAnkiTag(setOf("yomihon"), "reading vocab  japanese")

        assertEquals(setOf("yomihon", "reading", "vocab", "japanese"), result)
    }

    @Test
    fun `addAdditionalAnkiTag splits tags by comma`() {
        val result = addAdditionalAnkiTag(setOf("yomihon"), "reading,vocab,japanese")

        assertEquals(setOf("yomihon", "reading", "vocab", "japanese"), result)
    }

    @Test
    fun `addAdditionalAnkiTag splits tags by mixed commas and whitespace`() {
        val result = addAdditionalAnkiTag(setOf("yomihon"), "reading,  vocab jmdict,  grammar")

        assertEquals(setOf("yomihon", "reading", "vocab", "jmdict", "grammar"), result)
    }

    @Test
    fun `addAdditionalAnkiTag strips double quotes and control characters`() {
        val result = addAdditionalAnkiTag(
            tags = setOf("yomihon"),
            additionalTag = "\"reading\" \"vocab\"\u0000 \u001Ftag\u007F",
        )

        assertEquals(setOf("yomihon", "reading", "vocab", "tag"), result)
    }

    @Test
    fun `addAdditionalAnkiTag preserves hierarchical tags but trims edge colons`() {
        val result = addAdditionalAnkiTag(
            tags = setOf("yomihon"),
            additionalTag = "manga::vocab :::anime::grammar::: ::empty::",
        )

        assertEquals(setOf("yomihon", "manga::vocab", "anime::grammar", "empty"), result)
    }

    @Test
    fun `addAdditionalAnkiTag avoids duplicates with existing tags`() {
        val result = addAdditionalAnkiTag(setOf("yomihon", "vocab"), "yomihon reading vocab")

        assertEquals(setOf("yomihon", "vocab", "reading"), result)
    }

    @Test
    fun `addAdditionalAnkiTag ignores blank configured tag`() {
        val tags = setOf("yomihon")

        assertEquals(tags, addAdditionalAnkiTag(tags, "   "))
        assertEquals(tags, addAdditionalAnkiTag(tags, ""))
        assertEquals(tags, addAdditionalAnkiTag(tags, ",,,   ,,,"))
        assertEquals(tags, addAdditionalAnkiTag(tags, "\"\" \"  \" :::"))
    }

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

    @Test
    fun `formatSentenceWithBoldWord bolds expression in sentence`() {
        val result = formatSentenceWithBoldWord("リンゴを食べる。", "食べる", "たべる")
        assertEquals("リンゴを<b>食べる</b>。", result)
    }

    @Test
    fun `formatSentenceWithBoldWord prioritizes surface match for inflected forms`() {
        val result = formatSentenceWithBoldWord("昨日、りんごを食べた。", "食べる", "たべる", "食べた")
        assertEquals("昨日、りんごを<b>食べた</b>。", result)
    }

    @Test
    fun `formatSentenceWithBoldWord bolds sub-word in compound word via surface match`() {
        val result = formatSentenceWithBoldWord("彼は東京大学の学生です。", "大学", "だいがく", "大学")
        assertEquals("彼は東京<b>大学</b>の学生です。", result)
    }

    @Test
    fun `formatSentenceWithBoldWord does not duplicate bold tags if already bolded`() {
        val result = formatSentenceWithBoldWord("リンゴを<b>食べる</b>。", "食べる", "たべる", "食べる")
        assertEquals("リンゴを<b>食べる</b>。", result)
    }

    @Test
    fun `formatSentenceWithBoldWord falls back to reading if expression and surface aren't present`() {
        val result = formatSentenceWithBoldWord("わたしは学生です", "私", "わたし", "")
        assertEquals("<b>わたし</b>は学生です", result)
    }

    @Test
    fun `formatSentenceWithBoldWord handles case insensitivity for English text`() {
        val result = formatSentenceWithBoldWord("Apple is an apple.", "apple", "", "apple")
        assertEquals("<b>Apple</b> is an <b>apple</b>.", result)
    }

    @Test
    fun `formatSentenceWithBoldWord handles spaces breaking the word`() {
        val result = formatSentenceWithBoldWord("私 は 学生 です", "私は", "わたしは", "私は")
        assertEquals("<b>私 は</b> 学生 です", result)
    }

    @Test
    fun `formatSentenceWithBoldWord handles empty inputs`() {
        assertEquals("", formatSentenceWithBoldWord("", "食べる"))
        assertEquals("リンゴを食べる。", formatSentenceWithBoldWord("リンゴを食べる。", ""))
    }
}
