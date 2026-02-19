@file:Suppress("SpellCheckingInspection")

package mihon.data.ocr

/**
 * Assembles vocab from chunked parts to avoid JVM method size limits.
 *
 * Generated file — do not edit manually.
 */
@JvmField
val vocabFast: Array<String> = buildVocab()

private fun buildVocab(): Array<String> {
    val parts: Array<Array<String>> = arrayOf(
        vocabFastPart000(),
        vocabFastPart001(),
    )

    val total = parts.sumOf { it.size }
    val out = Array(total) { "" }

    var index = 0
    for (part in parts) {
        part.copyInto(out, destinationOffset = index)
        index += part.size
    }
    return out
}
