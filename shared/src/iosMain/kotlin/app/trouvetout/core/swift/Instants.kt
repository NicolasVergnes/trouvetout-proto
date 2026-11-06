package app.trouvetout.core.swift

import kotlin.time.Instant

/** An instant from Unix milliseconds: Swift cannot reach the companion of `kotlin.time.Instant` simply (lot 9). */
public fun instantOf(epochMillis: Long): Instant = Instant.fromEpochMilliseconds(epochMillis)
