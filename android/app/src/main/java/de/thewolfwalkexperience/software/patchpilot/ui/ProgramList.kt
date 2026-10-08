// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.ui.theme.DISABLED_ALPHA
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle
import kotlinx.coroutines.launch

/** The measurements the preset list and its floating drag ghost have to agree on, so the ghost sits over the row it was lifted from. */
private object ProgramListMetrics {
    /** Clears the bank rail (48.dp) plus its 4.dp inset, so no row slides underneath it. */
    val listEndPadding = 56.dp

    /** The drag handle's column, reserved even on rows with no glyph so headlines stay aligned. */
    val handleSize = 32.dp

    /** Material's minimum touch target, and the only way to jump banks. */
    val railWidth = 48.dp

    /** The dragged row, left in place and dimmed while the ghost follows the finger. */
    const val DRAGGED_ALPHA = 0.3f

    /** An empty slot: legible, clearly not a preset. */
    const val EMPTY_SLOT_ALPHA = 0.5f
}

/**
 * What the rows may offer, settled once for the whole list: the session's edits, the listing on
 * screen, and whether editing is possible right now. Facts about one row (an empty slot, a
 * read-only bank) are applied on top by [ProgramRowItem].
 *
 * @property hasCategories whether the instrument files presets under categories at all.
 * @property categoryCount how many categories a preset holds - one on a Nord, two on a Motif XS.
 * @property editsEnabled false while the listing is still arriving or an edit is running - see
 *   `editsEnabled` in [ProgramsScreen].
 */
internal data class RowRules(
    val canRename: Boolean,
    val canDelete: Boolean,
    val canRelocate: Boolean,
    val canCopy: Boolean,
    val canSelect: Boolean,
    val readOnlyBanks: Set<Int>,
    val hasCategories: Boolean,
    val categoryCount: Int,
    val browseScope: PresetScope,
    val editsEnabled: Boolean,
)

/**
 * The list: pull to refresh, the drag-to-reorder gesture, the rows and their bank headers, the
 * ghost of a dragged row, and the bank rail.
 *
 * The drag gesture is detected on the box around the whole LazyColumn rather than on a row, and
 * hit-tests the touch position against `listState.layoutInfo` plus a left-edge band for the
 * handle's column: a per-row `pointerInput` would be cancelled the moment the dragged row
 * scrolled out of the composed range. The dragged row stays in place, dimmed, and a separate
 * ghost ListItem positioned from the finger's own y follows the finger, since LazyColumn composes
 * from each item's untranslated layout slot and would tear the row down.
 *
 * Bank captions are interleaved into the same LazyColumn, so the drag indices are into
 * `visibleRows` rather than the preset list, and headers and rows are not the same height - which
 * is why a position resolves to a row through [ProgramListDragState] rather than by dividing by a
 * row height. Row shading alternates, and the dragged row and its drop target are set through
 * ListItem's own `colors`, which an external `Modifier.background()` would sit beneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgramListBody(
    listing: ProgramListing,
    index: PresetIndexState,
    rules: RowRules,
    ops: ProgramsController,
    drag: ProgramListDragState,
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    searchText: String,
    onClearFilter: () -> Unit,
    badgesFit: Boolean,
    virtualBanks: Set<String>,
    bankRailLabels: Map<String, String>,
    canSetFavorite: (SlotAddress) -> Boolean,
    canSetCategories: (SlotAddress) -> Boolean,
    /** See [de.thewolfwalkexperience.software.patchpilot.core.PresetTagger.favoritePanelHint]. */
    favoritePanelHint: String?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalThemeStyle.current
    val scope = rememberCoroutineScope()
    val visibleRows = listing.rows
    val occupied = listing.occupied
    val unreadable = listing.unreadable
    val browseScope = rules.browseScope
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        // The same function the app bar's refresh button calls, so the two agree.
        onRefresh = onRefresh,
        modifier = modifier
            .pointerInput(visibleRows, rules.canRelocate, index.complete, ops.picking, browseScope) {
                // Off while the index is still arriving (a drop into a region that has not
                // loaded has no target), on an instrument that cannot relocate presets,
                // and while picking a destination, which is resolved by a tap.
                if (!rules.canRelocate || !index.complete || ops.picking) return@pointerInput
                // A user-listing gesture: a read-only bank refuses the write, and the
                // favorites listing is not an address space, so "the row below" is not a
                // destination.
                if (browseScope != PresetScope.USER) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        drag.begin(offset) { i ->
                            val entry = visibleRows.getOrNull(i) as? ProgramRow.ProgramEntry
                            entry != null && entry.item.address in occupied
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        drag.update(dragAmount.y) { i ->
                            visibleRows.getOrNull(i) is ProgramRow.ProgramEntry
                        }
                    },
                    onDragEnd = {
                        val (from, to) = drag.end() ?: return@detectDragGesturesAfterLongPress
                        val source = visibleRows.getOrNull(from) as? ProgramRow.ProgramEntry
                        val target = visibleRows.getOrNull(to) as? ProgramRow.ProgramEntry
                        if (source != null && target != null) {
                            ops.onSwapDropped(source.item, target.item, occupied, unreadable)
                        }
                    },
                    onDragCancel = { drag.cancel() },
                )
            },
    ) {
        // A filter that matches nothing says so, and offers a way out.
        if (visibleRows.isEmpty() && index.complete) {
            NothingListed(browseScope, searchText, favoritePanelHint, onClearFilter)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(end = ProgramListMetrics.listEndPadding),
        ) {
            itemsIndexed(
                visibleRows,
                key = { _, row ->
                    when (row) {
                        is ProgramRow.BankHeader -> "header:${row.bank}"
                        is ProgramRow.ProgramEntry -> "slot:${row.item.address.bank}:${row.item.address.slot}"
                    }
                },
            ) { rowIndex, row ->
                when (row) {
                    is ProgramRow.BankHeader ->
                        theme.BankHeader(
                            // A virtual bank's label is already a number range; calling it
                            // a bank would suggest the instrument has one.
                            if (row.bank in virtualBanks) {
                                row.bank
                            } else {
                                stringResource(R.string.programs_bank_header, row.bank)
                            },
                        )

                    is ProgramRow.ProgramEntry -> ProgramRowItem(
                        row = row,
                        isEmpty = row.item.address !in occupied,
                        isUnreadable = row.item.address in unreadable,
                        isDragged = drag.draggedIndex == rowIndex,
                        isDropTarget = drag.dropTargetIndex == rowIndex &&
                            drag.draggedIndex != null && drag.draggedIndex != rowIndex,
                        rules = rules,
                        ops = ops,
                        badgesFit = badgesFit,
                        canSetFavorite = canSetFavorite(row.item.address),
                        canSetCategories = canSetCategories(row.item.address),
                    )
                }
            }
        }

        drag.draggedIndex?.let { idx ->
            (visibleRows.getOrNull(idx) as? ProgramRow.ProgramEntry)?.let { row ->
                DragGhost(row.item, drag.ghostOffsetY)
            }
        }

        if (listing.bankLabels.size > 1) {
            BankIndex(
                banks = listing.bankLabels,
                railLabel = { bankRailLabels[it] ?: it },
                onJump = { letter ->
                    // A plain jump rather than animateScrollToItem, which animates through
                    // every intermediate row: header rows break the average-item-size
                    // estimate it scrolls by, so a long jump overshoots and corrects, and
                    // successive drag-scrub jumps interrupt each other's animation.
                    listing.bankHeaderIndex[letter]?.let { row -> scope.launch { listState.scrollToItem(row) } }
                },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** What an empty list says, and the way out of a filter that matches nothing. */
@Composable
private fun NothingListed(
    browseScope: PresetScope,
    searchText: String,
    favoritePanelHint: String?,
    onClearFilter: () -> Unit,
) {
    CenteredMessage {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (searchText.isBlank()) {
                    // An instrument full of presets with none marked is not one
                    // that "reports no presets".
                    if (browseScope == PresetScope.FAVORITES) {
                        stringResource(R.string.programs_no_favorites)
                    } else {
                        stringResource(R.string.programs_no_presets)
                    }
                } else {
                    stringResource(R.string.programs_no_matches, searchText)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (searchText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClearFilter) { Text(stringResource(R.string.action_clear_filter)) }
            } else if (browseScope == PresetScope.FAVORITES) {
                // Says where a mark is set - in a row menu, or on the instrument
                // - so an empty list is not a dead end.
                Spacer(Modifier.height(8.dp))
                Text(
                    favoritePanelHint?.let { stringResource(R.string.programs_no_favorites_hint_panel, it) }
                        ?: stringResource(R.string.programs_no_favorites_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One preset or empty slot: name and id, the category badges where they fit, the drag handle,
 * and the row menu. Dimmed in place while dragged - the [DragGhost] follows the finger - and,
 * while a destination is being picked, wherever it is not a valid one.
 */
@Composable
private fun ProgramRowItem(
    row: ProgramRow.ProgramEntry,
    isEmpty: Boolean,
    isUnreadable: Boolean,
    isDragged: Boolean,
    isDropTarget: Boolean,
    rules: RowRules,
    ops: ProgramsController,
    badgesFit: Boolean,
    canSetFavorite: Boolean,
    canSetCategories: Boolean,
) {
    val theme = LocalThemeStyle.current
    val program = row.item
    val browseScope = rules.browseScope
    // Which rows a tap can act on depends on what is being picked
    // for: a copy needs somewhere empty, a move can land anywhere but
    // its own source.
    // An unreadable slot is neither: what it holds is unknown.
    val isPickTarget = ops.picking && !isUnreadable && when (ops.pickIntent) {
        PickIntent.COPY -> isEmpty
        PickIntent.MOVE -> program.address != ops.pickSource?.address
    }
    val rowColor = when {
        isDragged -> MaterialTheme.colorScheme.primaryContainer
        isDropTarget -> MaterialTheme.colorScheme.secondaryContainer
        row.programIndex % 2 == 0 -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    ListItem(
        headlineContent = {
            if (program.name == null) EmptySlotText(unreadable = isUnreadable) else Text(program.name)
        },
        supportingContent = {
            // Badges are short family-supplied strings rendered
            // uniformly, pushed to the trailing edge so a column of
            // them lines up. The badge text takes the remaining width
            // rather than a spacer, so a long one ellipsizes instead
            // of shoving the id off the row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(program.displayId)
                // Only where the row is wide enough: two full labels
                // beside the id do not fit a portrait phone, and the
                // categories stay a row-menu question there.
                if (badgesFit) {
                    if (program.badges.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = program.badges.joinToString(", "),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (rules.hasCategories && program.name != null) {
                        // Said rather than left blank: an empty gap
                        // where every other row carries a category
                        // reads as "not loaded yet".
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.programs_no_category),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = rowColor),
        // What this row in particular offers.
        //
        // A read-only bank refuses every write, so the row keeps only
        // the one action that does not need one - and Copy is a read
        // of *this* row into somewhere else, which is precisely the
        // point of browsing the factory voices at all.
        //
        // Relocating within the favorites listing is meaningless in a
        // different way: its rows are the marked voices in bank order,
        // not an address space, so there is nothing for a copy to
        // rearrange and no free slot in it to copy into.
        trailingContent = run {
            val rowWritable = program.address.bank !in rules.readOnlyBanks
            val rowRename = rules.canRename && rowWritable
            val rowDelete = rules.canDelete && rowWritable &&
                browseScope == PresetScope.USER
            // In every listing, favorites included: picking a
            // destination already moves to the user banks, so a
            // read-only source needs no special case.
            val rowCopy = rules.canCopy
            // The same conditions the drag gesture is enabled under,
            // so the accessible path is not the less capable one.
            val rowMove = rules.canRelocate && rowWritable &&
                browseScope == PresetScope.USER
            // Hidden outright while picking: none of these applies to
            // any row until the pick resolves. Favorites and categories are
            // asked of the facet rather than derived from `rowWritable`: a
            // Motif XS can favorite a factory voice but not re-categorise one.
            if (ops.picking || isEmpty ||
                (!rowRename && !rowDelete && !rowCopy && !canSetFavorite && !canSetCategories)
            ) {
                null
            } else {
                {
                    RowActionsMenu(
                        program = program,
                        canRename = rowRename,
                        canCopy = rowCopy,
                        canMove = rowMove,
                        canDelete = rowDelete,
                        canSetFavorite = canSetFavorite,
                        canSetCategories = canSetCategories,
                        categoryCount = rules.categoryCount,
                        enabled = rules.editsEnabled,
                        onRename = {
                            ops.renameTarget = program
                            ops.renameText = program.name.orEmpty()
                        },
                        onCopy = { ops.beginPicking(program, PickIntent.COPY) },
                        onMove = { ops.beginPicking(program, PickIntent.MOVE) },
                        onDelete = { ops.deleteTarget = program },
                        onSetFavorite = { ops.openTagDialog(program, forFavorite = true) },
                        onSetCategories = { ops.openTagDialog(program, forFavorite = false) },
                    )
                }
            }
        },
        // Always reserves the handle's column, so an empty row's
        // headline stays aligned with the others.
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(ProgramListMetrics.handleSize),
            ) {
                if (!isEmpty && rules.canRelocate && !ops.picking && browseScope == PresetScope.USER) {
                    // The handle is decorative and the row is what a
                    // screen reader describes, so the handle carries
                    // the instruction. Resolved out here because
                    // `semantics {}` is not a composable scope.
                    val handleDescription = if (rules.editsEnabled) {
                        stringResource(R.string.cd_reorder, program.name ?: program.displayId)
                    } else {
                        stringResource(R.string.cd_reorder_unavailable)
                    }
                    theme.DragHandle(
                        enabled = rules.editsEnabled,
                        modifier = Modifier.semantics { contentDescription = handleDescription },
                    )
                }
            }
        },
        // Dimmed in place rather than translated - the ghost
        // follows the finger. Empty slots and, while picking, every
        // row that is not a valid target get the same treatment.
        modifier = Modifier
            .fillMaxWidth()
            .let { theme.rowPanel(it, dragged = isDragged, dropTarget = isDropTarget) }
            .alpha(
                when {
                    isDragged -> ProgramListMetrics.DRAGGED_ALPHA
                    ops.picking && !isPickTarget -> DISABLED_ALPHA
                    isEmpty && !ops.picking -> ProgramListMetrics.EMPTY_SLOT_ALPHA
                    else -> 1f
                },
            )
            .let { m ->
                when {
                    isPickTarget -> {
                        val description = stringResource(
                            when (ops.pickIntent) {
                                PickIntent.COPY -> R.string.cd_copy_destination
                                PickIntent.MOVE -> R.string.cd_move_destination
                            },
                            program.displayId,
                        )
                        // Gated while an edit runs: the picker stays
                        // open across the copy it started.
                        m.clickable(enabled = ops.busy == null) {
                            ops.pickSource?.let {
                                ops.onPickConfirmed(it, program, isEmpty)
                            }
                        }.semantics { contentDescription = description }
                    }

                    // Inert while picking, as the row menu is.
                    ops.picking -> m

                    isEmpty || !rules.canSelect -> m

                    else -> m.clickable { ops.onProgramTapped(program) }
                }
            },
    )
}

/** The row being dragged, drawn where the finger is: the list's own row stays in place, dimmed. */
@Composable
private fun DragGhost(program: PresetSlot, offsetY: Int) {
    val theme = LocalThemeStyle.current
    ListItem(
        headlineContent = {
            if (program.name == null) EmptySlotText() else Text(program.name)
        },
        supportingContent = { Text(program.displayId) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(ProgramListMetrics.handleSize),
            ) {
                // Purely visual, and hidden from accessibility so it does not
                // read as a second control.
                theme.DragHandle(
                    enabled = true,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = ProgramListMetrics.listEndPadding)
            .offset { IntOffset(0, offsetY) }
            .shadow(4.dp),
    )
}

/**
 * Per-row actions (Rename, Copy, Move, Delete, favorite and categories), behind one overflow button: in a 128-row bank always-visible
 * buttons would be hundreds of controls competing with the preset names, with a destructive
 * action permanently a few dp from a benign one. Copy gets no confirmation of its own, being as
 * safe as Rename; choosing a destination in the list is the only decision it needs.
 */
@Composable
private fun RowActionsMenu(
    program: PresetSlot,
    canRename: Boolean,
    canCopy: Boolean,
    canMove: Boolean,
    canDelete: Boolean,
    canSetFavorite: Boolean,
    canSetCategories: Boolean,
    /**
     * How many categories this instrument's presets hold - one on a Nord, two on a Motif XS.
     * Decides whether the menu offers "Set category" or "Set categories".
     */
    categoryCount: Int,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onSetFavorite: () -> Unit,
    onSetCategories: () -> Unit,
    /** False while the listing is still arriving - see `editsEnabled` in ProgramsScreen. */
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // Disabled rather than hidden: a control that vanishes and reappears as a list loads is
        // harder to read than one that is visibly not yet available, and the progress row above
        // says why.
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(
                Icons.Default.MoreVert,
                // Named for its row: in a list of 128, "More options" alone says nothing about
                // which preset a screen-reader user is on.
                contentDescription = stringResource(
                    R.string.cd_row_options,
                    program.name ?: program.displayId,
                ),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (canRename) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = {
                        expanded = false
                        onRename()
                    },
                )
            }
            if (canCopy) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = {
                        expanded = false
                        onCopy()
                    },
                )
            }
            // The reachable half of drag-to-reorder: a long-press drag has no keyboard or
            // screen-reader equivalent, and this is the same destination-picking flow Copy uses.
            if (canMove) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move)) },
                    onClick = {
                        expanded = false
                        onMove()
                    },
                )
            }
            // Before Delete, which stays last.
            if (canSetFavorite) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_set_favorite)) },
                    onClick = {
                        expanded = false
                        onSetFavorite()
                    },
                )
            }
            if (canSetCategories) {
                DropdownMenuItem(
                    text = {
                        Text(pluralStringResource(R.plurals.action_set_categories, categoryCount))
                    },
                    onClick = {
                        expanded = false
                        onSetCategories()
                    },
                )
            }
            if (canDelete) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * Right-edge fast-scroll index: tapping or dragging across a bank letter jumps the list to that
 * bank's caption row. `detectDragGestures` rather than the long-press variant the row reorder
 * uses, since an index jump should react to the first touch. In its own `pointerInput` scope on
 * this narrow column, and a touch starting here is already past the reorder detector's handle
 * band, so that detector's `onDragStart` is a no-op regardless of arbitration timing.
 */
@Composable
private fun BankIndex(
    banks: List<String>,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
    railLabel: (String) -> String = { it },
) {
    var columnHeightPx by remember { mutableStateOf(0f) }
    val theme = LocalThemeStyle.current
    val pillShape = RoundedCornerShape(percent = 50)
    fun jumpToY(y: Float) {
        if (banks.isEmpty() || columnHeightPx <= 0f) return
        val index = ((y / columnHeightPx) * banks.size).toInt().coerceIn(0, banks.lastIndex)
        onJump(banks[index])
    }
    Column(
        modifier
            // Inset, so the rail reads as floating over the list rather than as its right-hand
            // margin.
            .padding(vertical = 12.dp, horizontal = 4.dp)
            // Wide enough for a label like "USER DR" without abbreviating.
            .width(ProgramListMetrics.railWidth)
            // A container tone rather than `surface`, so it separates itself in both light and
            // dark without a border. Clipped before the background, or the label taps' ripple
            // would paint square corners over the rounded ones.
            .clip(pillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .let { theme.railDecoration(it) }
            // After the decoration, before the measurement: `onSizeChanged` and `pointerInput`
            // both sit after it, so the height `jumpToY` divides is the same box the weighted
            // cells fill and a y position means the same thing to both.
            .padding(vertical = theme.railEndInset)
            .onSizeChanged { columnHeightPx = it.height.toFloat() }
            .pointerInput(banks) {
                detectDragGestures(
                    onDragStart = { offset -> jumpToY(offset.y) },
                    onDrag = { change, _ ->
                        change.consume()
                        jumpToY(change.position.y)
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        banks.forEach { letter ->
            // An equal weighted share each, with the whole cell as the target rather than the
            // glyph: `jumpToY` divides the rail's height into `banks.size` equal bands, which is
            // what these weights produce, so dragging and tapping resolve the same letter.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable { onJump(letter) },
            ) {
                Text(
                    railLabel(letter),
                    style = MaterialTheme.typography.labelSmall,
                    // An index is secondary to what it indexes.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
