/*
 * BepInEx.Android — shared trace logging helper.
 *
 * Logs go to logcat and are also mirrored to a file in this
 * app's external files directory so evidence survives when the
 * game process dies. Used by both libmain.so and libfusion.so.
 *
 * The trace path is derived from the process cmdline, not
 * hardcoded: libmain writes the trace before any FusionConfig
 * is parsed, so the applicationId has to come from the kernel.
 */

#ifndef BEPINEX_TRACE_LOG_H
#define BEPINEX_TRACE_LOG_H

#include <android/log.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>

namespace bepinex {

inline std::string &custom_external_files_dir() {
    static std::string s_dir;
    return s_dir;
}

inline void set_external_files_dir(const std::string &dir) {
    custom_external_files_dir() = dir;
}

// External files directory of this app:
// Uses dynamically supplied directory from Android Context (via FusionConfig) if available,
// falling back to /storage/emulated/0/Android/data/<applicationId>/files.
inline std::string external_files_dir() {
    if (!custom_external_files_dir().empty()) {
        return custom_external_files_dir();
    }
    char package[256] = {};
    if (FILE *cmdline = fopen("/proc/self/cmdline", "r")) {
        size_t n = fread(package, 1, sizeof(package) - 1, cmdline);
        package[n] = '\0';
        fclose(cmdline);
    }
    // Secondary processes carry a ":game" style suffix; the filesystem
    // bucket is keyed on the raw applicationId only.
    if (char *colon = strchr(package, ':')) *colon = '\0';
    return std::string("/storage/emulated/0/Android/data/") + package + "/files";
}

inline std::string trace_log_path() {
    return external_files_dir() + "/bepinex_trace.log";
}

inline void trace_append(const char *fmt, ...) {
    std::string path = trace_log_path();
    FILE *f = fopen(path.c_str(), "a");
    if (!f) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

inline void log_tag(int level, const char *tag, const char *fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(level, tag, "%s", buf);
    trace_append("%s", buf);
}

}  // namespace bepinex

#endif  // BEPINEX_TRACE_LOG_H
