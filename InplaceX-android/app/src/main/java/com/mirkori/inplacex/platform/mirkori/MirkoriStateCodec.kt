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
            require(input.available() == 0)
            MirkoriPersistedState(installation, session, pending, pendingRefresh).also(::validate)
        }
    }

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
    }

    private const val MinimumSupportedFormatVersion = 1
    private const val PendingRefreshFormatVersion = 2
    private const val FormatVersion = PendingRefreshFormatVersion
    private const val MaximumStateBytes = 32 * 1024
    private val HighEntropyTokenPattern = Regex("[A-Za-z0-9_-]{43,128}")
    private val CredentialPattern = Regex("\\S{32,8192}")
    private val SessionPattern = Regex("[A-Za-z0-9_-]{64}")
    private val PkcePattern = Regex("[A-Za-z0-9._~-]{43,128}")
    private val LoopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
}

private fun String.isCanonicalUuid(): Boolean = runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)
