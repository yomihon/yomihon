package mihon.data.dictionary

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryKanjiExport
import mihon.domain.dictionary.model.DictionaryKanjiMetaExport
import mihon.domain.dictionary.model.DictionaryLegacyRowCounts
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTermExport
import mihon.domain.dictionary.model.DictionaryTermMetaExport
import mihon.domain.dictionary.repository.DictionaryLegacyRepository
import mihon.domain.dictionary.repository.DictionaryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

class LegacyDictionaryArchiveBuilderTest {
    private lateinit var repository: DictionaryRepository
    private lateinit var legacyRepository: DictionaryLegacyRepository
    private lateinit var builder: LegacyDictionaryArchiveBuilder
    private lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        repository = mockk()
        legacyRepository = mockk()
        builder = LegacyDictionaryArchiveBuilder(repository, legacyRepository)
        tempDir = createTempDir(prefix = "legacy-dict-archive-test")
    }

    @AfterEach
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `buildArchive reconstructs yomitan zip with core banks`() = runTest {
        val dictionary = Dictionary(
            id = 1L,
            title = "Test Dict",
            revision = "2026",
            version = 3,
            author = "Author",
            styles = ".gloss { color: red; }",
            sourceLanguage = "ja",
            targetLanguage = "en",
        )

        coEvery { repository.getTagsForDictionary(1L) } returns listOf(
            DictionaryTag(
                dictionaryId = 1L,
                name = "n",
                category = "partOfSpeech",
                order = 1,
                notes = "noun",
                score = 0,
            ),
        )
        coEvery { legacyRepository.getLegacyRowCounts(1L) } returns DictionaryLegacyRowCounts(
            tagCount = 1L,
            termCount = 1L,
            termMetaCount = 1L,
            kanjiCount = 0L,
            kanjiMetaCount = 0L,
        )
        coEvery { legacyRepository.getTermsExportForDictionary(1L, any(), 0L) } returns listOf(
            DictionaryTermExport(
                expression = "食べる",
                reading = "たべる",
                definitionTags = "n",
                rules = "v1",
                score = 10,
                glossaryJson = """["to eat"]""",
                sequence = 42L,
                termTags = "common",
            ),
        )
        coEvery { legacyRepository.getTermsExportForDictionary(1L, any(), 1L) } returns emptyList()
        coEvery { legacyRepository.getTermMetaExportForDictionary(1L, any(), 0L) } returns listOf(
            DictionaryTermMetaExport(
                expression = "食べる",
                mode = "freq",
                dataJson = """{"value":12,"displayValue":"12"}""",
            ),
        )
        coEvery { legacyRepository.getTermMetaExportForDictionary(1L, any(), 1L) } returns emptyList()
        coEvery { legacyRepository.getKanjiExportForDictionary(1L, any(), any()) } returns emptyList()
        coEvery { legacyRepository.getKanjiMetaExportForDictionary(1L, any(), any()) } returns emptyList()

        val output = File(tempDir, "dictionary.zip")
        val result = builder.buildArchive(dictionary, output.absolutePath)

        result.sampleExpression shouldBe "食べる"

        ZipFile(output).use { zip ->
            (zip.getEntry("index.json") != null) shouldBe true
            (zip.getEntry("styles.css") != null) shouldBe true
            (zip.getEntry("tag_bank_1.json") != null) shouldBe true
            (zip.getEntry("term_bank_1.json") != null) shouldBe true
            (zip.getEntry("term_meta_bank_1.json") != null) shouldBe true

            zip.getInputStream(zip.getEntry("index.json")).bufferedReader().use { reader ->
                reader.readText() shouldContain "\"title\":\"Test Dict\""
            }
            zip.getInputStream(zip.getEntry("term_bank_1.json")).bufferedReader().use { reader ->
                val termBank = reader.readText()
                termBank shouldContain "食べる"
                termBank shouldContain "to eat"
            }
            zip.getInputStream(zip.getEntry("term_meta_bank_1.json")).bufferedReader().use { reader ->
                val termMetaBank = reader.readText()
                termMetaBank shouldContain """["食べる","freq",{"value":12,"displayValue":"12"}]"""
            }
        }
    }

    @Test
    fun `buildArchive preserves raw glossary and kanji json without reshaping`() = runTest {
        val dictionary = Dictionary(
            id = 2L,
            title = "Structured Dict",
            revision = "2026",
            version = 3,
        )

        coEvery { repository.getTagsForDictionary(2L) } returns emptyList()
        coEvery { legacyRepository.getLegacyRowCounts(2L) } returns DictionaryLegacyRowCounts(
            tagCount = 0L,
            termCount = 1L,
            termMetaCount = 1L,
            kanjiCount = 1L,
            kanjiMetaCount = 1L,
        )
        coEvery { legacyRepository.getTermsExportForDictionary(2L, any(), 0L) } returns listOf(
            DictionaryTermExport(
                expression = "語る",
                reading = "かたる",
                definitionTags = null,
                rules = null,
                score = 0,
                glossaryJson = """["to tell",{"type":"structured-content","content":["story"]}]""",
                sequence = null,
                termTags = null,
            ),
        )
        coEvery { legacyRepository.getTermsExportForDictionary(2L, any(), 1L) } returns emptyList()
        coEvery { legacyRepository.getTermMetaExportForDictionary(2L, any(), 0L) } returns listOf(
            DictionaryTermMetaExport(
                expression = "語る",
                mode = "pitch",
                dataJson = """{"reading":"かたる","pitches":[{"position":2}]}""",
            ),
        )
        coEvery { legacyRepository.getTermMetaExportForDictionary(2L, any(), 1L) } returns emptyList()
        coEvery { legacyRepository.getKanjiExportForDictionary(2L, any(), 0L) } returns listOf(
            DictionaryKanjiExport(
                character = "日",
                onyomi = "ニチ",
                kunyomi = "ひ",
                tags = "common",
                meaningsJson = """["sun","day"]""",
                statsJson = """{"jlpt":"N5"}""",
            ),
        )
        coEvery { legacyRepository.getKanjiExportForDictionary(2L, any(), 1L) } returns emptyList()
        coEvery { legacyRepository.getKanjiMetaExportForDictionary(2L, any(), 0L) } returns listOf(
            DictionaryKanjiMetaExport(
                character = "日",
                mode = "freq",
                dataJson = """{"value":1}""",
            ),
        )
        coEvery { legacyRepository.getKanjiMetaExportForDictionary(2L, any(), 1L) } returns emptyList()

        val output = File(tempDir, "structured.zip")
        builder.buildArchive(dictionary, output.absolutePath)

        ZipFile(output).use { zip ->
            zip.getInputStream(zip.getEntry("term_bank_1.json")).bufferedReader().use { reader ->
                val termBank = reader.readText()
                termBank shouldContain """"structured-content""""
                termBank shouldContain """"story""""
            }
            zip.getInputStream(zip.getEntry("kanji_bank_1.json")).bufferedReader().use { reader ->
                val kanjiBank = reader.readText()
                kanjiBank shouldContain """"sun""""
                kanjiBank shouldContain """"jlpt":"N5""""
            }
            zip.getInputStream(zip.getEntry("kanji_meta_bank_1.json")).bufferedReader().use { reader ->
                reader.readText() shouldContain """"value":1"""
            }
        }
    }

    @Test
    fun `buildArchive converts internal frequency metadata back to legacy zip format`() = runTest {
        val dictionary = Dictionary(
            id = 3L,
            title = "Freq Dict",
            revision = "2026",
            version = 3,
        )

        coEvery { repository.getTagsForDictionary(3L) } returns emptyList()
        coEvery { legacyRepository.getLegacyRowCounts(3L) } returns DictionaryLegacyRowCounts(
            tagCount = 0L,
            termCount = 0L,
            termMetaCount = 2L,
            kanjiCount = 0L,
            kanjiMetaCount = 0L,
        )
        coEvery { legacyRepository.getTermsExportForDictionary(3L, any(), any()) } returns emptyList()
        coEvery { legacyRepository.getTermMetaExportForDictionary(3L, any(), 0L) } returns listOf(
            DictionaryTermMetaExport(
                expression = "食べる",
                mode = "freq",
                dataJson = """{"reading":"たべる","value":12,"displayValue":"12"}""",
            ),
            DictionaryTermMetaExport(
                expression = "見る",
                mode = "freq",
                dataJson = """{"value":3,"displayValue":"3"}""",
            ),
        )
        coEvery { legacyRepository.getTermMetaExportForDictionary(3L, any(), 2L) } returns emptyList()
        coEvery { legacyRepository.getKanjiExportForDictionary(3L, any(), any()) } returns emptyList()
        coEvery { legacyRepository.getKanjiMetaExportForDictionary(3L, any(), any()) } returns emptyList()

        val output = File(tempDir, "freq.zip")
        builder.buildArchive(dictionary, output.absolutePath)

        ZipFile(output).use { zip ->
            zip.getInputStream(zip.getEntry("term_meta_bank_1.json")).bufferedReader().use { reader ->
                val termMetaBank = reader.readText()
                termMetaBank shouldContain """"reading":"たべる","frequency":{"value":12,"displayValue":"12"}"""
                termMetaBank shouldContain """["見る","freq",{"value":3,"displayValue":"3"}]"""
            }
        }
    }

    @Test
    fun `buildArchive converts internal pitch metadata back to legacy zip format`() = runTest {
        val dictionary = Dictionary(
            id = 4L,
            title = "Pitch Dict",
            revision = "2026",
            version = 3,
        )

        coEvery { repository.getTagsForDictionary(4L) } returns emptyList()
        coEvery { legacyRepository.getLegacyRowCounts(4L) } returns DictionaryLegacyRowCounts(
            tagCount = 0L,
            termCount = 0L,
            termMetaCount = 1L,
            kanjiCount = 0L,
            kanjiMetaCount = 0L,
        )
        coEvery { legacyRepository.getTermsExportForDictionary(4L, any(), any()) } returns emptyList()
        coEvery { legacyRepository.getTermMetaExportForDictionary(4L, any(), 0L) } returns listOf(
            DictionaryTermMetaExport(
                expression = "語る",
                mode = "pitch",
                dataJson = """
                {
                    "reading":"かたる",
                    "pitches":[
                        {
                            "position":"2",
                            "nasal":["1","3"],
                            "devoice":"2",
                            "tags":["common",1],
                            "extra":"ignored"
                        }
                    ],
                    "extra":"ignored"
                }
                """.trimIndent(),
            ),
        )
        coEvery { legacyRepository.getTermMetaExportForDictionary(4L, any(), 1L) } returns emptyList()
        coEvery { legacyRepository.getKanjiExportForDictionary(4L, any(), any()) } returns emptyList()
        coEvery { legacyRepository.getKanjiMetaExportForDictionary(4L, any(), any()) } returns emptyList()

        val output = File(tempDir, "pitch.zip")
        builder.buildArchive(dictionary, output.absolutePath)

        ZipFile(output).use { zip ->
            zip.getInputStream(zip.getEntry("term_meta_bank_1.json")).bufferedReader().use { reader ->
                val termMetaBank = reader.readText()
                termMetaBank shouldContain
                    """"reading":"かたる","pitches":[{"position":2,"nasal":[1,3],"devoice":2,"tags":["common","1"]}]"""
            }
        }
    }
}
