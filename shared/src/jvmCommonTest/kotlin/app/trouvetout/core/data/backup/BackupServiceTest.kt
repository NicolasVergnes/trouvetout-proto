package app.trouvetout.core.data.backup

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.inMemoryDriverFactory
import app.trouvetout.core.data.photo.FakeImageCodec
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.licensing.FileLicenseStore
import app.trouvetout.core.platform.CoroutineDispatchers
import app.trouvetout.core.platform.DeviceInfo
import app.trouvetout.core.platform.createFileStore
import app.trouvetout.core.platform.testTempDirectory
import app.trouvetout.core.platform.use
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A9, ADR-025: what the file protects against, the round trip, when it runs. */
@Suppress("TooManyFunctions") // one behaviour per test
class BackupServiceTest {
    private object FakeDevice : DeviceInfo {
        override val totalRamBytes: Long = 4L * 1024 * 1024 * 1024
        override val osName: String = "test"
        override val osVersion: String = "0"
    }

    private val crypto = JavaBackupCrypto()
    private val clock = TickingClock(1_800_000_000_000L)
    private val day = 24L * 60 * 60 * 1000

    private fun factory(directory: String = testTempDirectory()): SharedFactory {
        val config = SharedConfig(directory, FakeDevice, inMemoryDriverFactory(), FakeImageCodec())
        config.clock = clock
        config.random = Random(42)
        config.backupCrypto = crypto
        config.dispatchers =
            CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
        return SharedFactory(config)
    }

    private suspend fun SharedFactory.add(name: String) {
        clock.tick()
        itemCommands.create(ItemDraft(name), source = EventSource.TEXT)
    }

    /** A running backup: returns the encrypted bytes and the key. */
    private suspend fun SharedFactory.backUp(): Pair<ByteArray, String> {
        val key = backup.newRecoveryKey()
        assertTrue(backup.enable(key, "content://chosen"))
        val ready = assertIs<PreparedBackup.Ready>(backup.prepare())
        val bytes = File(ready.absolutePath).readBytes()
        backup.finished(ready, succeeded = true)
        return bytes to key
    }

    private suspend fun SharedFactory.restoreBytes(
        bytes: ByteArray,
        key: String,
    ): BackupRestoreResult {
        createFileStore(config.dataDirectory).write("imports/backup", bytes)
        return backup.restore("imports/backup", key)
    }

    @Test
    fun should_restore_everything_on_a_new_phone_with_the_key_U8() = runTest {
        val source = factory()
        source.add("passeport")
        source.add("tente")
        val (bytes, key) = source.backUp()

        val newPhone = factory()
        assertIs<BackupRestoreResult.Imported>(newPhone.restoreBytes(bytes, key))

        val names = newPhone.itemRepository.observeActive().first().map { it.item.name }
        assertEquals(setOf("passeport", "tente"), names.toSet())
        val leftovers = createFileStore(newPhone.config.dataDirectory).list("imports")
        assertTrue(leftovers.isEmpty(), "both private copies are gone: $leftovers")
    }

    @Test
    fun should_keep_nothing_readable_in_the_file() = runTest {
        val source = factory()
        source.add("passeport-tres-secret")
        val (bytes, _) = source.backUp()
        val text = bytes.decodeToString()
        assertFalse("passeport-tres-secret" in text)
        assertFalse("manifest.json" in text, "not even the names of the entries")
        assertTrue(text.startsWith("TTBK"))
    }

    @Test
    fun should_leave_no_plain_archive_behind() = runTest {
        val source = factory()
        source.add("passeport")
        val key = source.backup.newRecoveryKey()
        source.backup.enable(key, "content://chosen")
        val ready = assertIs<PreparedBackup.Ready>(source.backup.prepare())
        val store = createFileStore(source.config.dataDirectory)
        assertTrue(store.list("exports").isEmpty(), "the export is gone once encrypted")
        source.backup.finished(ready, succeeded = true)
        assertTrue(store.list(BackupService.DIRECTORY).isEmpty(), "the encrypted copy after the copy")
    }

    @Test
    fun should_refuse_a_wrong_key_and_say_when_it_is_a_typing_mistake() = runTest {
        val source = factory()
        source.add("passeport")
        val (bytes, key) = source.backUp()
        val target = factory()

        assertIs<BackupRestoreResult.CannotOpen>(target.restoreBytes(bytes, target.backup.newRecoveryKey()))
        val mistaken = (if (key[0] == 'A') 'B' else 'A') + key.drop(1)
        assertIs<BackupRestoreResult.BadKey>(target.restoreBytes(bytes, mistaken))
        assertTrue(target.itemRepository.observeActive().first().isEmpty())
    }

    @Test
    fun should_refuse_a_file_altered_truncated_extended_or_reordered() = runTest {
        val source = factory()
        // Over two blocks: random bytes do not compress, and photos go in as they are.
        val store = source.photoStore
        source.add("passeport")
        store.store("3000x1500".encodeToByteArray())
        val big = ByteArray(BackupFormat.BLOCK_BYTES * 2 + 100).also(Random(5)::nextBytes)
        val name = "01ARZ3NDEKTSV4RRFFQ69G5FAV.jpg"
        createFileStore(source.config.dataDirectory).write("photos/$name", big)
        val (bytes, key) = source.backUp()
        assertTrue(bytes.size > BackupFormat.BLOCK_BYTES * 2, "three blocks at least: ${bytes.size}")
        val header = 41
        val sealedBlock = BackupFormat.BLOCK_BYTES + 16

        val altered = bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2] + 1).toByte() }
        val headerAltered = bytes.copyOf().also { it[header - 1] = (it[header - 1] + 1).toByte() }
        val truncatedAtABlock = bytes.copyOf(header + sealedBlock)
        val truncated = bytes.copyOf(bytes.size - 1)
        val extended = bytes + ByteArray(32)
        val reordered =
            bytes.copyOf().also {
                bytes.copyInto(it, header, header + sealedBlock, header + 2 * sealedBlock)
                bytes.copyInto(it, header + sealedBlock, header, header + sealedBlock)
            }
        for ((label, file) in listOf(
            "altered" to altered,
            "header" to headerAltered,
            "truncated at a block" to truncatedAtABlock,
            "truncated" to truncated,
            "extended" to extended,
            "reordered" to reordered,
        )) {
            val target = factory()
            assertIs<BackupRestoreResult.CannotOpen>(target.restoreBytes(file, key), label)
            assertTrue(target.itemRepository.observeActive().first().isEmpty(), label)
            assertTrue(createFileStore(target.config.dataDirectory).list("imports").isEmpty(), label)
        }
    }

    @Test
    fun should_say_when_a_file_is_not_a_backup() = runTest {
        val target = factory()
        val key = target.backup.newRecoveryKey()
        assertIs<BackupRestoreResult.NotABackup>(target.restoreBytes("PK not a backup".encodeToByteArray(), key))
        val other = ByteArray(100).also(Random(1)::nextBytes)
        assertIs<BackupRestoreResult.NotABackup>(target.restoreBytes(other, key))
    }

    /** A file written from `docs/format-export.md` alone by another program: backups of years ago must still open. */
    @Test
    fun should_open_a_frozen_file_written_from_the_published_format() = runTest {
        val file =
            (
                "5454424b0100100000404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f" +
                    "63ec0973d384bfea19a074c48d126a7c6e9a92d4ff1c3e21e5973da5e16d4aa9e519cc1838543c306f74" +
                    "90ccd82ad729961289"
            ).hexToByteArray()
        val key = RecoveryKey.decode("208H-44RM-2MB1-E60S-38DH-R78Y-3ZY0")
        assertContentEquals(ByteArray(16) { (0x10 + it).toByte() }, key)
        val directory = testTempDirectory()
        val store = createFileStore(directory)
        store.write("frozen", file)
        val format = BackupFormat(crypto)

        store.sink("opened").use { sink ->
            checkNotNull(store.source("frozen")).use { source -> format.decrypt(this, checkNotNull(key), source, sink) }
        }

        assertEquals("TrouveTout backup format v1, frozen", store.read("opened")?.decodeToString())
    }

    @Test
    fun a_cancelled_export_leaves_nothing_plain_behind() = runTest {
        val source = factory()
        source.add("passeport")
        source.backup.enable(source.backup.newRecoveryKey(), "content://chosen")
        val store = createFileStore(source.config.dataDirectory)

        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            cancel()
            source.backup.prepare()
        }
        job.join()

        assertTrue(store.list(BackupService.DIRECTORY).isEmpty(), store.list(BackupService.DIRECTORY).toString())
        assertTrue(store.list("exports").isEmpty())
        assertNull(source.backup.state().problem, "leaving the app is no failure")
    }

    @Test
    fun what_a_killed_process_left_goes_at_the_next_launch() = runTest {
        val directory = testTempDirectory()
        val source = factory(directory)
        val store = createFileStore(directory)
        store.write("${BackupService.DIRECTORY}/archive.zip", "journal in the clear".encodeToByteArray())

        source.housekeeping()

        assertTrue(store.list(BackupService.DIRECTORY).isEmpty())
    }

    @Test
    fun the_launch_does_not_clear_a_backup_running_now() = runTest {
        val source = factory()
        source.add("passeport")
        source.backup.enable(source.backup.newRecoveryKey(), "content://chosen")
        val ready = assertIs<PreparedBackup.Ready>(source.backup.prepare())

        source.housekeeping()

        assertTrue(File(ready.absolutePath).isFile)
        source.backup.finished(ready, succeeded = true)
    }

    @Test
    fun a_copy_cut_by_a_killed_process_is_reported_and_tried_again() = runTest {
        val directory = testTempDirectory()
        val driver = inMemoryDriverFactory().create()
        val config = {
            SharedConfig(directory, FakeDevice, { driver }, FakeImageCodec()).also {
                it.clock = clock
                it.backupCrypto = crypto
                it.dispatchers =
                    CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
            }
        }
        val before = SharedFactory(config())
        before.add("passeport")
        before.backup.enable(before.backup.newRecoveryKey(), "content://chosen")
        before.backup.finished(assertIs(before.backup.prepare()), succeeded = true)
        assertNull(before.backup.state().problem)
        clock.millis += day
        before.add("tente")
        assertIs<PreparedBackup.Ready>(before.backup.prepare())
        before.backup.copying()
        assertNull(before.backup.state().problem, "running: nothing to say yet")

        // The process dies here; the next one finds the mark.
        val after = SharedFactory(config())
        after.warmUp()

        assertEquals(BackupProblem.FAILED, after.backup.state().problem)
        assertTrue(after.backup.isDue(), "tried again, even with nothing new")
        assertNotNull(after.backup.state().lastSuccessMillis, "the date is still said, beside the problem")
    }

    @Test
    fun a_failed_export_is_said_and_keeps_nothing() = runTest {
        val source = factory()
        source.add("passeport")
        source.backup.enable(source.backup.newRecoveryKey(), "content://chosen")
        val store = createFileStore(source.config.dataDirectory)
        // A directory where the encrypted file goes: the write fails.
        store.write("${BackupService.DIRECTORY}/backup.ttbk/x", ByteArray(1))

        assertFails { source.backup.prepare() }

        assertEquals(BackupProblem.FAILED, source.backup.state().problem)
        assertFalse(store.exists("${BackupService.DIRECTORY}/archive.zip"))
    }

    @Test
    fun a_lost_destination_waits_for_another_file_with_the_same_key() = runTest {
        val source = factory()
        source.add("passeport")
        source.backup.enable(source.backup.newRecoveryKey(), "content://chosen")
        source.backup.reportDestinationLost()
        assertEquals(BackupProblem.DESTINATION_LOST, source.backup.state().problem)
        clock.millis += day
        assertFalse(source.backup.isDue(), "no export for a file out of reach")

        source.backup.relocate("content://other")

        assertEquals("content://other", source.backup.state().location)
        assertNull(source.backup.state().problem)
        assertTrue(source.backup.isDue())
        assertIs<PreparedBackup.Ready>(source.backup.prepare())
    }

    @Test
    fun confirming_the_end_of_a_key_forgives_what_a_key_forgives() {
        val source = factory()
        assertTrue(source.backup.confirmsKeyEnd("AAAA-BBBB-1000", "1ooo"))
        assertTrue(source.backup.confirmsKeyEnd("AAAA-BBBB-1000", " 1000 "))
        assertFalse(source.backup.confirmsKeyEnd("AAAA-BBBB-1000", "BBBB"))
        assertFalse(source.backup.confirmsKeyEnd("AAAA-BBBB-1000", ""))
    }

    @Test
    fun should_run_only_when_on_after_a_change_and_once_a_day() = runTest {
        val source = factory()
        source.add("passeport")
        assertFalse(source.backup.isDue(), "off")
        val key = source.backup.newRecoveryKey()
        source.backup.enable(key, "content://chosen")
        assertTrue(source.backup.isDue(), "never done")

        source.backup.finished(assertIs(source.backup.prepare()), succeeded = true)
        assertFalse(source.backup.isDue(), "nothing new")

        source.add("tente")
        assertFalse(source.backup.isDue(), "within the day")
        clock.millis += day
        assertTrue(source.backup.isDue())

        source.backup.finished(assertIs(source.backup.prepare()), succeeded = false)
        assertEquals(BackupProblem.FAILED, source.backup.state().problem)
        assertFalse(source.backup.isDue(), "not a whole export at each return")
        clock.millis += 60 * 60 * 1000
        assertTrue(source.backup.isDue(), "a failure tries again")
        assertNotNull(source.backup.state().lastSuccessMillis)
    }

    @Test
    fun older_events_merged_by_an_import_count_as_new() = runTest {
        val other = factory()
        other.add("tente")
        val archive = other.exportService.export()
        val bytes = createFileStore(other.config.dataDirectory).read(archive.relativePath) ?: error("no export")

        clock.millis += day
        val source = factory()
        source.add("passeport")
        val key = source.backup.newRecoveryKey()
        source.backup.enable(key, "content://chosen")
        source.backup.finished(assertIs(source.backup.prepare()), succeeded = true)
        createFileStore(source.config.dataDirectory).write("imports/export.zip", bytes)
        source.importService.import("imports/export.zip")
        clock.millis += day

        assertTrue(source.backup.isDue(), "the head of the journal did not move, but it holds more")
    }

    @Test
    fun should_forget_the_key_when_turned_off() = runTest {
        val source = factory()
        source.add("passeport")
        val (_, _) = source.backUp()
        assertEquals("content://chosen", source.backup.state().location)
        source.backup.disable()
        assertNull(source.backup.state().location)
        assertIs<PreparedBackup.NotEnabled>(source.backup.prepare())
        assertFalse(source.backup.isDue())
    }

    @Test
    fun should_refuse_a_key_that_is_not_one() = runTest {
        val source = factory()
        assertFalse(source.backup.enable("not a key", "content://chosen"))
        assertNull(source.backup.state().location)
    }

    @Test
    fun the_wipe_takes_the_key_and_the_private_copies() = runTest {
        val directory = testTempDirectory()
        val source = factory(directory)
        source.add("passeport")
        source.backup.enable(source.backup.newRecoveryKey(), "content://chosen")
        assertIs<PreparedBackup.Ready>(source.backup.prepare())

        val removed = source.wipeAllData()

        assertTrue(BackupService.DIRECTORY in removed, removed.toString())
        assertNull(factory(directory).backup.state().location)
    }

    @Test
    fun restoring_an_old_backup_keeps_the_trial_it_had_started_U8() = runTest {
        val source = factory()
        source.add("passeport")
        val (bytes, key) = source.backUp()

        clock.millis += 200 * day
        val directory = testTempDirectory()
        val config = SharedConfig(directory, FakeDevice, inMemoryDriverFactory(), FakeImageCodec())
        config.clock = clock
        config.backupCrypto = crypto
        config.licenseStore = FileLicenseStore(createFileStore(directory))
        config.dispatchers =
            CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
        val newPhone = SharedFactory(config)
        assertTrue(newPhone.licensing.status().canAddItems)
        assertIs<BackupRestoreResult.Imported>(newPhone.restoreBytes(bytes, key))
        assertFalse(newPhone.licensing.status().canAddItems)
    }

    /** McGrew and Viega, test case 16 (AES-256, with additional data); XCTest checks CryptoKit on the same bytes. */
    @Test
    fun should_match_the_published_aes_gcm_vector() {
        val key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308".hexToByteArray()
        val nonce = "cafebabefacedbaddecaf888".hexToByteArray()
        val aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2".hexToByteArray()
        val plain =
            (
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                    "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
            ).hexToByteArray()
        val sealed =
            (
                "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                    "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                    "76fc6ece0f4e1768cddf8853bb2d551b"
            ).hexToByteArray()
        assertContentEquals(sealed, crypto.seal(key, nonce, aad, plain))
        assertContentEquals(plain, crypto.open(key, nonce, aad, sealed))
        assertNull(crypto.open(key, nonce, ByteArray(0), sealed), "other additional data")
    }
}
