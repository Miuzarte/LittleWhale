package io.github.miuzarte.littlewhale.host

import android.content.Context
import java.io.File

/**
 * The dsh patch overlay that loads LittleWhale's tools into the host's profile
 *
 * The tools ship inside the host tree the APK carries, but nothing in that tree mentions them:
 * a profile composes its plugins from the profile directory plus whatever patch overlays the
 * command line names, and this app is what composes that command line. Writing the overlay here
 * rather than into the profile keeps the profile the user's own
 */
object PluginOverlay {

    /** Where packaging put the plugin, relative to the root of the host tree */
    private const val ENTRY = "node_modules/littlewhale-channel/index.mjs"

    /** The row id in the composition, which is what a config dump names */
    private const val ID = "littlewhale-channel"

    private const val DIRECTORY = "lw"
    private const val FILE = "tool-plugin.yml"

    /**
     * Write the overlay the host should boot with
     *
     * Rewritten on every start so a stale file cannot outlive the tree it points into
     * @param context context whose sandbox holds both the tree and the overlay.
     * @returns the overlay to pass on the command line, or null when the tree carries no plugin.
     */
    fun write(context: Context): File? {
        val entry = File(DshHost.hostRoot(context), ENTRY)
        if (!entry.isFile) return null
        val file = File(File(context.filesDir, DIRECTORY).apply { mkdirs() }, FILE)
        file.writeText(
            """
            # Written by LittleWhale on every host start, edits are overwritten
            - insert:
                - id: $ID
                  name: '${entry.absolutePath}'
            """.trimIndent() + "\n",
        )
        return file
    }
}
