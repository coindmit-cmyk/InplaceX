package com.mirkori.inplacex.backend.identity

import com.mirkori.inplacex.backend.session.codec.BoundedJsonScanner
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Application.configureIdentityRoutes(service: GuestIdentityService) {
    val codec = IdentityJsonCodec()
    routing {
        post("/api/v1/auth/bootstrap") {
            if (!call.hasValidIdempotencyKey()) {
                call.respondIdentityError(HttpStatusCode.BadRequest, "invalid_idempotency_key")
                return@post
            }
            val command = runCatching { codec.decodeBootstrap(call.receiveText()) }.getOrElse {
                call.respondIdentityError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            val result = runCatching { service.bootstrap(command) }.getOrElse {
                call.respondIdentityError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            call.respondText(
                text = codec.encodeBootstrap(result),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            )
        }

        post("/api/v1/auth/refresh") {
            if (!call.hasValidIdempotencyKey()) {
                call.respondIdentityError(HttpStatusCode.BadRequest, "invalid_idempotency_key")
                return@post
            }
            val refreshToken = runCatching { codec.decodeRefresh(call.receiveText()) }.getOrElse {
                call.respondIdentityError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            val credentials = try {
                service.refresh(refreshToken)
            } catch (_: RefreshTokenRejectedException) {
                call.respondIdentityError(HttpStatusCode.Unauthorized, "refresh_rejected")
                return@post
            } catch (_: IllegalArgumentException) {
                call.respondIdentityError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            call.respondText(
                text = codec.encodeCredentials(credentials),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            )
        }
    }
}

private class IdentityJsonCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun decodeBootstrap(source: String): GuestBootstrapCommand {
        val value = decodeObject(
            source = source,
            requiredFields = BootstrapRequiredFields,
            allowedFields = BootstrapAllowedFields,
        )
        return GuestBootstrapCommand(
            installationId = value.requiredString("installationId", 128),
            platform = when (value.requiredString("platform", 16)) {
                "android" -> GuestPlatform.ANDROID
                "ios" -> GuestPlatform.IOS
                "desktop" -> GuestPlatform.DESKTOP
                "unknown" -> GuestPlatform.UNKNOWN
                else -> throw IllegalArgumentException("unsupported platform")
            },
            appVersion = value.optionalString("appVersion", 64),
            locale = value.optionalString("locale", 16),
            regionHint = value.optionalString("regionHint", 16),
        )
    }

    fun decodeRefresh(source: String): String =
        decodeObject(source, RefreshFields, RefreshFields).requiredString("refreshToken", 512)

    fun encodeBootstrap(result: GuestBootstrapResult): String = buildJsonObject {
        put("playerId", result.playerId)
        put("accountKind", result.accountKind)
        put("credentials", credentialsJson(result.credentials))
    }.toString()

    fun encodeCredentials(credentials: RenewableCredentials): String =
        credentialsJson(credentials).toString()

    private fun credentialsJson(credentials: RenewableCredentials): JsonObject = buildJsonObject {
        put("accessToken", credentials.accessToken)
        put("refreshToken", credentials.refreshToken)
        put("accessExpiresAtEpochMs", credentials.accessExpiresAt.toEpochMilli())
        put("refreshExpiresAtEpochMs", credentials.refreshExpiresAt.toEpochMilli())
    }

    private fun decodeObject(
        source: String,
        requiredFields: Set<String>,
        allowedFields: Set<String>,
    ): JsonObject {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= MaximumIdentityBodyBytes)
        BoundedJsonScanner(json).requireSafeStructure(source)
        val value = json.parseToJsonElement(source) as? JsonObject
            ?: throw IllegalArgumentException("request must be an object")
        require(value.keys.containsAll(requiredFields) && value.keys.all(allowedFields::contains))
        return value
    }

    private fun JsonObject.requiredString(name: String, maximum: Int): String =
        optionalString(name, maximum) ?: throw IllegalArgumentException("$name is required")

    private fun JsonObject.optionalString(name: String, maximum: Int): String? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("$name must be a string")
        require(primitive.isString)
        return primitive.content.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }
            ?: throw IllegalArgumentException("$name has invalid length")
    }

    private companion object {
        val BootstrapRequiredFields = setOf("installationId", "platform")
        val BootstrapAllowedFields = setOf(
            "installationId",
            "platform",
            "appVersion",
            "locale",
            "regionHint",
        )
        val RefreshFields = setOf("refreshToken")
        const val MaximumIdentityBodyBytes = 16 * 1024
    }
}

private fun io.ktor.server.application.ApplicationCall.hasValidIdempotencyKey(): Boolean {
    val value = request.headers[IdempotencyHeader] ?: return false
    return value.matches(Regex("[A-Za-z0-9._~-]{1,128}"))
}

private suspend fun io.ktor.server.application.ApplicationCall.respondIdentityError(
    status: HttpStatusCode,
    code: String,
) {
    respondText(
        text = buildJsonObject { put("error", code) }.toString(),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

private const val IdempotencyHeader = "Idempotency-Key"
