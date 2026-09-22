package io.github.miuzarte.littlewhale.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * The directory sessions work in, and the proof that the host can actually use it
 *
 * dsh works in real paths: its directory picker lists host filesystem paths and a session's `cwd`
 * is the workspace path. A storage-access-framework grant cannot serve that, because the grant
 * lives in this process's `ContentResolver` and the host is a Node child process with no way to
 * reach it - what makes shared storage writable *by path* is the all-files access appop. So the
 * shared workspace is used when the app holds that access, and the app's own media directory
 * otherwise, which needs no permission and is still visible to a file manager
 *
 * Every candidate is proven by a write before it is adopted, because the appop can be revoked
 * between runs, and a directory that lists is not necessarily one that accepts files
 */
object Workspace {
    /** Directory name used under whichever root wins, so the workspace keeps its name */
    private const val NAME = "DSH"

    /** Marker written and removed to prove a candidate accepts files */
    private const val PROBE = ".lw-write-probe"

    /** Logcat tag for the resolution */
    private const val TAG = "Workspace"

    /** Which storage the workspace ended up on */
    enum class Kind {
        /** Shared storage, needs the all-files access appop */
        Shared,

        /** The app's own media directory, no permission, visible to file managers */
        Media,

        /** The app sandbox, always available and invisible to everything else */
        Sandbox,
    }

    /**
     * A resolved workspace
     * @param directory the directory to work in.
     * @param kind which storage it came from.
     */
    data class Root(val directory: File, val kind: Kind)

    /** Whether the app holds the all-files access that makes shared storage writable by path */
    fun isManaging(): Boolean = Environment.isExternalStorageManager()

    /**
     * Resolve the workspace, creating it when needed
     *
     * The order is shared (`/sdcard/DSH`), the app's own media directory
     * (`/sdcard/Android/media/<package>/DSH`), then the sandbox, which always exists
     * @param context context whose media directory and files directory are the later candidates.
     * @returns the directory to work in and which storage it came from.
     */
    fun resolve(context: Context): Root {
        // The deprecated accessor is still the only way to name the shared root, and the appop
        // above is what decides whether writing there works
        @Suppress("DEPRECATION")
        val shared = File(Environment.getExternalStorageDirectory(), NAME)
        if (isManaging() && writable(shared)) return Root(shared, Kind.Shared)
        // Deprecated, and still the only accessor naming the app-scoped media directory: it is
        // the one shared location the app may write by path without holding any permission
        @Suppress("DEPRECATION")
        val media = context.getExternalMediaDirs()?.firstOrNull()
        if (media != null) {
            val directory = File(media, NAME)
            if (writable(directory)) return Root(directory, Kind.Media)
        }
        val sandbox = File(context.filesDir, NAME)
        sandbox.mkdirs()
        Log.i(TAG, "workspace ${sandbox.absolutePath} (${Kind.Sandbox})")
        return Root(sandbox, Kind.Sandbox)
    }

    /**
     * Ask the system for the all-files access this app cannot request as a runtime permission
     *
     * The app-specific page is where the user grants it, and the general page is the fallback for
     * a device whose settings app does not answer the specific one
     * @param context context used to resolve both intents.
     * @returns whether any settings page could be opened.
     */
    fun requestAllFilesAccess(context: Context): Boolean {
        val specific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        return try {
            context.startActivity(specific)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                true
            } catch (error: Exception) {
                Log.w(TAG, "no settings page for all files access: ${error.message}")
                false
            }
        }
    }

    /** Create the directory if needed and prove a file can be written into it */
    private fun writable(directory: File): Boolean {
        return try {
            directory.mkdirs()
            val probe = File(directory, PROBE)
            probe.writeText("")
            probe.delete()
            Log.i(TAG, "workspace ${directory.absolutePath} is writable")
            true
        } catch (error: Exception) {
            Log.w(TAG, "workspace ${directory.absolutePath} is not writable: ${error.message}")
            false
        }
    }
}
