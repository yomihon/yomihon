package eu.kanade.presentation.dictionary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryImageAttributes
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import com.turtlekazu.furiganable.compose.m3.TextWithReading
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Composable
fun GlossarySection(
    entries: List<GlossaryEntry>,
    isFormsEntry: Boolean,
    modifier: Modifier = Modifier,
    cssBoxSelectors: Set<String> = emptySet(),
    onLinkClick: (String) -> Unit,
) {
    if (entries.isEmpty()) return

    if (isFormsEntry) {
        val forms = remember(entries) { extractForms(entries) }
        if (forms.isNotEmpty()) {
            FormsRow(forms = forms, modifier = modifier)
            return
        }
    }

    Column(modifier = modifier) {
        entries.forEach { entry ->
            GlossaryEntryItem(entry = entry, cssBoxSelectors = cssBoxSelectors, onLinkClick = onLinkClick)
        }
    }
}

@Composable
private fun FormsRow(forms: List<String>, modifier: Modifier = Modifier) {
    if (forms.isEmpty()) return

    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Forms: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        TextWithReading(
            formattedText = forms.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
            furiganaFontSize = MaterialTheme.typography.bodySmall.fontSize * 0.60f,
        )
    }
}

@Composable
private fun GlossaryEntryItem(
    entry: GlossaryEntry,
    indentLevel: Int = 0,
    cssBoxSelectors: Set<String> = emptySet(),
    onLinkClick: (String) -> Unit,
) {
    when (entry) {
        is GlossaryEntry.TextDefinition -> DefinitionRow(text = entry.text, indentLevel = indentLevel)
        is GlossaryEntry.StructuredContent -> StructuredDefinition(entry.nodes, indentLevel, cssBoxSelectors, onLinkClick)
        is GlossaryEntry.ImageDefinition -> ImageEntryRow(entry.image, indentLevel)
        is GlossaryEntry.Deinflection -> DeinflectionRow(entry, indentLevel)
        is GlossaryEntry.Unknown -> Unit
    }
}

@Composable
private fun DefinitionRow(text: String, indentLevel: Int) {
    if (text.isBlank()) return
    Row(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        TextWithReading(
            formattedText = text,
            style = MaterialTheme.typography.bodyMedium,
            furiganaFontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.60f,
        )
    }
}

@Composable
private fun StructuredDefinition(
    nodes: List<GlossaryNode>,
    indentLevel: Int,
    cssBoxSelectors: Set<String>,
    onLinkClick: (String) -> Unit,
) {
    if (nodes.isEmpty()) return
    val containsLink = nodes.containsLink()
    if (!nodes.any { it.hasBlockContent() }) {
        if (containsLink) {
            InlineDefinitionRow(nodes, indentLevel, onLinkClick)
        } else {
            DefinitionRow(text = collectText(nodes), indentLevel = indentLevel)
        }
        return
    }
    Column(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
    ) {
        nodes.forEach { node -> StructuredNode(node, indentLevel, cssBoxSelectors, onLinkClick) }
    }
}

@Composable
private fun ImageEntryRow(image: GlossaryImageAttributes, indentLevel: Int) {
    Row(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "◦ ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val description = buildString {
            append("Image: ")
            append(image.path)
            image.title?.takeIf { it.isNotBlank() }?.let {
                append(" (")
                append(it)
                append(")")
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeinflectionRow(entry: GlossaryEntry.Deinflection, indentLevel: Int) {
    val ruleChain = entry.rules.joinToString(" → ")
    val content = if (ruleChain.isBlank()) {
        entry.baseForm
    } else {
        "${entry.baseForm} ← $ruleChain"
    }
    DefinitionRow(text = content, indentLevel = indentLevel)
}

@Composable
private fun StructuredNode(node: GlossaryNode, indentLevel: Int, cssBoxSelectors: Set<String>, onLinkClick: (String) -> Unit) {
    when (node) {
        is GlossaryNode.Text -> Text(
            text = node.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        is GlossaryNode.LineBreak -> Spacer(modifier = Modifier.height(4.dp))
        is GlossaryNode.Element -> StructuredElement(node, indentLevel, cssBoxSelectors, onLinkClick)
    }
}

@Composable
private fun StructuredElement(
    node: GlossaryNode.Element,
    indentLevel: Int,
    cssBoxSelectors: Set<String>,
    onLinkClick: (String) -> Unit,
) {
    when (node.tag) {
        GlossaryTag.UnorderedList -> StructuredList(node.children, indentLevel, ListType.Unordered, cssBoxSelectors, onLinkClick)
        GlossaryTag.OrderedList -> StructuredList(node.children, indentLevel, ListType.Ordered, cssBoxSelectors, onLinkClick)
        GlossaryTag.ListItem -> StructuredListItem(node, indentLevel, 0, ListType.Unordered, cssBoxSelectors, onLinkClick)
        GlossaryTag.Ruby -> RubyNode(node)
        GlossaryTag.Link -> LinkNode(node, onLinkClick)
        GlossaryTag.Image -> Unit // Ignore images
        GlossaryTag.Details -> DetailsNode(node, indentLevel, cssBoxSelectors, onLinkClick)
        GlossaryTag.Summary -> SummaryNode(node, indentLevel)
        GlossaryTag.Table -> TableNode(node, indentLevel, cssBoxSelectors, onLinkClick)
        GlossaryTag.Div -> DivNode(node, indentLevel, cssBoxSelectors, onLinkClick)
        GlossaryTag.Span -> SpanNode(node, cssBoxSelectors, onLinkClick)
        GlossaryTag.Thead, GlossaryTag.Tbody, GlossaryTag.Tfoot, GlossaryTag.Tr,
        GlossaryTag.Td, GlossaryTag.Th, GlossaryTag.Unknown, GlossaryTag.Rt, GlossaryTag.Rp -> {
            Column {
                node.children.forEach { child -> StructuredNode(child, indentLevel, cssBoxSelectors, onLinkClick) }
            }
        }
    }
}

@Composable
private fun StructuredList(
    children: List<GlossaryNode>,
    indentLevel: Int,
    type: ListType,
    cssBoxSelectors: Set<String>,
    onLinkClick: (String) -> Unit,
) {
    if (children.isEmpty()) return
    var itemIndex = 0
    Column(modifier = Modifier.padding(start = bulletIndent(1))) {
        children.forEach { child ->
            if (child is GlossaryNode.Element && child.tag == GlossaryTag.ListItem) {
                StructuredListItem(child, indentLevel + 1, itemIndex, type, cssBoxSelectors, onLinkClick)
                itemIndex += 1
            } else {
                StructuredNode(child, indentLevel, cssBoxSelectors, onLinkClick)
            }
        }
    }
}

@Composable
private fun StructuredListItem(
    node: GlossaryNode.Element,
    indentLevel: Int,
    index: Int,
    type: ListType,
    cssBoxSelectors: Set<String>,
    onLinkClick: (String) -> Unit,
) {
    val inlineText = if (node.children.any { child -> child.hasBlockContent() }) {
        null
    } else {
        collectText(node.children)
    }
    val containsLink = node.children.containsLink()

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val marker = when (type) {
            ListType.Unordered -> "•"
            ListType.Ordered -> "${index + 1}."
        }

        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )

        if (!inlineText.isNullOrBlank() && !containsLink) {
            TextWithReading(
                formattedText = inlineText,
                style = MaterialTheme.typography.bodyMedium,
                furiganaFontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.60f,
            )
        } else {
            Column {
                val children = node.children
                val firstListIndex = children.indexOfFirst {
                    it is GlossaryNode.Element && (it.tag == GlossaryTag.OrderedList || it.tag == GlossaryTag.UnorderedList)
                }.let { if (it == -1) children.size else it }

                val inlineChildren = children.subList(0, firstListIndex)
                val blockChildren = children.subList(firstListIndex, children.size)

                if (inlineChildren.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        inlineChildren.forEach { child ->
                            StructuredNode(child, indentLevel, cssBoxSelectors, onLinkClick)
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }

                if (blockChildren.isNotEmpty()) {
                    blockChildren.forEach { child ->
                        StructuredNode(child, indentLevel, cssBoxSelectors, onLinkClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun RubyNode(
    node: GlossaryNode.Element,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val baseNodes = node.children.filterNot { child ->
        child is GlossaryNode.Element && (child.tag == GlossaryTag.Rt || child.tag == GlossaryTag.Rp)
    }
    val readingNodes = node.children.filterIsInstance<GlossaryNode.Element>()
        .filter { it.tag == GlossaryTag.Rt }
        .flatMap { it.children }

    val baseText = collectText(baseNodes)
    val readingText = collectText(readingNodes)

    val furiganaText = if (readingText.isNotBlank()) {
        "[$baseText[$readingText]]"
    } else {
        baseText
    }

    TextWithReading(
        formattedText = furiganaText,
        style = textStyle,
        furiganaFontSize = textStyle.fontSize * 0.60f,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun LinkNode(
    node: GlossaryNode.Element,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val href = node.attributes.properties["href"]
    val linkText = collectText(node.children).ifBlank { href ?: "" }

    var queryParam: String? = null
    var typeParam: String? = null
    var primaryReadingParam: String? = null

    // Extract search parameters if href starts with '?'
    if (href?.startsWith("?") == true) {
        val params = href.drop(1).split("&")

        params.forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0]
                val rawValue = parts[1]

                val decodedValue = try {
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8.toString())
                } catch (e: Exception) {
                    rawValue
                }

                when (key) {
                    "query" -> queryParam = decodedValue
                    "type" -> typeParam = decodedValue
                    "primaryReading" -> primaryReadingParam = decodedValue
                    else -> {}
                }
            }
        }
    }

    // Logic to determine display text and click target
    val isDictionaryLink = queryParam != null

    val displayText = if (isDictionaryLink) {
        linkText
    } else if (!href.isNullOrBlank()) {
        "$linkText ($href)"
    } else {
        linkText
    }

    val clickTarget = queryParam ?: href ?: linkText

    val linkColor = MaterialTheme.colorScheme.primary
    TextWithReading(
        formattedText = displayText,
        style = textStyle,
        color = linkColor,
        fontWeight = FontWeight.Medium,
        furiganaFontSize = textStyle.fontSize * 0.60f,
        modifier = modifier
            .padding(bottom = 2.dp)
            .clickable(enabled = clickTarget.isNotEmpty()) {
                if (clickTarget.isNotEmpty()) {
                    onLinkClick(clickTarget)
                }
            }
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                val verticalOffset = (-1).dp.toPx()
                val y = size.height + verticalOffset - (strokeWidth / 2)
                drawLine(
                    color = linkColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = strokeWidth,
                )
            }
    )
}

@Composable
private fun DetailsNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    cssBoxSelectors: Set<String>,
    onLinkClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = bulletIndent(indentLevel)),
    ) {
        node.children.forEach { child -> StructuredNode(child, indentLevel + 1, cssBoxSelectors, onLinkClick) }
    }
}

@Composable
private fun SummaryNode(node: GlossaryNode.Element, indentLevel: Int) {
    val summary = collectText(node.children)
    TextWithReading(
        formattedText = summary,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        furiganaFontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.65f,
        modifier = Modifier.padding(start = bulletIndent(indentLevel), bottom = 2.dp),
    )
}

@Composable
private fun DivNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    cssBoxSelectors: Set<String>,
    onLinkClick: (String) -> Unit,
) {
    val hasBackground = hasBoxStyle(node.attributes.style, node.attributes.dataAttributes, cssBoxSelectors)
    val backgroundModifier = getBgModifier(
        hasBackground = hasBackground,
        cornerRadius = 8.dp,
        horizontalPadding = 6.dp,
        verticalPadding = 2.dp,
    )

    when (node.attributes.dataAttributes["content"]) {
        "example-sentence" -> ExampleSentenceNode(node, indentLevel, cssBoxSelectors, onLinkClick)
        "attribution" -> Unit // Hidden - shown at card bottom via collapsable section
        else -> {
            Column(modifier = backgroundModifier) {
                node.children.forEach { child -> StructuredNode(child, indentLevel, cssBoxSelectors, onLinkClick) }
            }
        }
    }
}

@Composable
private fun SpanNode(
    node: GlossaryNode.Element,
    cssBoxSelectors: Set<String>,
    onLinkClick: (String) -> Unit,
) {
    // Span applies schema styles to inline content
    val textStyle = applyTypography(
        MaterialTheme.typography.bodyMedium,
        node.attributes.style
    )
    val hasBackground = hasBoxStyle(node.attributes.style, node.attributes.dataAttributes, cssBoxSelectors)
    val backgroundModifier = getBgModifier(
        hasBackground = hasBackground,
        cornerRadius = 4.dp,
        horizontalPadding = 4.dp,
        verticalPadding = 0.dp,
    )

    FlowRow(modifier = backgroundModifier) {
        node.children.forEach { child ->
            InlineNode(child, onLinkClick, textStyle = textStyle)
        }
    }
}

@Composable
private fun ExampleSentenceNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    cssBoxSelectors: Set<String>,
    onLinkClick: (String) -> Unit,
) {
    val sentenceANode = node.children.filterIsInstance<GlossaryNode.Element>().find {
        it.attributes.dataAttributes["content"] == "example-sentence-a"
    }
    val sentenceBNode = node.children.filterIsInstance<GlossaryNode.Element>().find {
        it.attributes.dataAttributes["content"] == "example-sentence-b"
    }

    if (sentenceANode == null) return

    Column(
        modifier = Modifier
            .padding(start = bulletIndent(indentLevel), top = 4.dp, bottom = 4.dp)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        val jpSpan = sentenceANode.children.filterIsInstance<GlossaryNode.Element>().firstOrNull()
        if (jpSpan != null) {
            FlowRow {
                jpSpan.children.forEach { node ->
                    InlineNode(node, onLinkClick)
                }
            }
        }

        val engSpan = sentenceBNode?.children?.filterIsInstance<GlossaryNode.Element>()?.firstOrNull()
        if (engSpan != null) {
            Spacer(modifier = Modifier.height(4.dp))
            TextWithReading(
                formattedText = collectText(engSpan.children),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                furiganaFontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.60f,
            )
        }
    }
}

@Composable
internal fun InlineNode(
    node: GlossaryNode,
    onLinkClick: (String) -> Unit,
    isKeyword: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    when (node) {
        is GlossaryNode.Text -> Text(
            text = node.text,
            style = textStyle,
            fontWeight = if (isKeyword) FontWeight.SemiBold else null,
        )
        is GlossaryNode.LineBreak -> { /* Ignore in inline context */ }
        is GlossaryNode.Element -> {
            val isChildKeyword = isKeyword || node.attributes.dataAttributes["content"] == "example-keyword"

            val styledTextStyle = applyTypography(textStyle, node.attributes.style)

            val effectiveTextStyle = if (isChildKeyword) {
                styledTextStyle.copy(fontWeight = FontWeight.SemiBold)
            } else {
                styledTextStyle
            }
            when (node.tag) {
                GlossaryTag.Ruby -> RubyNode(node, modifier = Modifier, textStyle = effectiveTextStyle)
                GlossaryTag.Span -> {
                    node.children.forEach { InlineNode(it, onLinkClick, isChildKeyword, effectiveTextStyle) }
                }
                GlossaryTag.Link -> LinkNode(node, onLinkClick, modifier = Modifier, textStyle = effectiveTextStyle)
                else -> {
                    val text = collectText(listOf(node))
                    if (text.isNotBlank()) {
                        TextWithReading(
                            formattedText = text,
                            style = effectiveTextStyle,
                            furiganaFontSize = textStyle.fontSize * 0.65f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineDefinitionRow(
    nodes: List<GlossaryNode>,
    indentLevel: Int,
    onLinkClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        FlowRow {
            nodes.forEach { node ->
                InlineNode(node, onLinkClick)
            }
        }
    }
}

internal fun bulletIndent(indentLevel: Int): Dp {
    val level = indentLevel.coerceAtLeast(0)
    return 12.dp * level.toFloat()
}

private enum class ListType {
    Unordered,
    Ordered,
}

@Composable
private fun getBgModifier(
    hasBackground: Boolean,
    cornerRadius: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
): Modifier {
    return if (hasBackground) {
        Modifier
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                shape = RoundedCornerShape(cornerRadius)
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    } else {
        Modifier
    }
}
