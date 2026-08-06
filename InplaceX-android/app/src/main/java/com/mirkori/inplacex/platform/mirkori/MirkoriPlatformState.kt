package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.InstallationIdentity
import com.mirkori.platform.sdk.PendingGameLogin
import com.mirkori.platform.sdk.PlatformAuthMode

data class MirkoriPersistedState(
    val installation: InstallationIdentity,
    val session: GameIdentitySession? = null,
    val pendingLogin: PendingGameLogin? = null,
)

enum class MirkoriAccountStateKind {
    INITIALIZING,
    UNAVAILABLE,
    GUEST,
    LINKED,
}

data class MirkoriAccountState(
    val kind: MirkoriAccountStateKind,
    val gamePlayerId: String? = null,
    val authMode: PlatformAuthMode? = null,
)

sealed interface MirkoriLoginResult {
    data class BrowserReady(val connectUrl: String) : MirkoriLoginResult

    data class Connected(val accountState: MirkoriAccountState) : MirkoriLoginResult

    data object AlreadyConnected : MirkoriLoginResult

    data object ProfileConflict : MirkoriLoginResult

    data object Rejected : MirkoriLoginResult

    data object Unavailable : MirkoriLoginResult
}

interface SecureMirkoriStateStore {
    fun read(): MirkoriPersistedState?

    fun write(state: MirkoriPersistedState)

    fun clear()
}
