package app.trouvetout.core.di

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.inMemoryDriverFactory
import app.trouvetout.core.data.photo.FakeImageCodec
import app.trouvetout.core.domain.ids.Ulid
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.platform.CoroutineDispatchers
import app.trouvetout.core.platform.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedFactoryTest {
    private object FakeDevice : DeviceInfo {
        override val totalRamBytes: Long = 4L * 1024 * 1024 * 1024
        override val osName: String = "test"
        override val osVersion: String = "0"
    }

    private fun factory(): SharedFactory {
        val config = SharedConfig("/tmp/test", FakeDevice, inMemoryDriverFactory(), FakeImageCodec())
        config.clock = TickingClock()
        config.random = Random(42)
        config.dispatchers =
            CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
        return SharedFactory(config)
    }

    @Test
    fun should_generate_a_stable_device_id() {
        val factory = factory()
        val id = factory.deviceId
        assertTrue(Ulid.isValid(id))
        assertEquals(id, factory.deviceId)
    }

    @Test
    fun should_wire_commands_and_repositories_end_to_end() = runTest {
        val factory = factory()
        val cave = factory.placeCommands.create("cave", kind = PlaceKind.ROOT, source = EventSource.TEXT)
        val caveId = (
            assertIs<CommandResult.Done>(
                cave,
            ).events.single().payload as app.trouvetout.core.domain.model.EventPayload.PlaceCreated
        ).placeId
        val result = factory.itemCommands.create(
            ItemDraft("skis"),
            LocationRef.InPlace(caveId),
            EventSource.TEXT,
            "skis @ cave",
        )
        assertIs<CommandResult.Done>(result)

        val views = factory.itemRepository.observeActive().first()
        assertEquals(1, views.size)
        assertEquals("skis", views.single().item.name)
        assertEquals(listOf("cave"), views.single().path.labels)
        assertEquals(listOf("cave"), factory.placeRepository.observeChildren(null).first().map { it.name })
        assertEquals(3L, factory.eventStore.count(), "place + item + stored")
        assertTrue(factory.eventStore.all().all { it.deviceId == factory.deviceId })
        factory.close()
    }
}
