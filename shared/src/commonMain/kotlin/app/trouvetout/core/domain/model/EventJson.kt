package app.trouvetout.core.domain.model

import kotlinx.serialization.json.Json

/**
 * JSON codec of the journal payloads, shared by the database and the export format (A7, N9).
 * Unknown keys are ignored so that an export written by a newer version still imports.
 */
public object EventJson {
    public val format: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }

    public fun encodePayload(payload: EventPayload): String = format.encodeToString(EventPayload.serializer(), payload)

    public fun decodePayload(json: String): EventPayload = format.decodeFromString(EventPayload.serializer(), json)
}
