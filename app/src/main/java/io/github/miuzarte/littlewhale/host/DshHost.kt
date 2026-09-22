package io.github.miuzarte.littlewhale.host

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.miuzarte.littlewhale.channel.PrivilegedBridge
import io.github.miuzarte.littlewhale.workspace.Workspace
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Supervises the one dsh host process
 *
 * Node and the packed host tree both ship inside the app, so a start has two phases: unpack the
 * tree the APK carries if it is not there yet, then spawn the process with an explicit
 * environment - `DSH_HOME` inside the app sandbox, the shared libraries that live beside
 * `libnode.so` on `LD_LIBRARY_PATH`, a writable temp directory, and the `HOME` that dsh falls
 * back to, because an app process inherits none of that from a shell
 *
 * State sits in Compose snapshot state rather than a flow: the whole app is one process and
 * one screen, and this way the status screen observes the process directly with no plumbing
 */
object DshHost {
    /** Loopback port the GUI binds */
    const val PORT = 3080

    /** Host output lines kept for the status screen */
    private const val LOG_LINES = 300

    /** The readiness line dsh prints once the tree settled, LAN suffix and all */
    private val READY = Regex("""dsh web: (http://\S+)""")

    /** The LAN suffix dsh adds to that line only when it bound every interface */
    private val LAN_READY = Regex("""\(LAN: (http://\S+?)\)""")

    /** dsh's all-interfaces bind literal, which it refuses without the matching flag */
    private const val ALL_INTERFACES = "0.0.0.0"

    /** Current process state, written by the output reader and the exit hook */
    var status: HostStatus by mutableStateOf(HostStatus.Idle)
        private set

    /** Where sessions keep their files, resolved on every start */
    var workspace: Workspace.Root? by mutableStateOf(null)
        private set

    /** Where another device can open the GUI, reported by the host itself, null on a loopback bind */
    var remoteUrl: String? by mutableStateOf(null)
        private set

    /** Tail of the host's merged output */
    val log = mutableStateListOf<String>()

    private var process: Process? = null

    /** A start is in flight, anywhere between the unpack and the spawn */
    private var starting = false

    /** Set by stop() so a start that is still unpacking does not spawn afterwards */
    private var cancelled = false

    private val lock = Any()

    /** Root of the packed host tree inside the app sandbox */
    fun hostRoot(context: Context): File = File(context.filesDir, "host")

    /** Prepare the tree and spawn the host, unless one is already alive or on its way */
    fun start(context: Context) {
        synchronized(lock) {
            if (process?.isAlive == true || starting) return
            starting = true
            cancelled = false
        }
        val application = context.applicationContext
        Thread({ begin(application) }, "dsh-host-start").apply { isDaemon = true }.start()
    }

    /** Stop the host and wait briefly for the process to go away */
    fun stop() {
        val running = detach() ?: return
        status = HostStatus.Idle
        running.destroy()
        if (running.isAlive) running.destroyForcibly()
    }

    /** Restart the host, which is how a changed workspace takes effect */
    fun restart(context: Context) {
        val application = context.applicationContext
        Thread({
            stopAndWait()
            start(application)
        }, "dsh-host-restart").apply { isDaemon = true }.start()
    }

    /**
     * Stop the host and wait for the process to actually exit
     *
     * A restart needs the listen port back before it spawns a successor, and a process that was
     * only asked to die still holds it
     */
    private fun stopAndWait() {
        val running = detach() ?: return
        status = HostStatus.Idle
        running.destroy()
        val exited = try {
            running.waitFor(EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!exited) running.destroyForcibly()
    }

    /** Take the process out of the supervisor's hands and stop any start that is in flight */
    private fun detach(): Process? = synchronized(lock) {
        cancelled = true
        val current = process
        process = null
        current
    }

    /** Unpack the shipped tree if it is missing or older, then spawn the host */
    private fun begin(application: Context) {
        log.clear()
        remoteUrl = null
        val problem = HostInstaller.ensure(application) { done, total ->
            status = HostStatus.Installing(done, total)
        }
        val abandoned = synchronized(lock) {
            starting = false
            cancelled
        }
        if (abandoned) return
        if (problem != null) return fail(problem)
        val root = Workspace.resolve(application)
        workspace = root
        log.add("workspace ${root.directory.absolutePath} (${root.kind})")
        spawn(application, root)
    }

    /** Start the host process against the tree and workspace that are now in place */
    private fun spawn(application: Context, root: Workspace.Root) {
        val libraryDir = application.applicationInfo.nativeLibraryDir
        // The runtime is shipped as a native library so the installer extracts it into a
        // directory SELinux lets the app execute from, which the app's data directory is not
        val node = File(libraryDir, "libnode.so")
        // Android ships mksh and no bash, and the agent writes bash, so a real bash travels
        // with the app under the same native-library rule; ripgrep is the same story
        val bash = File(libraryDir, "liblwbash.so")
        val ripgrep = File(libraryDir, "liblwrg.so")
        val entry = File(hostRoot(application), "node_modules/@deepseek-ai/dsh/lib/bin.js")
        val home = File(application.filesDir, "dsh-home")
        val temp = File(application.cacheDir, "tmp")
        home.mkdirs()
        temp.mkdirs()
        // The runtime came from an environment whose libssl was built with OPENSSLDIR
        // pointing at a directory an app uid cannot read, and Node aborts during
        // bootstrap with exit 13 and no output at all until OPENSSL_CONF names a config
        // file it can open, so an empty readable file is materialised here
        val openssl = File(application.filesDir, "openssl.cnf")
        if (!openssl.exists()) openssl.writeText("")
        if (!node.isFile) return fail("node runtime is missing at ${node.absolutePath}")
        if (!entry.isFile) return fail("host tree is missing at ${entry.absolutePath}")
        val shellEnv = shellEnvFile(application, libraryDir)
        // Reaching the GUI from another device means binding every interface, which dsh gates
        // behind its own flag because the URL token is then the only thing guarding it
        val lanAccess = HostSettings.lanAccess(application)
        // The host's tools cannot hold a binder, so the privileged channel is reached over a
        // socket this process serves; starting it here keeps "the host knows where it is" and
        // "something is listening there" from depending on which of the two started first
        val channel = PrivilegedBridge.start()
        // The overlay is what adds this app's tools to the profile the host boots
        val overlay = PluginOverlay.write(application)
        log.add(if (overlay != null) "tools overlay ${overlay.absolutePath}" else "tools overlay is missing from the host tree")

        status = HostStatus.Starting
        val spawned = try {
            ProcessBuilder(
                node.absolutePath,
                // Live patch reload reads Node's own module loader, and the addon that
                // normally exposes it (node-addon-require-builtin) has no Android build
                "--expose-internals",
                entry.absolutePath,
                "web",
                // The overlay has to be named before any of the web app's own flags: the web
                // subcommand hands every unknown option, and everything after it, to the app,
                // which then rejects --patch as an unknown option of its own
                *(if (overlay != null) arrayOf("--patch", overlay.absolutePath) else emptyArray()),
                "--no-open",
                "--port",
                PORT.toString(),
                *(if (lanAccess) arrayOf("--host", ALL_INTERFACES, "--allow-lan") else emptyArray()),
            ).apply {
                // A session created without an explicit directory lands in the workspace, and
                // relative paths the host resolves for itself land there too
                directory(root.directory)
                redirectErrorStream(true)
                environment().apply {
                    put("DSH_HOME", home.absolutePath)
                    put("LD_LIBRARY_PATH", libraryDir)
                    // The GUI's directory picker starts at the host's home, so pointing home at
                    // the workspace opens its "choose a workspace" screen where the files are;
                    // dsh's own state stays in DSH_HOME, which outranks home
                    put("HOME", root.directory.absolutePath)
                    put("TMPDIR", temp.absolutePath)
                    put("DSH_TELEMETRY_DISABLED", "1")
                    put("NO_COLOR", "1")
                    put("OPENSSL_CONF", openssl.absolutePath)
                    // dsh resolves the shell itself: DSH_BASH names the bash that travels with
                    // the app, and SHELL is what any other child sees as the user's shell
                    put("DSH_BASH", bash.absolutePath)
                    put("SHELL", if (bash.isFile) bash.absolutePath else "/system/bin/sh")
                    if (ripgrep.isFile) put("DSH_RG_PATH", ripgrep.absolutePath)
                    // A shipped binary cannot also carry the bare name PATH needs, so the
                    // shell-level names come from a startup file bash sources for every
                    // non-interactive command
                    if (shellEnv != null) put("BASH_ENV", shellEnv.absolutePath)
                    // Where the tools find the app's privileged channel, and the token that says
                    // the request came from this host; both unset when the channel is down, and
                    // the tools then report it as unavailable
                    if (channel != null) {
                        put("LW_CHANNEL_ENDPOINT", channel.address)
                        put("LW_CHANNEL_TOKEN", channel.token)
                    }
                    // V8 keeps compiled bytecode in this cache, which is what makes the
                    // second boot of a 300k file tree much faster than the first
                    put("NODE_COMPILE_CACHE", File(application.cacheDir, "node-compile-cache").absolutePath)
                }
            }.start()
        } catch (error: Exception) {
            return fail("could not spawn ${node.name}: ${error.message}")
        }
        val keep = synchronized(lock) {
            if (cancelled) false else { process = spawned; true }
        }
        if (!keep) {
            spawned.destroyForcibly()
            return
        }
        Thread({ watch(spawned) }, "dsh-host-watch").apply { isDaemon = true }.start()
        Thread({ read(spawned) }, "dsh-host-output").apply { isDaemon = true }.start()
    }

    /** Report the exit once the process is gone, unless stop() asked for it */
    private fun watch(source: Process) {
        val code = source.waitFor()
        // stop() clears the field before destroying, so a cleared field means the exit was asked for
        val stopped = synchronized(lock) {
            if (process !== source) true else { process = null; false }
        }
        if (stopped) return
        status = if (status is HostStatus.Running) {
            HostStatus.Failed("host exited with code $code")
        } else {
            HostStatus.Failed("host exited with code $code before reporting its URL")
        }
    }

    /** Read merged host output until the process closes its side */
    private fun read(source: Process) {
        try {
            source.inputStream.bufferedReader().useLines { lines -> lines.forEach(::append) }
        } catch (error: Exception) {
            append("output reader stopped: ${error.message}")
        }
    }

    /**
     * Write the bash startup file that gives shipped binaries their shell names
     *
     * A binary inside the APK has to be named `lib*.so` to survive packaging, so it cannot
     * also be the bare name PATH resolution needs. bash sources the file named by BASH_ENV
     * before every non-interactive command, which is where those names can live instead.
     * The functions call the binary rather than `exec` it, so `node -e x && echo ok` keeps
     * its `echo`
     * @param application context that owns the file.
     * @param libraryDir the directory the shipped binaries live in.
     * @return the written startup file, or null when the APK shipped none of them.
     */
    private fun shellEnvFile(application: Context, libraryDir: String): File? {
        val names = listOf("liblwbash.so" to "bash", "liblwrg.so" to "rg", "libnode.so" to "node")
            .filter { (file, _) -> File(libraryDir, file).isFile }
        if (names.isEmpty()) return null
        val file = File(application.filesDir, "shell-env.sh")
        file.writeText(
            names.joinToString("\n") { (file, name) ->
                """$name() { "$libraryDir/$file" "${'$'}@"; }"""
            } + "\n",
        )
        return file
    }

    /** Keep one output line, publish the readiness line as the GUI URL */
    private fun append(line: String) {
        // The status screen only shows while the host is down, so the host's own output also
        // goes to logcat where it stays readable through the running phase
        Log.i(TAG, line)
        log.add(line)
        while (log.size > LOG_LINES) log.removeAt(0)
        val match = READY.find(line) ?: return
        if (status !is HostStatus.Running) status = HostStatus.Running(match.groupValues[1])
        // The host picks the address itself, so the URL shown to the user is the same one the
        // host's own browser-trust fence accepts
        LAN_READY.find(line)?.let { remoteUrl = it.groupValues[1] }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        status = HostStatus.Failed(reason)
    }

    /** Logcat tag for the host's merged output */
    private const val TAG = "DshHost"

    /** How long a restart waits for the departing host to release its port */
    private const val EXIT_TIMEOUT_MS = 5_000L
}
