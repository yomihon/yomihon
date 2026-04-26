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
import mihon.domain.dictionary.model.GlossaryElementAttributes
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryImageAttributes
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import mihon.domain.dictionary.service.DictionaryParseException
import mihon.domain.dictionary.service.DictionaryParser
import java.io.StringReader

class DictionaryParserImpl : DictionaryParser {

    private val jsonParser = Json { ignoreUnknownKeys = true }

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

    override fun parseGlossary(rawGlossary: String): List<GlossaryEntry> {
        if (rawGlossary.isBlank()) return emptyList()

        val reader = JsonReader(StringReader(rawGlossary))
        return try {
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> readGlossaryArray(reader)
                JsonToken.STRING -> listOf(GlossaryEntry.TextDefinition(reader.nextString()))
                JsonToken.BEGIN_OBJECT -> listOf(readGlossaryObject(reader))
                JsonToken.NULL -> {
                    reader.nextNull()
                    emptyList()
                }
                else -> {
                    reader.skipValue()
                    emptyList()
                }
            }
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse glossary", e)
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
}
