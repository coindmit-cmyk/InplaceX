package com.mirkori.inplacex.data.sync

import com.mirkori.inplacex.data.local.PendingSyncOperation
import com.mirkori.inplacex.data.local.PlatformLocalRepository
import com.mirkori.inplacex.data.local.SyncOperationStatus
import com.mirkori.inplacex.data.local.SyncOperationType
import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.inplacex.platform.online.GuestAuthSessionManager
import com.mirkori.inplacex.platform.online.GuestSession
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

data class CloudSaveSnapshot(
    val saveSchemaVersion: Int,
    val revision: Long,
    val stateJson: String,
) {
    init {
        require(saveSchemaVersion > 0)
        require(revision >= 0)
        require(stateJson.isNotBlank())
    }
}

data class CloudSaveMutation(
    val commandId: String = UUID.randomUUID().toString(),
    val playerId: String,
    val expectedRevision: Long,
    val saveSchemaVersion: Int,
    val stateJson: String,
) {
    init {
        UUID.fromString(commandId)
        require(playerId.isNotBlank())
        require(expectedRevision >= 0)
        require(saveSchemaVersion > 0)
        require(stateJson.isNotBlank())
    }
}

sealed interface CloudSavePushResult {
    data class Applied(val snapshot: CloudSaveSnapshot) : CloudSavePushResult

    data class Conflict(val current: CloudSaveSnapshot) : CloudSavePushResult

    data object Unauthorized : CloudSavePushResult

    data object TemporarilyUnavailable : CloudSavePushResult
}

fun interface CloudSaveApi {
    fun push(session: GuestSession, mutation: CloudSaveMutation): CloudSavePushResult
}

interface CloudSaveQueueStore {
    fun enqueue(mutation: CloudSaveMutation)

    fun pending(): List<CloudSaveMutation>

    fun complete(commandId: String)

    fun retry(commandId: String)

    fun conflict(commandId: String)
}

sealed interface CloudSaveReconcileResult {
    data class Applied(val commandIds: List<String>) : CloudSaveReconcileResult

    data class Conflict(val commandId: String, val current: CloudSaveSnapshot) : CloudSaveReconcileResult

    data class PendingRetry(val commandId: String) : CloudSaveReconcileResult

    data object AuthenticationUnavailable : CloudSaveReconcileResult
}

class CloudSaveSyncAdapter(
    private val auth: GuestAuthSessionManager,
    private val api: CloudSaveApi,
    private val queue: CloudSaveQueueStore,
    private val maxAttempts: Int = 3,
    private val logger: InplaceXLogger = InplaceXLogger(),
) {
    init {
        require(maxAttempts > 0)
    }

    fun queueLocalChange(mutation: CloudSaveMutation) {
        queue.enqueue(mutation)
    }

    fun reconcile(): CloudSaveReconcileResult {
        val session = auth.sessionWithFreshAccessTokenOrNull() ?: return CloudSaveReconcileResult.AuthenticationUnavailable
        val completed = mutableListOf<String>()
        for (mutation in queue.pending()) {
            when (val result = pushWithRetry(session, mutation)) {
                is CloudSavePushResult.Applied -> {
                    queue.complete(mutation.commandId)
                    completed += mutation.commandId
                }
                is CloudSavePushResult.Conflict -> {
                    queue.conflict(mutation.commandId)
                    logger.warn(
                        tag = LogTag,
                        message = "cloud save revision conflict retained in queue",
                        attributes = mapOf("commandId" to mutation.commandId),
                    )
                    return CloudSaveReconcileResult.Conflict(mutation.commandId, result.current)
                }
                CloudSavePushResult.TemporarilyUnavailable -> {
                    queue.retry(mutation.commandId)
                    return CloudSaveReconcileResult.PendingRetry(mutation.commandId)
                }
                CloudSavePushResult.Unauthorized -> return CloudSaveReconcileResult.AuthenticationUnavailable
            }
        }
        return CloudSaveReconcileResult.Applied(completed)
    }

    private fun pushWithRetry(initialSession: GuestSession, mutation: CloudSaveMutation): CloudSavePushResult {
        var session = initialSession
        var refreshed = false
        repeat(maxAttempts) { attempt ->
            when (val result = api.push(session, mutation)) {
                CloudSavePushResult.Unauthorized -> {
                    if (refreshed) return CloudSavePushResult.Unauthorized
                    val token = auth.refreshAccessTokenOrNull() ?: return CloudSavePushResult.Unauthorized
                    session = auth.sessionOrNull()?.takeIf { it.accessToken == token } ?: return CloudSavePushResult.Unauthorized
                    refreshed = true
                }
                CloudSavePushResult.TemporarilyUnavailable if attempt + 1 < maxAttempts -> Unit
                else -> return result
            }
        }
        return CloudSavePushResult.TemporarilyUnavailable
    }

    private companion object {
        const val LogTag = "CloudSaveSync"
    }
}

class PlatformLocalCloudSaveQueueStore(
    private val repository: PlatformLocalRepository,
) : CloudSaveQueueStore {
    override fun enqueue(mutation: CloudSaveMutation) {
        repository.enqueueSyncOperation(
            PendingSyncOperation(
                id = mutation.commandId,
                scope = "cloud_save",
                entityId = mutation.playerId,
                operationType = SyncOperationType.PUSH_PROGRESS,
                payloadJson = mutation.encode(),
                endpointPath = "/v1/players/${mutation.playerId}/progress/inplacex",
                method = "PUT",
                idempotencyKey = mutation.commandId,
            ),
        )
    }

    override fun pending(): List<CloudSaveMutation> = repository.loadPendingSyncOperations()
        .asSequence()
        .filter { it.scope == "cloud_save" }
        .filter { it.status == SyncOperationStatus.PENDING || it.status == SyncOperationStatus.IN_FLIGHT }
        .map { operation -> operation.payloadJson.decodeCloudSaveMutation() }
        .toList()

    override fun complete(commandId: String) {
        repository.updateSyncOperationStatus(commandId, SyncOperationStatus.COMPLETED)
    }

    override fun retry(commandId: String) {
        repository.updateSyncOperationStatus(
            operationId = commandId,
            status = SyncOperationStatus.PENDING,
            lastError = "temporary_unavailable",
            incrementRetryCount = true,
        )
    }

    override fun conflict(commandId: String) {
        repository.updateSyncOperationStatus(
            operationId = commandId,
            status = SyncOperationStatus.FAILED,
            lastError = "revision_conflict",
        )
    }
}

private fun CloudSaveMutation.encode(): String = listOf(
    commandId,
    playerId,
    expectedRevision.toString(),
    saveSchemaVersion.toString(),
    stateJson,
).joinToString(separator = ".") { value ->
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private fun String.decodeCloudSaveMutation(): CloudSaveMutation {
    val values = split('.').map { value ->
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }
    require(values.size == 5)
    return CloudSaveMutation(
        commandId = values[0],
        playerId = values[1],
        expectedRevision = values[2].toLong(),
        saveSchemaVersion = values[3].toInt(),
        stateJson = values[4],
    )
}
