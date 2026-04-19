package mihon.domain.dictionary.interactor

import io.kotest.matchers.shouldBe
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import org.junit.jupiter.api.Test

class DictionaryAutoSorterTest {

    private fun dict(title: String, priority: Int = 0, id: Long = title.hashCode().toLong()): Dictionary {
        return Dictionary(
            id = id,
            title = title,
            revision = "1",
            version = 1,
            priority = priority,
            backend = DictionaryBackend.HOSHI,
        )
    }

    @Test
    fun `frequency dictionaries are sorted before monolingual`() {
        val dictionaries = listOf(
            dict("Jitendex [2024-01]"),
            dict("JPDB"),
            dict("新明解国語辞典　第八版"),
        )

        val sorted = DictionaryAutoSorter.sort(dictionaries)

        sorted.map { it.title } shouldBe listOf(
            "JPDB",
            "新明解国語辞典　第八版",
            "Jitendex [2024-01]",
        )
    }

    @Test
    fun `priorities are assigned sequentially starting from 1`() {
        val dictionaries = listOf(
            dict("Innocent Ranked", priority = 50),
            dict("JPDB", priority = 10),
        )

        val sorted = DictionaryAutoSorter.sort(dictionaries)

        sorted[0].title shouldBe "JPDB"
        sorted[0].priority shouldBe 1
        sorted[1].title shouldBe "Innocent Ranked"
        sorted[1].priority shouldBe 2
    }

    @Test
    fun `unmatched dictionaries are appended at the end`() {
        val dictionaries = listOf(
            dict("My Custom Dict"),
            dict("JPDB"),
            dict("Another Custom Dict"),
        )

        val sorted = DictionaryAutoSorter.sort(dictionaries)

        sorted.map { it.title } shouldBe listOf(
            "JPDB",
            "My Custom Dict",
            "Another Custom Dict",
        )
    }

    @Test
    fun `unmatched dictionaries preserve their relative order`() {
        val dictionaries = listOf(
            dict("Zebra Dict"),
            dict("Alpha Dict"),
            dict("JPDB"),
        )

        val sorted = DictionaryAutoSorter.sort(dictionaries)

        sorted.map { it.title } shouldBe listOf(
            "JPDB",
            "Zebra Dict",
            "Alpha Dict",
        )
    }

    @Test
    fun `empty list returns empty`() {
        val sorted = DictionaryAutoSorter.sort(emptyList())
        sorted shouldBe emptyList()
    }

    @Test
    fun `single dictionary gets priority 1`() {
        val dictionaries = listOf(dict("JPDB"))
        val sorted = DictionaryAutoSorter.sort(dictionaries)

        sorted.size shouldBe 1
        sorted[0].priority shouldBe 1
    }

    @Test
    fun `all unmatched dictionaries keep relative order and get sequential priorities`() {
        val dictionaries = listOf(
            dict("Custom C", priority = 30),
            dict("Custom B", priority = 20),
            dict("Custom A", priority = 10),
        )

        val sorted = DictionaryAutoSorter.sort(dictionaries)

        sorted.map { it.title } shouldBe listOf("Custom C", "Custom B", "Custom A")
        sorted.map { it.priority } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `full sort order covers all groups`() {
        val dictionaries = listOf(
            // ZH
            dict("CC-CEDICT"),
            // YUE
            dict("CantoDict"),
            // JA-EN
            dict("Jitendex [2024-01]"),
            // JA Grammar
            dict("絵でわかる日本語"),
            // JA Kanji Info
            dict("KANJIDIC English"),
            // JA Frequency
            dict("JPDB"),
            // JA-JA
            dict("広辞苑 第七版"),
            // JA Pitch
            dict("NHK"),
            // YUE Freq
            dict("Words.hk Frequency"),
            // ZH Freq
            dict("HSK"),
            // JA Kanji Freq
            dict("Innocent Corpus Kanji"),
            // JA Names
            dict("JMnedict"),
        )

        val sorted = DictionaryAutoSorter.sort(dictionaries)
        val titles = sorted.map { it.title }

        // Verify overall group ordering
        val jpdbIndex = titles.indexOf("JPDB")
        val nhkIndex = titles.indexOf("NHK")
        val koujienIndex = titles.indexOf("広辞苑 第七版")
        val jitendexIndex = titles.indexOf("Jitendex [2024-01]")
        val jmnedictIndex = titles.indexOf("JMnedict")
        val grammarIndex = titles.indexOf("絵でわかる日本語")
        val kanjiFreqIndex = titles.indexOf("Innocent Corpus Kanji")
        val kanjiInfoIndex = titles.indexOf("KANJIDIC English")
        val yueFreqIndex = titles.indexOf("Words.hk Frequency")
        val yueIndex = titles.indexOf("CantoDict")
        val zhFreqIndex = titles.indexOf("HSK")
        val zhIndex = titles.indexOf("CC-CEDICT")

        // JA Freq < JA Pitch < JA-JA < JA-EN < JA Names < JA Grammar < JA Kanji Freq < JA Kanji Info
        assert(jpdbIndex < nhkIndex) { "Freq should come before pitch" }
        assert(nhkIndex < koujienIndex) { "Pitch should come before monolingual" }
        assert(koujienIndex < jitendexIndex) { "Monolingual should come before bilingual" }
        assert(jitendexIndex < jmnedictIndex) { "Bilingual should come before names" }
        assert(jmnedictIndex < grammarIndex) { "Names should come before grammar" }
        assert(grammarIndex < kanjiFreqIndex) { "Grammar should come before kanji freq" }
        assert(kanjiFreqIndex < kanjiInfoIndex) { "Kanji freq should come before kanji info" }

        // YUE after JA
        assert(kanjiInfoIndex < yueFreqIndex) { "JA should come before YUE" }
        assert(yueFreqIndex < yueIndex) { "YUE freq should come before YUE" }

        // ZH after YUE
        assert(yueIndex < zhFreqIndex) { "YUE should come before ZH" }
        assert(zhFreqIndex < zhIndex) { "ZH freq should come before ZH" }
    }

    @Test
    fun `regex patterns with wildcards match correctly`() {
        val dictionaries = listOf(
            dict("BCCWJ-LUW"),
            dict("BCCWJ SUW"),
            dict("KANJIDIC English"),
            dict("KANJIDIC French"),
        )

        val sorted = DictionaryAutoSorter.sort(dictionaries)

        // All should be matched (not at the end as unknown)
        sorted.all { it.title.startsWith("BCCWJ") || it.title.startsWith("KANJIDIC") } shouldBe true
        // BCCWJ variants come before KANJIDIC variants
        sorted.indexOfFirst { it.title.startsWith("BCCWJ") } shouldBe 0
    }

    @Test
    fun `does not modify other dictionary fields`() {
        val original = Dictionary(
            id = 42L,
            title = "JPDB",
            revision = "rev2",
            version = 3,
            author = "test author",
            url = "https://example.com",
            description = "test desc",
            attribution = "test attr",
            styles = "test styles",
            sourceLanguage = "ja",
            targetLanguage = "en",
            isEnabled = false,
            priority = 99,
            dateAdded = 12345L,
            backend = DictionaryBackend.HOSHI,
            storagePath = "/path",
            storageReady = true,
        )

        val sorted = DictionaryAutoSorter.sort(listOf(original))

        val result = sorted[0]
        result.id shouldBe 42L
        result.title shouldBe "JPDB"
        result.revision shouldBe "rev2"
        result.version shouldBe 3
        result.author shouldBe "test author"
        result.url shouldBe "https://example.com"
        result.description shouldBe "test desc"
        result.attribution shouldBe "test attr"
        result.styles shouldBe "test styles"
        result.sourceLanguage shouldBe "ja"
        result.targetLanguage shouldBe "en"
        result.isEnabled shouldBe false
        result.dateAdded shouldBe 12345L
        result.backend shouldBe DictionaryBackend.HOSHI
        result.storagePath shouldBe "/path"
        result.storageReady shouldBe true
        // Only priority should change
        result.priority shouldBe 1
    }
}
