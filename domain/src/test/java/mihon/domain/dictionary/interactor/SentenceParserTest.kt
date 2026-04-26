package mihon.domain.dictionary.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.DictionaryLookupMatch
import mihon.domain.dictionary.service.DictionarySearchEntry
import mihon.domain.dictionary.service.DictionarySearchGateway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SentenceParserTest {
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var dictionarySearchGateway: DictionarySearchGateway
    private lateinit var searchDictionaryTerms: SearchDictionaryTerms
    private val testDictionaryIds = listOf(1L)

    // Helper to create a mock DictionaryTerm
    private fun mockTerm(expression: String, reading: String = "", rules: String = ""): DictionaryTerm {
        return DictionaryTerm(
            id = expression.hashCode().toLong(),
            dictionaryId = 1L,
            expression = expression,
            reading = reading,
            rules = rules,
            definitionTags = "",
            glossary = emptyList(),
            sequence = 0,
            termTags = "",
            score = 0,
        )
    }

    @BeforeEach
    fun setup() {
        dictionaryRepository = mockk()
        dictionarySearchGateway = mockk()
        val defaultDictionary = Dictionary(
            id = 1L,
            title = "Test",
            revision = "1",
            version = 1,
            sourceLanguage = null,
        )

        // Default: return empty for any query
        coEvery { dictionaryRepository.searchTerms(any(), any()) } returns emptyList()
        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(defaultDictionary)
        coEvery { dictionarySearchGateway.exactSearch(any(), any()) } answers {
            runBlocking {
                dictionaryRepository.searchTerms(firstArg(), secondArg()).map { DictionarySearchEntry(it, emptyList()) }
            }
        }
        coEvery { dictionarySearchGateway.lookup(any(), any(), any()) } returns emptyList<DictionaryLookupMatch>()
        coEvery { dictionarySearchGateway.getTermMeta(any(), any()) } returns emptyMap()

        searchDictionaryTerms = SearchDictionaryTerms(dictionaryRepository, dictionarySearchGateway)
    }

    @Test
    fun `gets the word from sentence`() = runTest {
        // Setup: "食べる" exists in dictionary
        coEvery { dictionaryRepository.searchTerms("食べる", testDictionaryIds) } returns listOf(
            mockTerm("食べる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.findFirstWord("食べるって言ったよ", testDictionaryIds)

        word shouldBe "食べる"
    }

    @Test
    fun `gets the word when romaji input`() = runTest {
        // Setup: "たべる" (kana form) exists in dictionary
        coEvery { dictionaryRepository.searchTerms("たべる", testDictionaryIds) } returns listOf(
            mockTerm("たべる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.findFirstWord("taberutte itta yo", testDictionaryIds)

        word shouldBe "たべる"
    }

    @Test
    fun `findFirstWordMatch returns full romaji source length`() = runTest {
        coEvery { dictionaryRepository.searchTerms("かつて", testDictionaryIds) } returns listOf(
            mockTerm("かつて", "かつて", "v1"),
        )

        val match = searchDictionaryTerms.findFirstWordMatch("katsute ha kotoba desu ne", testDictionaryIds)

        match.word shouldBe "かつて"
        match.sourceOffset shouldBe 0
        match.sourceLength shouldBe 7
    }

    @Test
    fun `findFirstWordMatch tracks leading punctuation offset`() = runTest {
        coEvery { dictionaryRepository.searchTerms("たべる", testDictionaryIds) } returns listOf(
            mockTerm("たべる", "たべる", "v1"),
        )

        val match = searchDictionaryTerms.findFirstWordMatch("「taberu」", testDictionaryIds)

        match.word shouldBe "たべる"
        match.sourceOffset shouldBe 1
        match.sourceLength shouldBe 6
    }

    @Test
    fun `findFirstWordMatch ignores OCR spaces inside Japanese text and preserves source range`() = runTest {
        coEvery { dictionaryRepository.searchTerms("私は", testDictionaryIds) } returns listOf(
            mockTerm("私は", "わたしは", "n"),
        )

        val match = searchDictionaryTerms.findFirstWordMatch("私 は 学生", testDictionaryIds)

        match.word shouldBe "私は"
        match.sourceOffset shouldBe 0
        match.sourceLength shouldBe 3
    }

    @Test
    fun `gets the longest word match`() = runTest {
        // Setup: both "食べ" "食べる" and "食べ物" exist, but "食べ物" is longer
        coEvery { dictionaryRepository.searchTerms("食べ物", testDictionaryIds) } returns listOf(
            mockTerm("食べ物", "たべもの", "n"),
        )
        coEvery { dictionaryRepository.searchTerms("食べ", testDictionaryIds) } returns listOf(
            mockTerm("食べ", "たべ", "v1"),
        )
        coEvery { dictionaryRepository.searchTerms("食べる", testDictionaryIds) } returns listOf(
            mockTerm("食べる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.findFirstWord("食べ物が好き", testDictionaryIds)

        word shouldBe "食べ物"
    }

    @Test
    fun `handles word with leading brackets`() = runTest {
        // Setup: "食べる" exists in dictionary
        coEvery { dictionaryRepository.searchTerms("食べる", testDictionaryIds) } returns listOf(
            mockTerm("食べる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.findFirstWord("「食べる」って言ったよ", testDictionaryIds)

        word shouldBe "食べる"
    }

    @Test
    fun `returns first character when no match found`() = runTest {
        // No dictionary entries configured, so a match is impossible

        val word = searchDictionaryTerms.findFirstWord("あいうえお", testDictionaryIds)

        word shouldBe "あ"
    }

    @Test
    fun `returns empty string for blank input`() = runTest {
        val word = searchDictionaryTerms.findFirstWord("   ", testDictionaryIds)

        word shouldBe ""
    }

    @Test
    fun `returns empty string for empty dictionary ids`() = runTest {
        val word = searchDictionaryTerms.findFirstWord("食べる", emptyList())

        word shouldBe ""
    }

    @Test
    fun `handles deinflection for longest match`() = runTest {
        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(
            Dictionary(
                id = 1L,
                title = "Japanese",
                revision = "1",
                version = 1,
                sourceLanguage = "ja",
                backend = DictionaryBackend.HOSHI,
                storageReady = true,
            ),
        )
        coEvery { dictionarySearchGateway.lookup("食べたって言ったよ", testDictionaryIds, any()) } returns listOf(
            DictionaryLookupMatch(
                matched = "食べた",
                deinflected = "食べる",
                process = listOf("past"),
                term = mockTerm("食べる", "たべる", "v1"),
                termMeta = emptyList(),
            ),
        )

        val word = searchDictionaryTerms.findFirstWord("食べたって言ったよ", testDictionaryIds)

        word shouldBe "食べた"
        coVerify(exactly = 1) { dictionarySearchGateway.lookup("食べたって言ったよ", testDictionaryIds, any()) }
        coVerify(exactly = 0) { dictionarySearchGateway.exactSearch(any(), any()) }
    }

    @Test
    fun `matches word by reading when expression is different`() = runTest {
        coEvery { dictionaryRepository.searchTerms("わたし", testDictionaryIds) } returns listOf(
            mockTerm(expression = "私", reading = "わたし", rules = "n"),
        )

        val word = searchDictionaryTerms.findFirstWord("わたしはです", testDictionaryIds)

        word shouldBe "わたし"
    }

    // Multilingual / script-detection tests

    @Test
    fun `searches English term by direct lookup`() = runTest {
        coEvery { dictionaryRepository.searchTerms("apple", testDictionaryIds) } returns listOf(
            mockTerm("apple"),
        )

        val results = searchDictionaryTerms.search("apple", testDictionaryIds)

        results.map { it.expression } shouldBe listOf("apple")
    }

    @Test
    fun `searches romaji as Japanese fallback when no direct match`() = runTest {
        // "taberu" has no direct English entry, but kana form "たべる" exists
        coEvery { dictionaryRepository.searchTerms("たべる", testDictionaryIds) } returns listOf(
            mockTerm("たべる", "たべる", "v1"),
        )

        val results = searchDictionaryTerms.search("taberu", testDictionaryIds)

        results.map { it.expression } shouldBe listOf("たべる")
    }

    @Test
    fun `finds English multi-word phrase as longest match`() = runTest {
        coEvery { dictionaryRepository.searchTerms("to be", testDictionaryIds) } returns listOf(
            mockTerm("to be"),
        )

        val word = searchDictionaryTerms.findFirstWord("to be or not to be", testDictionaryIds)

        word shouldBe "to be"
    }

    @Test
    fun `falls back to first English word when not in dictionary`() = runTest {
        // No mock entries — nothing matches
        val word = searchDictionaryTerms.findFirstWord("hello world", testDictionaryIds)

        word shouldBe "hello"
    }

    @Test
    fun `segments Chinese text by longest character match`() = runTest {
        coEvery { dictionaryRepository.searchTerms("你好", testDictionaryIds) } returns listOf(
            mockTerm("你好"),
        )

        val word = searchDictionaryTerms.findFirstWord("你好世界", testDictionaryIds)

        word shouldBe "你好"
    }

    @Test
    fun `segments Chinese text across OCR spaces`() = runTest {
        coEvery { dictionaryRepository.searchTerms("你好", testDictionaryIds) } returns listOf(
            mockTerm("你好"),
        )

        val match = searchDictionaryTerms.findFirstWordMatch("你 好 世界", testDictionaryIds)

        match.word shouldBe "你好"
        match.sourceOffset shouldBe 0
        match.sourceLength shouldBe 3
    }

    @Test
    fun `segments Korean text by longest character match`() = runTest {
        coEvery { dictionaryRepository.searchTerms("안녕", testDictionaryIds) } returns listOf(
            mockTerm("안녕"),
        )

        val word = searchDictionaryTerms.findFirstWord("안녕하세요", testDictionaryIds)

        word shouldBe "안녕"
    }

    @Test
    fun `preserves Korean spaces for direct lookup`() = runTest {
        coEvery { dictionaryRepository.searchTerms("안녕 하세요", testDictionaryIds) } returns listOf(
            mockTerm("안녕 하세요"),
        )

        val word = searchDictionaryTerms.findFirstWord("안녕 하세요 반가워요", testDictionaryIds)

        word shouldBe "안녕 하세요"
    }

    @Test
    fun `detects Japanese in mixed kanji kana text`() = runTest {
        // "食べる" starts with CJK kanji but contains hiragana — must use Japanese pipeline
        coEvery { dictionaryRepository.searchTerms("食べる", testDictionaryIds) } returns listOf(
            mockTerm("食べる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.findFirstWord("食べる世界", testDictionaryIds)

        word shouldBe "食べる"
    }

    @Test
    fun `preserves English apostrophes correctly`() = runTest {
        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(
            Dictionary(
                title = "Test",
                revision = "1",
                version = 1,
                sourceLanguage = "en",
            ),
        )
        coEvery { dictionaryRepository.searchTerms("don't", testDictionaryIds) } returns listOf(
            mockTerm("don't"),
        )

        val match = searchDictionaryTerms.findFirstWordMatch("don't do that", testDictionaryIds)
        match.word shouldBe "don't"
        match.sourceLength shouldBe 5
    }

    @Test
    fun `segments kanji-only Japanese correctly when restricted`() = runTest {
        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(
            Dictionary(
                title = "Test",
                revision = "1",
                version = 1,
                sourceLanguage = "ja",
            ),
        )
        coEvery { dictionaryRepository.searchTerms("世界", testDictionaryIds) } returns listOf(
            mockTerm("世界", "せかい", "n"),
        )

        val word = searchDictionaryTerms.findFirstWord("世界", testDictionaryIds)

        word shouldBe "世界"
    }
}
