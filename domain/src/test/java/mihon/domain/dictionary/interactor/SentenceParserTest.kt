package mihon.domain.dictionary.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.repository.DictionaryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SentenceParserTest {
    private lateinit var dictionaryRepository: DictionaryRepository
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

        // Default: return empty for any query
        coEvery { dictionaryRepository.searchTerms(any(), any()) } returns emptyList()

        searchDictionaryTerms = SearchDictionaryTerms(dictionaryRepository)
    }

    @Test
    fun `gets the word from sentence`() = runTest {
        // Setup: "食べる" exists in dictionary
        coEvery { dictionaryRepository.searchTerms("食べる", testDictionaryIds) } returns listOf(
            mockTerm("食べる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.getWord("食べるって言ったよ", testDictionaryIds)

        word shouldBe "食べる"
    }

    @Test
    fun `gets the word when romaji input`() = runTest {
        // Setup: "たべる" (kana form) exists in dictionary
        coEvery { dictionaryRepository.searchTerms("たべる", testDictionaryIds) } returns listOf(
            mockTerm("たべる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.getWord("taberutte itta yo", testDictionaryIds)

        word shouldBe "たべる"
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

        val word = searchDictionaryTerms.getWord("食べ物っていいね", testDictionaryIds)

        word shouldBe "食べ物"
    }

    @Test
    fun `handles word with leading brackets`() = runTest {
        // Setup: "食べる" exists in dictionary
        coEvery { dictionaryRepository.searchTerms("食べる", testDictionaryIds) } returns listOf(
            mockTerm("食べる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.getWord("「食べる」って言ったよ", testDictionaryIds)

        word shouldBe "食べる"
    }

    @Test
    fun `returns first character when no match found`() = runTest {
        // No dictionary entries configured, so a match is impossible

        val word = searchDictionaryTerms.getWord("あいうえお", testDictionaryIds)

        word shouldBe "あ"
    }

    @Test
    fun `returns empty string for blank input`() = runTest {
        val word = searchDictionaryTerms.getWord("   ", testDictionaryIds)

        word shouldBe ""
    }

    @Test
    fun `returns empty string for empty dictionary ids`() = runTest {
        val word = searchDictionaryTerms.getWord("食べる", emptyList())

        word shouldBe ""
    }

    @Test
    fun `handles deinflection for longest match`() = runTest {
        // Setup: "食べる" (dictionary form of 食べた) exists
        coEvery { dictionaryRepository.searchTerms("食べる", testDictionaryIds) } returns listOf(
            mockTerm("食べる", "たべる", "v1"),
        )

        val word = searchDictionaryTerms.getWord("食べたって言ったよ", testDictionaryIds)

        // Returns the original substring "食べた", not the deinflected form "食べる"
        word shouldBe "食べた"
    }

    @Test
    fun `matches word by reading when expression is different`() = runTest {
        coEvery { dictionaryRepository.searchTerms("わたし", testDictionaryIds) } returns listOf(
            mockTerm(expression = "私", reading = "わたし", rules = "n"),
        )

        val word = searchDictionaryTerms.getWord("わたしはです", testDictionaryIds)

        word shouldBe "わたし"
    }
}