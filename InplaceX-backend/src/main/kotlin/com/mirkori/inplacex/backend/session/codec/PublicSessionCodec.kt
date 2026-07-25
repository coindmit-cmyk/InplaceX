package com.mirkori.inplacex.backend.session.codec

import com.mirkori.inplacex.backend.session.contract.PUBLIC_SESSION_SCHEMA_VERSION
import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionEvent
import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionResult
import com.mirkori.inplacex.backend.session.contract.PublicDuelSessionSnapshot
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object PublicSessionCodec {
    fun encodeSnapshot(snapshot: PublicDuelSessionSnapshot): String =
        CanonicalPublicJson.encodeFrame(PublicSnapshotJson.encode(snapshot))

    fun decodeSnapshot(json: String): PublicDuelSessionSnapshot =
        PublicSnapshotJson.decode(CanonicalPublicJson.parseFrame(json))

    fun encodeEvent(event: PublicDuelSessionEvent): String =
        CanonicalPublicJson.encodeFrame(PublicEventJson.encode(event))

    fun decodeEvent(json: String): PublicDuelSessionEvent =
        PublicEventJson.decode(CanonicalPublicJson.parseFrame(json))

    fun encodeResult(result: PublicDuelSessionResult): String =
        CanonicalPublicJson.encodeFrame(PublicResultJson.encode(result))
}

internal fun typedFrame(type: String, payload: JsonObject): JsonObject = publicJsonObject(
    "schemaVersion" to JsonPrimitive(PUBLIC_SESSION_SCHEMA_VERSION),
    "type" to JsonPrimitive(type),
    "payload" to payload,
)

internal fun decodeTypedFrame(element: JsonElement): Pair<String, JsonObject> {
    val frame = element.requireObject()
    frame.requireExactFields(setOf("schemaVersion", "type", "payload"))
    require(frame.requiredString("schemaVersion") == PUBLIC_SESSION_SCHEMA_VERSION) {
        "Unsupported public session schema version"
    }
    return frame.requiredString("type") to frame.getValue("payload").requireObject()
}
