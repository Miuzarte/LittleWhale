/*
 * Entry point of the privileged process
 *
 * The app cannot exec a file out of its own data directory (Android 10 refuses execute on
 * app_data_file), and the channel that starts this process runs as root or as the shell uid, so
 * the command it runs is this binary shipped as a native library. It points CLASSPATH at the APK
 * and execs `app_process` with a starter class, whose main() builds the service and hands its
 * binder back to the app through the app's own ContentProvider
 *
 * The identity is deliberately not changed here: su hands over uid 0 and Shizuku hands over the
 * shell identity, and both are exactly what the service is for. Reading /dev/input and injecting
 * input need the first, and nothing needs the app uid
 *
 * It forks before exec so that what the spawning channel tracks stays this launcher: Shizuku
 * SIGTERMs the process it started when the app dies, and the service is then free to exit
 * through its own binder death callback instead of being killed in the middle of cleanup
 */

#include <android/log.h>
#include <errno.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define TAG "LwLauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/** The runtime the platform ships, which is what turns a class name into a process */
static const char *const APP_PROCESS = "/system/bin/app_process";

/** Directory app_process reports in its own argv, matching what init uses */
static const char *const APP_PROCESS_DIR = "/system/bin";

/** Everything the launcher needs before it can exec, all of it required */
typedef struct {
    const char *apk_path;
    const char *process_name;
    const char *starter_class;
    const char *token;
    const char *package_name;
    const char *service_class;
    const char *debug_name;
    int user_id;
} LauncherArgs;

static bool starts_with(const char *value, const char *prefix) {
    return strncmp(value, prefix, strlen(prefix)) == 0;
}

static bool parse_int(const char *value, int *out) {
    char *end = NULL;
    long parsed = strtol(value, &end, 10);
    if (value[0] == '\0' || end == value || *end != '\0') return false;
    *out = (int) parsed;
    return true;
}

/** Read the `--name=value` options, refusing a command line that is missing any of them */
static bool parse_args(int argc, char **argv, LauncherArgs *out) {
    memset(out, 0, sizeof(*out));
    out->user_id = -1;

    for (int i = 1; i < argc; i++) {
        const char *arg = argv[i];
        if (starts_with(arg, "--apk=")) out->apk_path = arg + 6;
        else if (starts_with(arg, "--process-name=")) out->process_name = arg + 15;
        else if (starts_with(arg, "--starter-class=")) out->starter_class = arg + 16;
        else if (starts_with(arg, "--token=")) out->token = arg + 8;
        else if (starts_with(arg, "--package=")) out->package_name = arg + 10;
        else if (starts_with(arg, "--class=")) out->service_class = arg + 8;
        else if (starts_with(arg, "--debug-name=")) out->debug_name = arg + 13;
        else if (starts_with(arg, "--user-id=")) {
            if (!parse_int(arg + 10, &out->user_id)) {
                LOGE("invalid --user-id: %s", arg + 10);
                return false;
            }
        } else {
            LOGE("unknown option: %s", arg);
            return false;
        }
    }

    bool complete = out->apk_path != NULL
                    && out->process_name != NULL
                    && out->starter_class != NULL
                    && out->token != NULL
                    && out->package_name != NULL
                    && out->service_class != NULL
                    && out->user_id >= 0;
    if (!complete) LOGE("missing one of the required options");
    return complete;
}

/** Allocate one `--prefix=value` argument for the child's argv */
static char *join_option(const char *prefix, const char *value) {
    size_t size = strlen(prefix) + strlen(value) + 1;
    char *joined = malloc(size);
    if (joined == NULL) {
        LOGE("out of memory building %s", prefix);
        return NULL;
    }
    snprintf(joined, size, "%s%s", prefix, value);
    return joined;
}

/** Point the class loader at the APK and become app_process running the starter class */
static void exec_starter(const LauncherArgs *args) {
    char user_id[16];
    snprintf(user_id, sizeof(user_id), "%d", args->user_id);

    /* argv is app_process, its own argv[0] slot, then the flags the starter parses */
    char *nice_name = join_option("--nice-name=", args->process_name);
    char *token = join_option("--token=", args->token);
    char *package = join_option("--package=", args->package_name);
    char *service = join_option("--class=", args->service_class);
    char *user = join_option("--user-id=", user_id);
    char *debug = args->debug_name != NULL ? join_option("--debug-name=", args->debug_name) : NULL;
    if (nice_name == NULL || token == NULL || package == NULL || service == NULL || user == NULL
        || (args->debug_name != NULL && debug == NULL)) {
        exit(1);
    }

    if (setenv("CLASSPATH", args->apk_path, 1) != 0) {
        LOGE("setenv(CLASSPATH) failed: %s", strerror(errno));
        exit(1);
    }

    char *child_argv[12];
    int index = 0;
    child_argv[index++] = (char *) APP_PROCESS;
    child_argv[index++] = (char *) APP_PROCESS_DIR;
    child_argv[index++] = nice_name;
    child_argv[index++] = (char *) args->starter_class;
    child_argv[index++] = token;
    child_argv[index++] = package;
    child_argv[index++] = service;
    child_argv[index++] = user;
    if (debug != NULL) child_argv[index++] = debug;
    child_argv[index] = NULL;

    LOGI("exec %s with CLASSPATH=%s as uid %d", APP_PROCESS, args->apk_path, (int) getuid());
    execv(APP_PROCESS, child_argv);

    /* Only reached when the exec itself failed, which is worth a loud line */
    LOGE("execv(%s) failed: %s", APP_PROCESS, strerror(errno));
    exit(1);
}

int main(int argc, char **argv) {
    LauncherArgs args;
    if (!parse_args(argc, argv, &args)) return 1;

    LOGI("launcher start: uid=%d process=%s", (int) getuid(), args.process_name);

    pid_t child = fork();
    if (child < 0) {
        LOGE("fork failed: %s", strerror(errno));
        return 1;
    }
    if (child == 0) {
        exec_starter(&args);
        _exit(1);
    }

    /* The exit status is only visible to whoever ran this launcher in the foreground, which is
     * Shizuku; a backgrounded su command drops it, so logcat carries the same information */
    int status = 0;
    if (waitpid(child, &status, 0) < 0) {
        LOGE("waitpid failed: %s", strerror(errno));
        return 1;
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        LOGI("service process exited cleanly");
        return 0;
    }
    LOGE("service process exited with status=%d", status);
    return 1;
}
