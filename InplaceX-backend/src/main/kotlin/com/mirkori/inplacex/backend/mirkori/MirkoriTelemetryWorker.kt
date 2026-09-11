package com.mirkori.inplacex.backend.mirkori

import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.platform.sdk.MirkoriGameServerCredential
import com.mirkori.platform.sdk.MirkoriGameServerTelemetryClient
import com.mirkori.platform.sdk.PlatformApiException
import com.mirkori.platform.sdk.PlatformHttpMethod
import com.mirkori.platform.sdk.PlatformHttpRequest
import com.mirkori.platform.sdk.PlatformHttpResponse
import com.mirkori.platform.sdk.PlatformRecoveryAction
import com.mirkori.platform.sdk.PlatformTransport
import com.mirkori.platform.sdk.PlatformTransportException
import com.mirkori.platform.sdk.PlatformTransportFailure
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking

fun interface MirkoriTelemetrySender {
    suspend fun send(fact: StoredMirkoriTelemetryFact): MirkoriTelemetryDeliveryReceipt
}

class MirkoriPlatformTelemetrySender(
    private val client: MirkoriGameServerTelemetryClient,
    private val credential: MirkoriGameServerCredential,
) : MirkoriTelemetrySender {
    override suspend fun send(fact: StoredMirkoriTelemetryFact): MirkoriTelemetryDeliveryReceipt = when (fact) {
        is StoredMirkoriTelemetryFact.Achievement -> client.submitAchievement(
            credential = credential,
            fact = fact.toPlatformFact(),
        ).let { result -> MirkoriTelemetryDeliveryReceipt(decision = result.decision.wireName) }
        is StoredMirkoriTelemetryFact.Gameplay -> client.submitGameplay(
            credential = credential,
            fact = fact.toPlatformFact(),
        ).let { result ->
            MirkoriTelemetryDeliveryReceipt(
                decision = result.decision.wireName,
                sessionStatus = result.sessionStatus.wireName,
                trustedDurationSeconds = result.trustedDurationSeconds,
            )
        }
    }
}

class JavaHttpPlatformTransport(
    private val requestTimeout: Duration = Duration.ofSeconds(10),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : PlatformTransport {
    init {
        require(!requestTimeout.isNegative && !requestTimeout.isZero)
    }

    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        val builder = HttpRequest.newBuilder(java.net.URI.create(request.url)).timeout(requestTimeout)
        request.headers.forEach(builder::header)
        val body = HttpRequest.BodyPublishers.ofString(request.body, StandardCharsets.UTF_8)
        when (request.method) {
            PlatformHttpMethod.GET -> builder.GET()
            PlatformHttpMethod.POST -> builder.POST(body)
            PlatformHttpMethod.PUT -> builder.PUT(body)
        }
        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val responseBytes = response.body().use { it.readNBytes(MaximumResponseBytes + 1) }
            if (responseBytes.size > MaximumResponseBytes) {
                throw PlatformTransportException(PlatformTransportFailure.INVALID_RESPONSE)
            }
            PlatformHttpResponse(
                status = response.statusCode(),
                body = responseBytes.toString(StandardCharsets.UTF_8),
            )
        } catch (failure: HttpTimeoutException) {
            throw PlatformTransportException(PlatformTransportFailure.TIMEOUT)
        } catch (failure: SSLHandshakeException) {
            throw PlatformTransportException(PlatformTransportFailure.TLS_REJECTED)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PlatformTransportException(PlatformTransportFailure.CANCELLED)
        } catch (failure: IOException) {
            throw PlatformTransportException(PlatformTransportFailure.NETWORK_UNAVAILABLE)
        }
    }

    private companion object {
        const val MaximumResponseBytes = 64 * 1024
    }
}

class MirkoriTelemetryWorker(
    private val journal: JdbcMirkoriTelemetryJournal,
    private val sender: MirkoriTelemetrySender,
    private val logger: InplaceXLogger = InplaceXLogger(),
    private val clock: Clock = Clock.systemUTC(),
    private val heartbeatInterval: Duration = Duration.ofMinutes(4),
    private val claimLease: Duration = Duration.ofSeconds(30),
    private val pollInterval: Duration = Duration.ofSeconds(5),
    private val retryDelay: (Int) -> Duration = ::defaultRetryDelay,
    private val deliveryBatchSize: Int = DefaultDeliveryBatchSize,
) : AutoCloseable {
    init {
        require(deliveryBatchSize in 1..MaximumDeliveryBatchSize)
    }

    private val started = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        check(started.compareAndSet(false, true)) { "Mirkori telemetry worker is already started" }
        executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "mirkori-telemetry-worker").apply { isDaemon = true }
        }.also { scheduler ->
            scheduler.scheduleWithFixedDelay(
                {
                    runCatching { runBlocking { processAvailableBatch() } }
                        .onFailure { error ->
                            logger.error(
                                tag = "MirkoriTelemetry",
                                message = "telemetry worker iteration failed",
                                attributes = mapOf("failureType" to error::class.java.simpleName.take(64)),
                                throwable = error,
                            )
                        }
                },
                0,
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        }
    }

    suspend fun processOnce(now: Instant = clock.instant()): Boolean {
        journal.enqueueDueHeartbeats(now, heartbeatInterval)
        return processNext(now) == ProcessResult.DELIVERED
    }

    suspend fun processAvailableBatch(now: Instant = clock.instant()): Int {
        journal.enqueueDueHeartbeats(now, heartbeatInterval)
        var processed = 0
        while (processed < deliveryBatchSize) {
            when (processNext(now)) {
                ProcessResult.NO_FACT,
                ProcessResult.CANCELLED -> return processed
                ProcessResult.DELIVERED,
                ProcessResult.FAILED -> processed += 1
            }
        }
        return processed
    }

    private suspend fun processNext(now: Instant): ProcessResult {
        val fact = journal.claimDue(now, claimLease) ?: return ProcessResult.NO_FACT
        return try {
            val receipt = sender.send(fact)
            journal.markDelivered(fact, receipt, clock.instant())
            ProcessResult.DELIVERED
        } catch (error: Throwable) {
            val completedAt = clock.instant()
            if (error.isDeliveryCancellation()) {
                releaseCancelledClaim(fact, completedAt)
                logger.info(
                    tag = "MirkoriTelemetry",
                    message = "telemetry delivery interrupted; claim released",
                    attributes = mapOf("factType" to fact::class.java.simpleName),
                )
                return ProcessResult.CANCELLED
            }
            val failure = classifyFailure(error)
            if (failure.retryable && fact.attemptCount < MaximumAttempts) {
                journal.markRetry(
                    fact = fact,
                    errorCode = failure.errorCode,
                    nextAttemptAt = completedAt.plus(retryDelay(fact.attemptCount)),
                    now = completedAt,
                )
            } else {
                journal.markDead(
                    fact = fact,
                    errorCode = if (failure.retryable) RetryExhaustedError else failure.errorCode,
                    now = completedAt,
                )
            }
            logger.warn(
                tag = "MirkoriTelemetry",
                message = if (failure.retryable) "telemetry delivery scheduled for retry" else "telemetry fact dead-lettered",
                attributes = mapOf(
                    "factType" to fact::class.java.simpleName,
                    "errorCode" to failure.errorCode,
                    "attempt" to fact.attemptCount.toString(),
                ),
                throwable = error,
            )
            ProcessResult.FAILED
        }
    }

    override fun close() {
        val scheduler = executor
        executor = null
        scheduler?.shutdownNow()
        if (scheduler != null) {
            try {
                if (!scheduler.awaitTermination(ShutdownWaitSeconds, TimeUnit.SECONDS)) {
                    logger.warn(
                        tag = "MirkoriTelemetry",
                        message = "telemetry worker did not stop within the shutdown bound",
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        started.set(false)
    }

    private fun releaseCancelledClaim(fact: StoredMirkoriTelemetryFact, now: Instant) {
        val wasInterrupted = Thread.interrupted()
        try {
            journal.releaseClaim(fact, now)
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private enum class ProcessResult {
        NO_FACT,
        DELIVERED,
        FAILED,
        CANCELLED,
    }

    private data class DeliveryFailure(val retryable: Boolean, val errorCode: String)

    private companion object {
        const val MaximumAttempts = 20
        const val RetryExhaustedError = "retry_exhausted"
        const val DefaultDeliveryBatchSize = 100
        const val MaximumDeliveryBatchSize = 1_000
        const val ShutdownWaitSeconds = 5L

        fun Throwable.isDeliveryCancellation(): Boolean =
            this is PlatformTransportException && failure == PlatformTransportFailure.CANCELLED

        fun classifyFailure(error: Throwable): DeliveryFailure = when (error) {
            is PlatformTransportException -> DeliveryFailure(error.failure.retryable, error.failure.name.lowercase())
            is PlatformApiException -> DeliveryFailure(
                retryable = error.recoveryAction == PlatformRecoveryAction.RETRY_SAME_REQUEST,
                errorCode = error.errorCode,
            )
            is IllegalArgumentException -> DeliveryFailure(false, "invalid_response")
            else -> DeliveryFailure(false, "internal_error")
        }

        fun defaultRetryDelay(attempt: Int): Duration {
            val exponent = (attempt - 1).coerceIn(0, 8)
            return Duration.ofSeconds((5L shl exponent).coerceAtMost(900L))
        }
    }
}
