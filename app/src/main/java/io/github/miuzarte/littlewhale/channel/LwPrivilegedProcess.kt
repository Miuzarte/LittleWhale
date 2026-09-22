package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * Builds the privileged side of the channel inside the process app_process just started
 *
 * The class it instantiates is the AIDL service declared in the APK, which is on the class path
 * because the launcher pointed CLASSPATH at the APK. What the service needs from here is a
 * context it can call system services through, and a process that has an ActivityThread, because
 * several of those services dereference the current one
 *
 * The context is built the way scrcpy's server builds it: a bare ActivityThread is published as
 * the current one so that getSystemContext() answers, and the result is narrowed to the app's own
 * package. Every step is best effort - a service that only reports the uid it runs as survives
 * without a context, and naming the step that failed is more useful than refusing to start
 */
internal object LwPrivilegedProcess {

    private const val TAG = "LwProcess"

    /** What the starter holds once the service exists */
    class Started(
        val service: IBinder,
        val token: String,
        val packageName: String,
        val userId: Int,
        val context: Context?,
    ) {
        /** Ask the local service to release and exit, which is also what the app's destroy does */
        fun destroy() {
            try {
                LwServiceProtocol.proxy(service).destroy()
            } catch (error: Throwable) {
                Log.w(TAG, "could not destroy the service", error)
            }
        }
    }

    /** Where the work runs, and the token that claims it on the app side */
    fun start(args: Array<String>): Started? {
        val options = Options.parse(args) ?: return null
        nameProcess(options)
        val context = createContext(options.packageName)
        return try {
            val loader = context?.classLoader ?: LwPrivilegedProcess::class.java.classLoader
            val serviceClass = loader.loadClass(options.serviceClass)
            val service = serviceClass.getConstructor(Context::class.java)
                .newInstance(context) as IBinder
            Log.i(TAG, "created ${options.serviceClass} as uid ${Process.myUid()}")
            Started(service, options.token, options.packageName, options.userId, context)
        } catch (error: Throwable) {
            Log.e(TAG, "could not create ${options.serviceClass}", error)
            null
        }
    }

    /**
     * Make `ps` and every log line say which service this is
     *
     * app_process already set the process name from --nice-name, which only takes effect on a
     * debuggable build, so this covers the rest
     */
    private fun nameProcess(options: Options) {
        val name = options.debugName ?: options.packageName + ":lw_service"
        try {
            val ddm = Class.forName("android.ddm.DdmHandleAppName")
            val setAppName = ddm.getDeclaredMethod("setAppName", String::class.java, Integer.TYPE)
            setAppName.isAccessible = true
            setAppName.invoke(null, name, options.userId)
        } catch (error: Throwable) {
            Log.w(TAG, "could not name the process $name", error)
        }
    }

    /** A context that answers for the app's package, or null when the platform would not give one */
    private fun createContext(packageName: String): Context? {
        val system = try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val constructor = activityThreadClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val activityThread = constructor.newInstance()

            // Several system services read the current ActivityThread rather than taking one, so
            // publishing it is what makes them usable outside a zygote-forked app process
            val current = activityThreadClass.getDeclaredField("sCurrentActivityThread")
            current.isAccessible = true
            current.set(null, activityThread)

            val systemThread = activityThreadClass.getDeclaredField("mSystemThread")
            systemThread.isAccessible = true
            systemThread.setBoolean(activityThread, true)

            val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext")
            getSystemContext.isAccessible = true
            getSystemContext.invoke(activityThread) as? Context
        } catch (error: Throwable) {
            Log.w(TAG, "could not build a context for the privileged process", error)
            null
        }
        if (system == null) {
            Log.w(TAG, "the ActivityThread produced no system context")
            return null
        }

        // A package context carries the app's resources and its own name, which is what a provider
        // call wants; the wrapper covers the case where only the system context was available
        val base = try {
            system.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
        } catch (error: Throwable) {
            Log.w(TAG, "no package context for $packageName, using the system one", error)
            system
        }
        return LwContext(base, packageName)
    }

    /** The command line the launcher handed over, minus the options app_process consumed itself */
    private class Options(
        val token: String,
        val packageName: String,
        val serviceClass: String,
        val debugName: String?,
        val userId: Int,
    ) {
        companion object {
            fun parse(args: Array<String>): Options? {
                var token: String? = null
                var packageName: String? = null
                var serviceClass: String? = null
                var debugName: String? = null
                var userId = -1

                args.forEach { arg ->
                    when {
                        arg.startsWith("--token=") -> token = arg.substring(8)
                        arg.startsWith("--package=") -> packageName = arg.substring(10)
                        arg.startsWith("--class=") -> serviceClass = arg.substring(8)
                        arg.startsWith("--debug-name=") -> debugName = arg.substring(13)
                        arg.startsWith("--user-id=") -> {
                            userId = arg.substring(10).toIntOrNull() ?: run {
                                Log.e(TAG, "invalid --user-id: $arg")
                                -1
                            }
                        }
                    }
                }

                if (token == null || packageName == null || serviceClass == null || userId < 0) {
                    Log.e(TAG, "missing one of --token, --package, --class, --user-id")
                    return null
                }
                return Options(token, packageName, serviceClass, debugName, userId)
            }
        }
    }
}
