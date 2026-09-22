package io.github.miuzarte.littlewhale.channel

/** Which privileged identity the channel talks to */
enum class RemoteBackend(val label: String) {
    /** `su`, which is what KernelSU or Magisk provide and what this device is expected to use */
    ROOT("root"),

    /** Shizuku, running the launcher as the shell identity or as root when it was started so */
    SHIZUKU("shizuku"),
}
