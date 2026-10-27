package app.trouvetout.core.data.photo

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.AttachmentCommands
import app.trouvetout.core.domain.usecase.CommandContext
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.PlaceCommands
import app.trouvetout.core.domain.usecase.Rejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhotoAttacherTest {
    private val db = openTestDatabase()
    private val clock = TickingClock()
    private val ctx = CommandContext(db.testEventStore(), MonotonicUlidGenerator(clock, Random(1)), clock, "device-T")
    private val files = InMemoryFileStore()
    private var counter = 0
    private val photos = PhotoStore(files, FakeImageCodec(), { id(counter++) }, Dispatchers.Unconfined)
    private val attacher = PhotoAttacher(photos, AttachmentCommands(ctx))

    private fun id(n: Int) = "01HTEST" + "0".repeat(17) + n.toString().padStart(2, '0')

    @Test
    fun should_store_then_attach_in_one_call() = runTest {
        val created =
            assertIs<CommandResult.Done>(
                PlaceCommands(ctx).create("Cave", null, PlaceKind.ROOT, source = EventSource.TEXT),
            )
        val placeId = (created.events.single().payload as EventPayload.PlaceCreated).placeId

        val result = attacher.attach(PhotoOwnerType.PLACE, placeId, "3000x1500".encodeToByteArray(), EventSource.PHOTO)

        val attached =
            assertIs<EventPayload.PhotoAttached>(assertIs<CommandResult.Done>(result).events.single().payload)
        assertEquals(placeId, attached.ownerId)
        assertEquals(listOf(attached.fileName, attached.thumbName), photos.listFiles())
    }

    @Test
    fun should_leave_nothing_on_disk_when_the_attach_is_refused() = runTest {
        val result = attacher.attach(PhotoOwnerType.ITEM, "missing", "3000x1500".encodeToByteArray(), EventSource.PHOTO)

        assertEquals(Rejection.UNKNOWN_ITEM, assertIs<CommandResult.Rejected>(result).reason)
        assertEquals(emptyList(), photos.listFiles())
    }

    @Test
    fun should_refuse_oversized_or_undecodable_sources_before_writing() = runTest {
        val tooLarge = attacher.attach(
            PhotoOwnerType.ITEM,
            "x",
            ByteArray(PhotoStore.MAX_SOURCE_BYTES + 1),
            EventSource.PHOTO,
        )
        assertEquals(Rejection.IMAGE_TOO_LARGE, assertIs<CommandResult.Rejected>(tooLarge).reason)

        val garbage = attacher.attach(PhotoOwnerType.ITEM, "x", "not an image".encodeToByteArray(), EventSource.PHOTO)
        assertEquals(Rejection.UNSUPPORTED_IMAGE, assertIs<CommandResult.Rejected>(garbage).reason)
        assertEquals(emptyList(), photos.listFiles())
    }
}
