package mihon.data.ankidroid

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.util.isNotEmpty
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import com.ichi2.anki.api.AddContentApi.Companion.READ_WRITE_PERMISSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.ankidroid.repository.AnkiDroidRepository
import mihon.domain.dictionary.model.DictionaryTermCard
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.ankidroid.service.AnkiDroidPreferences

class AnkiDroidRepositoryImpl(
    context: Context,
    private val ankiDroidPreferences: AnkiDroidPreferences,
) : AnkiDroidRepository {

    private val appContext = context.applicationContext
    private val api by lazy { AddContentApi(appContext) }

    // Cache IDs to prevent repetitive DB queries
    private var cachedDeckId: Long? = null
    private var cachedModelId: Long? = null

    override suspend fun addCard(card: DictionaryTermCard): AnkiDroidRepository.Result = withContext(Dispatchers.IO) {
        // Check for AnkiDroid installation and card permissions, since they're needed for the API
        if (AddContentApi.getAnkiDroidPackageName(appContext) == null ||
            ContextCompat.checkSelfPermission(appContext, READ_WRITE_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext AnkiDroidRepository.Result.NotAvailable
        }

        try {
            // Get deck and model from preferences
            val preferredDeckId = ankiDroidPreferences.deckId().get()
            val preferredDeckName = ankiDroidPreferences.deckName().get()
            val preferredModelId = ankiDroidPreferences.modelId().get()
            val preferredModelName = ankiDroidPreferences.modelName().get()

            val deckId = getOrCreateDeck(name = preferredDeckName, id = preferredDeckId)
                ?: return@withContext AnkiDroidRepository.Result.Error()

            val modelId = getOrCreateModel(name = preferredModelName, deckId = deckId, id = preferredModelId)
                ?: return@withContext AnkiDroidRepository.Result.Error()

            // Get model fields and build content array based on field mappings
            val modelFields = api.getFieldList(modelId)?.toList() ?: emptyList()
            if (modelFields.isEmpty()) {
                return@withContext AnkiDroidRepository.Result.Error()
            }

            val fieldMappings = ankiDroidPreferences.fieldMappings().get()

            val pictureFilename = importPicture(card.pictureUrl)
            val audioFilename = importAudio(card.audio)

            val fieldValues = buildFieldValues(card, modelFields, fieldMappings, pictureFilename, audioFilename)

            val added = api.addNote(modelId, deckId, fieldValues, card.tags)

            if (added != null && added > 0) AnkiDroidRepository.Result.Added else AnkiDroidRepository.Result.Error()
        } catch (e: Exception) {
            AnkiDroidRepository.Result.Error(e)
        }
    }

    override suspend fun findExistingNotes(expressions: List<String>): Set<String> = withContext(Dispatchers.IO) {
        if (expressions.isEmpty()) return@withContext emptySet()

        if (AddContentApi.getAnkiDroidPackageName(appContext) == null ||
            ContextCompat.checkSelfPermission(appContext, READ_WRITE_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext emptySet()
        }

        try {
            val preferredDeckId = ankiDroidPreferences.deckId().get()
            val preferredDeckName = ankiDroidPreferences.deckName().get()
            val preferredModelId = ankiDroidPreferences.modelId().get()
            val preferredModelName = ankiDroidPreferences.modelName().get()

            val deckId = getOrCreateDeck(name = preferredDeckName, id = preferredDeckId)
                ?: return@withContext emptySet()

            val modelId = getOrCreateModel(name = preferredModelName, deckId = deckId, id = preferredModelId)
                ?: return@withContext emptySet()

            val duplicates = api.findDuplicateNotes(modelId, expressions)
                ?: return@withContext emptySet()

            buildSet {
                for (i in expressions.indices) {
                    val notes = duplicates[i]
                    if (notes != null && notes.isNotEmpty()) {
                        add(expressions[i])
                    }
                }
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Build an array of field values based on model fields and field mappings.
     */
    private fun buildFieldValues(
        card: DictionaryTermCard,
        modelFields: List<String>,
        fieldMappings: Map<String, String>,
        pictureFilename: String?,
        audioFilename: String?,
    ): Array<String> {
        return modelFields.map { noteField ->
            val appField = fieldMappings[noteField]
            if (appField == "picture") {
                if (pictureFilename != null) {
                    "<img src=\"$pictureFilename\" style=\"margin-top: 16px;\">"
                } else {
                    ""
                }
            } else if (appField == "audio") {
                audioFilename?.let { "[sound:$it]" }.orEmpty()
            } else if (appField == "furigana") {
                formatFurigana(card.expression, card.reading)
            } else if (appField != null) {
                card.getFieldValue(appField)
            } else {
                ""
            }
        }.toTypedArray()
    }

    private fun importPicture(pictureUrl: String): String? {
        if (pictureUrl.isBlank()) return null
        return runCatching {
            val uri = pictureUrl.toUri()
            val mimeType = appContext.contentResolver.getType(uri)
            val extension = ImageUtil.getExtensionFromMimeType(mimeType) {
                appContext.contentResolver.openInputStream(uri)!!
            }
            importMedia(
                uri = uri.toString(),
                preferredName = "yomihon-${System.currentTimeMillis()}.$extension",
            )
        }.getOrNull()
    }

    private fun importAudio(audioPath: String): String? {
        if (audioPath.isBlank()) return null
        return runCatching {
            val file = java.io.File(audioPath)
            if (!file.isFile) return@runCatching null
            val extension = file.extension.ifBlank { "mp3" }
            val uri = file.getUriCompat()
            importMedia(
                uri = uri.toString(),
                preferredName = "yomihon-audio-${System.currentTimeMillis()}.$extension",
            )
        }.getOrNull()
    }

    private fun importMedia(
        uri: String,
        preferredName: String,
    ): String? {
        AddContentApi.getAnkiDroidPackageName(appContext)?.let { packageName ->
            appContext.grantUriPermission(
                packageName,
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val contentValues = ContentValues()
        contentValues.put(FlashCardsContract.AnkiMedia.FILE_URI, uri)
        contentValues.put(FlashCardsContract.AnkiMedia.PREFERRED_NAME, preferredName)

        val returnUri = appContext.contentResolver.insert(
            FlashCardsContract.AnkiMedia.CONTENT_URI,
            contentValues,
        ) ?: return null

        return returnUri.lastPathSegment ?: preferredName
    }

    private fun java.io.File.getUriCompat(): Uri {
        return FileProvider.getUriForFile(
            appContext,
            appContext.packageName + ".provider",
            this,
        )
    }

    override suspend fun getDecks(): Map<Long, String> = withContext(Dispatchers.IO) {
        api.deckList ?: emptyMap()
    }

    override suspend fun getModels(minFields: Int): Map<Long, String> = withContext(Dispatchers.IO) {
        api.getModelList(minFields) ?: emptyMap()
    }

    override suspend fun getModelFields(modelId: Long): List<String> = withContext(Dispatchers.IO) {
        api.getFieldList(modelId)?.toList() ?: emptyList()
    }

    override suspend fun isApiAvailable(): Boolean = withContext(Dispatchers.IO) {
        AddContentApi.getAnkiDroidPackageName(appContext) != null
    }

    override suspend fun hasPermission(): Boolean = withContext(Dispatchers.IO) {
        ContextCompat.checkSelfPermission(appContext, READ_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun getOrCreateDeck(name: String, id: Long): Long? = withContext(Dispatchers.IO) {
        try {
            if (id > 0) {
                val decks = api.deckList ?: emptyMap()
                if (decks.containsKey(id)) {
                    cachedDeckId = id
                    return@withContext id
                }
            }

            val decks = api.deckList ?: emptyMap()
            val existingDeck = decks.entries.firstOrNull { it.value == name }
            if (existingDeck != null) {
                cachedDeckId = existingDeck.key
                return@withContext existingDeck.key
            }

            val newDeckId = api.addNewDeck(name)
            if (newDeckId != null && newDeckId > 0) {
                cachedDeckId = newDeckId
                newDeckId
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getOrCreateModel(name: String, deckId: Long, id: Long): Long? = withContext(Dispatchers.IO) {
        try {
            if (id > 0) {
                val models = api.modelList ?: emptyMap()
                if (models.containsKey(id)) {
                    cachedModelId = id
                    return@withContext id
                }
            }

            val models = api.modelList ?: emptyMap()
            val existingModel = models.entries.firstOrNull { it.value == name }
            if (existingModel != null) {
                cachedModelId = existingModel.key
                return@withContext existingModel.key
            }

            val newModelId = api.addNewCustomModel(
                name,
                YOMIHON_FIELDS,
                YOMIHON_CARD_NAMES,
                YOMIHON_FRONT,
                YOMIHON_BACK,
                YOMIHON_CSS,
                deckId,
                null,
            )
            if (newModelId != null && newModelId > 0) {
                cachedModelId = newModelId
                newModelId
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        // Yomihon Card default model definition
        private val YOMIHON_FIELDS = arrayOf(
            "Word",
            "Word Reading",
            "Word Meaning",
            "Sentence",
            "Word Audio",
            "Pitch Accent",
            "Frequency",
            "Picture",
            "Notes",
        )

        private val YOMIHON_CARD_NAMES = arrayOf("Yomihon Card")

        private val YOMIHON_FRONT = arrayOf(
            """
            <div lang="ja">
            {{Word}}
            </div>
            """.trimIndent(),
        )

        private val YOMIHON_BACK = arrayOf(
            """
            <div lang="ja" class="main-wrapper">

            <div class="word-reading" style='font-size: 32px'>{{Word Reading}}</div>

            <hr>

            <div class="glossary">{{Word Meaning}}</div>

            <hr>

            <div style='font-size: 18px;'>{{Sentence}}</div>
            <div style='font-size: 20px; padding-bottom:10px'></div>

            <div style='font-size: 20px;'>
            {{Word Audio}}

            </div>

            <!-- Flexbox for centering-->
            {{#Pitch Accent}}
            	<br><div style='font-size: 12px; display: flex; justify-content: center; margin-top: -40px;'>{{Pitch Accent}}</div>
            {{/Pitch Accent}}

            <br><div style='font-size: 12px; text-align: left; margin-top: -40px;'>{{Frequency}}
            </div>

            <div style="margin-top: 30px;">
            {{Picture}}
            </div>

            {{#Notes}}
            	<br>
            	<div style='font-size: 20px; padding-top:12px; border: solid gray 1px; color: #D5D6DB;'>{{Notes}}</div>
            {{/Notes}}

            </div>
            """.trimIndent(),
        )

        private const val YOMIHON_CSS = """
.card {
 font-family: "ヒラギノ角ゴ Pro W3", "Hiragino Kaku Gothic Pro", "Noto Sans JP", "Noto Sans CJK JP", Osaka, "メイリオ", Meiryo, "ＭＳ Ｐゴシック", "MS PGothic", "MS UI Gothic", sans-serif;
 font-size: 44px;
 text-align: center;
}

img {
	max-width: 900px;
	max-height: 900px;
}

.mobile img {
	max-width: 90vw;
}

b{color: #5586cd}

.glossary {
  font-size: 16px;
  padding-bottom: 20px;
  text-align: left;
}

.glossary table {
  border-collapse: collapse;
  border-spacing: 0;
}

.glossary th,
.glossary td {
  border: 1px solid #8a8a8a;
  padding: 0.25em 0.5em;
  vertical-align: top;
}

.glossary th {
  font-weight: bold;
}

.word-reading rt {
	font-size: 45%;
}

.mobile .main-wrapper {
  width: 100%;
  margin: 0 auto;
}
.main-wrapper {
  width: 50%;
  margin: 0 auto;
}

[data-sc-content|="attribution"] {
  display: none;
}

.mobile span[data-sc-class="tag"] {
  border-radius: 0.3em;
  font-size: 0.8em;
  font-weight: bold;
  margin-right: 0.5em;
  padding: 0.2em 0.3em;
  vertical-align: text-bottom;
  word-break: keep-all;
}
.mobile span[data-sc-content="part-of-speech-info"] {
  background-color: rgb(86, 86, 86);
  color: white;
}
"""
    }
}

private data class ExpressionToken(
    val value: String,
    val hasKanji: Boolean,
)

private data class FuriganaParseResult(
    val html: String,
    val nextReadingIndex: Int,
)

internal fun formatFurigana(expression: String, reading: String): String {
    if (reading.isBlank() || reading == expression) {
        return expression
    }

    val tokens = tokenizeExpression(expression)
    if (tokens.none(ExpressionToken::hasKanji)) {
        return expression
    }

    if (tokens.size == 1 && tokens[0].hasKanji) {
        return "<ruby>$expression<rt>$reading</rt></ruby>"
    }

    val normalizedReading = reading.normalizeKana()
    val formatted = buildFurigana(tokens, reading, normalizedReading, tokenIndex = 0, readingIndex = 0)
    return formatted?.takeIf { it.nextReadingIndex == reading.length }?.html ?: expression
}

private fun tokenizeExpression(expression: String): List<ExpressionToken> {
    if (expression.isEmpty()) return emptyList()

    val tokens = mutableListOf<ExpressionToken>()
    val current = StringBuilder()
    var currentHasKanji = expression[0].isKanjiLike()

    expression.forEach { char ->
        val hasKanji = char.isKanjiLike()
        if (current.isNotEmpty() && hasKanji != currentHasKanji) {
            tokens += ExpressionToken(current.toString(), currentHasKanji)
            current.clear()
        }
        current.append(char)
        currentHasKanji = hasKanji
    }

    if (current.isNotEmpty()) {
        tokens += ExpressionToken(current.toString(), currentHasKanji)
    }

    return tokens
}

private fun buildFurigana(
    tokens: List<ExpressionToken>,
    reading: String,
    normalizedReading: String,
    tokenIndex: Int,
    readingIndex: Int,
): FuriganaParseResult? {
    if (tokenIndex == tokens.size) {
        return FuriganaParseResult(html = "", nextReadingIndex = readingIndex)
    }

    val token = tokens[tokenIndex]
    if (!token.hasKanji) {
        val normalizedToken = token.value.normalizeKana()
        if (!normalizedReading.startsWith(normalizedToken, startIndex = readingIndex)) {
            return null
        }

        val next = buildFurigana(
            tokens = tokens,
            reading = reading,
            normalizedReading = normalizedReading,
            tokenIndex = tokenIndex + 1,
            readingIndex = readingIndex + token.value.length,
        ) ?: return null

        return FuriganaParseResult(
            html = token.value + next.html,
            nextReadingIndex = next.nextReadingIndex,
        )
    }

    val minReadingEnd = readingIndex + 1
    for (candidateEnd in minReadingEnd..reading.length) {
        val rubyReading = reading.substring(readingIndex, candidateEnd)
        val next = buildFurigana(
            tokens = tokens,
            reading = reading,
            normalizedReading = normalizedReading,
            tokenIndex = tokenIndex + 1,
            readingIndex = candidateEnd,
        ) ?: continue

        return FuriganaParseResult(
            html = "<ruby>${token.value}<rt>$rubyReading</rt></ruby>${next.html}",
            nextReadingIndex = next.nextReadingIndex,
        )
    }

    return null
}

private fun String.normalizeKana(): String {
    return buildString(length) {
        for (char in this@normalizeKana) {
            append(char.toHiragana())
        }
    }
}

private fun Char.toHiragana(): Char {
    return when (this) {
        in '\u30A1'..'\u30F6' -> (code - 0x60).toChar()
        else -> this
    }
}

private fun Char.isKanjiLike(): Boolean {
    return this == '々' || Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN
}
