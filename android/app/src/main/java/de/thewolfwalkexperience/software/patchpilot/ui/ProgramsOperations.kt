// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * The slice of [InstrumentViewModel] that [ProgramsController] uses, so the controller can be
 * tested: constructing an `AndroidViewModel` needs an `Application`, reaching an instrument needs
 * a session, and the project has no mocking framework. Narrow and named for the caller - anything
 * wider would describe the ViewModel rather than this dependency.
 */
internal interface ProgramsOperations {
    /** Which listing is on screen. The controller reads it when a copy has to move the browser. */
    val scope: StateFlow<PresetScope>
    fun setScope(scope: PresetScope)

    /** Runs an edit on a scope that outlives the screen - see [InstrumentViewModel.launchEdit]. */
    fun launchEdit(block: suspend () -> Unit): Job

    fun reloadIndex(): Int?

    suspend fun selectProgram(slot: PresetSlot): String
    suspend fun renameProgram(slot: PresetSlot, newName: String): String
    suspend fun deleteProgram(slot: PresetSlot): String
    suspend fun copyProgram(source: PresetSlot, destination: PresetSlot): String
    suspend fun moveProgram(source: PresetSlot, target: PresetSlot, targetIsEmpty: Boolean): String
    suspend fun presetTags(slot: PresetSlot): PresetTags
    suspend fun setFavorite(slot: PresetSlot, under: Set<Int>): String
    suspend fun setCategories(slot: PresetSlot, categories: List<CategoryRef?>): String
}

/**
 * Resolves a string resource, so the controller needs no `Context` - which also keeps a `Context`
 * out of an object held in `remember`.
 */
internal fun interface StringResolver {
    fun get(resId: Int, vararg args: Any): String
}

/**
 * Which operation a destination pick will perform: the two differ in what counts as a valid
 * destination, since a copy needs somewhere empty while a move can land on anything.
 */
internal enum class PickIntent { COPY, MOVE }
