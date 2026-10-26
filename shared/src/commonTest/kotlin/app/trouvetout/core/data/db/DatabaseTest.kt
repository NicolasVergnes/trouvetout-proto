package app.trouvetout.core.data.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatabaseTest {
    @Test
    fun should_create_the_schema_and_record_its_version() {
        val db = openTestDatabase()
        assertEquals(SCHEMA_VERSION.toString(), db.metaQueries.get(MetaKeys.SCHEMA_VERSION).executeAsOne())
        assertEquals(0L, db.eventQueries.count().executeAsOne())
        assertEquals(0L, db.itemQueries.countActive().executeAsOne())
        assertEquals(0L, db.placeQueries.countActive().executeAsOne())
    }

    @Test
    fun should_store_and_replace_meta_values() {
        val db = openTestDatabase()
        assertNull(db.metaQueries.get(MetaKeys.DEVICE_ID).executeAsOneOrNull())
        db.metaQueries.set(MetaKeys.DEVICE_ID, "device-a")
        db.metaQueries.set(MetaKeys.DEVICE_ID, "device-b")
        assertEquals("device-b", db.metaQueries.get(MetaKeys.DEVICE_ID).executeAsOne())
        db.metaQueries.remove(MetaKeys.DEVICE_ID)
        assertNull(db.metaQueries.get(MetaKeys.DEVICE_ID).executeAsOneOrNull())
    }
}
