package mihon.data.dictionary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import mihon.domain.dictionary.repository.DictionaryLegacyRepository
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.DictionaryArchiveBuildResult
import mihon.domain.dictionary.service.DictionaryArchiveBuilder
import mihon.domain.dictionary.service.DictionaryArchiveProgress
import java.io.File
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LegacyDictionaryArchiveBuilder(
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryLegacyRepository: DictionaryLegacyRepository,
) : DictionaryArchiveBuilder {

    override suspend fun buildArchive(
        dictionary: Dictionary,
        destinationPath: String,
        onProgress: suspend (DictionaryArchiveProgress) -> Unit,
    ): DictionaryArchiveBuildResult = withContext(Dispatchers.IO) {
        val destination = File(destinationPath)
        destination.parentFile?.mkdirs()
        if (destination.exists()) {
            destination.delete()
        }

        val counts = dictionaryLegacyRepository.getLegacyRowCounts(dictionary.id)
        val tagCount = counts.tagCount
        val termCount = counts.termCount
        val termMetaCount = counts.termMetaCount
        val kanjiCount = counts.kanjiCount
        val kanjiMetaCount = counts.kanjiMetaCount
        val sequenced = detectSequencedTerms(dictionary.id, termCount)
        val frequencyMode = detectFrequencyMode(dictionary.id, termMetaCount)

        var sampleExpression: String? = null

        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            writeIndex(zip, dictionary, sequenced, frequencyMode)
            val styles = dictionary.styles
            if (!styles.isNullOrBlank()) {
                writeTextEntry(zip, "styles.css", styles)
            }

            val tags = dictionaryRepository.getTagsForDictionary(dictionary.id)
            if (tags.isNotEmpty()) {
                writeTagBank(zip, tags)
                onProgress(
                    DictionaryArchiveProgress(
                        dictionaryId = dictionary.id,
                        writtenEntries = tagCount,
                        totalEntries = totalEntries(tagCount, termCount, termMetaCount, kanjiCount, kanjiMetaCount),
                        message = "Packed tags",
                    ),
                )
            }

            sampleExpression = writePagedTerms(zip, dictionary, termCount, onProgress)
            writePagedTermMeta(zip, dictionary, termMetaCount, onProgress)
            writePagedKanji(zip, dictionary, kanjiCount, onProgress)
            writePagedKanjiMeta(zip, dictionary, kanjiMetaCount, onProgress)
        }

        DictionaryArchiveBuildResult(
            archivePath = destination.absolutePath,
            sampleExpression = sampleExpression,
            tagCount = tagCount,
            termCount = termCount,
            termMetaCount = termMetaCount,
            kanjiCount = kanjiCount,
            kanjiMetaCount = kanjiMetaCount,
        )
    }

    private suspend fun writeIndex(
        zip: ZipOutputStream,
        dictionary: Dictionary,
        sequenced: Boolean,
        frequencyMode: String?,
    ) {
        putJsonEntry(zip, "index.json") { writer ->
            writer.beginObject()
            writer.name("title").value(dictionary.title)
            writer.name("revision").value(dictionary.revision)
            writer.name("format").value(dictionary.version.toLong())
            writer.name("version").value(dictionary.version.toLong())
            writer.name("sequenced").value(sequenced)
            frequencyMode?.let { writer.name("frequencyMode").value(it) }
            dictionary.author?.let { writer.name("author").value(it) }
            dictionary.url?.let { writer.name("url").value(it) }
            dictionary.description?.let { writer.name("description").value(it) }
            dictionary.attribution?.let { writer.name("attribution").value(it) }
            dictionary.sourceLanguage?.let { writer.name("sourceLanguage").value(it) }
            dictionary.targetLanguage?.let { writer.name("targetLanguage").value(it) }
            writer.endObject()
        }
    }

    private suspend fun writeTagBank(zip: ZipOutputStream, tags: List<DictionaryTag>) {
        putJsonEntry(zip, "tag_bank_1.json") { writer ->
            writer.beginArray()
            tags.forEach { tag ->
                currentCoroutineContext().ensureActive()
                writer.beginArray()
                writer.value(tag.name)
                writer.value(tag.category)
                writer.value(tag.order.toLong())
                writer.value(tag.notes)
                writer.value(tag.score.toLong())
                writer.endArray()
            }
            writer.endArray()
        }
    }

    private suspend fun writePagedTerms(
        zip: ZipOutputStream,
        dictionary: Dictionary,
        totalCount: Long,
        onProgress: suspend (DictionaryArchiveProgress) -> Unit,
    ): String? {
        if (totalCount == 0L) return null

        var bankIndex = 1
        var offset = 0L
        var sampleExpression: String? = null

        while (offset < totalCount) {
            currentCoroutineContext().ensureActive()
            val page = dictionaryLegacyRepository.getTermsExportForDictionary(
                dictionary.id,
                BANK_PAGE_SIZE,
                offset,
            )
            if (page.isEmpty()) break
            if (sampleExpression == null) {
                sampleExpression = page.first().expression
            }

            putJsonEntry(zip, "term_bank_$bankIndex.json") { writer ->
                writer.beginArray()
                page.forEach { term ->
                    currentCoroutineContext().ensureActive()
                    writer.beginArray()
                    writer.value(term.expression)
                    writer.value(term.reading)
                    writeNullableStringAsEmpty(writer, term.definitionTags)
                    writeNullableStringAsEmpty(writer, term.rules)
                    writer.value(term.score.toLong())
                    writer.rawValue(toLegacyGlossaryJson(term.glossaryJson))
                    term.sequence?.let(writer::value) ?: writer.nullValue()
                    writeNullableStringAsEmpty(writer, term.termTags)
                    writer.endArray()
                }
                writer.endArray()
            }

            offset += page.size
            onProgress(
                DictionaryArchiveProgress(
                    dictionaryId = dictionary.id,
                    writtenEntries = offset,
                    totalEntries = totalCount,
                    message = "Packed terms ($offset/$totalCount)",
                ),
            )
            bankIndex += 1
        }

        return sampleExpression
    }

    private suspend fun writePagedTermMeta(
        zip: ZipOutputStream,
        dictionary: Dictionary,
        totalCount: Long,
        onProgress: suspend (DictionaryArchiveProgress) -> Unit,
    ) {
        if (totalCount == 0L) return

        var bankIndex = 1
        var offset = 0L
        while (offset < totalCount) {
            currentCoroutineContext().ensureActive()
            val page = dictionaryLegacyRepository.getTermMetaExportForDictionary(
                dictionary.id,
                BANK_PAGE_SIZE,
                offset,
            )
            if (page.isEmpty()) break

            putJsonEntry(zip, "term_meta_bank_$bankIndex.json") { writer ->
                writer.beginArray()
                page.forEach { meta ->
                    currentCoroutineContext().ensureActive()
                    writer.beginArray()
                    writer.value(meta.expression)
                    writer.value(meta.mode)
                    writer.rawValue(toLegacyTermMetaJson(meta.mode, meta.dataJson))
                    writer.endArray()
                }
                writer.endArray()
            }

            offset += page.size
            onProgress(
                DictionaryArchiveProgress(
                    dictionaryId = dictionary.id,
                    writtenEntries = offset,
                    totalEntries = totalCount,
                    message = "Packed metadata ($offset/$totalCount)",
                ),
            )
            bankIndex += 1
        }
    }

    private suspend fun writePagedKanji(
        zip: ZipOutputStream,
        dictionary: Dictionary,
        totalCount: Long,
        onProgress: suspend (DictionaryArchiveProgress) -> Unit,
    ) {
        if (totalCount == 0L) return

        var bankIndex = 1
        var offset = 0L
        while (offset < totalCount) {
            currentCoroutineContext().ensureActive()
            val page = dictionaryLegacyRepository.getKanjiExportForDictionary(
                dictionary.id,
                BANK_PAGE_SIZE,
                offset,
            )
            if (page.isEmpty()) break

            putJsonEntry(zip, "kanji_bank_$bankIndex.json") { writer ->
                writer.beginArray()
                page.forEach { kanji ->
                    currentCoroutineContext().ensureActive()
                    writeKanji(writer, kanji)
                }
                writer.endArray()
            }

            offset += page.size
            onProgress(
                DictionaryArchiveProgress(
                    dictionaryId = dictionary.id,
                    writtenEntries = offset,
                    totalEntries = totalCount,
                    message = "Packed kanji ($offset/$totalCount)",
                ),
            )
            bankIndex += 1
        }
    }

    private suspend fun writePagedKanjiMeta(
        zip: ZipOutputStream,
        dictionary: Dictionary,
        totalCount: Long,
        onProgress: suspend (DictionaryArchiveProgress) -> Unit,
    ) {
        if (totalCount == 0L) return

        var bankIndex = 1
        var offset = 0L
        while (offset < totalCount) {
            currentCoroutineContext().ensureActive()
            val page = dictionaryLegacyRepository.getKanjiMetaExportForDictionary(
                dictionary.id,
                BANK_PAGE_SIZE,
                offset,
            )
            if (page.isEmpty()) break

            putJsonEntry(zip, "kanji_meta_bank_$bankIndex.json") { writer ->
                writer.beginArray()
                page.forEach { meta ->
                    currentCoroutineContext().ensureActive()
                    writer.beginArray()
                    writer.value(meta.character)
                    writer.value(meta.mode)
                    writer.rawValue(meta.dataJson)
                    writer.endArray()
                }
                writer.endArray()
            }

            offset += page.size
            onProgress(
                DictionaryArchiveProgress(
                    dictionaryId = dictionary.id,
                    writtenEntries = offset,
                    totalEntries = totalCount,
                    message = "Packed kanji metadata ($offset/$totalCount)",
                ),
            )
            bankIndex += 1
        }
    }

    private fun writeKanji(writer: SimpleJsonWriter, kanji: mihon.domain.dictionary.model.DictionaryKanjiExport) {
        writer.beginArray()
        writer.value(kanji.character)
        writer.value(kanji.onyomi)
        writer.value(kanji.kunyomi)
        writeNullableStringAsEmpty(writer, kanji.tags)
        writer.rawValue(kanji.meaningsJson)
        val stats = kanji.statsJson
        if (stats.isNullOrBlank()) {
            writer.nullValue()
        } else {
            writer.rawValue(stats)
        }
        writer.endArray()
    }

    private fun writeNullableStringAsEmpty(writer: SimpleJsonWriter, value: String?) {
        writer.value(value ?: "")
    }

    private suspend fun putJsonEntry(
        zip: ZipOutputStream,
        name: String,
        block: suspend (SimpleJsonWriter) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            zip.putNextEntry(ZipEntry(name))
        }
        val writer = SimpleJsonWriter(OutputStreamWriter(zip, Charsets.UTF_8))
        block(writer)
        writer.flush()
        withContext(Dispatchers.IO) {
            zip.closeEntry()
        }
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun totalEntries(
        tagCount: Long,
        termCount: Long,
        termMetaCount: Long,
        kanjiCount: Long,
        kanjiMetaCount: Long,
    ): Long {
        return tagCount + termCount + termMetaCount + kanjiCount + kanjiMetaCount
    }

    private fun toLegacyTermMetaJson(mode: String, dataJson: String): String {
        val element = runCatching { glossaryJsonParser.parseToJsonElement(dataJson) }
            .getOrNull() ?: return dataJson

        return when (mode.lowercase()) {
            "freq" -> normalizeFrequencyMetaJson(element).toString()
            "pitch" -> normalizePitchMetaJson(element).toString()
            else -> element.toString()
        }
    }

    private fun normalizeFrequencyMetaJson(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element

        val reading = element["reading"]?.jsonPrimitive?.contentOrNull
        val nestedFrequency = element["frequency"]
        if (nestedFrequency != null) {
            return if (reading != null) {
                buildJsonObject {
                    put("reading", JsonPrimitive(reading))
                    put("frequency", nestedFrequency)
                }
            } else {
                nestedFrequency
            }
        }

        val value = element["value"]?.jsonPrimitive?.intOrNull
        val displayValue = element["displayValue"]?.jsonPrimitive?.contentOrNull
        if (value == null && displayValue == null) return element

        val frequencyElement = buildJsonObject {
            value?.let { put("value", JsonPrimitive(it)) }
            displayValue?.let { put("displayValue", JsonPrimitive(it)) }
        }

        return if (reading != null) {
            buildJsonObject {
                put("reading", JsonPrimitive(reading))
                put("frequency", frequencyElement)
            }
        } else {
            frequencyElement
        }
    }

    private fun normalizePitchMetaJson(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element

        val reading = element["reading"]?.jsonPrimitive?.contentOrNull ?: return element
        val pitches = when (val value = element["pitches"]) {
            is JsonArray -> value.mapNotNull(::normalizePitchPattern)
            is JsonObject -> listOfNotNull(normalizePitchPattern(value))
            else -> emptyList()
        }

        return buildJsonObject {
            put("reading", JsonPrimitive(reading))
            put("pitches", JsonArray(pitches))
        }
    }

    private fun normalizePitchPattern(element: JsonElement): JsonObject? {
        val pitch = element as? JsonObject ?: return null
        val position = normalizePitchPosition(pitch["position"]) ?: return null
        val nasal = normalizePitchPositionList(pitch["nasal"])
        val devoice = normalizePitchPositionList(pitch["devoice"])
        val tags = normalizePitchTags(pitch["tags"])

        return buildJsonObject {
            put("position", position)
            nasal?.let { put("nasal", it) }
            devoice?.let { put("devoice", it) }
            tags?.let { put("tags", it) }
        }
    }

    private fun normalizePitchPosition(element: JsonElement?): JsonElement? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return JsonPrimitive(it) }

        val content = primitive.contentOrNull ?: return null
        return content.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(content)
    }

    private fun normalizePitchPositionList(element: JsonElement?): JsonElement? {
        return when (element) {
            is JsonPrimitive -> element.intOrNull?.let(::JsonPrimitive)
                ?: element.contentOrNull?.toIntOrNull()?.let(::JsonPrimitive)
            is JsonArray -> {
                val values = element.mapNotNull { item ->
                    (item as? JsonPrimitive)?.intOrNull
                        ?: (item as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                }
                JsonArray(values.map(::JsonPrimitive))
            }
            else -> null
        }
    }

    private fun normalizePitchTags(element: JsonElement?): JsonArray? {
        val tags = (element as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.map(::JsonPrimitive)
            ?: return null
        return JsonArray(tags)
    }

    private fun toLegacyGlossaryJson(glossaryJson: String): String {
        val parsedEntries = runCatching {
            glossaryJsonParser.decodeFromString(glossarySerializer, glossaryJson)
        }.getOrNull() ?: return glossaryJson

        val legacyEntries = parsedEntries.map { entry ->
            glossaryEntryToLegacyJson(entry)
        }
        return JsonArray(legacyEntries).toString()
    }

    private fun glossaryEntryToLegacyJson(entry: GlossaryEntry): JsonElement {
        return when (entry) {
            is GlossaryEntry.TextDefinition -> JsonPrimitive(entry.text)
            is GlossaryEntry.StructuredContent -> {
                buildJsonObject {
                    put("type", JsonPrimitive("structured-content"))
                    put("content", nodesToLegacyContent(entry.nodes))
                }
            }
            is GlossaryEntry.ImageDefinition -> {
                buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("path", JsonPrimitive(entry.image.path))
                    entry.image.width?.let { put("width", JsonPrimitive(it)) }
                    entry.image.height?.let { put("height", JsonPrimitive(it)) }
                    entry.image.title?.let { put("title", JsonPrimitive(it)) }
                    entry.image.alt?.let { put("alt", JsonPrimitive(it)) }
                    entry.image.description?.let { put("description", JsonPrimitive(it)) }
                    entry.image.pixelated?.let { put("pixelated", JsonPrimitive(it)) }
                    entry.image.imageRendering?.let { put("imageRendering", JsonPrimitive(it)) }
                    entry.image.appearance?.let { put("appearance", JsonPrimitive(it)) }
                    entry.image.background?.let { put("background", JsonPrimitive(it)) }
                    entry.image.collapsed?.let { put("collapsed", JsonPrimitive(it)) }
                    entry.image.collapsible?.let { put("collapsible", JsonPrimitive(it)) }
                    entry.image.verticalAlign?.let { put("verticalAlign", JsonPrimitive(it)) }
                    entry.image.border?.let { put("border", JsonPrimitive(it)) }
                    entry.image.borderRadius?.let { put("borderRadius", JsonPrimitive(it)) }
                    entry.image.sizeUnits?.let { put("sizeUnits", JsonPrimitive(it)) }
                    if (entry.image.dataAttributes.isNotEmpty()) {
                        put("data", stringMapToJsonObject(entry.image.dataAttributes))
                    }
                }
            }
            is GlossaryEntry.Deinflection -> {
                buildJsonArray {
                    add(JsonPrimitive(entry.baseForm))
                    add(JsonArray(entry.rules.map(::JsonPrimitive)))
                }
            }
            is GlossaryEntry.Unknown -> {
                runCatching { glossaryJsonParser.parseToJsonElement(entry.rawJson) }
                    .getOrElse { JsonPrimitive(entry.rawJson) }
            }
        }
    }

    private fun nodesToLegacyContent(nodes: List<GlossaryNode>): JsonElement {
        return when (nodes.size) {
            0 -> JsonArray(emptyList())
            1 -> glossaryNodeToLegacyJson(nodes.first())
            else -> JsonArray(nodes.map(::glossaryNodeToLegacyJson))
        }
    }

    private fun glossaryNodeToLegacyJson(node: GlossaryNode): JsonElement {
        return when (node) {
            is GlossaryNode.Text -> JsonPrimitive(node.text)
            GlossaryNode.LineBreak -> buildJsonObject { put("tag", JsonPrimitive("br")) }
            is GlossaryNode.Element -> {
                buildJsonObject {
                    put("tag", JsonPrimitive(node.tag.toRawTag()))

                    node.attributes.properties.forEach { (key, value) ->
                        put(key, JsonPrimitive(value))
                    }

                    if (node.attributes.dataAttributes.isNotEmpty()) {
                        put("data", stringMapToJsonObject(node.attributes.dataAttributes))
                    }
                    if (node.attributes.style.isNotEmpty()) {
                        put("style", stringMapToJsonObject(node.attributes.style))
                    }
                    if (node.children.isNotEmpty()) {
                        put("content", nodesToLegacyContent(node.children))
                    }
                }
            }
        }
    }

    private fun GlossaryTag.toRawTag(): String {
        return when (this) {
            GlossaryTag.Span -> "span"
            GlossaryTag.Div -> "div"
            GlossaryTag.Ruby -> "ruby"
            GlossaryTag.Rt -> "rt"
            GlossaryTag.Rp -> "rp"
            GlossaryTag.Table -> "table"
            GlossaryTag.Thead -> "thead"
            GlossaryTag.Tbody -> "tbody"
            GlossaryTag.Tfoot -> "tfoot"
            GlossaryTag.Tr -> "tr"
            GlossaryTag.Td -> "td"
            GlossaryTag.Th -> "th"
            GlossaryTag.OrderedList -> "ol"
            GlossaryTag.UnorderedList -> "ul"
            GlossaryTag.ListItem -> "li"
            GlossaryTag.Details -> "details"
            GlossaryTag.Summary -> "summary"
            GlossaryTag.Link -> "a"
            GlossaryTag.Image -> "img"
            GlossaryTag.Unknown -> "unknown"
        }
    }

    private fun stringMapToJsonObject(values: Map<String, String>): JsonObject {
        return buildJsonObject {
            values.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
    }

    private suspend fun detectSequencedTerms(dictionaryId: Long, totalTermCount: Long): Boolean {
        if (totalTermCount == 0L) return false

        var offset = 0L
        while (offset < totalTermCount) {
            currentCoroutineContext().ensureActive()
            val page = dictionaryLegacyRepository.getTermsExportForDictionary(
                dictionaryId,
                BANK_PAGE_SIZE,
                offset,
            )
            if (page.isEmpty()) break
            if (page.any { it.sequence != null }) {
                return true
            }
            offset += page.size
        }

        return false
    }

    private suspend fun detectFrequencyMode(dictionaryId: Long, totalTermMetaCount: Long): String? {
        if (totalTermMetaCount == 0L) return null

        var offset = 0L
        while (offset < totalTermMetaCount) {
            currentCoroutineContext().ensureActive()
            val page = dictionaryLegacyRepository.getTermMetaExportForDictionary(
                dictionaryId,
                BANK_PAGE_SIZE,
                offset,
            )
            if (page.isEmpty()) break

            if (page.any { it.mode.equals("freq", ignoreCase = true) }) {
                return "rank-based"
            }

            offset += page.size
        }

        return null
    }

    companion object {
        private const val BANK_PAGE_SIZE = 10_000L
        private val glossaryJsonParser = Json {
            ignoreUnknownKeys = true
        }
        private val glossarySerializer = ListSerializer(GlossaryEntry.serializer())
    }
}
