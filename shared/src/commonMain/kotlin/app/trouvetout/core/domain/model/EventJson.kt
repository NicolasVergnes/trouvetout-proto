package app.trouvetout.core.domain.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON codec of the journal payloads, shared by the database and the export format (A7, N9).
 * Unknown keys are ignored, and a payload whose type is unknown (written by a newer version) or that
 * cannot be decoded becomes an [EventPayload.Unknown] carrying its raw JSON: it is kept in the journal,
 * ignored by the projection and written back untouched by the export. Decoding never throws and never
 * puts payload text (business data) in an exception message.
 */
public object EventJson {
    public val format: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }

    public fun encodePayload(payload: EventPayload): String = when (payload) {
        is EventPayload.Unknown -> payload.rawJson
        else -> format.encodeToString(EventPayload.serializer(), payload)
    }

    public fun decodePayload(json: String): EventPayload = try {
        format.decodeFromString(EventPayload.serializer(), json)
    } catch (_: SerializationException) {
        unknown(json)
    } catch (_: IllegalArgumentException) {
        unknown(json)
    }

    private fun unknown(json: String): EventPayload.Unknown {
        val type =
            try {
                (format.parseToJsonElement(json) as? JsonObject)?.get("type")?.jsonPrimitive?.content
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        return EventPayload.Unknown(typeName = type ?: EventPayload.Unknown.UNREADABLE, rawJson = json)
    }

    /** Validates that [json] is a JSON object (used by import before storing a raw payload). */
    public fun isJsonObject(json: String): Boolean = try {
        format.parseToJsonElement(json).jsonObject
        true
    } catch (_: SerializationException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
