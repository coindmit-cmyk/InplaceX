package com.mirkori.inplacex.platform.update

import android.content.Context
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class AndroidAppUpdatePolicyStore(context: Context) : AppUpdatePolicyStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    override fun read(): AppUpdatePolicyReadResult = try {
        if (!preferences.contains(StateKey)) {
            AppUpdatePolicyReadResult.Missing
        } else {
            preferences.getString(StateKey, null)
                ?.let(::decodeAppUpdatePolicyState)
                ?.let { AppUpdatePolicyReadResult.Available(it) }
                ?: AppUpdatePolicyReadResult.Invalid
        }
    } catch (_: Exception) {
        AppUpdatePolicyReadResult.Invalid
    }

    override fun write(state: AppUpdatePolicyState): Boolean = runCatching {
        preferences.edit()
            .putString(StateKey, encodeAppUpdatePolicyState(state))
            .commit()
    }.getOrDefault(false)

    private companion object {
        const val PreferencesName = "inplacex_app_update_policy_v2"
        const val StateKey = "state_json"
        const val SchemaVersion = 3L
    }
}

internal fun encodeAppUpdatePolicyState(state: AppUpdatePolicyState): String = buildJsonObject {
    put("schemaVersion", 3)
    put("nextCheckAfterMs", state.nextCheckAfterMs)
    put("dismissedReleaseId", state.dismissedReleaseId?.let(::JsonPrimitive) ?: JsonNull)
    put("dismissedUntilMs", state.dismissedUntilMs)
    put("requiredRelease", state.requiredRelease?.toJson() ?: JsonNull)
    put("cachedArtifact", state.cachedArtifact?.toJson() ?: JsonNull)
    put("policyRecoveryRequired", state.policyRecoveryRequired)
}.toString()

internal fun decodeAppUpdatePolicyState(source: String): AppUpdatePolicyState? = runCatching {
    val root = Json.parseToJsonElement(source).jsonObject
    require(root.keys == setOf(
        "schemaVersion",
        "nextCheckAfterMs",
        "dismissedReleaseId",
        "dismissedUntilMs",
        "requiredRelease",
        "cachedArtifact",
        "policyRecoveryRequired",
    ))
    require(root.long("schemaVersion") == 3L)
    AppUpdatePolicyState(
        nextCheckAfterMs = root.long("nextCheckAfterMs"),
        dismissedReleaseId = root.optionalString("dismissedReleaseId"),
        dismissedUntilMs = root.long("dismissedUntilMs"),
        requiredRelease = root.optionalObject("requiredRelease")?.toRelease(),
        cachedArtifact = root.optionalObject("cachedArtifact")?.toArtifact(),
        policyRecoveryRequired = root.boolean("policyRecoveryRequired"),
    )
}.getOrNull()

private fun AppUpdateRelease.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("versionName", versionName)
    put("versionCode", versionCode)
    put("minimumSupportedVersionCode", minimumSupportedVersionCode)
    put("minimumAndroidSdk", minimumAndroidSdk)
    put("publishedAt", publishedAt.toString())
    put("changelog", changelog)
    put("fileName", fileName)
    put("sizeBytes", sizeBytes)
    put("sha256", sha256)
    put("downloadUrl", downloadUrl)
    put("packageName", packageName)
    put("signingCertificateSha256Fingerprints", stringArray(signingCertificateSha256Fingerprints))
}

private fun JsonObject.toRelease(): AppUpdateRelease {
    require(keys == setOf(
        "id",
        "versionName",
        "versionCode",
        "minimumSupportedVersionCode",
        "minimumAndroidSdk",
        "publishedAt",
        "changelog",
        "fileName",
        "sizeBytes",
        "sha256",
        "downloadUrl",
        "packageName",
        "signingCertificateSha256Fingerprints",
    ))
    return AppUpdateRelease(
        id = string("id"),
        versionName = string("versionName"),
        versionCode = long("versionCode"),
        minimumSupportedVersionCode = long("minimumSupportedVersionCode"),
        minimumAndroidSdk = long("minimumAndroidSdk").toIntExact(),
        publishedAt = Instant.parse(string("publishedAt")),
        changelog = string("changelog"),
        fileName = string("fileName"),
        sizeBytes = long("sizeBytes"),
        sha256 = string("sha256"),
        downloadUrl = string("downloadUrl"),
        packageName = string("packageName"),
        signingCertificateSha256Fingerprints = stringList("signingCertificateSha256Fingerprints"),
    )
}

private fun VerifiedAppUpdateArtifact.toJson(): JsonObject = buildJsonObject {
    put("releaseId", releaseId)
    put("cacheFileName", cacheFileName)
    put("sizeBytes", sizeBytes)
    put("sha256", sha256)
    put("packageName", packageName)
    put("versionName", versionName)
    put("versionCode", versionCode)
    put("minimumAndroidSdk", minimumAndroidSdk)
    put("debuggable", debuggable)
    put("currentSignerSha256Fingerprint", currentSignerSha256Fingerprint)
    put("orderedSignerLineage", stringArray(orderedSignerLineage))
}

private fun JsonObject.toArtifact(): VerifiedAppUpdateArtifact {
    require(keys == setOf(
        "releaseId",
        "cacheFileName",
        "sizeBytes",
        "sha256",
        "packageName",
        "versionName",
        "versionCode",
        "minimumAndroidSdk",
        "debuggable",
        "currentSignerSha256Fingerprint",
        "orderedSignerLineage",
    ))
    return VerifiedAppUpdateArtifact(
        releaseId = string("releaseId"),
        cacheFileName = string("cacheFileName"),
        sizeBytes = long("sizeBytes"),
        sha256 = string("sha256"),
        packageName = string("packageName"),
        versionName = string("versionName"),
        versionCode = long("versionCode"),
        minimumAndroidSdk = long("minimumAndroidSdk").toIntExact(),
        debuggable = getValue("debuggable").jsonPrimitive.content.toBooleanStrict(),
        currentSignerSha256Fingerprint = string("currentSignerSha256Fingerprint"),
        orderedSignerLineage = stringList("orderedSignerLineage"),
    )
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.longOrNull
    ?: throw IllegalArgumentException("Invalid update state")

private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.optionalString(name: String): String? = getValue(name).jsonPrimitive.contentOrNull

private fun JsonObject.optionalObject(name: String): JsonObject? = getValue(name).let { value ->
    if (value is JsonNull) null else value.jsonObject
}

private fun JsonObject.stringList(name: String): List<String> = getValue(name).jsonArray.map {
    it.jsonPrimitive.content
}

private fun stringArray(values: List<String>): JsonArray = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun Long.toIntExact(): Int = toInt().also { require(it.toLong() == this) }
