package app.trouvetout.core.licensing

import app.trouvetout.core.capture.CaptureOutcome
import app.trouvetout.core.capture.CaptureProblem
import app.trouvetout.core.capture.ItemChoice
import app.trouvetout.core.capture.PlaceChoice
import app.trouvetout.core.capture.PlaceResolution
import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.inMemoryDriverFactory
import app.trouvetout.core.data.export.ImportResult
import app.trouvetout.core.data.photo.FakeImageCodec
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.ItemPatch
import app.trouvetout.core.domain.usecase.Rejection
import app.trouvetout.core.platform.CoroutineDispatchers
import app.trouvetout.core.platform.DeviceInfo
import app.trouvetout.core.platform.createFileStore
import app.trouvetout.core.platform.testTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** J1, J4, U11, ADR-025: the start of the trial, what stops at its end, what never stops. */
class LicensingServiceTest {
    private object FakeDevice : DeviceInfo {
        override val totalRamBytes: Long = 4L * 1024 * 1024 * 1024
        override val osName: String = "test"
        override val osVersion: String = "0"
    }

    private class MemoryStore(
        var value: Long? = null,
    ) : LicenseStore {
        override fun trialStartMillis(): Long? = value

        override fun setTrialStartMillis(millis: Long) {
            value = millis
        }
    }

    private val start = millis("2026-11-07T10:00:00Z")
    private val afterTrial = millis("2027-05-08T10:00:00Z")
    private val clock = TickingClock(start)
    private val store = MemoryStore()

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    private fun factory(
        licenseStore: LicenseStore? = store,
        directory: String = "/tmp/test",
        testClock: TickingClock = clock,
    ): SharedFactory {
        val config = SharedConfig(directory, FakeDevice, inMemoryDriverFactory(), FakeImageCodec())
        config.clock = testClock
        config.random = Random(42)
        config.licenseStore = licenseStore
        config.dispatchers =
            CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
        return SharedFactory(config)
    }

    /** Captures with the default decisions: unknown places are created. */
    private suspend fun SharedFactory.say(
        text: String,
        choices: List<ItemChoice> = emptyList(),
    ): CaptureOutcome {
        clock.tick()
        val proposal = capture.propose(text)
        val place = (proposal.place as? PlaceResolution.Unknown)?.let { PlaceChoice.CreateNew(it.names, null, false) }
        return capture.commit(proposal, place, choices)
    }

    @Test
    fun should_start_the_trial_at_the_first_launch_not_at_the_first_object() = runTest {
        assertTrue(factory().warmUp())
        assertEquals(start, store.value)
    }

    @Test
    fun should_report_the_end_of_the_trial_and_stop_additions_there() = runTest {
        val factory = factory()
        val running = factory.licensing.status()
        assertEquals(millis("2027-05-07T10:00:00Z"), running.trialEndMillis)
        assertTrue(running.canAddItems)
        assertFalse(running.purchased)
        clock.millis = running.trialEndMillis
        assertFalse(factory.licensing.status().canAddItems)
    }

    @Test
    fun a_purchase_unlocks_and_a_subscription_that_ends_locks_again() = runTest {
        val factory = factory()
        factory.licensing.status()
        clock.millis = afterTrial
        factory.licensing.recordPurchased(true)
        assertTrue(factory.licensing.status().canAddItems)
        factory.licensing.recordPurchased(false)
        assertFalse(factory.licensing.status().canAddItems)
    }

    @Test
    fun the_total_wipe_does_not_start_the_trial_again() = runTest {
        factory().licensing.status()
        clock.millis = afterTrial
        // A new core over an empty database, as after H6, keeping only what the wipe leaves.
        val status = factory().licensing.status()
        assertFalse(status.canAddItems)
        assertEquals(start, store.value)
    }

    @Test
    fun the_wipe_leaves_the_date_and_takes_the_purchase_state() = runTest {
        val directory = testTempDirectory()
        val files = createFileStore(directory)
        val fileStore = FileLicenseStore(files)
        val factory = factory(fileStore, directory)
        factory.licensing.status()
        factory.licensing.recordPurchased(true)

        factory.wipeAllData()

        assertEquals(start, FileLicenseStore(createFileStore(directory)).trialStartMillis())
        assertFalse(factory(fileStore, directory).licensing.status().purchased, "asked of the store again")
    }

    @Test
    fun a_damaged_date_file_counts_as_no_date() = runTest {
        val directory = testTempDirectory()
        val files = createFileStore(directory)
        files.write(FileLicenseStore.PATH, "not a date".encodeToByteArray())
        assertNull(FileLicenseStore(files).trialStartMillis())
    }

    @Test
    fun importing_an_export_after_a_reinstall_does_not_start_the_trial_again_U8() = runTest {
        val source = factory(MemoryStore(), testTempDirectory())
        assertIs<CaptureOutcome.Recorded>(source.say("passeport @ bureau"))
        val archive = source.exportService.export()

        // Seven months later, on a reinstalled app: a new trial, until the export comes back.
        clock.millis = afterTrial
        val directory = testTempDirectory()
        val reinstalled = factory(MemoryStore(), directory)
        assertTrue(reinstalled.licensing.status().canAddItems)
        val bytes =
            createFileStore(source.config.dataDirectory).read(archive.relativePath) ?: error("no export")
        createFileStore(directory).write("imports/export.zip", bytes)
        assertIs<ImportResult.Done>(reinstalled.importService.import("imports/export.zip"))

        val status = reinstalled.licensing.status()
        assertFalse(status.canAddItems)
        assertEquals(millis("2027-05-07T10:00:01Z"), status.trialEndMillis, "from the first event of the journal")
    }

    @Test
    fun an_export_imported_by_mistake_stops_counting_once_erased() = runTest {
        val foreign = factory(MemoryStore(), testTempDirectory())
        assertIs<CaptureOutcome.Recorded>(foreign.say("passeport @ bureau"))
        val archive = foreign.exportService.export()
        val bytes = createFileStore(foreign.config.dataDirectory).read(archive.relativePath) ?: error("no export")

        clock.millis = afterTrial
        val directory = testTempDirectory()
        val mine = factory(store, directory)
        mine.licensing.status()
        val firstLaunch = store.value
        createFileStore(directory).write("imports/export.zip", bytes)
        assertIs<ImportResult.Done>(mine.importService.import("imports/export.zip"))
        assertFalse(mine.licensing.status().canAddItems, "the old journal counts while it is here")
        assertEquals(firstLaunch, store.value, "and is never stored")

        mine.wipeAllData()

        assertTrue(factory(store, testTempDirectory()).licensing.status().canAddItems)
    }

    @Test
    fun an_absurd_date_in_a_journal_does_not_end_the_trial() = runTest {
        val factory = factory()
        factory.licensing.status()
        clock.millis = 1_000L // a phone whose clock reads 1970 (the import accepts such a date)
        val cave = factory.placeCommands.create("cave", kind = PlaceKind.ROOT, source = EventSource.TEXT)
        assertIs<CommandResult.Done>(cave)
        clock.millis = start + 1_000L
        assertTrue(factory.licensing.status().canAddItems)
        assertEquals(start, store.value)
    }

    @Test
    fun a_date_ahead_of_the_clock_does_not_push_the_start_forward() = runTest {
        val factory = factory()
        clock.millis = start + 1_000_000L
        val cave = factory.placeCommands.create("cave", kind = PlaceKind.ROOT, source = EventSource.TEXT)
        assertIs<CommandResult.Done>(cave)
        clock.millis = start
        factory.licensing.status()
        assertEquals(start, store.value)
    }

    @Test
    fun the_date_signed_by_the_store_only_moves_the_start_back() = runTest {
        val factory = factory()
        factory.licensing.status()
        val earlier = start - 1_000_000L
        factory.licensing.recordStoreDate(earlier)
        assertEquals(earlier, store.value)
        factory.licensing.recordStoreDate(start)
        assertEquals(earlier, store.value)
        factory.licensing.recordStoreDate(1_000L)
        assertEquals(earlier, store.value, "older than the app itself: ignored")
        factory.licensing.recordStoreDate(start + 86_400_000L)
        assertEquals(earlier, store.value, "in the future: ignored")
    }

    @Test
    fun without_a_store_nothing_is_ever_blocked() = runTest {
        val factory = factory(licenseStore = null)
        clock.millis = afterTrial
        assertIs<CommandResult.Done>(factory.itemCommands.create(ItemDraft("tente"), source = EventSource.TEXT))
    }

    @Test
    fun a_new_item_is_refused_after_the_trial_with_nothing_written() = runTest {
        val factory = factory()
        factory.licensing.status()
        clock.millis = afterTrial
        val before = factory.eventStore.count()

        val command = factory.itemCommands.create(ItemDraft("tente"), source = EventSource.TEXT)
        assertEquals(Rejection.TRIAL_ENDED, assertIs<CommandResult.Rejected>(command).reason)
        val capture = factory.say("tente @ grenier")
        assertEquals(CaptureProblem.TRIAL_ENDED, assertIs<CaptureOutcome.Refused>(capture).problem)

        assertEquals(before, factory.eventStore.count(), "not even the new place")
        assertTrue(factory.placeRepository.observeChildren(null).first().isEmpty())
    }

    @Test
    fun a_sentence_with_a_known_and_a_new_object_is_refused_whole() = runTest {
        val factory = factory()
        assertIs<CaptureOutcome.Recorded>(factory.say("passeport @ bureau"))
        clock.millis = afterTrial
        val before = factory.eventStore.count()
        val proposal = factory.capture.propose("passeport et tente @ bureau")
        val known = proposal.targets.indexOfFirst { it.mention.name == "passeport" }
        val existing = proposal.targets[known].matches.first().itemId

        val outcome = factory.capture.commit(proposal, null, listOf(ItemChoice(known, existing)))

        assertEquals(CaptureProblem.TRIAL_ENDED, assertIs<CaptureOutcome.Refused>(outcome).problem)
        assertEquals(before, factory.eventStore.count())
    }

    @Test
    fun everything_but_a_new_item_stays_free_after_the_trial() = runTest {
        val factory = factory()
        assertIs<CaptureOutcome.Recorded>(factory.say("passeport @ bureau"))
        val itemId = factory.itemRepository.observeActive().first().single().item.id
        clock.millis = afterTrial
        assertFalse(factory.licensing.status().canAddItems)

        // Sentences on what exists.
        assertIs<CaptureOutcome.Recorded>(factory.say("je prends le passeport"))
        assertIs<CaptureOutcome.Recorded>(factory.say("je remets le passeport"))
        assertIs<CaptureOutcome.Recorded>(factory.say("le passeport est maintenant dans le garage"))
        val again = factory.capture.propose("passeport @ cuisine")
        val place = (again.place as? PlaceResolution.Unknown)?.let { PlaceChoice.CreateNew(it.names, null, false) }
        val stored = factory.capture.commit(again, place, listOf(ItemChoice(0, itemId)))
        assertIs<CaptureOutcome.Recorded>(stored, "storing a known object again creates nothing")

        // Commands: edit, bin, restore.
        val renamed = factory.itemCommands.update(itemId, ItemPatch(name = "vieux passeport"), EventSource.TEXT)
        assertIs<CommandResult.Done>(renamed)
        assertIs<CommandResult.Done>(factory.itemCommands.delete(itemId, EventSource.TEXT))
        assertIs<CommandResult.Done>(factory.itemCommands.restore(itemId, EventSource.TEXT))
    }
}
