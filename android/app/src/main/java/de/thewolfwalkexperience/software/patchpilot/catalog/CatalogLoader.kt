package de.thewolfwalkexperience.software.patchpilot.catalog

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Reads one family's catalog asset, once per process.
 *
 * The one asset-open-and-decode every family shares, so a new family inherits it rather than
 * copying it.
 *
 * The lock is not ceremony: `descriptors()` is called from a coroutine, and while decoding twice
 * would be harmless here, a half-published `var` is not something to leave to luck.
 */
class CatalogLoader<T : Any>(
    private val assetName: String,
    private val serializer: KSerializer<T>,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    @Volatile
    private var cached: T? = null

    /** The parsed catalog, decoding it on first use. */
    fun load(context: Context): T = cached ?: synchronized(lock) {
        cached ?: decode(context).also { cached = it }
    }

    /** The family's `Json`, so a factory can decode its per-device config with the same settings. */
    val format: Json get() = json

    private fun decode(context: Context): T =
        context.assets.open(assetName).bufferedReader().use { json.decodeFromString(serializer, it.readText()) }
}
