/**
 * Contains Compose UI-specific helper functions for rendering GlossaryNode trees.
 *
 * Domain logic (text extraction, node analysis) is in:
 *   mihon.domain.dictionary.model.GlossaryExtensions
 *
 * CSS parsing is in:
 *   mihon.domain.dictionary.css.CssParser
 */
package eu.kanade.presentation.dictionary

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import mihon.domain.dictionary.model.collectText

/**
 * Represents a clickable link range within annotated text.
 */
internal data class LinkRange(
    val start: Int,
    val end: Int,
    val target: String,
)

/**
 * Result of building annotated text from GlossaryNode tree.
 */
internal data class AnnotatedTextResult(
    val text: String,
    val linkRanges: List<LinkRange>,
)

/**
 * Builds annotated text from GlossaryNode children with link tracking.
 * Ruby text is formatted using `[Base \[Reading]]` for furiganable.
 * Link positions are tracked for click handling.
 */
internal fun buildAnnotatedText(nodes: List<GlossaryNode>): AnnotatedTextResult {
    val builder = StringBuilder()
    val linkRanges = mutableListOf<LinkRange>()

    fun appendNode(node: GlossaryNode) {
        when (node) {
            is GlossaryNode.Text -> builder.append(node.text)
            is GlossaryNode.LineBreak -> builder.append(' ')
            is GlossaryNode.Element -> when (node.tag) {
                GlossaryTag.Ruby -> {
                    val baseNodes = node.children.filterNot { child ->
                        child is GlossaryNode.Element && (child.tag == GlossaryTag.Rt || child.tag == GlossaryTag.Rp)
                    }
                    val readingNodes = node.children.filterIsInstance<GlossaryNode.Element>()
                        .filter { it.tag == GlossaryTag.Rt }
                        .flatMap { it.children }

                    val baseText = baseNodes.collectText()
                    val readingText = readingNodes.collectText()

                    if (readingText.isNotBlank()) {
                        builder.append("[$baseText[$readingText]]")
                    } else {
                        builder.append(baseText)
                    }
                }
                GlossaryTag.Link -> {
                    val href = node.attributes.properties["href"]
                    val linkText = node.children.collectText().ifBlank { href ?: "" }

                    // Extract query parameter for dictionary links
                    val target = if (href?.startsWith("?") == true) {
                        href.drop(1).split("&")
                            .find { it.startsWith("query=") }
                            ?.substringAfter("query=")
                            ?.let { rawValue ->
                                try {
                                    java.net.URLDecoder.decode(rawValue, "UTF-8")
                                } catch (e: Exception) {
                                    rawValue
                                }
                            }
                            ?: href
                    } else {
                        href ?: linkText
                    }

                    val start = builder.length
                    builder.append(linkText)
                    val end = builder.length

                    if (target.isNotBlank()) {
                        linkRanges.add(LinkRange(start, end, target))
                    }
                }
                GlossaryTag.Image -> Unit // Ignore images
                GlossaryTag.Rt, GlossaryTag.Rp -> Unit // Handled by Ruby
                else -> node.children.forEach { child -> appendNode(child) }
            }
        }
    }

    nodes.forEach { node -> appendNode(node) }

    return AnnotatedTextResult(
        text = builder.toString(),
        linkRanges = linkRanges.toList(),
    )
}

/**
 * Applies typography styles to a TextStyle.
 * Ignores color properties to maintain theme compatibility.
 */
internal fun applyTypography(
    baseStyle: TextStyle,
    styleMap: Map<String, String>,
): TextStyle {
    return styleMap.entries.fold(baseStyle) { newStyle, (key, value) ->
        when (key) {
            "fontStyle" -> if (value == "italic") {
                newStyle.copy(fontStyle = FontStyle.Italic)
            } else {
                newStyle
            }

            "fontWeight" -> if (value == "bold") {
                newStyle.copy(fontWeight = FontWeight.Bold)
            } else {
                newStyle
            }

            "fontSize" -> {
                val baseFontSize = baseStyle.fontSize.let {
                    if (it == TextUnit.Unspecified) 14.sp else it
                }
                val scale = when {
                    value.endsWith("em") -> value.removeSuffix("em").toFloatOrNull()
                    value.endsWith("%") -> value.removeSuffix("%").toFloatOrNull()?.div(100f)
                    else -> null
                }
                if (scale != null) {
                    newStyle.copy(fontSize = baseFontSize * scale)
                } else {
                    newStyle
                }
            }

            else -> newStyle
        }
    }
}
