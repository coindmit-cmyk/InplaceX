package com.mirkori.inplacex.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

data class VerifiedTelegramLogin(
    val externalUserId: String,
    val displayName: String?,
    val authenticatedAt: Instant,
) {
    override fun toString(): String = "VerifiedTelegramLogin([redacted])"
}

class TelegramLoginVerifier(
    private val maximumAge: Duration = DefaultMaximumAge,
    private val futureClockSkew: Duration = DefaultFutureClockSkew,
) {
    init {
        require(!maximumAge.isNegative && !maximumAge.isZero)
        require(!futureClockSkew.isNegative)
    }

    fun verify(
        fields: Map<String, String>,
        botToken: String,
        now: Instant,
    ): VerifiedTelegramLogin? {
        if (fields.keys !in AllowedKeySets) return null
        if (botToken.length !in 16..256 || botToken.any(Char::isWhitespace)) return null
        val expectedHash = fields["hash"]?.lowercase()?.takeIf { it.matches(HashPattern) }
            ?: return null
        val userId = fields["id"]?.takeIf { it.matches(UserIdPattern) } ?: return null
        val authEpochSeconds = fields["auth_date"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val authenticatedAt = runCatching { Instant.ofEpochSecond(authEpochSeconds) }.getOrNull()
            ?: return null
        if (authenticatedAt.isAfter(now.plus(futureClockSkew))) return null
        if (authenticatedAt.isBefore(now.minus(maximumAge))) return null
        if (fields.any { (key, value) ->
                key.length > MaximumKeyCharacters ||
                    value.length > MaximumValueCharacters ||
                    key.any(Char::isISOControl) ||
                    value.any(Char::isISOControl)
            }
        ) {
            return null
        }

        val dataCheckString = fields
            .filterKeys { it != "hash" }
            .toSortedMap()
            .entries
            .joinToString("\n") { (key, value) -> "$key=$value" }
        val secretKey = MessageDigest.getInstance("SHA-256")
            .digest(botToken.toByteArray(StandardCharsets.UTF_8))
        val actualHash = ProviderSubjectDeriver.hmacSha256(
            secretKey,
            dataCheckString.toByteArray(StandardCharsets.UTF_8),
        ).toLowerHex()
        if (
            !MessageDigest.isEqual(
                actualHash.toByteArray(StandardCharsets.US_ASCII),
                expectedHash.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            return null
        }

        return VerifiedTelegramLogin(
            externalUserId = userId,
            displayName = displayName(fields),
            authenticatedAt = authenticatedAt,
        )
    }

    private fun displayName(fields: Map<String, String>): String? {
        val name = listOfNotNull(fields["first_name"], fields["last_name"])
            .joinToString(" ")
            .trim()
            .ifEmpty { fields["username"]?.trim().orEmpty() }
        return name.takeIf {
            it.length in 1..MaximumDisplayNameCharacters && it.none(Char::isISOControl)
        }
    }

    private companion object {
        val DefaultMaximumAge: Duration = Duration.ofMinutes(10)
        val DefaultFutureClockSkew: Duration = Duration.ofMinutes(1)
        val AllowedKeys = setOf(
            "id",
            "first_name",
            "last_name",
            "username",
            "photo_url",
            "auth_date",
            "hash",
        )
        val RequiredKeys = setOf("id", "auth_date", "hash")
        val AllowedKeySets = buildSet {
            val optional = AllowedKeys - RequiredKeys
            val values = optional.toList()
            repeat(1 shl values.size) { mask ->
                add(
                    RequiredKeys + values.filterIndexed { index, _ ->
                        mask and (1 shl index) != 0
                    },
                )
            }
        }
        val HashPattern = Regex("[0-9a-f]{64}")
        val UserIdPattern = Regex("[1-9][0-9]{0,19}")
        const val MaximumKeyCharacters = 32
        const val MaximumValueCharacters = 512
        const val MaximumDisplayNameCharacters = 120
    }
}

internal fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}
