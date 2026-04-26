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
import mihon.domain.dictionary.service.DictionarySearchEntry
import mihon.domain.dictionary.service.DictionarySearchGateway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SearchDictionaryTermsHybridTest {
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var dictionarySearchGateway: DictionarySearchGateway
    private lateinit var searchDictionaryTerms: SearchDictionaryTerms

    @BeforeEach
    fun setup() {
        dictionaryRepository = mockk()
        dictionarySearchGateway = mockk()

        coEvery { dictionaryRepository.getAllDictionaries() } returns emptyList()
        coEvery { dictionarySearchGateway.exactSearch(any(), any()) } returns emptyList()
        coEvery { dictionarySearchGateway.lookup(any(), any(), any()) } returns emptyList<DictionaryLookupMatch>()
        coEvery { dictionarySearchGateway.getTermMeta(any(), any()) } returns emptyMap()

        searchDictionaryTerms = SearchDictionaryTerms(dictionaryRepository, dictionarySearchGateway)
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
        coEvery { dictionarySearchGateway.exactSearch("apple", listOf(1L)) } returns listOf(
            DictionarySearchEntry(term = term, termMeta = listOf(meta)),
        )
        coEvery { dictionarySearchGateway.getTermMeta(listOf("apple"), listOf(1L)) } returns
            mapOf("apple" to listOf(meta))

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

        coEvery { dictionarySearchGateway.exactSearch("apple", listOf(1L, 2L)) } returns listOf(
            DictionarySearchEntry(
                term = DictionaryTerm(
                    dictionaryId = 1L,
                    expression = "apple",
                    reading = "apple",
                    definitionTags = null,
                    rules = null,
                    score = 50,
                    glossary = emptyList(),
                    termTags = null,
                ),
                termMeta = emptyList(),
            ),
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
    fun `exact expression match outranks higher priority reading match`() = runTest {
        val higherPriorityDictionary = Dictionary(
            id = 1L,
            title = "HighPriority",
            revision = "1",
            version = 3,
            sourceLanguage = "en",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 1,
        )
        val lowerPriorityDictionary = Dictionary(
            id = 2L,
            title = "LowerPriority",
            revision = "1",
            version = 3,
            sourceLanguage = "en",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 2,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(
            higherPriorityDictionary,
            lowerPriorityDictionary,
        )

        coEvery { dictionarySearchGateway.exactSearch("apple", listOf(1L, 2L)) } returns listOf(
            DictionarySearchEntry(
                term = DictionaryTerm(
                    dictionaryId = 1L,
                    expression = "apples",
                    reading = "apple",
                    definitionTags = null,
                    rules = null,
                    score = 999,
                    glossary = emptyList(),
                    termTags = null,
                ),
                termMeta = emptyList(),
            ),
            DictionarySearchEntry(
                term = DictionaryTerm(
                    dictionaryId = 2L,
                    expression = "apple",
                    reading = "apple",
                    definitionTags = null,
                    rules = null,
                    score = 1,
                    glossary = emptyList(),
                    termTags = null,
                ),
                termMeta = emptyList(),
            ),
        )

        val results = searchDictionaryTerms.search(
            query = "apple",
            dictionaryIds = listOf(1L, 2L),
            parserLanguage = ParserLanguage.CHINESE,
        )

        results.map { it.dictionaryId } shouldBe listOf(2L, 1L)
    }

    @Test
    fun `empty dictionary selection returns no results without touching the search gateway`() = runTest {
        val results = searchDictionaryTerms.search("apple", emptyList())
        val meta = searchDictionaryTerms.getTermMeta(listOf("apple"), emptyList())

        results shouldBe emptyList()
        meta["apple"] shouldBe emptyList()

        coVerify(exactly = 0) { dictionarySearchGateway.exactSearch(any(), any()) }
    }

    @Test
    fun `japanese search uses lookup for deinflection`() = runTest {
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
        coEvery { dictionarySearchGateway.lookup("食べた", listOf(1L), any()) } returns listOf(
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
        coVerify(exactly = 1) { dictionarySearchGateway.lookup("食べた", listOf(1L), any()) }
        coVerify(exactly = 0) { dictionarySearchGateway.exactSearch(any(), any()) }
    }

    @Test
    fun `japanese lookup preserves backend order when scores tie`() = runTest {
        val japaneseDictionary = Dictionary(
            id = 1L,
            title = "Japanese",
            revision = "1",
            version = 3,
            sourceLanguage = "ja",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 1,
        )

        val longerMatch = DictionaryTerm(
            dictionaryId = 1L,
            expression = "食べる",
            reading = "たべる",
            definitionTags = null,
            rules = "v1",
            score = 0,
            glossary = emptyList(),
            termTags = null,
        )
        val shorterMatch = DictionaryTerm(
            dictionaryId = 1L,
            expression = "食う",
            reading = "くう",
            definitionTags = null,
            rules = "v5",
            score = 0,
            glossary = emptyList(),
            termTags = null,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(japaneseDictionary)
        coEvery { dictionarySearchGateway.lookup("食べた", listOf(1L), any()) } returns listOf(
            DictionaryLookupMatch(
                matched = "食べた",
                deinflected = "食べる",
                process = listOf("past"),
                term = longerMatch,
                termMeta = emptyList(),
            ),
            DictionaryLookupMatch(
                matched = "食べ",
                deinflected = "食う",
                process = listOf("dictionary"),
                term = shorterMatch,
                termMeta = emptyList(),
            ),
        )

        val results = searchDictionaryTerms.search("食べた", listOf(1L))

        results.map { it.expression } shouldBe listOf("食べる", "食う")
    }

    @Test
    fun `japanese search removes OCR spaces before lookup`() = runTest {
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
            expression = "私は",
            reading = "わたしは",
            definitionTags = null,
            rules = null,
            score = 0,
            glossary = emptyList(),
            termTags = null,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(japaneseDictionary)
        coEvery { dictionarySearchGateway.lookup("私は", listOf(1L), any()) } returns listOf(
            DictionaryLookupMatch(
                matched = "私は",
                deinflected = "私は",
                process = listOf("dictionary"),
                term = term,
                termMeta = emptyList(),
            ),
        )

        val results = searchDictionaryTerms.search("私 は", listOf(1L))

        results.map { it.expression } shouldBe listOf("私は")
        coVerify(exactly = 1) { dictionarySearchGateway.lookup("私は", listOf(1L), any()) }
    }

    @Test
    fun `japanese lookup ranks exact expression then reading then deinflected matches`() = runTest {
        val japaneseDictionary = Dictionary(
            id = 1L,
            title = "Japanese",
            revision = "1",
            version = 3,
            sourceLanguage = "ja",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 1,
        )

        val exactExpression = DictionaryTerm(
            dictionaryId = 1L,
            expression = "まじ",
            reading = "まじ",
            definitionTags = null,
            rules = null,
            score = 1,
            glossary = emptyList(),
            termTags = null,
        )
        val readingMatch = DictionaryTerm(
            dictionaryId = 1L,
            expression = "交じ",
            reading = "まじ",
            definitionTags = null,
            rules = null,
            score = 50,
            glossary = emptyList(),
            termTags = null,
        )
        val deinflectedMatch = DictionaryTerm(
            dictionaryId = 1L,
            expression = "混じる",
            reading = "まじる",
            definitionTags = null,
            rules = "v1",
            score = 999,
            glossary = emptyList(),
            termTags = null,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(japaneseDictionary)
        coEvery { dictionarySearchGateway.lookup("まじ", listOf(1L), any()) } returns listOf(
            DictionaryLookupMatch(
                matched = "まじ",
                deinflected = "まじ",
                process = listOf("dictionary"),
                term = exactExpression,
                termMeta = emptyList(),
            ),
            DictionaryLookupMatch(
                matched = "まじ",
                deinflected = "まじ",
                process = listOf("dictionary"),
                term = readingMatch,
                termMeta = emptyList(),
            ),
            DictionaryLookupMatch(
                matched = "まじ",
                deinflected = "まじる",
                process = listOf("deinflect"),
                term = deinflectedMatch,
                termMeta = emptyList(),
            ),
        )

        val results = searchDictionaryTerms.search("まじ", listOf(1L))

        results.map { it.expression } shouldBe listOf("まじ", "交じ", "混じる")
    }

    @Test
    fun `japanese exact expression lookup outranks higher priority reading match`() = runTest {
        val higherPriorityDictionary = Dictionary(
            id = 1L,
            title = "HigherPriority",
            revision = "1",
            version = 3,
            sourceLanguage = "ja",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 1,
        )
        val lowerPriorityDictionary = Dictionary(
            id = 2L,
            title = "LowerPriority",
            revision = "1",
            version = 3,
            sourceLanguage = "ja",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 2,
        )

        val readingMatch = DictionaryTerm(
            dictionaryId = 1L,
            expression = "交じ",
            reading = "まじ",
            definitionTags = null,
            rules = null,
            score = 999,
            glossary = emptyList(),
            termTags = null,
        )
        val exactExpression = DictionaryTerm(
            dictionaryId = 2L,
            expression = "まじ",
            reading = "まじ",
            definitionTags = null,
            rules = null,
            score = 1,
            glossary = emptyList(),
            termTags = null,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(
            higherPriorityDictionary,
            lowerPriorityDictionary,
        )
        coEvery { dictionarySearchGateway.lookup("まじ", listOf(1L, 2L), any()) } returns listOf(
            DictionaryLookupMatch(
                matched = "まじ",
                deinflected = "まじ",
                process = listOf("dictionary"),
                term = readingMatch,
                termMeta = emptyList(),
            ),
            DictionaryLookupMatch(
                matched = "まじ",
                deinflected = "まじ",
                process = listOf("dictionary"),
                term = exactExpression,
                termMeta = emptyList(),
            ),
        )

        val results = searchDictionaryTerms.search("まじ", listOf(1L, 2L))

        results.map { it.dictionaryId } shouldBe listOf(2L, 1L)
    }

    @Test
    fun `japanese exact lookup match outranks higher priority partial match`() = runTest {
        val higherPriorityDictionary = Dictionary(
            id = 1L,
            title = "HigherPriority",
            revision = "1",
            version = 3,
            sourceLanguage = "ja",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 1,
        )
        val lowerPriorityDictionary = Dictionary(
            id = 2L,
            title = "LowerPriority",
            revision = "1",
            version = 3,
            sourceLanguage = "ja",
            backend = DictionaryBackend.HOSHI,
            storageReady = true,
            priority = 2,
        )

        val partialMatch = DictionaryTerm(
            dictionaryId = 1L,
            expression = "食べ",
            reading = "たべ",
            definitionTags = null,
            rules = null,
            score = 0,
            glossary = emptyList(),
            termTags = null,
        )
        val exactMatch = DictionaryTerm(
            dictionaryId = 2L,
            expression = "食べた",
            reading = "たべた",
            definitionTags = null,
            rules = null,
            score = 0,
            glossary = emptyList(),
            termTags = null,
        )

        coEvery { dictionaryRepository.getAllDictionaries() } returns listOf(
            higherPriorityDictionary,
            lowerPriorityDictionary,
        )
        coEvery { dictionarySearchGateway.lookup("食べた", listOf(1L, 2L), any()) } returns listOf(
            DictionaryLookupMatch(
                matched = "食べ",
                deinflected = "食べ",
                process = listOf("dictionary"),
                term = partialMatch,
                termMeta = emptyList(),
            ),
            DictionaryLookupMatch(
                matched = "食べた",
                deinflected = "食べた",
                process = listOf("dictionary"),
                term = exactMatch,
                termMeta = emptyList(),
            ),
        )

        val results = searchDictionaryTerms.search("食べた", listOf(1L, 2L))

        results.map { it.dictionaryId } shouldBe listOf(2L, 1L)
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
        coEvery { dictionarySearchGateway.exactSearch("looked", listOf(1L)) } returns emptyList()
        coEvery { dictionarySearchGateway.exactSearch("look", listOf(1L)) } returns listOf(
            DictionarySearchEntry(term = term, termMeta = emptyList()),
        )

        val results = searchDictionaryTerms.search("looked", listOf(1L))

        results.map { it.expression } shouldBe listOf("look")
        coVerify(exactly = 1) { dictionarySearchGateway.exactSearch("looked", listOf(1L)) }
        coVerify(atLeast = 1) { dictionarySearchGateway.exactSearch("look", listOf(1L)) }
    }
}
