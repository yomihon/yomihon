/**
 * Converts GlossaryEntry and GlossaryNode structures to HTML for Anki card export.
 * Preserves the structured content including data attributes used by dictionary CSS.
 */
package mihon.domain.dictionary.service

import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryImageAttributes
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag

/**
 * Converts a list of glossary entries to HTML wrapped in a glossary div.
 *
 * @param styles Optional CSS styles from the dictionary to embed
 * @return HTML string with embedded styles and glossary content
 */
fun List<GlossaryEntry>.toHtml(styles: String? = null): String {
    val sb = StringBuilder()
    if (!styles.isNullOrBlank()) {
        sb.append("<style>").append(styles).append("</style>")
    }
    sb.append("<div class=\"glossary\">")
    forEach { it.appendTo(sb) }
    sb.append("</div>")
    return sb.toString()
}

/**
 * Appends a glossary entry as HTML to the StringBuilder.
 */
private fun GlossaryEntry.appendTo(sb: StringBuilder) {
    when (this) {
        is GlossaryEntry.TextDefinition -> {
            sb.append("<div class=\"definition\">")
            sb.append(text.escapeHtml())
            sb.append("</div>")
        }
        is GlossaryEntry.StructuredContent -> {
            nodes.forEach { it.appendTo(sb) }
        }
        is GlossaryEntry.ImageDefinition -> {
            image.appendTo(sb)
        }
        is GlossaryEntry.Deinflection -> {
            sb.append("<div class=\"deinflection\">")
            sb.append(baseForm.escapeHtml())
            if (rules.isNotEmpty()) {
                sb.append(" ← ")
                sb.append(rules.joinToString(" → ").escapeHtml())
            }
            sb.append("</div>")
        }
        is GlossaryEntry.Unknown -> {
            // Skip unknown entries
        }
    }
}

/**
 * Appends an image as HTML to the StringBuilder.
 */
private fun GlossaryImageAttributes.appendTo(sb: StringBuilder) {
    sb.append("<img src=\"").append(path.escapeHtml()).append("\"")
    width?.let { sb.append(" width=\"").append(it).append("\"") }
    height?.let { sb.append(" height=\"").append(it).append("\"") }
    alt?.let { sb.append(" alt=\"").append(it.escapeHtml()).append("\"") }
    title?.let { sb.append(" title=\"").append(it.escapeHtml()).append("\"") }
    sb.append(" />")
}

/**
 * Appends a glossary node as HTML to the StringBuilder.
 */
private fun GlossaryNode.appendTo(sb: StringBuilder) {
    when (this) {
        is GlossaryNode.Text -> sb.append(text.escapeHtml())
        is GlossaryNode.LineBreak -> sb.append("<br />")
        is GlossaryNode.Element -> appendElementTo(sb)
    }
}

/**
 * Appends an element node as HTML to the StringBuilder.
 */
private fun GlossaryNode.Element.appendElementTo(sb: StringBuilder) {
    val tagName = tag.toHtmlTag()

    sb.append("<").append(tagName)
    appendAttributesTo(sb)

    if (tag.isVoidElement()) {
        sb.append(" />")
    } else {
        sb.append(">")
        children.forEach { it.appendTo(sb) }
        sb.append("</").append(tagName).append(">")
    }
}

/**
 * Appends element attributes to the StringBuilder.
 */
private fun GlossaryNode.Element.appendAttributesTo(sb: StringBuilder) {
    // Data attributes (data-sc-content, data-sc-class, etc.)
    attributes.dataAttributes.forEach { (key, value) ->
        sb.append(" data-sc-").append(key).append("=\"").append(value.escapeHtml()).append("\"")
    }

    // Regular properties (href, etc.)
    attributes.properties.forEach { (key, value) ->
        sb.append(" ").append(key).append("=\"").append(value.escapeHtml()).append("\"")
    }

    // Inline styles
    if (attributes.style.isNotEmpty()) {
        sb.append(" style=\"")
        var first = true
        attributes.style.forEach { (key, value) ->
            if (!first) sb.append("; ")
            sb.append(key.toKebabCase()).append(": ").append(value)
            first = false
        }
        sb.append("\"")
    }
}

private fun GlossaryTag.toHtmlTag(): String = when (this) {
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
    GlossaryTag.Unknown -> "span"
}

private fun GlossaryTag.isVoidElement(): Boolean = this == GlossaryTag.Image

/**
 * Escapes HTML special characters.
 */
private fun String.escapeHtml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/**
 * Converts camelCase to kebab-case for CSS property names.
 */
private fun String.toKebabCase(): String {
    val sb = StringBuilder()
    forEach { char ->
        if (char.isUpperCase()) {
            sb.append('-').append(char.lowercaseChar())
        } else {
            sb.append(char)
        }
    }
    return sb.toString()
}
