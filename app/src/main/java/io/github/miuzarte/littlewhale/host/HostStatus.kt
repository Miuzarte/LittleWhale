package io.github.miuzarte.littlewhale.host

/** Where the host process currently stands */
sealed interface HostStatus {
    /** Nothing spawned yet, or the host was stopped on purpose */
    data object Idle : HostStatus

    /** Unpacking the host tree the APK carries, before there is anything to spawn */
    data class Installing(val done: Int, val total: Int) : HostStatus

    /** Spawned, still waiting for the readiness line */
    data object Starting : HostStatus

    /** The host reported its GUI URL, connection token included */
    data class Running(val url: String) : HostStatus

    /** Boot failed, the reason is what the operator needs to see */
    data class Failed(val reason: String) : HostStatus
}
