package app.trouvetout.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventJsonTest {
    private val samples: List<EventPayload> =
        listOf(
            EventPayload.ItemCreated(
                "i1",
                "passeport",
                description = "rouge",
                tags = listOf("papiers"),
                sensitive = true,
            ),
            EventPayload.ItemUpdated("i1", name = "passeport FR", clearDescription = true, pinned = true),
            EventPayload.ItemStored("i1", placeId = "p1"),
            EventPayload.ItemStored("i1", containerId = "c1"),
            EventPayload.ItemTaken("i1"),
            EventPayload.ItemReturned("i1"),
            EventPayload.ItemLent("i1", to = "Marc"),
            EventPayload.ItemMovedTo("i1", placeId = "p2"),
            EventPayload.ItemArchived("i1", archived = false),
            EventPayload.ItemDeleted("i1"),
            EventPayload.ItemRestored("i1"),
            EventPayload.PlaceCreated("p1", "cave", kind = PlaceKind.ROOT, aliases = listOf("sous-sol")),
            EventPayload.PlaceRenamed("p1", "la cave", aliases = listOf("sous-sol", "cellier")),
            EventPayload.PlaceMerged("p1", "p2"),
            EventPayload.PlaceDeleted("p1"),
            EventPayload.ContainerCreated("c1", "Carton 7", placeId = "p1", number = 7),
            EventPayload.ContainerMoved("c1", placeId = "p2"),
            EventPayload.ContainerDeleted("c1"),
            EventPayload.PhotoAttached(
                "ph1",
                PhotoOwnerType.ITEM,
                "i1",
                "ph1.jpg",
                "ph1_t.jpg",
                1200,
                900,
                "ab" + "cd",
            ),
            EventPayload.PhotoDetached("ph1"),
            EventPayload.ReminderSet("r1", "i1", fireAtEpochMillis = 1_800_000_000_000L, kind = ReminderKind.CHECK),
            EventPayload.ReminderDone("r1"),
        )

    @Test
    fun should_round_trip_every_payload_type() {
        for (payload in samples) {
            val json = EventJson.encodePayload(payload)
            assertEquals(payload, EventJson.decodePayload(json), json)
        }
    }

    @Test
    fun should_write_the_type_discriminator_matching_typeName() {
        for (payload in samples) {
            val json = EventJson.encodePayload(payload)
            assertTrue(json.contains("\"type\":\"${payload.typeName}\""), json)
        }
    }

    @Test
    fun should_ignore_unknown_fields_from_newer_versions() {
        val json = """{"type":"ItemStored","itemId":"i1","placeId":"p1","futureField":{"a":1},"anotherOne":"x"}"""
        assertEquals(EventPayload.ItemStored("i1", placeId = "p1"), EventJson.decodePayload(json))
    }

    @Test
    fun should_apply_defaults_for_missing_optional_fields() {
        val json = """{"type":"ItemCreated","itemId":"i1","name":"clés"}"""
        assertEquals(EventPayload.ItemCreated("i1", "clés"), EventJson.decodePayload(json))
    }

    @Test
    fun should_expose_every_touched_record_as_subject() {
        val stored = EventPayload.ItemStored("i1", containerId = "c1")
        assertEquals(
            listOf(
                EventSubject(EventSubject.SubjectType.ITEM, "i1"),
                EventSubject(EventSubject.SubjectType.CONTAINER, "c1"),
            ),
            stored.subjects,
        )
        val merged = EventPayload.PlaceMerged("p1", "p2")
        assertEquals(2, merged.subjects.size)
    }

    @Test
    fun should_turn_an_unknown_type_into_an_unknown_payload_that_round_trips_untouched() {
        val json = """{"type":"ItemTeleported","itemId":"i1","to":"Mars"}"""
        val payload = EventJson.decodePayload(json)
        assertEquals(EventPayload.Unknown("ItemTeleported", json), payload)
        assertEquals(json, EventJson.encodePayload(payload))
        assertTrue(payload.subjects.isEmpty())
    }

    @Test
    fun should_not_throw_on_malformed_or_non_object_payloads() {
        assertEquals(EventPayload.Unknown.UNREADABLE, EventJson.decodePayload("not json").typeName)
        assertEquals(EventPayload.Unknown.UNREADABLE, EventJson.decodePayload("[1,2]").typeName)
        // Known type, missing required field: kept, not crashed on.
        val broken = EventJson.decodePayload("""{"type":"ItemStored"}""")
        assertEquals("ItemStored", broken.typeName)
        assertTrue(broken is EventPayload.Unknown)
    }

    @Test
    fun should_parse_unknown_sources_as_system() {
        assertEquals(EventSource.VOICE, EventSource.parse("VOICE"))
        assertEquals(EventSource.SYSTEM, EventSource.parse("TELEPATHY"))
    }

    @Test
    fun should_keep_business_data_out_of_event_toString() {
        val event =
            Event(
                "01E",
                kotlin.time.Instant.fromEpochMilliseconds(0),
                "dev",
                payload = EventPayload.ItemCreated("i1", "passeport"),
                source = EventSource.VOICE,
                rawInput = "passeport dans la commode",
            )
        val text = event.toString()
        assertTrue("passeport" !in text, text)
        assertTrue("commode" !in text, text)
        assertTrue("ItemCreated" in text && "01E" in text, text)
    }
}
