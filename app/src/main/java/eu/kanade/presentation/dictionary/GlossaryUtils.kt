/**
 * Contains non-UI, helper functions for processing the GlossaryNode tree.
 *
 * The functions in this file are responsible for transforming, analyzing, or extracting glossary data.
 */
package eu.kanade.presentation.dictionary

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import kotlin.collections.forEach

internal fun extractForms(entries: List<GlossaryEntry>): List<String> {
    if (entries.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        when (entry) {
            is GlossaryEntry.TextDefinition -> entry.text.takeIf { it.isNotBlank() }
            is GlossaryEntry.StructuredContent -> collectText(entry.nodes).takeIf { it.isNotBlank() }
            is GlossaryEntry.Deinflection -> entry.baseForm.takeIf { it.isNotBlank() }
            else -> null
        }
    }
        .distinct()
}

/**
 * Extracts attribution text from glossary entries.
 * Attribution content is identified by data-content="attribution" in div elements.
 */
internal fun extractAttributionText(entries: List<GlossaryEntry>): String? {
    for (entry in entries) {
        if (entry is GlossaryEntry.StructuredContent) {
            val text = extractAttributionFromNodes(entry.nodes)
            if (text != null) return text
        }
    }
    return null
}

private fun extractAttributionFromNodes(nodes: List<GlossaryNode>): String? {
    for (node in nodes) {
        if (node is GlossaryNode.Element) {
            if (node.tag == GlossaryTag.Div &&
                node.attributes.dataAttributes["content"] == "attribution") {
                return collectText(node.children).takeIf { it.isNotBlank() }
            }
            // Recurse into children
            val result = extractAttributionFromNodes(node.children)
            if (result != null) return result
        }
    }
    return null
}

internal fun collectText(nodes: List<GlossaryNode>): String {
    val builder = StringBuilder()
    nodes.forEach { node -> builder.appendNodeText(node) }
    return builder.toString().replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
}

internal fun StringBuilder.appendNodeText(node: GlossaryNode) {
    when (node) {
        is GlossaryNode.Text -> append(node.text)
        is GlossaryNode.LineBreak -> append('\n')
        is GlossaryNode.Element -> when (node.tag) {
            GlossaryTag.Ruby -> appendRubyInline(node)
            GlossaryTag.Link -> appendLinkInline(node)
            GlossaryTag.Image -> Unit // Ignore images
            GlossaryTag.Summary -> {
                append("Summary: ")
                node.children.forEach { child -> appendNodeText(child) }
            }
            else -> node.children.forEach { child -> appendNodeText(child) }
        }
    }
}


internal fun StringBuilder.appendRubyInline(node: GlossaryNode.Element) {
    val baseNodes = node.children.filterNot { child ->
        child is GlossaryNode.Element && (child.tag == GlossaryTag.Rt || child.tag == GlossaryTag.Rp)
    }
    val readingNodes = node.children.filterIsInstance<GlossaryNode.Element>()
        .filter { it.tag == GlossaryTag.Rt }
        .flatMap { it.children }

    val baseText = collectText(baseNodes)
    val readingText = collectText(readingNodes)

    if (readingText.isNotBlank()) {
        append("[")
        append(baseText)
        append("[")
        append(readingText)
        append("]]")
    } else {
        append(baseText)
    }
}

internal fun StringBuilder.appendLinkInline(node: GlossaryNode.Element) {
    val linkText = collectText(node.children).ifBlank { node.attributes.properties["href"] ?: "" }
    append(linkText)
    node.attributes.properties["href"]?.takeIf { it.isNotBlank() }?.let { href ->
        append(" (")
        append(href)
        append(')')
    }
}

internal fun GlossaryNode.hasBlockContent(): Boolean {
    return when (this) {
        is GlossaryNode.Text -> false
        is GlossaryNode.LineBreak -> true
        is GlossaryNode.Element -> when (tag) {
            GlossaryTag.Ruby, GlossaryTag.Link, GlossaryTag.Span, GlossaryTag.Image ->
                children.any { child -> child.hasBlockContent() }
            GlossaryTag.Rt, GlossaryTag.Rp -> false
            GlossaryTag.Div -> children.any { child -> child.hasBlockContent() }
            GlossaryTag.Unknown -> children.any { child -> child.hasBlockContent() }
            else -> true
        }
    }
}

internal fun GlossaryNode.containsLink(): Boolean {
    return when (this) {
        is GlossaryNode.Text -> false
        is GlossaryNode.LineBreak -> false
        is GlossaryNode.Element -> tag == GlossaryTag.Link || children.any { it.containsLink() }
    }
}

internal fun List<GlossaryNode>.containsLink(): Boolean {
    return any { it.containsLink() }
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

/**
 * Checks if the dictionary node has a box style.
 *
 * @param style Inline style properties from the element
 * @param dataAttributes Data attributes from the element (e.g., "content", "class")
 * @param cssBoxSelectors Selectors that have box styles, as parsed from the dictionary's styles.css
 */
internal fun hasBoxStyle(
    style: Map<String, String>,
    dataAttributes: Map<String, String>,
    cssBoxSelectors: Set<String> = emptySet(),
): Boolean {
    // Check inline style properties for background/border
    val hasStyleBox = style.keys.any { key ->
        key.startsWith("background") || key.startsWith("border")
    }

    // Check if any dataAttribute value matches a selector that has box styles in CSS
    val hasDataAttrBox = dataAttributes.values.any { value ->
        value in cssBoxSelectors
    }

    return hasStyleBox || hasDataAttrBox
}

private val RULE_PATTERN = Regex("""([^{}]+)\{([^{}]*)\}""", RegexOption.DOT_MATCHES_ALL)
private val SELECTOR_ATTR_PATTERN = Regex("""\[data-sc-(?:content|class)=["']([^"']+)["']]""")
private val BOX_PROPERTY_PATTERN = Regex("""(?:^|\s|;)(background(?:-color)?|border(?:-[a-z]+)?)\s*:""", RegexOption.IGNORE_CASE)

internal fun getBoxSelectors(cssText: String?): Set<String> {
    if (cssText.isNullOrBlank()) return emptySet()

    return RULE_PATTERN.findAll(cssText)
        .filter { match -> match.groupValues[2].contains(BOX_PROPERTY_PATTERN) }
        .flatMap { match -> SELECTOR_ATTR_PATTERN.findAll(match.groupValues[1]) }
        .map { it.groupValues[1] }
        .toSet()
}

private val FONT_SIZE_PATTERN = Regex("""font-size\s*:\s*([^;}\s]+)""", RegexOption.IGNORE_CASE)

internal fun getFontStyleSelectors(cssText: String?): Map<String, Map<String, String>> {
    if (cssText.isNullOrBlank()) return emptyMap()

    val result = mutableMapOf<String, MutableMap<String, String>>()

    RULE_PATTERN.findAll(cssText).forEach { match ->
        val selectorPart = match.groupValues[1]
        val propertyPart = match.groupValues[2]

        // Extract font-size if present
        val fontSizeMatch = FONT_SIZE_PATTERN.find(propertyPart)
        if (fontSizeMatch != null) {
            val fontSize = fontSizeMatch.groupValues[1].trim()

            // Find all matching selectors in the selector part
            SELECTOR_ATTR_PATTERN.findAll(selectorPart).forEach { selectorMatch ->
                val selectorName = selectorMatch.groupValues[1]
                result.getOrPut(selectorName) { mutableMapOf() }["fontSize"] = fontSize
            }
        }
    }

    return result
}
