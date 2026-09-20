// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Reads one family's catalog asset, once per process. `descriptors()` is called from coroutines,
 * hence the lock.
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
