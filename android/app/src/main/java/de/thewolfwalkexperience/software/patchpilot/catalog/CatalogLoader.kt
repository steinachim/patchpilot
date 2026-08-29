package de.thewolfwalkexperience.software.patchpilot.catalog

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Reads one family's catalog asset, once per process.
 *
 * **All three families had their own copy of this**: the same `Json { ignoreUnknownKeys = true }`,
 * the same nullable cache field, the same `cached ?: load().also { cached = it }`, the same
 * asset-open-and-decode. The copies had already drifted in naming (`cachedCatalog`/`loadCatalog`
 * against `cached`/`load`), and each repeated an unsynchronised `var` on a singleton.
 *
 * `InstrumentFamily` is described in its own doc as "the whole extension point", which makes this
 * the boilerplate a fourth family should inherit rather than copy.
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
