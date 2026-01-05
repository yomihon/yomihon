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
        append("[$baseText[$readingText]]")
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

                    val baseText = collectText(baseNodes)
                    val readingText = collectText(readingNodes)

                    if (readingText.isNotBlank()) {
                        builder.append("[$baseText[$readingText]]")
                    } else {
                        builder.append(baseText)
                    }
                }
                GlossaryTag.Link -> {
                    val href = node.attributes.properties["href"]
                    val linkText = collectText(node.children).ifBlank { href ?: "" }
                    
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
 * Parsed CSS data from a dictionary's styles.css file.
 * Contains both box selectors (for background/border styling) and
 * selector styles (for font properties like font-size).
 */
data class ParsedCss(
    val boxSelectors: Set<String>,
    val selectorStyles: Map<String, Map<String, String>>,
) {
    companion object {
        val EMPTY = ParsedCss(emptySet(), emptyMap())
    }
}

/**
 * Parses CSS text from a dictionary's styles.css into a structured format.
 * Uses a simple tokenizer approach instead of regex for cleaner parsing.
 *
 * Extracts:
 * - Box selectors: data-sc-content/class values that have background/border properties
 * - Selector styles: All CSS properties mapped by data-sc-content/class selector values
 */
internal fun parseDictionaryCss(cssText: String?): ParsedCss {
    if (cssText.isNullOrBlank()) return ParsedCss.EMPTY

    val cleanedCss = stripCssComments(cssText)
    if (cleanedCss.isBlank()) return ParsedCss.EMPTY

    val boxSelectors = mutableSetOf<String>()
    val selectorStyles = mutableMapOf<String, MutableMap<String, String>>()

    // Parse CSS rules by finding { } blocks
    var i = 0
    while (i < cleanedCss.length) {
        // Find the start of a rule block
        val braceStart = cleanedCss.indexOf('{', i)
        if (braceStart == -1) break

        // Extract selector part (before '{')
        val selectorPart = cleanedCss.substring(i, braceStart).trim()

        // Find the end of the rule block
        val braceEnd = cleanedCss.indexOf('}', braceStart)
        if (braceEnd == -1) break

        // Extract properties part (between '{' and '}')
        val propertiesPart = cleanedCss.substring(braceStart + 1, braceEnd)

        // Parse properties into key-value pairs
        val properties = parseProperties(propertiesPart)

        // Extract data-sc-content and data-sc-class selector values
        val dataSelectors = extractDataSelectors(selectorPart)

        // Check if this rule has box-related properties
        val hasBoxProperty = properties.keys.any { key ->
            key.startsWith("background") ||
            key.startsWith("border") ||
            key.startsWith("padding") ||
            key.startsWith("margin") ||
            key == "clip-path"
        }

        // Store results for each selector
        for (selector in dataSelectors) {
            if (hasBoxProperty) {
                boxSelectors.add(selector)
            }
            // Store all properties for this selector
            val existingStyles = selectorStyles.getOrPut(selector) { mutableMapOf() }
            // Convert CSS property names to camelCase for consistency
            properties.forEach { (key, value) ->
                existingStyles[toCamelCase(key)] = value
            }
        }

        i = braceEnd + 1
    }

    return ParsedCss(boxSelectors, selectorStyles)
}

/**
 * Strips CSS block comments (/* ... */) from the input string.
 */
private fun stripCssComments(css: String): String {
    val result = StringBuilder()
    var i = 0
    while (i < css.length) {
        if (i + 1 < css.length && css[i] == '/' && css[i + 1] == '*') {
            // Find end of comment
            val endIndex = css.indexOf("*/", i + 2)
            if (endIndex == -1) {
                // Unclosed comment, skip the rest
                break
            }
            i = endIndex + 2
        } else {
            result.append(css[i])
            i++
        }
    }
    return result.toString()
}

/**
 * Parses CSS property declarations from a properties block.
 * E.g., "font-size: 0.8em; color: red" -> {"font-size" to "0.8em", "color" to "red"}
 */
private fun parseProperties(propertiesPart: String): Map<String, String> {
    val result = mutableMapOf<String, String>()

    propertiesPart.split(';').forEach { declaration ->
        val colonIndex = declaration.indexOf(':')
        if (colonIndex != -1) {
            val key = declaration.take(colonIndex).trim().lowercase()
            val value = declaration.substring(colonIndex + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
    }

    return result
}

/**
 * Extracts data-sc-content and data-sc-class selector values from a CSS selector string.
 * E.g., "[data-sc-content='example']" -> ["example"]
 */
private fun extractDataSelectors(selectorPart: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0

    while (i < selectorPart.length) {
        // Look for [data-sc-content= or [data-sc-class=
        val attrStart = selectorPart.indexOf("[data-sc-", i)
        if (attrStart == -1) break

        // Find the attribute name end (=)
        val equalsIndex = selectorPart.indexOf('=', attrStart)
        if (equalsIndex == -1) {
            i = attrStart + 1
            continue
        }

        // Only content and class attribute names are supported
        val attrName = selectorPart.substring(attrStart + 1, equalsIndex)
        if (attrName != "data-sc-content" && attrName != "data-sc-class") {
            i = equalsIndex + 1
            continue
        }

        val valueStart = equalsIndex + 1
        if (valueStart >= selectorPart.length) break

        val quote = selectorPart[valueStart]
        if (quote != '\'' && quote != '"') {
            i = valueStart + 1
            continue
        }

        val valueEnd = selectorPart.indexOf(quote, valueStart + 1)
        if (valueEnd == -1) break

        val value = selectorPart.substring(valueStart + 1, valueEnd)
        if (value.isNotEmpty()) {
            result.add(value)
        }

        i = valueEnd + 1
    }

    return result
}

private fun toCamelCase(cssProperty: String): String {
    val parts = cssProperty.split('-')
    if (parts.size == 1) return parts[0]

    return buildString {
        append(parts[0])
        for (i in 1 until parts.size) {
            val part = parts[i]
            if (part.isNotEmpty()) {
                append(part[0].uppercaseChar())
                append(part.substring(1))
            }
        }
    }
}

/**
 * Represents parsed box styling properties from CSS.
 * Background color uses theme-based values for display compatibility.
 */
data class BoxStyle(
    val hasBackground: Boolean = false,
    val hasBorder: Boolean = false,
    val borderRadius: Float? = null,
    val paddingStart: Float? = null,
    val paddingEnd: Float? = null,
    val paddingTop: Float? = null,
    val paddingBottom: Float? = null,
    val marginStart: Float? = null,
    val marginEnd: Float? = null,
    val marginTop: Float? = null,
    val marginBottom: Float? = null,
) {
    companion object {
        val EMPTY = BoxStyle()
    }

    val hasPadding: Boolean
        get() = paddingStart != null || paddingEnd != null || paddingTop != null || paddingBottom != null

    val hasMargin: Boolean
        get() = marginStart != null || marginEnd != null || marginTop != null || marginBottom != null

    val hasAnyStyle: Boolean
        get() = hasBackground || hasBorder || hasPadding || hasMargin
}

/**
 * Parses a CSS style map into a BoxStyle.
 * Checks for background, border, padding, and margin properties.
 *
 * @param styleMap CSS style properties to parse
 * @param baseFontSizeSp Base font size in sp for em/rem unit conversion
 */
internal fun parseBoxStyle(styleMap: Map<String, String>, baseFontSizeSp: Float = 14f): BoxStyle {
    var hasBackground = false
    var hasBorder = false
    var borderRadius: Float? = null
    var paddingStart: Float? = null
    var paddingEnd: Float? = null
    var paddingTop: Float? = null
    var paddingBottom: Float? = null
    var marginStart: Float? = null
    var marginEnd: Float? = null
    var marginTop: Float? = null
    var marginBottom: Float? = null

    for ((key, value) in styleMap) {
        when {
            key == "backgroundColor" || key == "background" -> {
                // Check if it's a meaningful background (not transparent/inherit)
                if (value.isNotBlank() && value != "transparent" && value != "inherit" && value != "none") {
                    hasBackground = true
                }
            }

            key == "border" || key == "borderColor" || key == "borderWidth" -> {
                if (value.isNotBlank() && value != "none" && value != "0" && value != "0px") {
                    hasBorder = true
                }
            }

            key == "borderRadius" -> {
                borderRadius = parseDpValue(value, baseFontSizeSp)
            }

            key == "padding" -> {
                val dp = parseDpValue(value, baseFontSizeSp)
                if (dp != null) {
                    paddingStart = dp
                    paddingEnd = dp
                    paddingTop = dp
                    paddingBottom = dp
                }
            }
            key == "paddingLeft" || key == "paddingInlineStart" -> {
                paddingStart = parseDpValue(value, baseFontSizeSp)
            }
            key == "paddingRight" || key == "paddingInlineEnd" -> {
                paddingEnd = parseDpValue(value, baseFontSizeSp)
            }
            key == "paddingTop" -> {
                paddingTop = parseDpValue(value, baseFontSizeSp)
            }
            key == "paddingBottom" -> {
                paddingBottom = parseDpValue(value, baseFontSizeSp)
            }

            key == "margin" -> {
                val dp = parseDpValue(value, baseFontSizeSp)
                if (dp != null) {
                    marginStart = dp
                    marginEnd = dp
                    marginTop = dp
                    marginBottom = dp
                }
            }
            key == "marginLeft" || key == "marginInlineStart" -> {
                marginStart = parseDpValue(value, baseFontSizeSp)
            }
            key == "marginRight" || key == "marginInlineEnd" -> {
                marginEnd = parseDpValue(value, baseFontSizeSp)
            }
            key == "marginTop" -> {
                marginTop = parseDpValue(value, baseFontSizeSp)
            }
            key == "marginBottom" -> {
                marginBottom = parseDpValue(value, baseFontSizeSp)
            }
        }
    }

    return BoxStyle(
        hasBackground = hasBackground,
        hasBorder = hasBorder,
        borderRadius = borderRadius,
        paddingStart = paddingStart,
        paddingEnd = paddingEnd,
        paddingTop = paddingTop,
        paddingBottom = paddingBottom,
        marginStart = marginStart,
        marginEnd = marginEnd,
        marginTop = marginTop,
        marginBottom = marginBottom,
    )
}

/**
 * Parses a CSS dimension value to a Float representing dp.
 * Supports: px, em, rem, dp, and unitless numbers.
 */
private fun parseDpValue(value: String, baseFontSizeSp: Float): Float? {
    val trimmed = value.trim().lowercase()
    return when {
        trimmed.endsWith("px") -> trimmed.removeSuffix("px").toFloatOrNull()
        trimmed.endsWith("em") -> trimmed.removeSuffix("em").toFloatOrNull()?.times(baseFontSizeSp)
        trimmed.endsWith("rem") -> trimmed.removeSuffix("rem").toFloatOrNull()?.times(baseFontSizeSp)
        trimmed.endsWith("dp") -> trimmed.removeSuffix("dp").toFloatOrNull()
        else -> trimmed.toFloatOrNull()
    }
}

/**
 * Gets merged CSS styles for an element based on its data attributes.
 *
 * @param dataAttributes Data attributes from the element
 * @param parsedCss Parsed CSS from the dictionary's styles.css
 * @return Merged style map from all matching selectors
 */
internal fun getCssStyles(
    dataAttributes: Map<String, String>,
    parsedCss: ParsedCss,
): Map<String, String> {
    return dataAttributes.values
        .mapNotNull { parsedCss.selectorStyles[it] }
        .fold(emptyMap()) { acc, map -> acc + map }
}
