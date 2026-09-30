package com.mirkori.inplacex.auth

enum class AuthProvider(val wireName: String) {
    GOOGLE("google"),
    EMAIL("email"),
    TELEGRAM("telegram"),
}

data class ProviderIdentity(
    val provider: AuthProvider,
    val subject: String,
) {
    init {
        require(subject.length in 16..255)
        require(subject.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    }

    override fun toString(): String =
        "ProviderIdentity(provider=${provider.wireName}, subject=[redacted])"
}
