cmake_minimum_required(VERSION 3.22.1)

project(littlewhale_native C)

# The launcher is an executable that ships as a native library: Android 10 refuses to execute a
# file in the app's data directory, while anything unpacked into nativeLibraryDir is executable
# by every app domain, so the output is named liblauncher.so and the app execs that path
add_executable(launcher launcher.c)
set_target_properties(launcher PROPERTIES
        OUTPUT_NAME "liblauncher"
        SUFFIX ".so"
        LINKER_LANGUAGE C)

# Only android_log_print is needed; nothing here touches the NDK's own libraries
target_link_libraries(launcher log)
