package eu.kanade.presentation.dictionary.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.turtlekazu.furiganable.compose.m3.TextWithReading
import mihon.domain.dictionary.css.BoxStyle
import mihon.domain.dictionary.css.ParsedCss
import mihon.domain.dictionary.css.getCssStyles
import mihon.domain.dictionary.css.parseBoxStyle
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryImageAttributes
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import mihon.domain.dictionary.model.collectText
import mihon.domain.dictionary.model.containsLink
import mihon.domain.dictionary.model.extractForms
import mihon.domain.dictionary.model.hasBlockContent
import mihon.domain.dictionary.model.hasFurigana
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Composable
fun GlossarySection(
    entries: List<GlossaryEntry>,
    isFormsEntry: Boolean,
    modifier: Modifier = Modifier,
    parsedCss: ParsedCss = ParsedCss.EMPTY,
    onLinkClick: (String) -> Unit,
) {
    if (entries.isEmpty()) return

    if (isFormsEntry) {
        val forms = remember(entries) { entries.extractForms() }
        if (forms.isNotEmpty()) {
            FormsRow(forms = forms, modifier = modifier)
            return
        }
    }

    Column(modifier = modifier) {
        entries.forEach { entry ->
            GlossaryEntryItem(entry = entry, parsedCss = parsedCss, onLinkClick = onLinkClick)
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
    parsedCss: ParsedCss = ParsedCss.EMPTY,
    onLinkClick: (String) -> Unit,
) {
    when (entry) {
        is GlossaryEntry.TextDefinition -> DefinitionRow(text = entry.text, indentLevel = indentLevel)
        is GlossaryEntry.StructuredContent -> StructuredDefinition(entry.nodes, indentLevel, parsedCss, onLinkClick)
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
    parsedCss: ParsedCss,
    onLinkClick: (String) -> Unit,
) {
    if (nodes.isEmpty()) return
    val containsLink = nodes.containsLink()
    if (!nodes.any { it.hasBlockContent() }) {
        if (containsLink) {
            InlineDefinitionRow(nodes, indentLevel, onLinkClick)
        } else {
            DefinitionRow(text = nodes.collectText(), indentLevel = indentLevel)
        }
        return
    }
    Column(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
    ) {
        nodes.forEach { node -> StructuredNode(node, indentLevel, parsedCss, onLinkClick) }
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
private fun StructuredNode(
    node: GlossaryNode,
    indentLevel: Int,
    parsedCss: ParsedCss,
    onLinkClick: (String) -> Unit,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    when (node) {
        is GlossaryNode.Text -> Text(
            text = node.text,
            style = textStyle,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        is GlossaryNode.LineBreak -> Spacer(modifier = Modifier.height(4.dp))
        is GlossaryNode.Element -> StructuredElement(node, indentLevel, parsedCss, onLinkClick, textStyle)
    }
}

@Composable
private fun StructuredElement(
    node: GlossaryNode.Element,
    indentLevel: Int,
    parsedCss: ParsedCss,
    onLinkClick: (String) -> Unit,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    when (node.tag) {
        GlossaryTag.UnorderedList -> StructuredList(
            node.children,
            indentLevel,
            ListType.Unordered,
            parsedCss,
            onLinkClick,
            textStyle,
        )
        GlossaryTag.OrderedList -> StructuredList(
            node.children,
            indentLevel,
            ListType.Ordered,
            parsedCss,
            onLinkClick,
            textStyle,
        )
        GlossaryTag.ListItem -> StructuredListItem(
            node,
            indentLevel,
            0,
            ListType.Unordered,
            parsedCss,
            onLinkClick,
            textStyle,
        )
        GlossaryTag.Ruby -> RubyNode(node, textStyle = textStyle)
        GlossaryTag.Link -> LinkNode(node, onLinkClick, textStyle = textStyle)
        GlossaryTag.Image -> Unit // Ignore images
        GlossaryTag.Details -> DetailsNode(node, indentLevel, parsedCss, onLinkClick, textStyle)
        GlossaryTag.Summary -> SummaryNode(node, indentLevel, parsedCss, textStyle)
        GlossaryTag.Table -> TableNode(node, indentLevel, parsedCss, onLinkClick, textStyle)
        GlossaryTag.Div -> DivNode(node, indentLevel, parsedCss, onLinkClick, textStyle)
        GlossaryTag.Span -> SpanNode(node, parsedCss, onLinkClick, textStyle)
        GlossaryTag.Thead, GlossaryTag.Tbody, GlossaryTag.Tfoot, GlossaryTag.Tr,
        GlossaryTag.Td, GlossaryTag.Th, GlossaryTag.Unknown, GlossaryTag.Rt, GlossaryTag.Rp,
        -> {
            Column {
                node.children.forEach { child -> StructuredNode(child, indentLevel, parsedCss, onLinkClick, textStyle) }
            }
        }
    }
}

@Composable
private fun StructuredList(
    children: List<GlossaryNode>,
    indentLevel: Int,
    type: ListType,
    parsedCss: ParsedCss,
    onLinkClick: (String) -> Unit,
    textStyle: TextStyle,
) {
    if (children.isEmpty()) return
    var itemIndex = 0
    Column(modifier = Modifier.padding(start = bulletIndent(1))) {
        children.forEach { child ->
            if (child is GlossaryNode.Element && child.tag == GlossaryTag.ListItem) {
                StructuredListItem(
                    child,
                    indentLevel + 1,
                    itemIndex,
                    type,
                    parsedCss,
                    onLinkClick,
                    baseTextStyle = textStyle,
                )
                itemIndex += 1
            } else {
                StructuredNode(child, indentLevel, parsedCss, onLinkClick, textStyle)
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
    parsedCss: ParsedCss,
    onLinkClick: (String) -> Unit,
    baseTextStyle: TextStyle,
) {
    val cssStyleMap = getCssStyles(node.attributes.dataAttributes, parsedCss)
    val combinedStyleMap = cssStyleMap + node.attributes.style
    val textStyle = applyTypography(baseTextStyle, combinedStyleMap)

    val inlineText = if (node.children.any { child -> child.hasBlockContent() }) {
        null
    } else {
        node.children.collectText()
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
            style = textStyle,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )

        if (!inlineText.isNullOrBlank() && !containsLink) {
            TextWithReading(
                formattedText = inlineText,
                style = textStyle,
                furiganaFontSize = textStyle.fontSize * 0.60f,
            )
        } else {
            Column {
                val children = node.children
                val firstListIndex = children.indexOfFirst {
                    it is GlossaryNode.Element &&
                        (it.tag == GlossaryTag.OrderedList || it.tag == GlossaryTag.UnorderedList)
                }.let { if (it == -1) children.size else it }

                val inlineChildren = children.subList(0, firstListIndex)
                val blockChildren = children.subList(firstListIndex, children.size)

                if (inlineChildren.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        inlineChildren.forEach { child ->
                            StructuredNode(child, indentLevel, parsedCss, onLinkClick, textStyle)
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }

                if (blockChildren.isNotEmpty()) {
                    blockChildren.forEach { child ->
                        StructuredNode(child, indentLevel, parsedCss, onLinkClick, textStyle)
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

    val baseText = baseNodes.collectText()
    val readingText = readingNodes.collectText()

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

@Suppress("AssignedValueIsNeverRead", "VariableNeverRead")
@Composable
private fun LinkNode(
    node: GlossaryNode.Element,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val href = node.attributes.properties["href"]
    val linkText = node.children.collectText().ifBlank { href ?: "" }

    var queryParam: String? = null
    // Other link parameters, but they're currently unused
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
            },
    )
}

@Composable
private fun DetailsNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    parsedCss: ParsedCss,
    onLinkClick: (String) -> Unit,
    textStyle: TextStyle,
) {
    Column {
        node.children.forEach { child -> StructuredNode(child, indentLevel + 1, parsedCss, onLinkClick, textStyle) }
    }
}

@Composable
private fun SummaryNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    parsedCss: ParsedCss,
    baseTextStyle: TextStyle,
) {
    val cssStyleMap = getCssStyles(node.attributes.dataAttributes, parsedCss)
    val combinedStyleMap = cssStyleMap + node.attributes.style
    val textStyle = applyTypography(
        baseTextStyle.copy(fontWeight = FontWeight.SemiBold),
        combinedStyleMap,
    )

    val summary = node.children.collectText()
    TextWithReading(
        formattedText = summary,
        style = textStyle,
        furiganaFontSize = textStyle.fontSize * 0.60f,
        modifier = Modifier.padding(start = bulletIndent(indentLevel), bottom = 2.dp),
    )
}

@Composable
private fun DivNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    parsedCss: ParsedCss,
    onLinkClick: (String) -> Unit,
    baseTextStyle: TextStyle,
) {
    // Skip attribution - shown at card bottom via collapsible section
    if (node.attributes.dataAttributes["content"] == "attribution") return

    val cssStyleMap = getCssStyles(node.attributes.dataAttributes, parsedCss)
    val combinedStyleMap = cssStyleMap + node.attributes.style
    val textStyle = applyTypography(baseTextStyle, combinedStyleMap)

    val baseFontSizeSp = baseTextStyle.fontSize.let { if (it.isSp) it.value else 14f }
    val boxStyle = parseBoxStyle(combinedStyleMap, baseFontSizeSp)

    // If needed, apply extra top padding so furigana doesn't get cut off
    val defaultPadding = if ((boxStyle.hasBackground || boxStyle.hasBorder) && node.children.any { it.hasFurigana() }) {
        PaddingValues(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 2.dp)
    } else {
        PaddingValues(horizontal = 6.dp, vertical = 2.dp)
    }

    val boxModifier = Modifier.applyBoxStyle(
        boxStyle,
        defaultCornerRadius = 8.dp,
        defaultPadding = defaultPadding,
        defaultMargin = PaddingValues(vertical = 2.dp),
    )

    Column(modifier = boxModifier) {
        node.children.forEach { child -> StructuredNode(child, indentLevel, parsedCss, onLinkClick, textStyle) }
    }
}

@Composable
private fun SpanNode(
    node: GlossaryNode.Element,
    parsedCss: ParsedCss,
    onLinkClick: (String) -> Unit,
    baseTextStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val cssStyleMap = getCssStyles(node.attributes.dataAttributes, parsedCss)
    val combinedStyleMap = cssStyleMap + node.attributes.style
    val textStyle = applyTypography(
        baseTextStyle,
        combinedStyleMap,
    )

    val baseFontSizeSp = baseTextStyle.fontSize.let { if (it.isSp) it.value else 14f }
    val boxStyle = parseBoxStyle(combinedStyleMap, baseFontSizeSp)
    val boxModifier = Modifier.applyBoxStyle(
        boxStyle,
        defaultCornerRadius = 4.dp,
        defaultPadding = PaddingValues(horizontal = 4.dp),
    )

    // Build annotated text for proper character-level wrapping
    val annotatedResult = remember(node.children) { buildAnnotatedText(node.children) }

    if (annotatedResult.linkRanges.isEmpty()) {
        // No links - simple text rendering
        TextWithReading(
            formattedText = annotatedResult.text,
            style = textStyle,
            furiganaFontSize = textStyle.fontSize * 0.60f,
            modifier = boxModifier,
        )
    } else {
        val linkColor = MaterialTheme.colorScheme.primary
        val annotatedString = remember(annotatedResult, textStyle, linkColor) {
            buildAnnotatedString {
                append(annotatedResult.text)
                annotatedResult.linkRanges.forEach { link ->
                    addStyle(
                        SpanStyle(color = linkColor, fontWeight = FontWeight.Medium),
                        start = link.start,
                        end = link.end,
                    )
                    addLink(
                        LinkAnnotation.Clickable(link.target) { onLinkClick(link.target) },
                        start = link.start,
                        end = link.end,
                    )
                }
            }
        }
        Text(
            text = annotatedString,
            style = textStyle,
            modifier = boxModifier,
        )
    }
}

@Composable
internal fun InlineNode(
    node: GlossaryNode,
    onLinkClick: (String) -> Unit,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    when (node) {
        is GlossaryNode.Text -> Text(
            text = node.text,
            style = textStyle,
        )
        is GlossaryNode.LineBreak -> { /* Ignore in inline context */ }
        is GlossaryNode.Element -> {
            val styledTextStyle = applyTypography(textStyle, node.attributes.style)

            when (node.tag) {
                GlossaryTag.Ruby -> RubyNode(node, modifier = Modifier, textStyle = styledTextStyle)
                GlossaryTag.Span -> {
                    node.children.forEach { InlineNode(it, onLinkClick, styledTextStyle) }
                }
                GlossaryTag.Link -> LinkNode(node, onLinkClick, modifier = Modifier, textStyle = styledTextStyle)
                else -> {
                    val text = listOf(node).collectText()
                    if (text.isNotBlank()) {
                        TextWithReading(
                            formattedText = text,
                            style = styledTextStyle,
                            furiganaFontSize = textStyle.fontSize * 0.60f,
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

/**
 * Applies box styling using the user theme for color compatibility.
 */
@Composable
private fun Modifier.applyBoxStyle(
    boxStyle: BoxStyle,
    defaultCornerRadius: Dp = 0.dp,
    defaultPadding: PaddingValues = PaddingValues(),
    defaultMargin: PaddingValues = PaddingValues(),
): Modifier {
    var modifier: Modifier = this

    if (!boxStyle.hasAnyStyle) return modifier

    val backgroundColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val cornerRadius = boxStyle.borderRadius?.dp ?: defaultCornerRadius
    val shape = RoundedCornerShape(cornerRadius)

    // Apply margin (as outer padding)
    if (boxStyle.hasMargin) {
        modifier = modifier.padding(
            start = boxStyle.marginStart?.dp ?: 0.dp,
            end = boxStyle.marginEnd?.dp ?: 0.dp,
            top = boxStyle.marginTop?.dp ?: 0.dp,
            bottom = boxStyle.marginBottom?.dp ?: 0.dp,
        )
    } else if (boxStyle.hasBackground || boxStyle.hasBorder) {
        modifier = modifier.padding(defaultMargin)
    }

    if (boxStyle.hasBorder) {
        modifier = modifier.border(1.dp, borderColor, shape)
    }

    if (boxStyle.hasBackground) {
        modifier = modifier.background(backgroundColor, shape)
    }

    if (boxStyle.hasPadding) {
        modifier = modifier.padding(
            start = boxStyle.paddingStart?.dp ?: 0.dp,
            end = boxStyle.paddingEnd?.dp ?: 0.dp,
            top = boxStyle.paddingTop?.dp ?: 0.dp,
            bottom = boxStyle.paddingBottom?.dp ?: 0.dp,
        )
    } else if (boxStyle.hasBackground || boxStyle.hasBorder) {
        modifier = modifier.padding(defaultPadding)
    }

    return modifier
}
