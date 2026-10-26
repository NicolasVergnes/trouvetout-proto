package app.trouvetout.core.data.db

import app.trouvetout.core.domain.model.EventJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Tags and aliases are stored as JSON arrays of strings in TEXT columns. */
internal object JsonLists {
    private val serializer = ListSerializer(String.serializer())

    fun encode(values: List<String>): String = EventJson.format.encodeToString(serializer, values)

    fun decode(json: String): List<String> =
        if (json.isBlank()) emptyList() else EventJson.format.decodeFromString(serializer, json)
}
