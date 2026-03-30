package mihon.domain.dictionary.interactor

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.TermMetaMode
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.DictionaryLookupMatch
import mihon.domain.dictionary.service.DictionarySearchBackend
import mihon.domain.dictionary.service.DictionarySearchEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SearchDictionaryTermsHybridTest {
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var dictionarySearchBackend: DictionarySearchBackend
    private lateinit var searchDictionaryTerms: SearchDictionaryTerms

    @BeforeEach
    fun setup() {
        dictionaryRepository = mockk()
        dictionarySearchBackend = mockk()

        coEvery { dictionaryRepository.searchTerms(any(), any()) } returns emptyList()
        coEvery { dictionaryRepository.getTermMetaForExpression(any(), any()) } returns emptyList()
        coEvery { dictionaryRepository.getAllDictionaries() } returns emptyList()
        coEvery { dictionarySearchBackend.exactSearch(any(), any()) } returns emptyList()
        coEvery { dictionarySearchBackend.lookup(any(), any(), any()) } returns emptyList<DictionaryLookupMatch>()

        searchDictionaryTerms = SearchDictionaryTerms(dictionaryRepository, dictionarySearchBackend)
    }

    @Test
    fun `search uses hoshi backend for migrated direct lookup`() = runTest {
        val migratedDictionary = Dictionary(
            id = 1L,
            title = "Migrated",
            revision = "1",
            version = 3,
            sourceLanguage = "en",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 1,
        )
        val meta = DictionaryTermMeta(
            dictionaryId = 1L,
            expression = "apple",
            mode = TermMetaMode.FREQUENCY,
            data = """{"value":7,"displayValue":"7"}""",
        )
        val term = DictionaryTerm(
            dictionaryId = 1L,
            expression = "apple",
            reading = "apple",
            definitionTags = null,
            rules = null,
            score = 10,
            glossary = emptyList(),
            termTags = null,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(migratedDictionary)
        coEvery { dictionarySearchBackend.exactSearch("apple", listOf(1L)) } returns listOf(
            DictionarySearchEntry(term = term, termMeta = listOf(meta)),
        )

        val results = searchDictionaryTerms.search("apple", listOf(1L))
        val termMeta = searchDictionaryTerms.getTermMeta(listOf("apple"), listOf(1L))

        results.map { it.expression } shouldBe listOf("apple")
        termMeta["apple"].orEmpty() shouldBe listOf(meta)
    }

    @Test
    fun `mixed legacy and hoshi results stay ordered by dictionary priority`() = runTest {
        val legacyDictionary = Dictionary(
            id = 1L,
            title = "Legacy",
            revision = "1",
            version = 3,
            sourceLanguage = "en",
            backend = DictionaryBackend.LEGACY_DB,
            priority = 1,
        )
        val migratedDictionary = Dictionary(
            id = 2L,
            title = "Migrated",
            revision = "1",
            version = 3,
            sourceLanguage = "en",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 2,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(legacyDictionary, migratedDictionary)

        coEvery { dictionaryRepository.searchTerms("apple", listOf(1L)) } returns listOf(
            DictionaryTerm(
                dictionaryId = 1L,
                expression = "apple",
                reading = "apple",
                definitionTags = null,
                rules = null,
                score = 50,
                glossary = emptyList(),
                termTags = null,
            ),
        )

        coEvery { dictionarySearchBackend.exactSearch("apple", listOf(2L)) } returns listOf(
            DictionarySearchEntry(
                term = DictionaryTerm(
                    dictionaryId = 2L,
                    expression = "apple",
                    reading = "apple",
                    definitionTags = null,
                    rules = null,
                    score = 100,
                    glossary = emptyList(),
                    termTags = null,
                ),
                termMeta = emptyList(),
            ),
        )

        val results = searchDictionaryTerms.search("apple", listOf(1L, 2L))

        results shouldHaveSize 2
        results.map { it.dictionaryId } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `empty dictionary selection returns no results without touching either backend`() = runTest {
        val results = searchDictionaryTerms.search("apple", emptyList())
        val meta = searchDictionaryTerms.getTermMeta(listOf("apple"), emptyList())

        results shouldBe emptyList()
        meta["apple"] shouldBe emptyList()

        coVerify(exactly = 0) { dictionaryRepository.searchTerms(any(), any()) }
        coVerify(exactly = 0) { dictionarySearchBackend.exactSearch(any(), any()) }
    }

    @Test
    fun `japanese search uses hoshi lookup for deinflection`() = runTest {
        val japaneseDictionary = Dictionary(
            id = 1L,
            title = "Japanese",
            revision = "1",
            version = 3,
            sourceLanguage = "ja",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
        )
        val term = DictionaryTerm(
            dictionaryId = 1L,
            expression = "食べる",
            reading = "たべる",
            definitionTags = null,
            rules = "v1",
            score = 0,
            glossary = emptyList(),
            termTags = null,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(japaneseDictionary)
        coEvery { dictionarySearchBackend.lookup("食べた", listOf(1L), any()) } returns listOf(
            DictionaryLookupMatch(
                matched = "食べた",
                deinflected = "食べる",
                process = listOf("past"),
                term = term,
                termMeta = emptyList(),
            ),
        )

        val results = searchDictionaryTerms.search("食べた", listOf(1L))

        results.map { it.expression } shouldBe listOf("食べる")
        coVerify(exactly = 1) { dictionarySearchBackend.lookup("食べた", listOf(1L), any()) }
        coVerify(exactly = 0) { dictionaryRepository.searchTerms(any(), any()) }
        coVerify(exactly = 0) { dictionarySearchBackend.exactSearch(any(), any()) }
    }

    @Test
    fun `english search uses kotlin deinflection with exact lookup`() = runTest {
        val englishDictionary = Dictionary(
            id = 1L,
            title = "English",
            revision = "1",
            version = 3,
            sourceLanguage = "en",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
        )
        val term = DictionaryTerm(
            dictionaryId = 1L,
            expression = "look",
            reading = "look",
            definitionTags = null,
            rules = null,
            score = 0,
            glossary = emptyList(),
            termTags = null,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(englishDictionary)
        coEvery { dictionarySearchBackend.exactSearch("looked", listOf(1L)) } returns emptyList()
        coEvery { dictionarySearchBackend.exactSearch("look", listOf(1L)) } returns listOf(
            DictionarySearchEntry(term = term, termMeta = emptyList()),
        )

        val results = searchDictionaryTerms.search("looked", listOf(1L))

        results.map { it.expression } shouldBe listOf("look")
        coVerify(exactly = 1) { dictionarySearchBackend.exactSearch("looked", listOf(1L)) }
        coVerify(atLeast = 1) { dictionarySearchBackend.exactSearch("look", listOf(1L)) }
        coVerify(exactly = 0) { dictionarySearchBackend.lookup(any(), any(), any()) }
    }
}
