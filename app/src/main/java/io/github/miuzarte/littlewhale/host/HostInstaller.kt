package io.github.miuzarte.littlewhale.host

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks the host tree the APK carries, once per shipped version
 *
 * The tree is ~280 MB in 30k files and travels as one archive, so unpacking is a step of its own
 * with real progress, and it cannot run on the main thread
 *
 * The stamp the build wrote beside the archive is the contract between the two: it is compared
 * against the stamp inside the tree, and it is written only after every file landed. An unpack
 * the process did not survive therefore leaves no stamp, and the next start unpacks again instead
 * of running a half tree
 */
object HostInstaller {
    /** Archive name among the APK assets */
    private const val ARCHIVE = "host.zip"

    /** Stamp name, both as an asset and inside the unpacked tree */
    private const val STAMP = "host-version.txt"

    /** What the tree must contain before the host is worth spawning */
    private const val ENTRY = "node_modules/@deepseek-ai/dsh/lib/bin.js"

    /** Files written between two progress reports */
    private const val PROGRESS_EVERY = 512

    /** Copy buffer for one archive entry */
    private const val BUFFER_SIZE = 64 * 1024

    /** Logcat tag for the unpack */
    private const val TAG = "HostInstaller"

    /** What the build recorded about the archive beside it */
    private data class Stamp(val version: String, val entries: Int)

    /**
     * Make the sandbox tree match the one the APK carries
     *
     * Blocking: unpacking hundreds of megabytes takes tens of seconds, so callers run this off
     * the main thread
     * @param context context whose assets and files directory are used.
     * @param onProgress reports the file count to unpack, then the running count.
     * @returns null once the tree is in place, otherwise why it is not.
     */
    fun ensure(context: Context, onProgress: (done: Int, total: Int) -> Unit): String? {
        val target = DshHost.hostRoot(context)
        val stamp = File(target, STAMP)
        val expected = readStamp(context)
            ?: return "the APK carries no usable $STAMP, so there is nothing to unpack"
        val installed = if (stamp.isFile) stamp.readText().trim() else null
        if (installed == expected.version && File(target, ENTRY).isFile) {
            Log.i(TAG, "host tree ${expected.version} is already unpacked")
            return null
        }
        Log.i(TAG, "unpacking host tree ${expected.version}, ${expected.entries} file(s)")
        target.deleteRecursively()
        if (!target.mkdirs() && !target.isDirectory) return "could not create ${target.absolutePath}"
        onProgress(0, expected.entries)
        val written = try {
            context.assets.open(ARCHIVE).use { archive -> extract(archive, target, expected.entries, onProgress) }
        } catch (error: Exception) {
            return "unpacking $ARCHIVE failed: ${error.message}"
        }
        if (written != expected.entries) return "$ARCHIVE held $written file(s), not ${expected.entries}"
        if (!File(target, ENTRY).isFile) return "$ARCHIVE did not contain $ENTRY"
        return try {
            stamp.writeText(expected.version + "\n")
            Log.i(TAG, "host tree ${expected.version} unpacked into ${target.absolutePath}")
            null
        } catch (error: Exception) {
            "could not stamp the unpacked tree: ${error.message}"
        }
    }

    /** Stream one archive into the tree, reporting progress and counting the files that landed */
    private fun extract(
        source: InputStream,
        target: File,
        total: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int {
        val root = target.canonicalPath + File.separator
        var files = 0
        ZipInputStream(BufferedInputStream(source)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                // bsdtar records every path relative to the tree root with a leading `./`
                val name = entry.name.removePrefix("./")
                if (name.isEmpty()) continue
                val destination = File(target, name)
                // The archive is an input from outside the app, so an entry that would land
                // outside the tree is refused rather than written
                if (!destination.canonicalPath.startsWith(root)) {
                    throw IOException("archive entry escapes the host tree: $name")
                }
                if (entry.isDirectory) {
                    destination.mkdirs()
                    continue
                }
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use { file -> archive.copyTo(file, BUFFER_SIZE) }
                // npm marks the launchers in .bin executable on POSIX and a zip carries no
                // permission bits, so dsh shelling out to one of them needs this
                if (isLauncher(name)) destination.setExecutable(true, true)
                files += 1
                if (files % PROGRESS_EVERY == 0) onProgress(files, total)
            }
        }
        onProgress(files, total)
        return files
    }

    /** Whether a tree path is a launcher npm would have marked executable */
    private fun isLauncher(name: String): Boolean =
        name.startsWith("node_modules/.bin/") || name.contains("/node_modules/.bin/")

    /** The stamp the build wrote beside the archive, or null when the APK carries none */
    private fun readStamp(context: Context): Stamp? {
        val fields = try {
            context.assets.open(STAMP).bufferedReader().use { reader ->
                reader.readLines()
                    .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                    .associate { it[0] to it[1] }
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not read the $STAMP asset: ${error.message}")
            return null
        }
        val version = fields["version"] ?: return null
        val entries = fields["entries"]?.toIntOrNull() ?: return null
        return Stamp(version, entries)
    }
}
