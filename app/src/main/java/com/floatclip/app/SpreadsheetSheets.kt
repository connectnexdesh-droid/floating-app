package com.floatclip.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floatclip.app.data.ClipboardSheetEntity
import com.floatclip.app.data.FloatClipDao
import com.floatclip.app.data.QuickTextEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.floor

private val GridLine = Color(0xFFB8C2C7)
private val GridText = Color(0xFF202124)

private data class SheetPalette(
    val rowHeader: Color,
    val columns: List<Color>
)

private val sheetPalettes = listOf(
    SheetPalette(
        rowHeader = Color(0xFFE91E63),
        columns = listOf(Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00897B))
    ),
    SheetPalette(
        rowHeader = Color(0xFF0F766E),
        columns = listOf(Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFEA580C), Color(0xFFDB2777), Color(0xFF0891B2))
    ),
    SheetPalette(
        rowHeader = Color(0xFF475569),
        columns = listOf(Color(0xFF0891B2), Color(0xFF65A30D), Color(0xFFD97706), Color(0xFF9333EA), Color(0xFF0F766E))
    )
)

private val columnNames = listOf("COL A", "COL B", "COL C", "COL D", "COL E")

private data class HistoryAction(val key: String, val label: String, val undo: suspend () -> Unit, val redo: suspend () -> Unit)
class FloatClipHistory {
    private val undoStack = mutableStateListOf<HistoryAction>()
    private val redoStack = mutableStateListOf<HistoryAction>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    fun record(key: String, label: String, undo: suspend () -> Unit, redo: suspend () -> Unit) {
        val previous = undoStack.lastOrNull()
        if (previous?.key == key) undoStack[undoStack.lastIndex] = HistoryAction(key, label, previous.undo, redo)
        else { undoStack.add(HistoryAction(key, label, undo, redo)); if (undoStack.size > 100) undoStack.removeAt(0) }
        redoStack.clear()
    }
    suspend fun undo(): String? { if (undoStack.isEmpty()) return null; val action = undoStack.removeAt(undoStack.lastIndex); action.undo(); redoStack.add(action); return action.label }
    suspend fun redo(): String? { if (redoStack.isEmpty()) return null; val action = redoStack.removeAt(redoStack.lastIndex); action.redo(); undoStack.add(action); return action.label }
}

private data class CellPosition(val row: Int, val column: Int)

private data class SheetSelection(
    val startRow: Int = 0,
    val startColumn: Int = 0,
    val endRow: Int = startRow,
    val endColumn: Int = startColumn,
    val wholeRow: Int? = null,
    val wholeColumn: Int? = null
) {
    fun contains(row: Int, column: Int): Boolean {
        if (wholeRow != null) return row == wholeRow
        if (wholeColumn != null) return column == wholeColumn
        return row in minOf(startRow, endRow)..maxOf(startRow, endRow) &&
            column in minOf(startColumn, endColumn)..maxOf(startColumn, endColumn)
    }

    fun topRow(): Int = wholeRow ?: if (wholeColumn != null) 0 else minOf(startRow, endRow)
    fun bottomRow(lastExistingRow: Int): Int =
        wholeRow ?: if (wholeColumn != null) lastExistingRow else maxOf(startRow, endRow)
    fun leftColumn(): Int = wholeColumn ?: if (wholeRow != null) 0 else minOf(startColumn, endColumn)
    fun rightColumn(columnCount: Int): Int =
        wholeRow?.let { columnCount - 1 } ?: wholeColumn ?: maxOf(startColumn, endColumn)

    fun rowSpan(): Int = if (wholeRow != null || wholeColumn != null) 1 else maxOf(startRow, endRow) - minOf(startRow, endRow) + 1
    fun columnSpan(columnCount: Int): Int =
        if (wholeRow != null) columnCount else if (wholeColumn != null) 1 else maxOf(startColumn, endColumn) - minOf(startColumn, endColumn) + 1

    fun label(columnCount: Int): String {
        if (wholeColumn != null) return "${columnLetter(wholeColumn)}:${columnLetter(wholeColumn)}"
        if (wholeRow != null) return "A${wholeRow + 1}:${columnLetter(columnCount - 1)}${wholeRow + 1}"
        val top = minOf(startRow, endRow)
        val bottom = maxOf(startRow, endRow)
        val left = minOf(startColumn, endColumn)
        val right = maxOf(startColumn, endColumn)
        val first = "${columnLetter(left)}${top + 1}"
        val last = "${columnLetter(right)}${bottom + 1}"
        return if (first == last) first else "${first}:${last}"
    }
}

private fun columnLetter(index: Int): String =
    listOf("A", "B", "C", "D", "E").getOrElse(index) { "?" }


@Composable
fun QuickTextSpreadsheet(items: List<QuickTextEntity>, dao: FloatClipDao, queue: Int, history: FloatClipHistory) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val horizontalState = rememberScrollState()
    val editMutex = remember { Mutex() }
    var paletteIndex by remember { mutableStateOf(0) }
    var selection by remember(queue) { mutableStateOf(SheetSelection()) }
    var rangeSelectMode by remember(queue) { mutableStateOf(false) }
    var rangeAnchor by remember(queue) { mutableStateOf<CellPosition?>(null) }
    var showPurgeDialog by remember(queue) { mutableStateOf(false) }
    var hasActiveCellSelection by remember(queue) { mutableStateOf(false) }
    val selectedRowIds = remember(queue) { mutableStateListOf<Long>() }
    val listState = rememberLazyListState()
    val palette = sheetPalettes[paletteIndex]
    val rowCount = maxOf(16, items.size + 1)
    val rows = (0 until rowCount).map { index -> items.getOrNull(index) ?: QuickTextEntity(id = -(index + 1L), title = "", content = "", position = index, queue = queue) }
    val toggleSelectedRow: (Long) -> Unit = { id -> if (id > 0L) { if (selectedRowIds.contains(id)) selectedRowIds.remove(id) else selectedRowIds.add(id) } }

    val selectCell: (Int, Int) -> Unit = { row, column ->
        selectedRowIds.clear()
        hasActiveCellSelection = true
        if (rangeSelectMode) {
            val anchor = rangeAnchor
            if (anchor == null) {
                rangeAnchor = CellPosition(row, column)
                selection = SheetSelection(row, column, row, column)
                ClipboardTools.toast(context, "Start cell selected. Tap the last cell of the range.")
            } else {
                selection = SheetSelection(anchor.row, anchor.column, row, column)
                rangeAnchor = null
                rangeSelectMode = false
                ClipboardTools.toast(context, "Selected range ${selection.label(1)}.")
            }
        } else {
            selection = SheetSelection(row, column, row, column)
            rangeAnchor = null
        }
    }
    val selectColumn: (Int) -> Unit = { column ->
        focusManager.clearFocus()
        selectedRowIds.clear()
        hasActiveCellSelection = false
        selection = SheetSelection(wholeColumn = column)
        rangeAnchor = null
        rangeSelectMode = false
        ClipboardTools.toast(context, "Selected entire Column A.")
    }
    val selectRow: (Int) -> Unit = { row ->
        focusManager.clearFocus()
        selection = SheetSelection(wholeRow = row)
        rangeAnchor = null
        rangeSelectMode = false
        ClipboardTools.toast(context, "Selected entire Row ${row + 1}.")
    }
    val toggleRangeSelection = {
        if (rangeSelectMode) {
            rangeSelectMode = false
            rangeAnchor = null
            ClipboardTools.toast(context, "Range selection cancelled.")
        } else {
            focusManager.clearFocus()
            rangeSelectMode = true
            rangeAnchor = null
            ClipboardTools.toast(context, "Tap the first cell, then the last cell to select a range.")
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        SheetToolbar(
            palette = palette,
            columnCount = 1,
            selectedCellLabel = selection.label(1),
            rangeSelectMode = rangeSelectMode,
            onToggleRange = toggleRangeSelection,
            onCopySelection = {
                val first = selection.topRow()
                val last = selection.bottomRow(items.lastIndex.coerceAtLeast(0))
                val values = if (items.isEmpty()) emptyList() else (first..last).map { row ->
                    items.getOrNull(row)?.content.orEmpty()
                }
                if (values.isEmpty() || values.all { it.isBlank() }) {
                    ClipboardTools.toast(context, "Selected cells are empty.")
                } else {
                    ClipboardTools.copyText(context, "FloatClip selection", values.joinToString("\n"))
                    ClipboardTools.toast(context, "Copied selection ${selection.label(1)}.")
                }
            },
            onPaste = {
                val clipboardText = ClipboardTools.readText(context)
                if (clipboardText.isNullOrBlank()) {
                    ClipboardTools.toast(context, "Clipboard is empty. Copy text first.")
                } else {
                    val grid = parseClipboardGrid(clipboardText)
                    val startRow = selection.topRow()
                    val startColumn = selection.leftColumn()
                    val isSingleClipboardCell = grid.size == 1 && grid.firstOrNull()?.size == 1
                    val fillSelectedRange = isSingleClipboardCell &&
                        selection.wholeColumn == null &&
                        selection.rowSpan() > 1
                    val destinationEndRow = if (fillSelectedRange) selection.bottomRow(rowCount - 1)
                        else startRow + grid.lastIndex.coerceAtLeast(0)
                    focusManager.clearFocus()
                    scope.launch {
                        val before = dao.getQuickTextsOnce(queue)
                        editMutex.withLock {
                            if (fillSelectedRange) {
                                val value = grid.first().firstOrNull().orEmpty()
                                for (row in startRow..destinationEndRow) updateQuickTextCell(dao, row, value, queue)
                            } else grid.forEachIndexed { offset, values -> updateQuickTextCell(dao, startRow + offset, values.firstOrNull().orEmpty(), queue) }
                        }
                        val after = dao.getQuickTextsOnce(queue)
                        history.record("quick-paste-" + queue, "Paste TXT" + queue + " rows", undo = { dao.restoreQuickTextQueue(queue, before) }, redo = { dao.restoreQuickTextQueue(queue, after) })
                        selection = SheetSelection(startRow, 0, destinationEndRow, 0)
                        rangeSelectMode = false
                        rangeAnchor = null
                        ClipboardTools.toast(context, "Pasted into Column A starting at A${startRow + 1}.")
                    }
                }
            },
            onDeleteRows = {
                val ids = selectedRowIds.toList().ifEmpty {
                    val selectedWholeRow = selection.wholeRow
                    if (selectedWholeRow != null) {
                        listOfNotNull(items.getOrNull(selectedWholeRow)?.id?.takeIf { it > 0L })
                    } else if (hasActiveCellSelection && selection.wholeColumn == null && items.isNotEmpty()) {
                        val firstRow = selection.topRow().coerceIn(0, items.lastIndex)
                        val lastRow = selection.bottomRow(items.lastIndex).coerceIn(firstRow, items.lastIndex)
                        (firstRow..lastRow).mapNotNull { items.getOrNull(it)?.id?.takeIf { id -> id > 0L } }
                    } else {
                        emptyList()
                    }
                }.distinct()
                if (ids.isEmpty()) ClipboardTools.toast(context, "Select a row number or a cell/range first.")
                else scope.launch {
                    val before = dao.getQuickTextsOnce(queue)
                    ids.forEach { dao.deleteQuickText(it) }
                    val after = dao.getQuickTextsOnce(queue)
                    history.record("quick-delete-" + queue, "Delete TXT" + queue + " rows", undo = { dao.restoreQuickTextQueue(queue, before) }, redo = { dao.restoreQuickTextQueue(queue, after) })
                    selectedRowIds.clear()
                    hasActiveCellSelection = false
                    selection = SheetSelection()
                    rangeAnchor = null
                    rangeSelectMode = false
                    ClipboardTools.toast(context, "Deleted " + ids.size + " row(s). Undo is available.")
                }
            },
            onUndo = { scope.launch { history.undo()?.let { ClipboardTools.toast(context, "Undid: " + it) } } },
            onRedo = { scope.launch { history.redo()?.let { ClipboardTools.toast(context, "Redid: " + it) } } },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            onCopyColumn = {
                val values = items.map { it.content }.filter { it.isNotBlank() }
                if (values.isEmpty()) {
                    ClipboardTools.toast(context, "Column A is empty.")
                } else {
                    ClipboardTools.copyText(context, "FloatClip Quick Text", values.joinToString("\n"))
                    ClipboardTools.toast(context, "Column A copied.")
                }
            },
            onCsv = {
                val csv = buildCsv(
                    listOf("COL A"),
                    items.filter { it.content.isNotBlank() }.map { listOf(it.content) }
                )
                if (csv.isBlank()) ClipboardTools.toast(context, "No records to export.")
                else exportCsv(context, "QuickText", csv)
            },
            onPalette = { paletteIndex = (paletteIndex + 1) % sheetPalettes.size }
        )

        if (rangeSelectMode) {
            Text(
                if (rangeAnchor == null) "Range selection: tap the first cell" else "Range selection: tap the last cell",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                color = Color(0xFF1A73E8),
                fontSize = 12.sp
            )
        }

        Text("★ Last copied · Tap a row number or select a cell/range, then use Del Row. Hold and slide to select multiple rows.", modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PurgeAllButton(onClick = { showPurgeDialog = true })
        SpreadsheetHeader(
            horizontalState,
            palette,
            columnCount = 1,
            selectedColumn = selection.wholeColumn,
            onSelectColumn = selectColumn
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, item ->
                val rowSelected = item.id > 0L && selectedRowIds.contains(item.id)
                Row(verticalAlignment = Alignment.Top) {
                    SpreadsheetRowNumber(
                        number = index + 1,
                        rowIndex = index,
                        rowCount = rows.size,
                        color = palette.rowHeader,
                        selected = selection.wholeRow == index || rowSelected,
                        lastCopied = item.lastCopied,
                        onClick = { if (item.id > 0L) { hasActiveCellSelection = false; toggleSelectedRow(item.id) } },
                        onLongPressDrag = { target -> hasActiveCellSelection = false; rows.getOrNull(target)?.id?.let(toggleSelectedRow) }
                    )
                    Row(modifier = Modifier.horizontalScroll(horizontalState)) {
                        SpreadsheetCell(
                            rowIndex = index,
                            columnIndex = 0,
                            value = item.content,
                            width = 310.dp,
                            accent = palette.columns[0],
                            selected = selection.contains(index, 0) || rowSelected,
                            onSelect = { selectCell(index, 0) },
                            onValueChange = { value, previous ->
                                val existingRowId = items.getOrNull(index)?.id
                                history.record("quick-cell-" + queue + "-" + index, "Edit TXT" + queue + " row " + (index + 1),
                                    undo = {
                                        if (existingRowId == null) dao.getQuickTextsOnce(queue).getOrNull(index)?.let { dao.deleteQuickText(it.id) }
                                        else updateQuickTextCell(dao, index, previous, queue)
                                    },
                                    redo = { updateQuickTextCell(dao, index, value, queue) })
                                scope.launch { editMutex.withLock { updateQuickTextCell(dao, index, value, queue) } }
                            }
                        )
                    }
                    TextButton(enabled = item.id > 0L, onClick = {
                        scope.launch {
                            val before = dao.getQuickTextsOnce(queue)
                            dao.deleteQuickText(item.id)
                            val after = dao.getQuickTextsOnce(queue)
                            history.record("quick-delete-" + queue, "Delete TXT" + queue + " row", undo = { dao.restoreQuickTextQueue(queue, before) }, redo = { dao.restoreQuickTextQueue(queue, after) })
                            selectedRowIds.remove(item.id)
                            ClipboardTools.toast(context, "Row deleted. Undo is available.")
                        }
                    }) { Text("×", color = MaterialTheme.colorScheme.error, fontSize = 20.sp) }
                }
            }
        }
    }

    if (showPurgeDialog) {
        AlertDialog(
            onDismissRequest = { showPurgeDialog = false },
            title = { Text("Purge all records?") },
            text = { Text("This removes every row in TXT queue. You can restore it with Undo.") },
            confirmButton = {
                TextButton(onClick = {
                    showPurgeDialog = false
                    scope.launch {
                        val before = dao.getQuickTextsOnce(queue)
                        dao.deleteQuickTextQueue(queue)
                        history.record("quick-purge-" + queue, "Purge TXT" + queue, undo = { dao.restoreQuickTextQueue(queue, before) }, redo = { dao.deleteQuickTextQueue(queue) })
                        selectedRowIds.clear()
                        ClipboardTools.toast(context, "All TXT" + queue + " records deleted. Undo is available.")
                    }
                }) { Text("Purge all") }
            },
            dismissButton = { TextButton(onClick = { showPurgeDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ClipboardSheetSpreadsheet(items: List<ClipboardSheetEntity>, dao: FloatClipDao, history: FloatClipHistory) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val horizontalState = rememberScrollState()
    val editMutex = remember { Mutex() }
    var paletteIndex by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf(SheetSelection()) }
    var rangeSelectMode by remember { mutableStateOf(false) }
    var rangeAnchor by remember { mutableStateOf<CellPosition?>(null) }
    var showPurgeDialog by remember { mutableStateOf(false) }
    var hasActiveCellSelection by remember { mutableStateOf(false) }
    val selectedRowIds = remember { mutableStateListOf<Long>() }
    val listState = rememberLazyListState()
    val toggleSelectedRow: (Long) -> Unit = { id -> if (id > 0L) { if (selectedRowIds.contains(id)) selectedRowIds.remove(id) else selectedRowIds.add(id) } }
    val palette = sheetPalettes[paletteIndex]
    val rowCount = maxOf(16, items.size + 1)
    val rows = (0 until rowCount).map { index ->
        items.getOrNull(index) ?: ClipboardSheetEntity(position = index)
    }

    val selectCell: (Int, Int) -> Unit = { row, column ->
        selectedRowIds.clear()
        hasActiveCellSelection = true
        if (rangeSelectMode) {
            val anchor = rangeAnchor
            if (anchor == null) {
                rangeAnchor = CellPosition(row, column)
                selection = SheetSelection(row, column, row, column)
                ClipboardTools.toast(context, "Start cell selected. Tap the last cell of the range.")
            } else {
                selection = SheetSelection(anchor.row, anchor.column, row, column)
                rangeAnchor = null
                rangeSelectMode = false
                ClipboardTools.toast(context, "Selected range ${selection.label(5)}.")
            }
        } else {
            selection = SheetSelection(row, column, row, column)
            rangeAnchor = null
        }
    }
    val selectColumn: (Int) -> Unit = { column ->
        focusManager.clearFocus()
        selectedRowIds.clear()
        hasActiveCellSelection = false
        selection = SheetSelection(wholeColumn = column)
        rangeAnchor = null
        rangeSelectMode = false
        ClipboardTools.toast(context, "Selected entire Column ${columnLetter(column)}.")
    }
    val selectRow: (Int) -> Unit = { row ->
        focusManager.clearFocus()
        selection = SheetSelection(wholeRow = row)
        rangeAnchor = null
        rangeSelectMode = false
        ClipboardTools.toast(context, "Selected entire Row ${row + 1}.")
    }
    val toggleRangeSelection = {
        if (rangeSelectMode) {
            rangeSelectMode = false
            rangeAnchor = null
            ClipboardTools.toast(context, "Range selection cancelled.")
        } else {
            focusManager.clearFocus()
            rangeSelectMode = true
            rangeAnchor = null
            ClipboardTools.toast(context, "Tap the first cell, then the last cell to select a range.")
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        SheetToolbar(
            palette = palette,
            columnCount = 5,
            selectedCellLabel = selection.label(5),
            rangeSelectMode = rangeSelectMode,
            onToggleRange = toggleRangeSelection,
            onDeleteRows = {
                val ids = selectedRowIds.toList().ifEmpty {
                    val selectedWholeRow = selection.wholeRow
                    if (selectedWholeRow != null) {
                        listOfNotNull(items.getOrNull(selectedWholeRow)?.id?.takeIf { it > 0L })
                    } else if (hasActiveCellSelection && selection.wholeColumn == null && items.isNotEmpty()) {
                        val firstRow = selection.topRow().coerceIn(0, items.lastIndex)
                        val lastRow = selection.bottomRow(items.lastIndex).coerceIn(firstRow, items.lastIndex)
                        (firstRow..lastRow).mapNotNull { items.getOrNull(it)?.id?.takeIf { id -> id > 0L } }
                    } else {
                        emptyList()
                    }
                }.distinct()
                if (ids.isEmpty()) ClipboardTools.toast(context, "Select a row number or a cell/range first.")
                else scope.launch {
                    val before = dao.getClipboardSheetOnce()
                    ids.forEach { dao.deleteClipboardSheet(it) }
                    val after = dao.getClipboardSheetOnce()
                    history.record("clipboard-delete", "Delete clipboard rows", undo = { dao.restoreClipboardSheet(before) }, redo = { dao.restoreClipboardSheet(after) })
                    selectedRowIds.clear()
                    hasActiveCellSelection = false
                    selection = SheetSelection()
                    rangeAnchor = null
                    rangeSelectMode = false
                    ClipboardTools.toast(context, "Deleted " + ids.size + " row(s). Undo is available.")
                }
            },
            onUndo = { scope.launch { history.undo()?.let { ClipboardTools.toast(context, "Undid: " + it) } } },
            onRedo = { scope.launch { history.redo()?.let { ClipboardTools.toast(context, "Redid: " + it) } } },
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            onCopySelection = {
                val firstRow = selection.topRow()
                val lastRow = selection.bottomRow(items.lastIndex.coerceAtLeast(0))
                val firstColumn = selection.leftColumn()
                val lastColumn = selection.rightColumn(5)
                val values = if (items.isEmpty()) emptyList() else (firstRow..lastRow).map { row ->
                    (firstColumn..lastColumn).map { column ->
                        items.getOrNull(row)?.let { clipboardCell(it, column) }.orEmpty()
                    }
                }
                if (values.isEmpty() || values.all { row -> row.all { it.isBlank() } }) {
                    ClipboardTools.toast(context, "Selected cells are empty.")
                } else {
                    ClipboardTools.copyText(
                        context,
                        "FloatClip selection",
                        values.joinToString("\n") { row -> row.joinToString("\t") }
                    )
                    ClipboardTools.toast(context, "Copied selection ${selection.label(5)}.")
                }
            },
            onPaste = {
                val clipboardText = ClipboardTools.readText(context)
                if (clipboardText.isNullOrBlank()) {
                    ClipboardTools.toast(context, "Clipboard is empty. Copy text first.")
                } else {
                    val grid = parseClipboardGrid(clipboardText)
                    val startRow = selection.topRow()
                    val startColumn = selection.leftColumn()
                    val singleClipboardCell = grid.size == 1 && grid.firstOrNull()?.size == 1
                    val fillSelectedRange = singleClipboardCell &&
                        selection.wholeColumn == null &&
                        selection.rowSpan() * selection.columnSpan(5) > 1
                    val destinationEndRow = if (fillSelectedRange) selection.bottomRow(rowCount - 1)
                        else startRow + grid.lastIndex.coerceAtLeast(0)
                    val destinationEndColumn = if (fillSelectedRange) selection.rightColumn(5)
                        else minOf(4, startColumn + (grid.maxOfOrNull { it.size } ?: 1) - 1)
                    focusManager.clearFocus()
                    scope.launch {
                        val before = dao.getClipboardSheetOnce()
                        editMutex.withLock {
                            if (fillSelectedRange) {
                                val value = grid.first().firstOrNull().orEmpty()
                                for (row in startRow..destinationEndRow) for (column in startColumn..destinationEndColumn) updateClipboardCell(dao, row, column, value)
                            } else grid.forEachIndexed { rowOffset, values ->
                                values.forEachIndexed { columnOffset, value ->
                                    val destinationColumn = startColumn + columnOffset
                                    if (destinationColumn in 0..4) updateClipboardCell(dao, startRow + rowOffset, destinationColumn, value)
                                }
                            }
                        }
                        val after = dao.getClipboardSheetOnce()
                        history.record("clipboard-paste", "Paste clipboard cells", undo = { dao.restoreClipboardSheet(before) }, redo = { dao.restoreClipboardSheet(after) })
                        selection = SheetSelection(
                            startRow,
                            startColumn,
                            destinationEndRow,
                            destinationEndColumn
                        )
                        rangeSelectMode = false
                        rangeAnchor = null
                        val pastedCellCount = if (fillSelectedRange) {
                            (destinationEndRow - startRow + 1) * (destinationEndColumn - startColumn + 1)
                        } else {
                            grid.sumOf { values -> values.indices.count { startColumn + it in 0..4 } }
                        }
                        val suffix = if (grid.any { it.size + startColumn > 5 }) " Extra cells beyond COL E were skipped." else ""
                        ClipboardTools.toast(
                            context,
                            "Pasted $pastedCellCount cell(s) starting at ${columnLetter(startColumn)}${startRow + 1}.$suffix"
                        )
                    }
                }
            },
            onCopyColumn = { column ->
                val values = items.map { clipboardCell(it, column) }.filter { it.isNotBlank() }
                if (values.isEmpty()) {
                    ClipboardTools.toast(context, "Column " + columnNames[column].last() + " is empty.")
                } else {
                    ClipboardTools.copyText(
                        context,
                        "FloatClip " + columnNames[column],
                        values.joinToString("\n")
                    )
                    ClipboardTools.toast(context, "Column " + columnNames[column].last() + " copied.")
                }
            },
            onCsv = {
                val csv = buildCsv(
                    columnNames,
                    items.filter {
                        it.colA.isNotBlank() || it.colB.isNotBlank() ||
                            it.colC.isNotBlank() || it.colD.isNotBlank() || it.colE.isNotBlank()
                    }.map { listOf(it.colA, it.colB, it.colC, it.colD, it.colE) }
                )
                if (csv.isBlank()) ClipboardTools.toast(context, "No records to export.")
                else exportCsv(context, "ClipboardSheet", csv)
            },
            onPalette = { paletteIndex = (paletteIndex + 1) % sheetPalettes.size }
        )

        if (rangeSelectMode) {
            Text(
                if (rangeAnchor == null) "Range selection: tap the first cell" else "Range selection: tap the last cell",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                color = Color(0xFF1A73E8),
                fontSize = 12.sp
            )
        }

        Text("Tap a row number or select a cell/range, then use Del Row. Hold and slide to select multiple rows.", modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PurgeAllButton(onClick = { showPurgeDialog = true })
        SpreadsheetHeader(
            horizontalState,
            palette,
            columnCount = 5,
            selectedColumn = selection.wholeColumn,
            onSelectColumn = selectColumn
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, item ->
                val rowSelected = item.id > 0L && selectedRowIds.contains(item.id)
                Row(verticalAlignment = Alignment.Top) {
                    SpreadsheetRowNumber(
                        number = index + 1,
                        rowIndex = index,
                        rowCount = rows.size,
                        color = palette.rowHeader,
                        selected = selection.wholeRow == index || rowSelected,
                        onClick = { if (item.id > 0L) { hasActiveCellSelection = false; toggleSelectedRow(item.id) } },
                        onLongPressDrag = { target -> hasActiveCellSelection = false; rows.getOrNull(target)?.id?.let(toggleSelectedRow) }
                    )
                    Row(modifier = Modifier.horizontalScroll(horizontalState)) {
                        for (column in 0..4) {
                            SpreadsheetCell(
                                rowIndex = index,
                                columnIndex = column,
                                value = clipboardCell(item, column),
                                width = 150.dp,
                                accent = palette.columns[column],
                                selected = selection.contains(index, column) || rowSelected,
                                onSelect = { selectCell(index, column) },
                                onValueChange = { value, previous ->
                                    val existingRowId = items.getOrNull(index)?.id
                                    history.record("clipboard-cell-" + index + "-" + column, "Edit " + columnLetter(column) + (index + 1),
                                        undo = {
                                            if (existingRowId == null) dao.getClipboardSheetOnce().getOrNull(index)?.let { dao.deleteClipboardSheet(it.id) }
                                            else updateClipboardCell(dao, index, column, previous)
                                        },
                                        redo = { updateClipboardCell(dao, index, column, value) })
                                    scope.launch { editMutex.withLock { updateClipboardCell(dao, index, column, value) } }
                                }
                            )
                        }
                        TextButton(enabled = item.id > 0L, onClick = {
                            scope.launch {
                                val before = dao.getClipboardSheetOnce()
                                dao.deleteClipboardSheet(item.id)
                                val after = dao.getClipboardSheetOnce()
                                history.record("clipboard-delete", "Delete clipboard row", undo = { dao.restoreClipboardSheet(before) }, redo = { dao.restoreClipboardSheet(after) })
                                selectedRowIds.remove(item.id)
                                ClipboardTools.toast(context, "Row deleted. Undo is available.")
                            }
                        }) { Text("×", color = MaterialTheme.colorScheme.error, fontSize = 20.sp) }
                    }
                }
            }
        }
    }

    if (showPurgeDialog) {
        AlertDialog(
            onDismissRequest = { showPurgeDialog = false },
            title = { Text("Purge all records?") },
            text = { Text("This removes every row in the Clipboard Sheet. You can restore it with Undo.") },
            confirmButton = {
                TextButton(onClick = {
                    showPurgeDialog = false
                    scope.launch {
                        val before = dao.getClipboardSheetOnce()
                        dao.deleteAllClipboardSheet()
                        history.record("clipboard-purge", "Purge clipboard sheet", undo = { dao.restoreClipboardSheet(before) }, redo = { dao.deleteAllClipboardSheet() })
                        selectedRowIds.clear()
                        ClipboardTools.toast(context, "All Clipboard Sheet records deleted. Undo is available.")
                    }
                }) { Text("Purge all") }
            },
            dismissButton = { TextButton(onClick = { showPurgeDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SheetToolbar(
    palette: SheetPalette,
    columnCount: Int,
    selectedCellLabel: String,
    rangeSelectMode: Boolean,
    onToggleRange: () -> Unit,
    onDeleteRows: (() -> Unit)? = null,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onCopySelection: () -> Unit,
    onPaste: () -> Unit,
    onCopyColumn: (Int) -> Unit,
    onCsv: () -> Unit,
    onPalette: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onPaste,
            modifier = Modifier.width(64.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A73E8),
                contentColor = Color.White
            )
        ) { Text("Paste", fontSize = 11.sp, maxLines = 1) }

        Button(
            onClick = onToggleRange,
            modifier = Modifier.width(84.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (rangeSelectMode) Color(0xFF0F766E) else Color(0xFF64748B),
                contentColor = Color.White
            )
        ) { Text(if (rangeSelectMode) "Pick End" else "Range", fontSize = 11.sp, maxLines = 1) }

        if (onDeleteRows != null) {
            Button(
                onClick = { onDeleteRows() },
                modifier = Modifier.width(72.dp).height(40.dp),
                shape = RoundedCornerShape(5.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB91C1C),
                    contentColor = Color.White
                )
            ) { Text("Del Row", fontSize = 11.sp, maxLines = 1) }
        }

        Button(
            onClick = onCopySelection,
            modifier = Modifier.width(74.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF475569),
                contentColor = Color.White
            )
        ) { Text("Copy Sel", fontSize = 10.sp, maxLines = 1) }

        Button(onClick = onUndo, enabled = canUndo, modifier = Modifier.width(58.dp).height(40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Undo", fontSize = 10.sp) }
        Button(onClick = onRedo, enabled = canRedo, modifier = Modifier.width(58.dp).height(40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Redo", fontSize = 10.sp) }
        Text(
            selectedCellLabel,
            modifier = Modifier.padding(horizontal = 2.dp),
            color = Color(0xFF1A73E8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )

        for (column in 0 until columnCount) {
            Button(
                onClick = { onCopyColumn(column) },
                modifier = Modifier.width(if (columnCount == 1) 82.dp else 64.dp).height(40.dp),
                shape = RoundedCornerShape(5.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.columns[column],
                    contentColor = Color.White
                )
            ) {
                Text("Copy " + columnNames[column].last(), fontSize = 11.sp, maxLines = 1)
            }
        }
        Button(
            onClick = onCsv,
            modifier = Modifier.width(48.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE91E63),
                contentColor = Color.White
            )
        ) { Text("CSV", fontSize = 12.sp) }

        Button(
            onClick = onPalette,
            modifier = Modifier.width(40.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00ACC1),
                contentColor = Color.White
            )
        ) { Text("🎨", fontSize = 17.sp) }
    }
}

@Composable
private fun PurgeAllButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp).height(40.dp),
        shape = RoundedCornerShape(5.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFD32F2F),
            contentColor = Color.White
        )
    ) {
        Text("PURGE ALL RECORDS", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun SpreadsheetHeader(
    horizontalState: androidx.compose.foundation.ScrollState,
    palette: SheetPalette,
    columnCount: Int,
    selectedColumn: Int?,
    onSelectColumn: (Int) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.width(42.dp).height(46.dp)
                .background(palette.rowHeader)
                .border(1.dp, GridLine),
            contentAlignment = Alignment.Center
        ) {
            Text("#", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Row(modifier = Modifier.horizontalScroll(horizontalState)) {
            for (column in 0 until columnCount) {
                Box(
                    modifier = Modifier
                        .width(if (columnCount == 1) 310.dp else 150.dp)
                        .height(46.dp)
                        .background(if (selectedColumn == column) Color(0xFF1A73E8) else palette.columns[column])
                        .border(if (selectedColumn == column) 3.dp else 1.dp, if (selectedColumn == column) Color(0xFF0D47A1) else GridLine)
                        .clickable { onSelectColumn(column) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        columnNames[column],
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SpreadsheetRowNumber(
    number: Int,
    rowIndex: Int,
    rowCount: Int,
    color: Color,
    selected: Boolean,
    lastCopied: Boolean = false,
    onClick: () -> Unit,
    onLongPressDrag: (Int) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }
    val currentOnLongPressDrag by rememberUpdatedState(onLongPressDrag)
    Box(
        modifier = Modifier.width(42.dp).height(56.dp)
            .background(if (selected) Color(0xFF1A73E8) else if (lastCopied) Color(0xFF0F766E) else color)
            .border(if (selected || lastCopied) 3.dp else 1.dp, if (selected) Color(0xFF0D47A1) else if (lastCopied) Color(0xFF115E59) else GridLine)
            .pointerInput(rowIndex, rowCount, rowHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        var lastRow = rowIndex
                        currentOnLongPressDrag(lastRow)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val targetRow = (rowIndex + floor(change.position.y / rowHeightPx).toInt()).coerceIn(0, rowCount - 1)
                            if (targetRow != lastRow) {
                                val step = if (targetRow > lastRow) 1 else -1
                                var row = lastRow + step
                                while (if (step > 0) row <= targetRow else row >= targetRow) {
                                    currentOnLongPressDrag(row)
                                    row += step
                                }
                                lastRow = targetRow
                            }
                            if (!change.pressed) break
                            change.consume()
                        }
                    }
                }
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(if (lastCopied) number.toString() + " ★" else number.toString(), color = Color.White, fontSize = if (lastCopied) 11.sp else 14.sp)
    }
}

@Composable
private fun SpreadsheetCell(
    rowIndex: Int,
    columnIndex: Int,
    value: String,
    width: Dp,
    accent: Color,
    selected: Boolean,
    onSelect: () -> Unit,
    onValueChange: (String, String) -> Unit
) {
    var localText by remember(rowIndex, columnIndex) { mutableStateOf(value) }
    var isFocused by remember(rowIndex, columnIndex) { mutableStateOf(false) }

    LaunchedEffect(value, isFocused) {
        if (!isFocused && localText != value) localText = value
    }

    Box(
        modifier = Modifier.width(width).height(56.dp)
            .background(if (selected) Color(0xFFD2E3FC) else Color.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFF1A73E8) else GridLine)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = localText,
            onValueChange = {
                val previous = localText
                localText = it
                onValueChange(it, previous)
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onSelect()
            },
            textStyle = TextStyle(color = GridText, fontSize = 14.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(accent),
            maxLines = 3
        )
    }
}

private suspend fun updateQuickTextCell(dao: FloatClipDao, rowIndex: Int, value: String, queue: Int) {
    var rows = dao.getQuickTextsOnce(queue)
    while (rows.size <= rowIndex) {
        val position = (dao.maxQuickTextPosition(queue) ?: -1) + 1
        dao.insertQuickText(QuickTextEntity(title = "", content = "", position = position, queue = queue))
        rows = dao.getQuickTextsOnce(queue)
    }
    val row = rows[rowIndex]
    dao.updateQuickText(row.copy(title = value.take(40), content = value))
}

private fun parseClipboardGrid(text: String): List<List<String>> {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
    if (normalized.isEmpty()) return listOf(listOf(""))
    return normalized.split('\n').map { line -> line.split('\t') }
}

private fun clipboardCell(item: ClipboardSheetEntity, column: Int): String = when (column) {
    0 -> item.colA
    1 -> item.colB
    2 -> item.colC
    3 -> item.colD
    else -> item.colE
}

private suspend fun updateClipboardCell(
    dao: FloatClipDao,
    rowIndex: Int,
    column: Int,
    value: String
) {
    var rows = dao.getClipboardSheetOnce()
    while (rows.size <= rowIndex) {
        val position = (dao.maxClipboardSheetPosition() ?: -1) + 1
        dao.insertClipboardSheet(ClipboardSheetEntity(position = position))
        rows = dao.getClipboardSheetOnce()
    }
    val row = rows[rowIndex]
    val updated = when (column) {
        0 -> row.copy(colA = value)
        1 -> row.copy(colB = value)
        2 -> row.copy(colC = value)
        3 -> row.copy(colD = value)
        else -> row.copy(colE = value)
    }
    dao.updateClipboardSheet(updated)
}

private fun buildCsv(headers: List<String>, rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    return (listOf(headers) + rows).joinToString("\n") { row ->
        row.joinToString(",") { value -> "\"" + value.replace("\"", "\"\"") + "\"" }
    }
}

private fun exportCsv(context: Context, sheetName: String, csv: String) {
    val fileName = "FloatClip_" + sheetName + "_" + System.currentTimeMillis() + ".csv"
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/FloatClip"
                )
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the CSV file.")
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open the CSV file.")
            output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(csv) }
            ClipboardTools.toast(context, "CSV saved to Downloads/FloatClip.")
        } else {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            val file = File(directory, fileName)
            file.writeText(csv, Charsets.UTF_8)
            ClipboardTools.toast(context, "CSV saved: " + file.absolutePath)
        }
    } catch (exception: Exception) {
        ClipboardTools.toast(context, "CSV export failed: " + (exception.message ?: "Unknown error"))
    }
}
