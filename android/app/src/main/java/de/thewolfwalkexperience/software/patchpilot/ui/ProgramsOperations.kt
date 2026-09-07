package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * The slice of [InstrumentViewModel] that [ProgramsController] actually uses.
 *
 * **This exists so the controller can be tested.** [InstrumentViewModel] is an `AndroidViewModel`:
 * constructing one needs an `Application`, and reaching an instrument through it needs a USB or
 * MIDI session. Neither is available to a plain JVM unit test, and the project deliberately has no
 * mocking framework - so without a seam here the operation logic would be exactly as untestable
 * after being lifted out of the composable as it was inside it, which would make the whole move
 * cosmetic.
 *
 * It is a narrow interface named for the caller rather than a general-purpose one: twelve members
 * is the whole of what this screen's operations need, and anything wider would start describing
 * the ViewModel instead of describing this dependency.
 */
internal interface ProgramsOperations {
    /** Which listing is on screen. The controller reads it when a copy has to move the browser. */
    val scope: StateFlow<PresetScope>
    fun setScope(scope: PresetScope)

    /**
     * Runs an edit on a scope that outlives the screen - see [InstrumentViewModel.launchEdit] for
     * why interrupting one mid-write is the one thing that can leave an instrument stuck.
     */
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
 * Resolves a string resource, so the controller needs no `Context`.
 *
 * Two things fall out of this beyond testability: nothing user-facing gets hardcoded in the
 * controller to work around not having a `Context`, and a `Context` is not retained by an object
 * held in `remember`.
 */
internal fun interface StringResolver {
    fun get(resId: Int, vararg args: Any): String
}
