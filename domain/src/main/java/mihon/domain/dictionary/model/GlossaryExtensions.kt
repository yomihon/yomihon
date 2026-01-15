/**
 * Extension functions for analyzing and extracting data from GlossaryNode trees.
 */
package mihon.domain.dictionary.model

/**
 * Extracts text forms from glossary entries for display or search purposes.
 */
fun List<GlossaryEntry>.extractForms(): List<String> {
    if (isEmpty()) return emptyList()
    return mapNotNull { entry ->
        when (entry) {
            is GlossaryEntry.TextDefinition -> entry.text.takeIf { it.isNotBlank() }
            is GlossaryEntry.StructuredContent -> entry.nodes.collectText().takeIf { it.isNotBlank() }
            is GlossaryEntry.Deinflection -> entry.baseForm.takeIf { it.isNotBlank() }
            else -> null
        }
    }.distinct()
}

/**
 * Extracts attribution text from glossary entries.
 * Attribution content is identified by data-content="attribution" in div elements.
 */
fun List<GlossaryEntry>.extractAttributionText(): String? {
    for (entry in this) {
        if (entry is GlossaryEntry.StructuredContent) {
            val text = entry.nodes.extractAttributionFromNodes()
            if (text != null) return text
        }
    }
    return null
}

private fun List<GlossaryNode>.extractAttributionFromNodes(): String? {
    for (node in this) {
        if (node is GlossaryNode.Element) {
            if (node.tag == GlossaryTag.Div &&
                node.attributes.dataAttributes["content"] == "attribution"
            ) {
                return node.children.collectText().takeIf { it.isNotBlank() }
            }
            val result = node.children.extractAttributionFromNodes()
            if (result != null) return result
        }
    }
    return null
}

/**
 * Collects all text content from a list of GlossaryNodes into a single string.
 * Normalizes whitespace and newlines.
 */
fun List<GlossaryNode>.collectText(): String {
    val builder = StringBuilder()
    forEach { node -> builder.appendNodeText(node) }
    return builder.toString().replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
}

private fun StringBuilder.appendNodeText(node: GlossaryNode) {
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

private fun StringBuilder.appendRubyInline(node: GlossaryNode.Element) {
    val baseNodes = node.children.filterNot { child ->
        child is GlossaryNode.Element && (child.tag == GlossaryTag.Rt || child.tag == GlossaryTag.Rp)
    }
    val readingNodes = node.children.filterIsInstance<GlossaryNode.Element>()
        .filter { it.tag == GlossaryTag.Rt }
        .flatMap { it.children }

    val baseText = baseNodes.collectText()
    val readingText = readingNodes.collectText()

    if (readingText.isNotBlank()) {
        append("[$baseText[$readingText]]")
    } else {
        append(baseText)
    }
}

private fun StringBuilder.appendLinkInline(node: GlossaryNode.Element) {
    val linkText = node.children.collectText().ifBlank { node.attributes.properties["href"] ?: "" }
    append(linkText)
    node.attributes.properties["href"]?.takeIf { it.isNotBlank() }?.let { href ->
        append(" (")
        append(href)
        append(')')
    }
}

fun GlossaryNode.hasBlockContent(): Boolean {
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

fun GlossaryNode.containsLink(): Boolean {
    return when (this) {
        is GlossaryNode.Text -> false
        is GlossaryNode.LineBreak -> false
        is GlossaryNode.Element -> tag == GlossaryTag.Link || children.any { it.containsLink() }
    }
}

fun List<GlossaryNode>.containsLink(): Boolean {
    return any { it.containsLink() }
}

fun GlossaryNode.hasFurigana(): Boolean = when (this) {
    is GlossaryNode.Text, is GlossaryNode.LineBreak -> false
    is GlossaryNode.Element -> tag == GlossaryTag.Ruby || children.any { it.hasFurigana() }
}
