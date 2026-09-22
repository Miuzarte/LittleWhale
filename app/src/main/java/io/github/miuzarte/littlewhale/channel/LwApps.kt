package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Process
import android.util.Log

/**
 * The apps this device offers to start, and what each one is called
 *
 * A caller that has a name for an app - "设置", "哔哩哔哩" - has no way to turn it into the package a
 * launch needs, and the shell commands that would list them are refused this app's uid: `pm` without
 * a `--user` asks about every user at once and needs `INTERACT_ACROSS_USERS_FULL` to do it (measured:
 * `Permission Denial: runListPackages from pm command asks to run as user 999 but is calling from
 * uid u0a326`), and with an explicit `--user` the same command answers with this app alone, because
 * Android 11 hides every other package from an app that has not asked for it (measured: 148
 * third-party packages as root against 1 as this uid)
 *
 * So the list is asked for the way an app is meant to ask: `queryIntentActivities` on the launcher
 * intent, which is the same question the home screen asks. What makes the other apps visible is the
 * `<queries>` declaration in the manifest - the narrow one for that intent rather than
 * `QUERY_ALL_PACKAGES`, because "the things a person can start" is exactly what is wanted here, and
 * it is a declaration rather than a permission
 *
 * Two things this cannot answer, and both are why the privileged side stays in the loop: it only
 * sees the user this app runs as (a cloned app is a package in another user, and
 * [LwLaunch.packages] is what knows about those), and a package that no launcher icon points at is
 * not in the list at all
 */
object LwApps {

    private const val TAG = "LwApps"

    /**
     * The stride between one user's uids and the next
     *
     * `UserHandle.getUserId` would be the tidy way to say this and it is not public API, so the
     * division is written out: uid 10326 (u0a326) is user 0, 1000326 is user 10
     */
    private const val PER_USER_RANGE = 100_000

    /** One thing a person can start */
    data class App(val packageName: String, val label: String, val component: String)

    /** 进程里活着的那个 context, `MainActivity` 起第一帧之前挂上来, 与 `LwOcr` 一个样子 */
    private var application: Context? = null

    /** The user this app runs as, which is the one its own answers are about */
    val ownUser: Int get() = Process.myUid() / PER_USER_RANGE

    fun attach(context: Context) {
        application = context.applicationContext
    }

    /**
     * Everything with a launcher icon, by label
     *
     * One entry per launcher activity rather than per package, because that is what starting one
     * means: a few apps ship two, and a caller naming that app has to be told there are two rather
     * than have one of them picked for it
     *
     * The label is loaded here rather than asked for later, since it is the whole point: it is the
     * name a person sees, in the phone's own language, and it is what a caller's "设置" matches
     *
     * @returns the apps, sorted by label, or nothing when the package manager cannot be asked
     */
    fun launchable(): List<App> {
        val context = application ?: error("LwApps.attach 还没被调用")
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = try {
            manager.queryIntentActivities(launcher, 0)
        } catch (problem: Throwable) {
            Log.w(TAG, "could not ask the package manager which apps the launcher shows", problem)
            return emptyList()
        }
        return found.mapNotNull { info ->
            val activity = info?.activityInfo ?: return@mapNotNull null
            val packageName = activity.packageName ?: return@mapNotNull null
            App(
                packageName = packageName,
                label = label(manager, info, packageName),
                component = "$packageName/${activity.name}",
            )
        }.distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The apps one name reaches, most exact first
     *
     * Narrow to wide, the same order pressing by name uses: the package as written, then the label
     * as written, then either one containing it. So "设置" finds the settings app rather than every
     * app with 设置 somewhere in its name, while "bili" still finds it
     *
     * @returns the candidates, or nothing when no app answers to that name
     */
    fun resolve(name: String): List<App> {
        val wanted = name.trim()
        if (wanted.isEmpty()) return emptyList()
        val apps = launchable()
        val asked = wanted.lowercase()
        val packageExact = apps.filter { it.packageName.equals(wanted, ignoreCase = true) }
        if (packageExact.isNotEmpty()) return packageExact
        val labelExact = apps.filter { it.label.equals(wanted, ignoreCase = true) }
        if (labelExact.isNotEmpty()) return labelExact
        return apps.filter {
            it.label.lowercase().contains(asked) || it.packageName.lowercase().contains(asked)
        }
    }

    /** What a person would call this app, falling back to the package when it has no name of its own */
    private fun label(manager: PackageManager, info: ResolveInfo, packageName: String): String {
        val loaded = try {
            info.loadLabel(manager).toString().trim()
        } catch (problem: Throwable) {
            Log.w(TAG, "could not read the name of $packageName", problem)
            ""
        }
        return loaded.ifBlank { packageName }
    }
}
