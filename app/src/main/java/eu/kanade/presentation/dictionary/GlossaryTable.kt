package eu.kanade.presentation.dictionary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import com.turtlekazu.furiganable.compose.m3.TextWithReading

@Composable
internal fun TableNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    onLinkClick: (String) -> Unit,
) {
    val model = remember(node) { buildTableModel(node) }
    if (model.rows.isEmpty()) return

    Column(
        modifier = Modifier
            .padding(start = bulletIndent(indentLevel), top = 4.dp, bottom = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        model.rows.forEachIndexed { index, row ->
            TableRowNode(row.segments, onLinkClick)
            if (index < model.rows.size - 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun TableRowNode(
    segments: List<TableSegment>,
    onLinkClick: (String) -> Unit,
) {
    if (segments.isEmpty()) return

    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
    ) {
        segments.forEachIndexed { index, seg ->
            val cellModifier = Modifier.weight(seg.colSpan.toFloat())
            if (seg.isPlaceholder) {
                // Occupy the correct width to keep columns aligned, but render nothing
                Spacer(modifier = cellModifier)
            } else {
                TableCellNode(
                    node = seg.cell!!,
                    onLinkClick = onLinkClick,
                    modifier = cellModifier,
                )
            }

            if (index < segments.size - 1) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                )
            }
        }
    }
}

@Composable
private fun TableCellNode(
    node: GlossaryNode.Element,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isHeader = node.tag == GlossaryTag.Th
    val textAlign = if (isHeader) TextAlign.Center else TextAlign.Start

    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        fontWeight = if (isHeader) FontWeight.Bold else null,
        textAlign = textAlign,
    )

    // Check for form indicator cells
    val formClass = node.attributes.dataAttributes["class"]
    val cellContent = collectText(node.children)

    Box(
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            formClass == "form-pri" && cellContent.isBlank() -> {
                // High priority form indicator
                Text(
                    text = "◉",
                    style = textStyle.copy(color = MaterialTheme.colorScheme.primary),
                )
            }
            formClass == "form-valid" && cellContent.isBlank() -> {
                // Valid form indicator
                Text(
                    text = "○",
                    style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            node.children.isNotEmpty() -> {
                val hasBlockContent = node.children.any { it.hasBlockContent() }
                if (!hasBlockContent) {
                    FlowRow {
                        node.children.forEach { child ->
                            InlineNode(child, onLinkClick, textStyle = textStyle)
                        }
                    }
                } else if (cellContent.isNotBlank()) {
                    TextWithReading(
                        formattedText = cellContent,
                        style = textStyle,
                        furiganaFontSize = textStyle.fontSize * 0.60f,
                    )
                } else {
                    Text(text = "", style = textStyle)
                }
            }
            cellContent.isNotBlank() -> {
                TextWithReading(
                    formattedText = cellContent,
                    style = textStyle,
                    furiganaFontSize = textStyle.fontSize * 0.60f,
                )
            }
            else -> {
                // Empty cell
                Text(text = "", style = textStyle)
            }
        }
    }
}

private fun buildTableModel(table: GlossaryNode.Element): TableModel {
    val trElements = table.children.asSequence()
        .filterIsInstance<GlossaryNode.Element>()
        .flatMap {
            when (it.tag) {
                GlossaryTag.Thead, GlossaryTag.Tbody, GlossaryTag.Tfoot -> it.children.asSequence()
                GlossaryTag.Tr -> sequenceOf(it)
                else -> emptySequence()
            }
        }
        .filterIsInstance<GlossaryNode.Element>()
        .filter { it.tag == GlossaryTag.Tr }
        .toList()

    // Parse cells and spans per row
    val baseRows: List<List<BaseCell>> = trElements.map { tr ->
        tr.children
            .filterIsInstance<GlossaryNode.Element>()
            .filter { it.tag == GlossaryTag.Td || it.tag == GlossaryTag.Th }
            .map { cellEl ->
                BaseCell(
                    node = cellEl,
                    isHeader = cellEl.tag == GlossaryTag.Th,
                    colSpan = readSpan(cellEl, "colSpan"),
                    rowSpan = readSpan(cellEl, "rowSpan"),
                )
            }
    }

    // Determine the table's column count (max colSpan sum over rows)
    val columnCount = baseRows.maxOfOrNull { row -> row.sumOf { it.colSpan.coerceAtLeast(1) } } ?: 0
    if (columnCount == 0) return TableModel(emptyList(), 0)

    // Track active row spans from previous rows per column (how many more rows they occupy)
    val activeRowSpan = IntArray(columnCount)

    val outRows = mutableListOf<TableRowModel>()
    baseRows.forEach { rowCells ->
        var currentCol = 0
        var cellIndexInRow = 0
        val segments = mutableListOf<TableSegment>()

        while (currentCol < columnCount) {
            // If this column is occupied by a cell from a previous row (rowSpan), add a placeholder segment
            if (activeRowSpan[currentCol] > 0) {
                var width = 0
                while (currentCol < columnCount && activeRowSpan[currentCol] > 0) {
                    width++
                    currentCol++
                }
                segments.add(
                    TableSegment(
                        cell = null,
                        colSpan = width,
                        isPlaceholder = true,
                    ),
                )
                continue
            }

            // Place the next cell in this row, if any
            val nextCell = rowCells.getOrNull(cellIndexInRow)
            if (nextCell != null) {
                cellIndexInRow++
                val spanWidth = nextCell.colSpan.coerceAtLeast(1)

                segments.add(
                    TableSegment(
                        cell = nextCell.node,
                        colSpan = spanWidth,
                        isPlaceholder = false,
                    ),
                )

                // Track remaining row spans (inclusive) in this column
                val totalRowSpan = nextCell.rowSpan.coerceAtLeast(1)
                if (totalRowSpan > 1) {
                    for (i in 0 until spanWidth) {
                        val col = currentCol + i
                        if (col in 0 until columnCount) {
                            // Keep the largest remaining span for safety
                            activeRowSpan[col] = maxOf(activeRowSpan[col], totalRowSpan)
                        }
                    }
                }

                currentCol += spanWidth
            } else {
                // No more cells in the source row: pad with placeholders to fill the grid
                segments.add(
                    TableSegment(
                        cell = null,
                        colSpan = columnCount - currentCol,
                        isPlaceholder = true,
                    ),
                )
                currentCol = columnCount
            }
        }

        // One Compose Row per table row
        outRows.add(TableRowModel(segments))

        // Decrement active row spans for the next row
        for (i in 0 until columnCount) {
            if (activeRowSpan[i] > 0) activeRowSpan[i] -= 1
        }
    }

    return TableModel(outRows, columnCount)
}


private data class BaseCell(
    val node: GlossaryNode.Element,
    val isHeader: Boolean,
    val colSpan: Int,
    val rowSpan: Int,
)

private data class TableSegment(
    val cell: GlossaryNode.Element?, // null for a placeholder segment
    val colSpan: Int,
    val isPlaceholder: Boolean,
)

private data class TableRowModel(
    val segments: List<TableSegment>,
)

private data class TableModel(
    val rows: List<TableRowModel>,
    val columnCount: Int,
)

private fun readSpan(element: GlossaryNode.Element, name: String): Int {
    val props = element.attributes.properties
    return props[name]?.toIntOrNull()
        ?: props[name.lowercase()]?.toIntOrNull()
        ?: 1
}
