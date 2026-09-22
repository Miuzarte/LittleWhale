package io.github.miuzarte.littlewhale.channel

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Starting an app onto one of the virtual screens
 *
 * Nothing in this app can do this for itself. The `am` command presents itself as the shell's
 * package, so the activity manager refuses it from an app uid outright - "Permission Denial:
 * package=com.android.shell does not belong to uid=..." - which was measured rather than assumed,
 * and which is also why this belongs on the privileged side with the other things that need a uid
 * the platform trusts
 *
 * It is deliberately thin: one `am start`, with the two ways of naming a target a caller actually
 * has. A component is used as given; a package is resolved by the platform's own launcher intent,
 * and what comes back says which activity that turned out to be
 */
internal class LwLaunch {

    /** What the command did, in its own words */
    data class Result(val code: Int, val output: String)

    /**
     * Start one activity, blocking until it is up
     *
     * `-W` is what makes this wait for the launch rather than for the request, so the time budget
     * has to be a cold start's worth and not a tap's
     *
     * @param userId the Android user it is for. Zero is the person holding the phone, and anything
     *   else is one of that device's other users - which is the only difference between an app and
     *   a clone of it
     */
    fun start(
        displayId: Int,
        userId: Int,
        packageName: String,
        component: String,
        timeoutMs: Long,
    ): Result {
        val command = when {
            component.isNotBlank() -> base(displayId, userId) + listOf("-n", component)
            packageName.isNotBlank() ->
                base(displayId, userId) + listOf("-n", launcherOf(packageName, userId, timeoutMs))

            else -> return Result(NOT_RUN, "a launch has to name either a package or a component")
        }
        return run(command, timeoutMs)
    }

    /**
     * The users this device has, as the platform lists them
     *
     * The list is what says whether an app has a second copy at all, and what that copy is called:
     * the answer is handed on as it came, because it is the device's own words about itself
     */
    fun users(): String = run(listOf(PM, "list", "users"), QUERY_TIMEOUT_MS).output

    /**
     * Every package installed for one user, in the platform's own words
     *
     * This is the half of "what can be started" that only a privileged uid can answer: a cloned app
     * is a package in another user rather than a second package, and no app-uid call can see across
     * users (see [LwApps] for what that refusal looks like). `--user` is always written out even for
     * the owner, because the command's own default is *every* user, which is not what a question
     * about one user means
     *
     * System packages are left in: 设置, 相机 and 时钟 are system apps, and they are exactly what a
     * caller usually wants to open. Which of these can be started at all is decided on the app's
     * side, by the launcher intent
     */
    fun packages(userId: Int): String =
        run(listOf(PM, "list", "packages", "--user", userId.toString()), QUERY_TIMEOUT_MS).output

    /**
     * The activity a package would start from the home screen
     *
     * The obvious way to start a package - `am start -a MAIN -c LAUNCHER -p <pkg>` - is not
     * reliable, and this was measured rather than assumed: an implicit intent is matched with
     * `MATCH_DEFAULT_ONLY`, and plenty of real apps declare MAIN and LAUNCHER **without** DEFAULT
     * (Flutter's and Unity's manifest templates both do), so `am` answers "unable to resolve
     * Intent" for an app that starts perfectly well from the launcher. Asking the platform the
     * same question the launcher asks avoids the category problem entirely
     *
     * The resolver prints a header line and then the component; the component is the line with a
     * slash in it. When nothing resolves, the previous behaviour is the fallback, so the caller
     * still gets the device's own words about why
     */
    private fun launcherOf(packageName: String, userId: Int, timeoutMs: Long): String {
        val query = mutableListOf(CMD, "package", "resolve-activity", "--brief")
        if (userId != OWNER) query += listOf("--user", userId.toString())
        query += listOf("-c", LAUNCHER, packageName)
        val queried = run(query, timeoutMs)
        return queried.output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains('/') }
            ?: packageName
    }

    /** One command, with its exit code and everything it printed */
    private fun run(command: List<String>, timeoutMs: Long): Result {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            Log.w(TAG, "could not start ${command.joinToString(" ")}", error)
            return Result(NOT_RUN, "could not run ${command.first()}: ${error.message}")
        }
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Throwable) {
            Log.w(TAG, "could not read what ${command.first()} printed", error)
            ""
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "${command.first()} did not finish within ${timeoutMs}ms, killing it")
            process.destroy()
            return Result(NOT_RUN, "$output\nthe launch did not finish within ${timeoutMs}ms")
        }
        return Result(process.exitValue(), output)
    }

    /** Everything every launch has in common, which is the screen it lands on and who it is for */
    private fun base(displayId: Int, userId: Int): List<String> {
        val command = mutableListOf(AM, "start", "-W", "--display", displayId.toString())
        // 不给机主加 `--user 0`: 那是缺省, 写出来只是多一个可能拼错的地方
        if (userId != OWNER) command += listOf("--user", userId.toString())
        return command
    }

    private companion object {
        const val TAG = "LwLaunch"

        /** The platform's own way of starting something without an app process */
        const val AM = "/system/bin/am"

        /** The same, for asking a question rather than acting */
        const val CMD = "/system/bin/cmd"

        /** And the package manager's own front end, which is where the users are listed */
        const val PM = "/system/bin/pm"

        const val LAUNCHER = "android.intent.category.LAUNCHER"

        /** The person holding the phone, whose user is the default everywhere here */
        const val OWNER = 0

        /** Listing users is a question, so it gets a question's budget rather than a launch's */
        const val QUERY_TIMEOUT_MS = 5_000L

        /** Nothing ran, so there is no exit code to report */
        const val NOT_RUN = -1
    }
}
