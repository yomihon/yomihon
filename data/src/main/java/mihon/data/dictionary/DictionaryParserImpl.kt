package mihon.data.dictionary

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.GlossaryElementAttributes
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryImageAttributes
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import mihon.domain.dictionary.model.KanjiMetaMode
import mihon.domain.dictionary.model.TermMetaMode
import mihon.domain.dictionary.service.DictionaryParseException
import mihon.domain.dictionary.service.DictionaryParser
import java.io.InputStream
import java.io.InputStreamReader

class DictionaryParserImpl : DictionaryParser {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private inline fun <T> parseBank(
        stream: InputStream,
        bankName: String,
        crossinline readItem: (JsonReader) -> T,
    ): Sequence<T> = sequence {
        val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
        try {
            reader.beginArray()
            while (reader.hasNext()) {
                yield(readItem(reader))
            }
            reader.endArray()
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse $bankName", e)
        }
    }

    override fun parseIndex(jsonString: String): DictionaryIndex {
        try {
            val jsonObject = jsonParser.parseToJsonElement(jsonString).jsonObject

            val tagMeta = jsonObject["tagMeta"]?.jsonObject?.mapValues { (_, value) ->
                val metaObj = value.jsonObject
                DictionaryIndex.TagMeta(
                    category = metaObj["category"]?.jsonPrimitive?.contentOrNull ?: "",
                    order = metaObj["order"]?.jsonPrimitive?.int ?: 0,
                    notes = metaObj["notes"]?.jsonPrimitive?.contentOrNull ?: "",
                    score = metaObj["score"]?.jsonPrimitive?.int ?: 0,
                )
            }

            return DictionaryIndex(
                title = jsonObject["title"]?.jsonPrimitive?.content
                    ?: throw DictionaryParseException("Missing title in index.json"),
                revision = jsonObject["revision"]?.jsonPrimitive?.content
                    ?: throw DictionaryParseException("Missing revision in index.json"),
                format = jsonObject["format"]?.jsonPrimitive?.int,
                version = jsonObject["version"]?.jsonPrimitive?.int,
                author = jsonObject["author"]?.jsonPrimitive?.contentOrNull,
                url = jsonObject["url"]?.jsonPrimitive?.contentOrNull,
                description = jsonObject["description"]?.jsonPrimitive?.contentOrNull,
                attribution = jsonObject["attribution"]?.jsonPrimitive?.contentOrNull,
                sourceLanguage = jsonObject["sourceLanguage"]?.jsonPrimitive?.contentOrNull,
                targetLanguage = jsonObject["targetLanguage"]?.jsonPrimitive?.contentOrNull,
                sequenced = jsonObject["sequenced"]?.jsonPrimitive?.boolean,
                frequencyMode = jsonObject["frequencyMode"]?.jsonPrimitive?.contentOrNull,
                tagMeta = tagMeta,
            )
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse index.json", e)
        }
    }

    override fun parseTagBank(stream: InputStream): Sequence<DictionaryTag> =
        parseBank(stream, "tag_bank") { reader -> readSingleTag(reader) }

    private fun readSingleTag(reader: JsonReader): DictionaryTag {
        reader.beginArray()
        val name = reader.nextString()
        val category = reader.nextString()
        val order = reader.nextInt()
        val notes = reader.nextString()
        val score = reader.nextInt()
        reader.endArray()

        return DictionaryTag(
            dictionaryId = 0L,
            name = name,
            category = category,
            order = order,
            notes = notes,
            score = score,
        )
    }

    override fun parseTermBank(stream: InputStream, version: Int): Sequence<DictionaryTerm> =
        parseBank(stream, "term_bank") { reader -> readSingleTerm(reader, version) }

    private fun readSingleTerm(reader: JsonReader, version: Int): DictionaryTerm {
        reader.beginArray()
        val expression = reader.nextString()
        val reading = reader.nextString()
        val definitionTags = readStringOrArray(reader)
        val rules = readStringOrArray(reader)
        val score = reader.nextInt()

        val glossary: List<GlossaryEntry>
        val sequence: Long?
        val termTags: String?

        if (version == 1) {
            val glossaryList = mutableListOf<GlossaryEntry>()
            while (reader.hasNext()) {
                glossaryList.add(GlossaryEntry.TextDefinition(reader.nextString()))
            }
            glossary = glossaryList
            sequence = null
            termTags = null
        } else {
            glossary = readGlossaryArray(reader)
            sequence = if (reader.hasNext() && reader.peek() != JsonToken.END_ARRAY) {
                readNullableLong(reader)
            } else {
                null
            }
            termTags = if (reader.hasNext() && reader.peek() != JsonToken.END_ARRAY) {
                readStringOrArray(reader)
            } else {
                null
            }
        }

        reader.endArray()

        return DictionaryTerm(
            dictionaryId = 0L,
            expression = expression,
            reading = reading,
            definitionTags = definitionTags,
            rules = rules,
            score = score,
            glossary = glossary,
            sequence = sequence,
            termTags = termTags,
        )
    }

    private fun readStringOrArray(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            JsonToken.STRING -> reader.nextString().takeIf { it.isNotEmpty() }
            JsonToken.BEGIN_ARRAY -> {
                val parts = mutableListOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    parts.add(reader.nextString())
                }
                reader.endArray()
                parts.toList().joinToString(" ").takeIf { it.isNotEmpty() }
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun readNullableLong(reader: JsonReader): Long? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            JsonToken.NUMBER -> reader.nextLong()
            JsonToken.STRING -> reader.nextString().toLongOrNull()
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun readGlossaryArray(reader: JsonReader): List<GlossaryEntry> {
        val entries = mutableListOf<GlossaryEntry>()
        reader.beginArray()
        while (reader.hasNext()) {
            entries.add(readGlossaryEntry(reader))
        }
        reader.endArray()
        return entries.toList()
    }

    private fun readGlossaryEntry(reader: JsonReader): GlossaryEntry {
        return when (reader.peek()) {
            JsonToken.STRING -> GlossaryEntry.TextDefinition(reader.nextString())
            JsonToken.BEGIN_ARRAY -> readDeinflectionEntry(reader)
            JsonToken.BEGIN_OBJECT -> readGlossaryObject(reader)
            else -> {
                reader.skipValue()
                GlossaryEntry.Unknown("unknown")
            }
        }
    }

    private fun readDeinflectionEntry(reader: JsonReader): GlossaryEntry {
        reader.beginArray()
        val elements = mutableListOf<String>()
        val rulesList = mutableListOf<String>()
        var index = 0

        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonToken.STRING -> {
                    if (index == 0) {
                        elements.add(reader.nextString())
                    } else {
                        reader.skipValue()
                    }
                }
                JsonToken.BEGIN_ARRAY -> {
                    if (index == 1) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            rulesList.add(reader.nextString())
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
            index++
        }
        reader.endArray()

        return if (elements.isNotEmpty() && rulesList.isNotEmpty()) {
            GlossaryEntry.Deinflection(elements[0], rulesList)
        } else {
            GlossaryEntry.Unknown("invalid deinflection")
        }
    }

    private fun readGlossaryObject(reader: JsonReader): GlossaryEntry {
        val obj = readJsonElement(reader).jsonObject
        return parseGlossaryObject(obj)
    }

    private fun readJsonElement(reader: JsonReader): JsonElement {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val content = mutableMapOf<String, JsonElement>()
                reader.beginObject()
                while (reader.hasNext()) {
                    content[reader.nextName()] = readJsonElement(reader)
                }
                reader.endObject()
                JsonObject(content)
            }
            JsonToken.BEGIN_ARRAY -> {
                val content = mutableListOf<JsonElement>()
                reader.beginArray()
                while (reader.hasNext()) {
                    content.add(readJsonElement(reader))
                }
                reader.endArray()
                JsonArray(content)
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                val number: Number? = raw.toLongOrNull() ?: raw.toDoubleOrNull()
                if (number != null) JsonPrimitive(number) else JsonPrimitive(raw)
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull
            }
            else -> {
                reader.skipValue()
                JsonNull
            }
        }
    }

    private fun parseGlossaryObject(obj: JsonObject): GlossaryEntry {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "text" -> GlossaryEntry.TextDefinition(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
            "image" -> parseImageEntry(obj) ?: GlossaryEntry.Unknown(obj.toString())
            "structured-content" -> {
                val content = obj["content"] ?: return GlossaryEntry.StructuredContent(emptyList())
                val nodes = parseStructuredNodes(content)
                GlossaryEntry.StructuredContent(nodes)
            }
            else -> GlossaryEntry.Unknown(obj.toString())
        }
    }

    private fun parseImageEntry(obj: JsonObject): GlossaryEntry.ImageDefinition? {
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return null
        val attributes = GlossaryImageAttributes(
            path = path,
            width = obj["width"]?.jsonPrimitive?.intOrNull,
            height = obj["height"]?.jsonPrimitive?.intOrNull,
            title = obj["title"]?.jsonPrimitive?.contentOrNull,
            alt = obj["alt"]?.jsonPrimitive?.contentOrNull,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            pixelated = obj["pixelated"]?.jsonPrimitive?.booleanOrNull,
            imageRendering = obj["imageRendering"]?.jsonPrimitive?.contentOrNull,
            appearance = obj["appearance"]?.jsonPrimitive?.contentOrNull,
            background = obj["background"]?.jsonPrimitive?.booleanOrNull,
            collapsed = obj["collapsed"]?.jsonPrimitive?.booleanOrNull,
            collapsible = obj["collapsible"]?.jsonPrimitive?.booleanOrNull,
            verticalAlign = obj["verticalAlign"]?.jsonPrimitive?.contentOrNull,
            border = obj["border"]?.jsonPrimitive?.contentOrNull,
            borderRadius = obj["borderRadius"]?.jsonPrimitive?.contentOrNull,
            sizeUnits = obj["sizeUnits"]?.jsonPrimitive?.contentOrNull,
            dataAttributes = parseStringMap(obj["data"]?.jsonObject),
        )
        return GlossaryEntry.ImageDefinition(attributes)
    }

    private fun parseStructuredNodes(element: JsonElement): List<GlossaryNode> {
        return when (element) {
            is JsonPrimitive -> listOf(GlossaryNode.Text(element.content))
            is JsonArray -> element.flatMap { parseStructuredNodes(it) }
            is JsonObject -> listOf(parseStructuredObject(element))
            else -> emptyList()
        }
    }

    private fun parseStructuredObject(obj: JsonObject): GlossaryNode {
        val rawTag = obj["tag"]?.jsonPrimitive?.contentOrNull
        if (rawTag == "br") {
            return GlossaryNode.LineBreak
        }

        val tag = GlossaryTag.fromRaw(rawTag)
        val children = obj["content"]?.let { parseStructuredNodes(it) } ?: emptyList()
        val attributes = parseElementAttributes(obj)

        return GlossaryNode.Element(
            tag = tag,
            children = children,
            attributes = attributes,
        )
    }

    private fun parseElementAttributes(obj: JsonObject): GlossaryElementAttributes {
        val dataAttributes = parseStringMap(obj["data"]?.jsonObject)
        val styleAttributes = parseStringMap(obj["style"]?.jsonObject)
        val properties = parseProperties(obj)

        return GlossaryElementAttributes(
            properties = properties,
            dataAttributes = dataAttributes,
            style = styleAttributes,
        )
    }

    private fun parseStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        obj.forEach { (key, value) ->
            val stringValue = value.stringValueOrNull()
            if (stringValue != null) {
                result[key] = stringValue
            }
        }
        return result.toMap()
    }

    private fun parseProperties(obj: JsonObject): Map<String, String> {
        val excludedKeys = setOf("tag", "content", "data", "style")
        val result = mutableMapOf<String, String>()
        obj.forEach { (key, value) ->
            if (key in excludedKeys) return@forEach
            val stringValue = value.stringValueOrNull()
            if (stringValue != null) {
                result[key] = stringValue
            }
        }
        return result.toMap()
    }

    private fun JsonElement?.stringValueOrNull(): String? {
        if (this == null) return null
        return when (this) {
            is JsonPrimitive -> {
                val stringContent = this.contentOrNull
                when {
                    stringContent != null -> stringContent
                    this.booleanOrNull != null -> this.booleanOrNull?.toString()
                    this.longOrNull != null -> this.longOrNull?.toString()
                    this.doubleOrNull != null -> this.doubleOrNull?.toString()
                    else -> this.content
                }
            }
            else -> this.toString()
        }
    }

    override fun parseKanjiBank(stream: InputStream, version: Int): Sequence<DictionaryKanji> =
        parseBank(stream, "kanji_bank") { reader -> readSingleKanji(reader, version) }

    private fun readSingleKanji(reader: JsonReader, version: Int): DictionaryKanji {
        reader.beginArray()
        val character = reader.nextString()
        val onyomi = reader.nextString()
        val kunyomi = reader.nextString()
        val tags = readStringOrArray(reader)

        val meanings: List<String>
        val stats: Map<String, String>?

        if (version == 1) {
            val meaningsList = mutableListOf<String>()
            while (reader.hasNext()) {
                meaningsList.add(reader.nextString())
            }
            meanings = meaningsList
            stats = null
        } else {
            meanings = readStringArray(reader)
            stats = if (reader.hasNext() && reader.peek() == JsonToken.BEGIN_OBJECT) {
                readStringMap(reader)
            } else {
                null
            }
        }

        reader.endArray()

        return DictionaryKanji(
            dictionaryId = 0L,
            character = character,
            onyomi = onyomi,
            kunyomi = kunyomi,
            tags = tags,
            meanings = meanings,
            stats = stats,
        )
    }

    private fun readStringArray(reader: JsonReader): List<String> {
        val result = mutableListOf<String>()
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    result.add(reader.nextString())
                }
                reader.endArray()
            }
            JsonToken.STRING -> result.add(reader.nextString())
            else -> reader.skipValue()
        }
        return result.toList()
    }

    private fun readStringMap(reader: JsonReader): Map<String, String> {
        val result = mutableMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            val value = when (reader.peek()) {
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextString()
                JsonToken.BOOLEAN -> reader.nextBoolean().toString()
                JsonToken.NULL -> {
                    reader.nextNull()
                    ""
                }
                else -> {
                    reader.skipValue()
                    ""
                }
            }
            result[key] = value
        }
        reader.endObject()
        return result.toMap()
    }

    override fun parseTermMetaBank(stream: InputStream): Sequence<DictionaryTermMeta> =
        parseBank(stream, "term_meta_bank") { reader -> readSingleTermMeta(reader) }

    private fun readSingleTermMeta(reader: JsonReader): DictionaryTermMeta {
        reader.beginArray()
        val expression = reader.nextString()
        val mode = reader.nextString()
        val data = readJsonElement(reader).toString()
        reader.endArray()

        return DictionaryTermMeta(
            dictionaryId = 0L,
            expression = expression,
            mode = TermMetaMode.fromString(mode),
            data = data,
        )
    }

    override fun parseKanjiMetaBank(stream: InputStream): Sequence<DictionaryKanjiMeta> =
        parseBank(stream, "kanji_meta_bank") { reader -> readSingleKanjiMeta(reader) }

    private fun readSingleKanjiMeta(reader: JsonReader): DictionaryKanjiMeta {
        reader.beginArray()
        val character = reader.nextString()
        val mode = reader.nextString()
        val data = readJsonElement(reader).toString()
        reader.endArray()

        return DictionaryKanjiMeta(
            dictionaryId = 0L,
            character = character,
            mode = KanjiMetaMode.fromString(mode),
            data = data,
        )
    }
}
