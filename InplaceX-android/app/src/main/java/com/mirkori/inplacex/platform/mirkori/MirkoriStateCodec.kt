package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.InstallationIdentity
import com.mirkori.platform.sdk.PendingGameLogin
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformCredentials
import com.mirkori.platform.sdk.PlatformIdempotencyKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.time.Instant
import java.util.UUID

internal object MirkoriStateCodec {
    fun encode(state: MirkoriPersistedState): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(FormatVersion)
            output.writeUTF(state.installation.installationId)
            output.writeUTF(state.installation.installationSecret)
            output.writeBoolean(state.session != null)
            state.session?.let { session ->
                output.writeUTF(session.accountId)
                output.writeUTF(session.gamePlayerId)
                output.writeUTF(session.gameId)
                output.writeUTF(session.authMode.wireName)
                output.writeCredentials(session.credentials)
            }
            output.writeBoolean(state.pendingLogin != null)
            state.pendingLogin?.let { pending ->
                output.writeUTF(pending.session)
                output.writeUTF(pending.state)
                output.writeUTF(pending.codeVerifier)
                output.writeUTF(pending.connectUrl)
                output.writeLong(pending.expiresAt.toEpochMilli())
            }
            output.writeBoolean(state.pendingRefresh != null)
            state.pendingRefresh?.let { pending ->
                output.writeUTF(pending.refreshToken)
                output.writeUTF(pending.idempotencyKey.value)
            }
            output.writeBoolean(state.pendingPurchase != null)
            state.pendingPurchase?.let { pending ->
                output.writeUTF(pending.accountId)
                output.writeUTF(pending.gamePlayerId)
                output.writeUTF(pending.productId)
                output.writeUTF(pending.currency)
                output.writeBoolean(pending.orderId != null)
                pending.orderId?.let(output::writeUTF)
                output.writeUTF(pending.orderIdempotencyKey.value)
                output.writeUTF(pending.checkoutIdempotencyKey.value)
                output.writeBoolean(pending.offerSnapshot != null)
                pending.offerSnapshot?.let { snapshot ->
                    output.writeLong(snapshot.amountMinor)
                    output.writeUTF(snapshot.currency)
                    output.writeInt(snapshot.entitlementSchemaVersion)
                    output.writeBoolean(snapshot.productVersion != null)
                    snapshot.productVersion?.let(output::writeLong)
                }
            }
            output.writeBoolean(state.confirmedEntitlements != null)
            state.confirmedEntitlements?.let { entitlements ->
                output.writeUTF(entitlements.accountId)
                output.writeUTF(entitlements.gamePlayerId)
                output.writeLong(entitlements.confirmedAtEpochMs)
                output.writeFeatureGrant(entitlements.removeAds)
                output.writeFeatureGrant(entitlements.pro)
                output.writeFeatureGrant(entitlements.proPlus)
            }
            output.writeBoolean(state.trustedTimeAnchor != null)
            state.trustedTimeAnchor?.let { anchor ->
                output.writeLong(anchor.serverEpochMs)
                output.writeLong(anchor.monotonicAtObservationMs)
                output.writeLong(anchor.bootMarker)
            }
        }
        bytes.toByteArray().also { require(it.size <= MaximumStateBytes) }
    }

    fun decode(bytes: ByteArray): MirkoriPersistedState {
        require(bytes.size in 1..MaximumStateBytes)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val formatVersion = input.readInt()
            require(formatVersion in MinimumSupportedFormatVersion..FormatVersion)
            val installation = InstallationIdentity(input.readUTF(), input.readUTF())
            val session = if (input.readBoolean()) {
                val accountId = input.readUTF()
                val gamePlayerId = input.readUTF()
                val gameId = input.readUTF()
                val authMode = PlatformAuthMode.fromPersisted(input.readUTF())
                GameIdentitySession(
                    accountId = accountId,
                    gamePlayerId = gamePlayerId,
                    gameId = gameId,
                    installationId = installation.installationId,
                    authMode = authMode,
                    credentials = input.readCredentials(),
                )
            } else {
                null
            }
            val pending = if (input.readBoolean()) {
                PendingGameLogin(
                    session = input.readUTF(),
                    state = input.readUTF(),
                    codeVerifier = input.readUTF(),
                    connectUrl = input.readUTF(),
                    expiresAt = Instant.ofEpochMilli(input.readLong()),
                )
            } else {
                null
            }
            val pendingRefresh = if (formatVersion >= PendingRefreshFormatVersion && input.readBoolean()) {
                PendingMirkoriRefresh(
                    refreshToken = input.readUTF(),
                    idempotencyKey = PlatformIdempotencyKey(input.readUTF()),
                )
            } else {
                null
            }
            val pendingPurchase = if (formatVersion >= CommerceStateFormatVersion && input.readBoolean()) {
                val decoded = PendingMirkoriPurchase(
                    accountId = input.readUTF(),
                    gamePlayerId = input.readUTF(),
                    productId = input.readUTF(),
                    currency = input.readUTF(),
                    orderId = if (input.readBoolean()) input.readUTF() else null,
                    orderIdempotencyKey = PlatformIdempotencyKey(input.readUTF()),
                    checkoutIdempotencyKey = PlatformIdempotencyKey(input.readUTF()),
                )
                if (formatVersion >= ImmutableCommerceFormatVersion && input.readBoolean()) {
                    decoded.copy(
                        offerSnapshot = PendingMirkoriOfferSnapshot(
                            amountMinor = input.readLong(),
                            currency = input.readUTF(),
                            entitlementSchemaVersion = input.readInt(),
                            productVersion = if (input.readBoolean()) input.readLong() else null,
                        ),
                    )
                } else {
                    decoded
                }
            } else {
                null
            }
            val confirmedEntitlements = if (formatVersion >= CommerceStateFormatVersion && input.readBoolean()) {
                ConfirmedMirkoriEntitlements(
                    accountId = input.readUTF(),
                    gamePlayerId = input.readUTF(),
                    confirmedAtEpochMs = input.readLong(),
                    removeAds = input.readFeatureGrant(),
                    pro = input.readFeatureGrant(),
                    proPlus = input.readFeatureGrant(),
                )
            } else {
                null
            }
            val trustedTimeAnchor = if (formatVersion >= ImmutableCommerceFormatVersion && input.readBoolean()) {
                MirkoriTrustedTimeAnchor(
                    serverEpochMs = input.readLong(),
                    monotonicAtObservationMs = input.readLong(),
                    bootMarker = input.readLong(),
                )
            } else {
                null
            }
            require(input.available() == 0)
            MirkoriPersistedState(
                installation = installation,
                session = session,
                pendingLogin = pending,
                pendingRefresh = pendingRefresh,
                pendingPurchase = pendingPurchase,
                confirmedEntitlements = confirmedEntitlements,
                trustedTimeAnchor = trustedTimeAnchor,
            ).also(::validate)
        }
    }

    private fun DataOutputStream.writeFeatureGrant(grant: MirkoriFeatureGrant) {
        writeBoolean(grant.active)
        writeBoolean(grant.validUntilEpochMs != null)
        grant.validUntilEpochMs?.let(::writeLong)
    }

    private fun DataInputStream.readFeatureGrant(): MirkoriFeatureGrant = MirkoriFeatureGrant(
        active = readBoolean(),
        validUntilEpochMs = if (readBoolean()) readLong() else null,
    )

    private fun DataOutputStream.writeCredentials(credentials: PlatformCredentials) {
        writeUTF(credentials.accessToken)
        writeUTF(credentials.refreshToken)
        writeLong(credentials.accessExpiresAt.toEpochMilli())
        writeLong(credentials.refreshExpiresAt.toEpochMilli())
    }

    private fun DataInputStream.readCredentials(): PlatformCredentials = PlatformCredentials(
        accessToken = readUTF(),
        refreshToken = readUTF(),
        accessExpiresAt = Instant.ofEpochMilli(readLong()),
        refreshExpiresAt = Instant.ofEpochMilli(readLong()),
    )

    private fun PlatformAuthMode.Companion.fromPersisted(value: String): PlatformAuthMode =
        PlatformAuthMode.entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown platform authentication mode")

    private fun validate(state: MirkoriPersistedState) {
        require(state.installation.installationId.isCanonicalUuid())
        require(state.installation.installationSecret.matches(HighEntropyTokenPattern))
        state.session?.let { session ->
            require(session.accountId.isCanonicalUuid())
            require(session.gamePlayerId.isCanonicalUuid())
            require(session.gameId == "inplacex")
            require(session.credentials.accessToken.matches(CredentialPattern))
            require(session.credentials.refreshToken.matches(CredentialPattern))
            require(session.credentials.accessExpiresAt.toEpochMilli() > 0)
            require(session.credentials.refreshExpiresAt >= session.credentials.accessExpiresAt)
        }
        state.pendingLogin?.let { pending ->
            require(pending.session.matches(SessionPattern))
            require(pending.state.matches(PkcePattern))
            require(pending.codeVerifier.matches(PkcePattern))
            val connectUri = URI(pending.connectUrl)
            val connectHost = requireNotNull(connectUri.host)
            require(
                connectUri.scheme.equals("https", ignoreCase = true) ||
                    (
                        connectUri.scheme.equals("http", ignoreCase = true) &&
                            connectHost.lowercase() in LoopbackHosts
                    )
            )
            require(connectUri.userInfo == null && connectUri.fragment == null)
            require(pending.expiresAt.toEpochMilli() > 0)
        }
        state.pendingRefresh?.let { pending ->
            val session = requireNotNull(state.session)
            require(pending.refreshToken == session.credentials.refreshToken)
            require(pending.refreshToken.matches(CredentialPattern))
        }
        state.pendingPurchase?.let { pending ->
            val session = requireNotNull(state.session)
            require(pending.accountId == session.accountId)
            require(pending.gamePlayerId == session.gamePlayerId)
            require(pending.accountId.isCanonicalUuid())
            require(pending.gamePlayerId.isCanonicalUuid())
            require(pending.productId.matches(ResourceIdPattern))
            require(pending.currency.matches(CurrencyPattern))
            pending.orderId?.let { require(it.isCanonicalUuid()) }
            pending.offerSnapshot?.let { snapshot ->
                require(snapshot.amountMinor > 0)
                require(snapshot.currency == pending.currency && snapshot.currency.matches(CurrencyPattern))
                require(snapshot.entitlementSchemaVersion == CurrentEntitlementSchemaVersion)
                snapshot.productVersion?.let { require(it > 0) }
            }
        }
        state.trustedTimeAnchor?.let { anchor ->
            require(anchor.serverEpochMs > 0)
            require(anchor.monotonicAtObservationMs >= 0)
            require(anchor.bootMarker >= 0)
        }
        state.confirmedEntitlements?.let { entitlements ->
            val session = requireNotNull(state.session)
            require(entitlements.accountId == session.accountId)
            require(entitlements.gamePlayerId == session.gamePlayerId)
            require(entitlements.confirmedAtEpochMs > 0)
            listOf(entitlements.removeAds, entitlements.pro, entitlements.proPlus).forEach { grant ->
                grant.validUntilEpochMs?.let { require(it > entitlements.confirmedAtEpochMs) }
            }
        }
    }

    private const val MinimumSupportedFormatVersion = 1
    private const val PendingRefreshFormatVersion = 2
    private const val CommerceStateFormatVersion = 3
    private const val ImmutableCommerceFormatVersion = 4
    private const val FormatVersion = ImmutableCommerceFormatVersion
    private const val CurrentEntitlementSchemaVersion = 1
    private const val MaximumStateBytes = 32 * 1024
    private val HighEntropyTokenPattern = Regex("[A-Za-z0-9_-]{43,128}")
    private val CredentialPattern = Regex("\\S{32,8192}")
    private val SessionPattern = Regex("[A-Za-z0-9_-]{64}")
    private val PkcePattern = Regex("[A-Za-z0-9._~-]{43,128}")
    private val ResourceIdPattern = Regex("[a-z0-9][a-z0-9._-]{1,63}")
    private val CurrencyPattern = Regex("[A-Z]{3}")
    private val LoopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
}

private fun String.isCanonicalUuid(): Boolean = runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)
