package mihon.data.dictionary

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.TermMetaMode
import tachiyomi.data.Dictionary_tags
import tachiyomi.data.Dictionary_term_meta
import tachiyomi.data.Dictionary_terms

fun Dictionary_tags.toDomain(): DictionaryTag {
    return DictionaryTag(
        id = _id,
        dictionaryId = dictionary_id,
        name = name,
        category = category,
        order = tag_order.toInt(),
        notes = notes,
        score = score.toInt(),
    )
}

fun Dictionary_terms.toDomain(): DictionaryTerm {
    return DictionaryTerm(
        id = _id,
        dictionaryId = dictionary_id,
        expression = expression,
        reading = reading,
        definitionTags = definition_tags,
        rules = rules,
        score = score.toInt(),
        glossary = parseGlossary(glossary),
        sequence = sequence,
        termTags = term_tags,
    )
}

fun Dictionary_term_meta.toDomain(): DictionaryTermMeta {
    return DictionaryTermMeta(
        id = _id,
        dictionaryId = dictionary_id,
        expression = expression,
        mode = TermMetaMode.fromString(mode),
        data = data_,
    )
}

// Helper functions for JSON parsing
private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val glossarySerializer = ListSerializer(GlossaryEntry.serializer())
private val legacyStringListSerializer = ListSerializer(String.serializer())

private fun parseGlossary(inputJson: String?): List<GlossaryEntry> {
    if (inputJson.isNullOrBlank() || inputJson == "[]") return emptyList()

    return try {
        jsonParser.decodeFromString(glossarySerializer, inputJson)
    } catch (e: SerializationException) {
        parseLegacyGlossary(inputJson)
    } catch (e: IllegalArgumentException) {
        parseLegacyGlossary(inputJson)
    }
}

private fun parseLegacyGlossary(inputJson: String): List<GlossaryEntry> {
    val legacy = try {
        jsonParser.decodeFromString(legacyStringListSerializer, inputJson)
    } catch (_: SerializationException) {
        parseLegacyStringList(inputJson)
    } catch (_: IllegalArgumentException) {
        parseLegacyStringList(inputJson)
    }

    if (legacy.isEmpty()) return emptyList()

    return legacy.map { GlossaryEntry.TextDefinition(it) }
}

private fun parseLegacyStringList(inputJson: String): List<String> {
    val trimmed = inputJson.trim().removePrefix("[").removeSuffix("]")
    if (trimmed.isEmpty()) return emptyList()

    val result = mutableListOf<String>()
    var inString = false
    var escaped = false
    var depth = 0
    val currentToken = StringBuilder()

    for (char in trimmed) {
        when {
            escaped -> {
                currentToken.append(char)
                escaped = false
            }
            char == '\\' -> {
                escaped = true
                if (inString) {
                    currentToken.append(char)
                }
            }
            char == '"' -> {
                inString = !inString
                if (depth == 0) {
                    if (!inString && currentToken.isNotEmpty()) {
                        result.add(currentToken.toString())
                        currentToken.clear()
                    } else if (inString) {
                        currentToken.clear()
                    }
                }
            }
            inString -> currentToken.append(char)
            char == '{' || char == '[' -> {
                depth++
                if (depth == 1) {
                    currentToken.clear()
                }
            }
            char == '}' || char == ']' -> {
                depth--
                if (depth < 0) depth = 0
            }
        }
    }

    return result
}
