package app.trouvetout.core.domain.usecase

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandContextConcurrencyTest {
    @Test
    fun should_hand_out_unique_increasing_ids_under_concurrency() = runTest {
        val ctx =
            CommandContext(
                openTestDatabase().testEventStore(),
                MonotonicUlidGenerator(TickingClock(), Random(3)),
                TickingClock(),
                "d",
            )
        val ids =
            withContext(Dispatchers.Default) {
                List(16) { async { List(500) { ctx.newId() } } }.awaitAll().flatten()
            }
        assertEquals(ids.size, ids.toSet().size, "every id is unique")
    }
}
