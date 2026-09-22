package io.github.miuzarte.littlewhale.channel

import android.util.Log
import com.topjohnwu.superuser.Shell
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * How a privileged process gets started
 *
 * This is the one thing root and Shizuku disagree on, so it is the only thing the two channels do
 * not share. A spawner turns a launcher invocation into a command its own mechanism can run, and
 * reports what it can about the process afterwards - root cannot report anything, because the
 * command has to be backgrounded inside a shared shell
 */
interface ProcessSpawner {

    /** Wrap the launcher invocation into a command this channel's shell can run */
    fun wrapCommand(launcherPath: String, invocation: String): String

    /** Start it, returning a handle when the channel can observe the process, null when it cannot */
    fun spawn(command: String): SpawnHandle?

    /** Kill whatever a launch left behind after it failed to hand back a binder */
    fun killResidual(processName: String)
}

/** Liveness of a process the channel started, for the channels that can observe one */
interface SpawnHandle {
    fun isAlive(): Boolean

    fun exitCode(): Int?
}

/** The process was gone before it could hand its binder back, so waiting longer is pointless */
class ProcessExitedException(val exitCode: Int?) :
    IllegalStateException("the launcher exited with code ${exitCode ?: "?"}")

/** Single quote a value for `sh -c`, the only shell every channel can count on */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

/** Kill by process name, which is how a half started launch is cleaned up without a pid */
internal fun killByNameCommand(processName: String): String =
    "kill ${'$'}(pidof ${shellQuote(processName)}) 2>/dev/null"

/**
 * root, through libsu's shared shell
 *
 * The launcher has to be backgrounded: it waits for the service process, and running it in the
 * foreground would hold libsu's shell open for as long as the service lives, which is the rest of
 * the session. Backgrounding is also why this spawner has no handle - the shell returns before
 * anything can be said about the process, so a launch that fails is seen as a binder timeout
 * rather than as an exit code
 */
object SuSpawner : ProcessSpawner {

    override fun wrapCommand(launcherPath: String, invocation: String): String =
        "$invocation >/dev/null 2>&1 &"

    override fun spawn(command: String): SpawnHandle? {
        val result = Shell.cmd(command).exec()
        check(result.isSuccess) {
            result.err.joinToString("\n").ifBlank { "su exited with code ${result.code}" }
        }
        return null
    }

    override fun killResidual(processName: String) {
        runCatching { Shell.cmd(killByNameCommand(processName)).exec() }
            .onFailure { Log.w(TAG, "could not kill a leftover $processName", it) }
    }

    private const val TAG = "SuSpawner"
}

/**
 * Shizuku, through the server's own AIDL
 *
 * `Shizuku.newProcess` is private and slated for removal, and Shizuku's user services are a
 * heavier mechanism than one binder needs, so this calls `IShizukuService.newProcess` directly.
 * The process it returns is tracked, which is what makes a launcher that dies early visible
 *
 * The command runs in the foreground here: the tracked process is the launcher, which forks the
 * service and waits for it, so its liveness is the service's liveness
 */
object ShizukuSpawner : ProcessSpawner {

    /**
     * `test -x` turns a launcher that cannot be executed into exit 126 immediately, instead of a
     * timeout that says nothing, and the stdio redirections keep a full pipe from wedging the
     * service - everything it has to say goes to logcat
     */
    override fun wrapCommand(launcherPath: String, invocation: String): String =
        "test -x ${shellQuote(launcherPath)} || exit 126; exec $invocation </dev/null >/dev/null 2>&1"

    override fun spawn(command: String): SpawnHandle =
        RemoteProcessHandle(service().newProcess(arrayOf("sh", "-c", command), null, null))

    override fun killResidual(processName: String) {
        runCatching {
            val process = service().newProcess(
                arrayOf("sh", "-c", killByNameCommand(processName)), null, null,
            )
            // Wait for the kill so the next launch cannot race it
            runCatching { process.waitFor() }
            runCatching { process.destroy() }
        }.onFailure { Log.w(TAG, "could not kill a leftover $processName", it) }
    }

    private fun service(): IShizukuService {
        val binder = Shizuku.getBinder() ?: error("the Shizuku binder is not available")
        return IShizukuService.Stub.asInterface(binder)
    }

    private class RemoteProcessHandle(private val process: IRemoteProcess) : SpawnHandle {

        /** A Shizuku restart makes liveness unknowable, and assuming death would abort a live launch */
        override fun isAlive(): Boolean = runCatching { process.alive() }.getOrDefault(true)

        override fun exitCode(): Int? = runCatching { process.exitValue() }.getOrNull()
    }

    private const val TAG = "ShizukuSpawner"
}
