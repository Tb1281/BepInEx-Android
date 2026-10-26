// Debug-only JNI call tracer for the PairIP VM's environment checks.
//
// klB runs inside libpairipcore.so and talks to the Java runtime only through
// JNI. The VM bytecode is obfuscated, but every class lookup, method lookup,
// field read and call passes through the process-wide JNIEnv function table.
// Patching that table is data-only: it does not modify libart or
// libpairipcore code, so the module/maps checks the VM performs itself are not
// affected.
//
// Only calls whose immediate caller is inside libpairipcore.so are logged.
// Everything else (framework, Kotlin, the launcher) is forwarded unchanged.
//
// Not used by production builds: the shield only installs it when the
// debug-only `pairip_jni_trace` extra is present.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#define TRACE_TAG "PairIpJni"
#define TRACEI(...) __android_log_print(ANDROID_LOG_INFO, TRACE_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Module range
// ---------------------------------------------------------------------------

struct ModuleRange {
    uintptr_t begin = 0;
    uintptr_t end = 0;
};

struct ModuleLookup {
    const char *name;
    ModuleRange *range;
};

int FindModuleCallback(dl_phdr_info *info, size_t, void *data) {
    auto *lookup = static_cast<ModuleLookup *>(data);
    if (info == nullptr || lookup == nullptr || lookup->range == nullptr) {
        return 0;
    }
    if (info->dlpi_name == nullptr ||
        std::strstr(info->dlpi_name, lookup->name) == nullptr) {
        return 0;
    }
    uintptr_t end = info->dlpi_addr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_LOAD) continue;
        const uintptr_t segment_end =
            info->dlpi_addr + phdr.p_vaddr + phdr.p_memsz;
        if (segment_end > end) end = segment_end;
    }
    lookup->range->begin = info->dlpi_addr;
    lookup->range->end = end;
    return 1;
}

ModuleRange PairIpRange() {
    ModuleRange range;
    ModuleLookup lookup{"libpairipcore.so", &range};
    dl_iterate_phdr(FindModuleCallback, &lookup);
    return range;
}

ModuleRange g_pairip_range;
std::atomic<bool> g_enabled{false};

void NoteThread() {
    static thread_local bool noted = false;
    if (noted) return;
    noted = true;
    char name[32] = {};
    if (pthread_getname_np(pthread_self(), name, sizeof(name)) != 0) {
        std::snprintf(name, sizeof(name), "?");
    }
    const long tid = syscall(SYS_gettid);
    TRACEI("jni: thread tid=%ld name=%s", tid, name);
}

bool IsPairIpCaller(const void *caller) {
    const auto address = reinterpret_cast<uintptr_t>(caller);
    const bool match = g_enabled.load(std::memory_order_relaxed) &&
                       address >= g_pairip_range.begin &&
                       address < g_pairip_range.end;
    if (match) NoteThread();
    return match;
}

// ---------------------------------------------------------------------------
// Saved JNI function table entries
// ---------------------------------------------------------------------------

using FindClassFn = jclass(JNICALL *)(JNIEnv *, const char *);
using GetMethodIDFn = jmethodID(JNICALL *)(JNIEnv *, jclass, const char *,
                                           const char *);
using GetStaticMethodIDFn = jmethodID(JNICALL *)(JNIEnv *, jclass, const char *,
                                                 const char *);
using GetFieldIDFn = jfieldID(JNICALL *)(JNIEnv *, jclass, const char *,
                                         const char *);
using GetStaticFieldIDFn = jfieldID(JNICALL *)(JNIEnv *, jclass, const char *,
                                               const char *);
using GetObjectClassFn = jclass(JNICALL *)(JNIEnv *, jobject);
using IsInstanceOfFn = jboolean(JNICALL *)(JNIEnv *, jobject, jclass);
using GetObjectFieldFn = jobject(JNICALL *)(JNIEnv *, jobject, jfieldID);
using GetStaticObjectFieldFn = jobject(JNICALL *)(JNIEnv *, jclass, jfieldID);
using GetStaticBooleanFieldFn = jboolean(JNICALL *)(JNIEnv *, jclass, jfieldID);
using GetStaticIntFieldFn = jint(JNICALL *)(JNIEnv *, jclass, jfieldID);
using GetBooleanFieldFn = jboolean(JNICALL *)(JNIEnv *, jobject, jfieldID);
using GetIntFieldFn = jint(JNICALL *)(JNIEnv *, jobject, jfieldID);
using NewObjectVFn = jobject(JNICALL *)(JNIEnv *, jclass, jmethodID, va_list);
using NewObjectAFn = jobject(JNICALL *)(JNIEnv *, jclass, jmethodID,
                                       const jvalue *);
using CallObjectMethodVFn = jobject(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                               va_list);
using CallObjectMethodAFn = jobject(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                               const jvalue *);
using CallBooleanMethodVFn = jboolean(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                                va_list);
using CallBooleanMethodAFn = jboolean(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                                const jvalue *);
using CallIntMethodVFn = jint(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                        va_list);
using CallIntMethodAFn = jint(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                        const jvalue *);
using CallVoidMethodVFn = void(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                         va_list);
using CallVoidMethodAFn = void(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                         const jvalue *);
using CallStaticObjectMethodVFn = jobject(JNICALL *)(JNIEnv *, jclass,
                                                     jmethodID, va_list);
using CallStaticObjectMethodAFn = jobject(JNICALL *)(JNIEnv *, jclass,
                                                     jmethodID, const jvalue *);
using CallStaticBooleanMethodVFn = jboolean(JNICALL *)(JNIEnv *, jclass,
                                                      jmethodID, va_list);
using CallStaticBooleanMethodAFn = jboolean(JNICALL *)(JNIEnv *, jclass,
                                                      jmethodID,
                                                      const jvalue *);
using CallStaticIntMethodVFn = jint(JNICALL *)(JNIEnv *, jclass, jmethodID,
                                              va_list);
using CallStaticIntMethodAFn = jint(JNICALL *)(JNIEnv *, jclass, jmethodID,
                                              const jvalue *);
using CallStaticVoidMethodVFn = void(JNICALL *)(JNIEnv *, jclass, jmethodID,
                                               va_list);
using CallStaticVoidMethodAFn = void(JNICALL *)(JNIEnv *, jclass, jmethodID,
                                               const jvalue *);
using GetArrayLengthFn = jsize(JNICALL *)(JNIEnv *, jarray);
using GetObjectArrayElementFn = jobject(JNICALL *)(JNIEnv *, jobjectArray,
                                                   jsize);
using ExceptionOccurredFn = jthrowable(JNICALL *)(JNIEnv *);
using ExceptionClearFn = void(JNICALL *)(JNIEnv *);
using ExceptionCheckFn = jboolean(JNICALL *)(JNIEnv *);
using ThrowFn = jint(JNICALL *)(JNIEnv *, jthrowable);
using ThrowNewFn = jint(JNICALL *)(JNIEnv *, jclass, const char *);
using GetStringUTFCharsFn = const char *(JNICALL *)(JNIEnv *, jstring,
                                                    jboolean *);
using ReleaseStringUTFCharsFn = void(JNICALL *)(JNIEnv *, jstring,
                                                const char *);
using NewGlobalRefFn = jobject(JNICALL *)(JNIEnv *, jobject);
using DeleteGlobalRefFn = void(JNICALL *)(JNIEnv *, jobject);
using DeleteLocalRefFn = void(JNICALL *)(JNIEnv *, jobject);
using CallObjectMethodFn = jobject(JNICALL *)(JNIEnv *, jobject, jmethodID,
                                             ...);
using CallStaticObjectMethodFn = jobject(JNICALL *)(JNIEnv *, jclass,
                                                   jmethodID, ...);

FindClassFn g_find_class = nullptr;
GetMethodIDFn g_get_method_id = nullptr;
GetStaticMethodIDFn g_get_static_method_id = nullptr;
GetFieldIDFn g_get_field_id = nullptr;
GetStaticFieldIDFn g_get_static_field_id = nullptr;
GetObjectClassFn g_get_object_class = nullptr;
IsInstanceOfFn g_is_instance_of = nullptr;
GetObjectFieldFn g_get_object_field = nullptr;
GetStaticObjectFieldFn g_get_static_object_field = nullptr;
GetStaticBooleanFieldFn g_get_static_boolean_field = nullptr;
GetStaticIntFieldFn g_get_static_int_field = nullptr;
GetBooleanFieldFn g_get_boolean_field = nullptr;
GetIntFieldFn g_get_int_field = nullptr;
NewObjectVFn g_new_object_v = nullptr;
NewObjectAFn g_new_object_a = nullptr;
CallObjectMethodVFn g_call_object_v = nullptr;
CallObjectMethodAFn g_call_object_a = nullptr;
CallBooleanMethodVFn g_call_boolean_v = nullptr;
CallBooleanMethodAFn g_call_boolean_a = nullptr;
CallIntMethodVFn g_call_int_v = nullptr;
CallIntMethodAFn g_call_int_a = nullptr;
CallVoidMethodVFn g_call_void_v = nullptr;
CallVoidMethodAFn g_call_void_a = nullptr;
CallStaticObjectMethodVFn g_call_static_object_v = nullptr;
CallStaticObjectMethodAFn g_call_static_object_a = nullptr;
CallStaticBooleanMethodVFn g_call_static_boolean_v = nullptr;
CallStaticBooleanMethodAFn g_call_static_boolean_a = nullptr;
CallStaticIntMethodVFn g_call_static_int_v = nullptr;
CallStaticIntMethodAFn g_call_static_int_a = nullptr;
CallStaticVoidMethodVFn g_call_static_void_v = nullptr;
CallStaticVoidMethodAFn g_call_static_void_a = nullptr;
GetArrayLengthFn g_get_array_length = nullptr;
GetObjectArrayElementFn g_get_object_array_element = nullptr;
ExceptionOccurredFn g_exception_occurred = nullptr;
ExceptionClearFn g_exception_clear = nullptr;
ExceptionCheckFn g_exception_check = nullptr;
ThrowFn g_throw = nullptr;
ThrowNewFn g_throw_new = nullptr;
GetStringUTFCharsFn g_get_string_utf = nullptr;
ReleaseStringUTFCharsFn g_release_string_utf = nullptr;
NewGlobalRefFn g_new_global_ref = nullptr;
DeleteGlobalRefFn g_delete_global_ref = nullptr;
DeleteLocalRefFn g_delete_local_ref = nullptr;

jclass g_class_class = nullptr;
jmethodID g_class_get_name = nullptr;
jclass g_string_class = nullptr;
jclass g_throwable_class = nullptr;
jmethodID g_throwable_to_string = nullptr;
jclass g_stack_trace_element_class = nullptr;
jmethodID g_ste_get_class_name = nullptr;
jmethodID g_ste_get_method_name = nullptr;
jmethodID g_ste_get_file_name = nullptr;
jmethodID g_ste_get_line_number = nullptr;

// ---------------------------------------------------------------------------
// Method/field metadata
// ---------------------------------------------------------------------------

struct MemberInfo {
    std::string clazz;
    std::string name;
    std::string sig;
};

std::mutex g_members_mutex;
std::unordered_map<jmethodID, MemberInfo> g_methods;
std::unordered_map<jfieldID, MemberInfo> g_fields;

void RegisterMethod(jmethodID id, const std::string &clazz,
                    const char *name, const char *sig) {
    if (id == nullptr) return;
    MemberInfo info{clazz, name != nullptr ? name : "?",
                    sig != nullptr ? sig : "?"};
    std::lock_guard<std::mutex> lock(g_members_mutex);
    g_methods[id] = std::move(info);
}

void RegisterField(jfieldID id, const std::string &clazz,
                   const char *name, const char *sig) {
    if (id == nullptr) return;
    MemberInfo info{clazz, name != nullptr ? name : "?",
                    sig != nullptr ? sig : "?"};
    std::lock_guard<std::mutex> lock(g_members_mutex);
    g_fields[id] = std::move(info);
}

MemberInfo LookupMethod(jmethodID id) {
    std::lock_guard<std::mutex> lock(g_members_mutex);
    auto it = g_methods.find(id);
    if (it == g_methods.end()) return MemberInfo{"?", "?", "?"};
    return it->second;
}

MemberInfo LookupField(jfieldID id) {
    std::lock_guard<std::mutex> lock(g_members_mutex);
    auto it = g_fields.find(id);
    if (it == g_fields.end()) return MemberInfo{"?", "?", "?"};
    return it->second;
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

std::string Sanitize(std::string value, size_t limit = 240) {
    if (value.size() > limit) {
        value.resize(limit);
        value += "...";
    }
    for (char &c : value) {
        const auto byte = static_cast<unsigned char>(c);
        if (c == '\n') {
            c = ' ';
        } else if (byte < 0x20 || byte >= 0x7f) {
            c = '.';
        }
    }
    return value;
}

size_t SafeLength(const char *text, size_t limit) {
    size_t length = 0;
    while (length < limit && text[length] != '\0') ++length;
    return length;
}

std::string JStringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr || g_get_string_utf == nullptr) return "(null)";
    const char *utf = g_get_string_utf(env, value, nullptr);
    if (utf == nullptr) return "(unreadable)";
    std::string out(utf);
    if (g_release_string_utf != nullptr) {
        g_release_string_utf(env, value, utf);
    }
    return Sanitize(std::move(out));
}

// Class.getName() takes no arguments. A variadic JNI call still requires a
// valid (possibly empty) va_list, which cannot be produced portably in C++.
// Call the A variant with an empty argument array instead.
jobject CallObjectNoArgs(JNIEnv *env, jobject receiver, jmethodID id) {
    if (g_call_object_a == nullptr || id == nullptr) return nullptr;
    return g_call_object_a(env, receiver, id, nullptr);
}

std::string ClassNameOfClassSafe(JNIEnv *env, jclass clazz);

std::string ClassNameOfClassImpl(JNIEnv *env, jclass clazz) {
    if (clazz == nullptr) return "null";
    if (g_class_get_name == nullptr) return "class";
    if (g_exception_check != nullptr && g_exception_check(env)) {
        return "(pending)";
    }
    auto name = static_cast<jstring>(
        CallObjectNoArgs(env, clazz, g_class_get_name));
    if (name == nullptr) return "class";
    std::string out = JStringToUtf8(env, name);
    if (g_delete_local_ref != nullptr) g_delete_local_ref(env, name);
    return out;
}

std::string ClassNameOfClassSafe(JNIEnv *env, jclass clazz) {
    return ClassNameOfClassImpl(env, clazz);
}

std::string ClassName(JNIEnv *env, jobject object) {
    if (object == nullptr) return "null";
    if (g_get_object_class == nullptr) return "object";
    if (g_exception_check != nullptr && g_exception_check(env)) {
        return "(pending)";
    }
    jclass clazz = g_get_object_class(env, object);
    std::string out = ClassNameOfClassSafe(env, clazz);
    if (clazz != nullptr && g_delete_local_ref != nullptr) {
        g_delete_local_ref(env, clazz);
    }
    return out;
}

bool IsString(JNIEnv *env, jobject object) {
    if (object == nullptr || g_string_class == nullptr ||
        g_is_instance_of == nullptr) {
        return false;
    }
    return g_is_instance_of(env, object, g_string_class) == JNI_TRUE;
}

std::string ObjectText(JNIEnv *env, jobject object) {
    if (object == nullptr) return "null";
    if (g_exception_check != nullptr && g_exception_check(env)) {
        return "(pending)";
    }
    if (g_get_object_class == nullptr || g_get_method_id == nullptr ||
        g_call_object_a == nullptr) {
        return ClassName(env, object);
    }
    jclass clazz = g_get_object_class(env, object);
    if (clazz == nullptr) return ClassName(env, object);
    const jmethodID to_string =
        g_get_method_id(env, clazz, "toString", "()Ljava/lang/String;");
    if (to_string == nullptr) {
        if (g_delete_local_ref != nullptr) g_delete_local_ref(env, clazz);
        return ClassName(env, object);
    }
    auto text = static_cast<jstring>(
        g_call_object_a(env, object, to_string, nullptr));
    std::string out;
    if (text != nullptr) {
        out = JStringToUtf8(env, text);
        if (g_delete_local_ref != nullptr) g_delete_local_ref(env, text);
    } else {
        out = ClassName(env, object);
    }
    if (ClassNameOfClassSafe(env, clazz) == "android.content.Intent") {
        const jmethodID get_extras = g_get_method_id(
            env, clazz, "getExtras", "()Landroid/os/Bundle;");
        if (get_extras != nullptr) {
            jobject extras =
                g_call_object_a(env, object, get_extras, nullptr);
            if (extras != nullptr) {
                out += " extras=" + ObjectText(env, extras);
                if (g_delete_local_ref != nullptr) {
                    g_delete_local_ref(env, extras);
                }
            }
        }
    }
    if (g_delete_local_ref != nullptr) g_delete_local_ref(env, clazz);
    return out;
}

std::string FormatObject(JNIEnv *env, jobject object, const std::string &desc) {
    if (object == nullptr) return "null";
    if (g_exception_check != nullptr && g_exception_check(env)) {
        return "(pending)";
    }
    if (desc == "java/lang/String" && IsString(env, object)) {
        return "\"" + JStringToUtf8(env, static_cast<jstring>(object)) + "\"";
    }
    const std::string cls = ClassName(env, object);
    if (!desc.empty() && desc[0] == '[' && g_get_array_length != nullptr) {
        const jsize length =
            g_get_array_length(env, static_cast<jarray>(object));
        return cls + "[len=" + std::to_string(length) + "]";
    }
    if (cls == "android.content.Intent" || cls == "android.app.ContextImpl" ||
        cls == "android.app.ContextWrapper" ||
        cls == "android.content.ContextWrapper") {
        return cls + "{" + ObjectText(env, object) + "}";
    }
    return cls;
}

struct ArgType {
    char kind = 'V';  // Z B C S I J F D L [
    std::string desc;
};

std::vector<ArgType> ParseArgs(const std::string &signature) {
    std::vector<ArgType> args;
    const size_t open = signature.find('(');
    if (open == std::string::npos) return args;
    size_t i = open + 1;
    while (i < signature.size() && signature[i] != ')') {
        ArgType type;
        if (signature[i] == '[') {
            type.kind = '[';
            size_t start = i;
            while (i < signature.size() && signature[i] == '[') ++i;
            if (i < signature.size() && signature[i] == 'L') {
                const size_t end = signature.find(';', i);
                i = end == std::string::npos ? signature.size() : end + 1;
            } else if (i < signature.size()) {
                ++i;
            }
            type.desc = signature.substr(start, i - start);
        } else if (signature[i] == 'L') {
            type.kind = 'L';
            const size_t start = i + 1;
            const size_t end = signature.find(';', start);
            if (end == std::string::npos) {
                type.desc = signature.substr(start);
                i = signature.size();
            } else {
                type.desc = signature.substr(start, end - start);
                i = end + 1;
            }
        } else {
            type.kind = signature[i];
            ++i;
        }
        args.push_back(std::move(type));
    }
    return args;
}

std::string FormatArgsVa(JNIEnv *env, const std::string &signature,
                         va_list args) {
    std::string out;
    const std::vector<ArgType> types = ParseArgs(signature);
    for (size_t i = 0; i < types.size(); ++i) {
        if (i != 0) out += ", ";
        switch (types[i].kind) {
            case 'Z':
                out += va_arg(args, int) != 0 ? "true" : "false";
                break;
            case 'B':
                out += std::to_string(static_cast<int>(va_arg(args, int)));
                break;
            case 'C':
                out += std::to_string(static_cast<unsigned int>(va_arg(args, int)));
                break;
            case 'S':
                out += std::to_string(static_cast<int>(va_arg(args, int)));
                break;
            case 'I':
                out += std::to_string(va_arg(args, jint));
                break;
            case 'J':
                out += std::to_string(static_cast<long long>(va_arg(args, jlong)));
                break;
            case 'F':
                out += std::to_string(va_arg(args, double));
                break;
            case 'D':
                out += std::to_string(va_arg(args, double));
                break;
            case 'L':
            case '[': {
                auto object = va_arg(args, jobject);
                out += FormatObject(env, object, types[i].desc);
                break;
            }
            default:
                out += "?";
                break;
        }
    }
    return out;
}

std::string FormatArgsA(JNIEnv *env, const std::string &signature,
                        const jvalue *args) {
    std::string out;
    const std::vector<ArgType> types = ParseArgs(signature);
    if (args == nullptr) return out;
    for (size_t i = 0; i < types.size(); ++i) {
        if (i != 0) out += ", ";
        switch (types[i].kind) {
            case 'Z':
                out += args[i].z != JNI_FALSE ? "true" : "false";
                break;
            case 'B':
                out += std::to_string(static_cast<int>(args[i].b));
                break;
            case 'C':
                out += std::to_string(static_cast<unsigned int>(args[i].c));
                break;
            case 'S':
                out += std::to_string(static_cast<int>(args[i].s));
                break;
            case 'I':
                out += std::to_string(args[i].i);
                break;
            case 'J':
                out += std::to_string(static_cast<long long>(args[i].j));
                break;
            case 'F':
                out += std::to_string(args[i].f);
                break;
            case 'D':
                out += std::to_string(args[i].d);
                break;
            case 'L':
            case '[':
                out += FormatObject(env, args[i].l, types[i].desc);
                break;
            default:
                out += "?";
                break;
        }
    }
    return out;
}

// ---------------------------------------------------------------------------
// Stack-trace inspection
// ---------------------------------------------------------------------------

void DumpStackTrace(JNIEnv *env, jobjectArray frames) {
    if (frames == nullptr || g_get_array_length == nullptr ||
        g_get_object_array_element == nullptr) {
        return;
    }
    if (g_exception_check != nullptr && g_exception_check(env)) return;
    const jsize count = g_get_array_length(env, frames);
    TRACEI("jni: stack trace (%d frame%s)", count, count == 1 ? "" : "s");
    const jsize limit = count > 48 ? 48 : count;
    for (jsize i = 0; i < limit; ++i) {
        jobject element = g_get_object_array_element(env, frames, i);
        if (element == nullptr) {
            TRACEI("jni:   #%d <null>", i);
            continue;
        }
        auto callString = [env, element](jmethodID id) -> std::string {
            if (id == nullptr) return "?";
            auto value = static_cast<jstring>(CallObjectNoArgs(env, element, id));
            if (value == nullptr) return "?";
            std::string out = JStringToUtf8(env, value);
            if (g_delete_local_ref != nullptr) g_delete_local_ref(env, value);
            return out;
        };
        auto callInt = [env, element](jmethodID id) -> jint {
            if (id == nullptr) return -1;
            // getLineNumber() is the only int-returning accessor we query.
            jvalue no_args{};
            return g_call_int_a != nullptr
                       ? g_call_int_a(env, element, id, &no_args)
                       : -1;
        };
        const std::string cls = callString(g_ste_get_class_name);
        const std::string method = callString(g_ste_get_method_name);
        const std::string file = callString(g_ste_get_file_name);
        const jint line = callInt(g_ste_get_line_number);
        TRACEI("jni:   #%d %s.%s(%s:%d)", i, cls.c_str(), method.c_str(),
               file.c_str(), line);
        if (g_delete_local_ref != nullptr) g_delete_local_ref(env, element);
    }
    if (count > limit) {
        TRACEI("jni:   ... %d more", count - limit);
    }
}

std::string ThrowableText(JNIEnv *env, jobject throwable) {
    if (throwable == nullptr || g_throwable_to_string == nullptr) {
        return "(throwable)";
    }
    if (g_exception_check != nullptr && g_exception_check(env)) {
        return "(pending)";
    }
    auto text = static_cast<jstring>(
        CallObjectNoArgs(env, throwable, g_throwable_to_string));
    if (text == nullptr) return "(throwable)";
    std::string out = JStringToUtf8(env, text);
    if (g_delete_local_ref != nullptr) g_delete_local_ref(env, text);
    return out;
}

void LogObjectResult(JNIEnv *env, const char *kind, jobject result) {
    if (result == nullptr) {
        TRACEI("jni: %s -> null", kind);
        return;
    }
    if (g_exception_check != nullptr && g_exception_check(env)) {
        TRACEI("jni: %s -> <pending exception>", kind);
        return;
    }
    const std::string cls = ClassName(env, result);
    if (cls == "[Ljava.lang.StackTraceElement;") {
        TRACEI("jni: %s -> %s", kind, cls.c_str());
        DumpStackTrace(env, static_cast<jobjectArray>(result));
    } else if (g_throwable_class != nullptr && g_is_instance_of != nullptr &&
               g_is_instance_of(env, result, g_throwable_class)) {
        TRACEI("jni: %s -> %s: %s", kind, cls.c_str(),
               ThrowableText(env, result).c_str());
    } else if (cls == "android.content.Intent") {
        TRACEI("jni: %s -> %s: %s", kind, cls.c_str(),
               ObjectText(env, result).c_str());
    } else if (cls == "java.lang.Long" && g_get_method_id != nullptr) {
        jclass long_class = g_get_object_class != nullptr
                                ? g_get_object_class(env, result)
                                : nullptr;
        jmethodID long_value = long_class != nullptr
            ? g_get_method_id(env, long_class, "longValue", "()J")
            : nullptr;
        jlong value = long_value != nullptr
            ? env->functions->CallLongMethodA(env, result, long_value, nullptr)
            : 0;
        TRACEI("jni: %s -> %s: 0x%llx (%lld)", kind, cls.c_str(),
               static_cast<unsigned long long>(value),
               static_cast<long long>(value));
    } else {
        TRACEI("jni: %s -> %s", kind, cls.c_str());
    }
}

// ---------------------------------------------------------------------------
// Hook implementations
// ---------------------------------------------------------------------------

jclass JNICALL HookFindClass(JNIEnv *env, const char *name) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace && name != nullptr) {
        const size_t length = SafeLength(name, 512);
        TRACEI("jni: FindClass(\"%.*s\"%s)", static_cast<int>(length), name,
               length == 512 ? "..." : "");
    }
    jclass result = g_find_class(env, name);
    if (trace && result != nullptr) {
        TRACEI("jni: FindClass -> %s", ClassNameOfClassSafe(env, result).c_str());
    }
    return result;
}

jmethodID JNICALL HookGetMethodID(JNIEnv *env, jclass clazz, const char *name,
                                  const char *sig) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    std::string cls;
    if (trace) cls = ClassNameOfClassSafe(env, clazz);
    jmethodID result = g_get_method_id(env, clazz, name, sig);
    if (trace) {
        TRACEI("jni: GetMethodID %s.%s%s -> %p", cls.c_str(),
               name != nullptr ? name : "?", sig != nullptr ? sig : "?",
               result);
        if (result != nullptr) RegisterMethod(result, cls, name, sig);
    }
    return result;
}

jmethodID JNICALL HookGetStaticMethodID(JNIEnv *env, jclass clazz,
                                        const char *name, const char *sig) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    std::string cls;
    if (trace) cls = ClassNameOfClassSafe(env, clazz);
    jmethodID result = g_get_static_method_id(env, clazz, name, sig);
    if (trace) {
        TRACEI("jni: GetStaticMethodID %s.%s%s -> %p", cls.c_str(),
               name != nullptr ? name : "?", sig != nullptr ? sig : "?",
               result);
        if (result != nullptr) RegisterMethod(result, cls, name, sig);
    }
    return result;
}

jfieldID JNICALL HookGetFieldID(JNIEnv *env, jclass clazz, const char *name,
                                const char *sig) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    std::string cls;
    if (trace) cls = ClassNameOfClassSafe(env, clazz);
    jfieldID result = g_get_field_id(env, clazz, name, sig);
    if (trace) {
        TRACEI("jni: GetFieldID %s.%s:%s -> %p", cls.c_str(),
               name != nullptr ? name : "?", sig != nullptr ? sig : "?",
               result);
        if (result != nullptr) RegisterField(result, cls, name, sig);
    }
    return result;
}

jfieldID JNICALL HookGetStaticFieldID(JNIEnv *env, jclass clazz,
                                      const char *name, const char *sig) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    std::string cls;
    if (trace) cls = ClassNameOfClassSafe(env, clazz);
    jfieldID result = g_get_static_field_id(env, clazz, name, sig);
    if (trace) {
        TRACEI("jni: GetStaticFieldID %s.%s:%s -> %p", cls.c_str(),
               name != nullptr ? name : "?", sig != nullptr ? sig : "?",
               result);
        if (result != nullptr) RegisterField(result, cls, name, sig);
    }
    return result;
}

jobject JNICALL HookGetStaticObjectField(JNIEnv *env, jclass clazz,
                                         jfieldID id) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    jobject result = g_get_static_object_field(env, clazz, id);
    if (trace) {
        const MemberInfo info = LookupField(id);
        TRACEI("jni: GetStaticObjectField %s.%s -> %s", info.clazz.c_str(),
               info.name.c_str(),
               FormatObject(env, result, info.sig).c_str());
    }
    return result;
}

jobject JNICALL HookGetObjectField(JNIEnv *env, jobject object,
                                   jfieldID id) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    jobject result = g_get_object_field(env, object, id);
    if (trace) {
        const MemberInfo info = LookupField(id);
        TRACEI("jni: GetObjectField %s.%s on %s -> %s", info.clazz.c_str(),
               info.name.c_str(), ClassName(env, object).c_str(),
               FormatObject(env, result, info.sig).c_str());
    }
    return result;
}

jboolean JNICALL HookGetStaticBooleanField(JNIEnv *env, jclass clazz,
                                           jfieldID id) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    const jboolean result = g_get_static_boolean_field(env, clazz, id);
    if (trace && g_exception_check != nullptr && !g_exception_check(env)) {
        const MemberInfo info = LookupField(id);
        TRACEI("jni: GetStaticBooleanField %s.%s -> %s", info.clazz.c_str(),
               info.name.c_str(), result ? "true" : "false");
    }
    return result;
}

jint JNICALL HookGetStaticIntField(JNIEnv *env, jclass clazz, jfieldID id) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    const jint result = g_get_static_int_field(env, clazz, id);
    if (trace && g_exception_check != nullptr && !g_exception_check(env)) {
        const MemberInfo info = LookupField(id);
        TRACEI("jni: GetStaticIntField %s.%s -> %d", info.clazz.c_str(),
               info.name.c_str(), result);
    }
    return result;
}

jboolean JNICALL HookGetBooleanField(JNIEnv *env, jobject object,
                                     jfieldID id) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    const jboolean result = g_get_boolean_field(env, object, id);
    if (trace && g_exception_check != nullptr && !g_exception_check(env)) {
        const MemberInfo info = LookupField(id);
        TRACEI("jni: GetBooleanField %s.%s on %s -> %s", info.clazz.c_str(),
               info.name.c_str(), ClassName(env, object).c_str(),
               result ? "true" : "false");
    }
    return result;
}

jint JNICALL HookGetIntField(JNIEnv *env, jobject object, jfieldID id) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    const jint result = g_get_int_field(env, object, id);
    if (trace && g_exception_check != nullptr && !g_exception_check(env)) {
        const MemberInfo info = LookupField(id);
        TRACEI("jni: GetIntField %s.%s on %s -> %d", info.clazz.c_str(),
               info.name.c_str(), ClassName(env, object).c_str(), result);
    }
    return result;
}

jobject JNICALL HookNewObjectV(JNIEnv *env, jclass clazz, jmethodID id,
                               va_list args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        va_list copy;
        va_copy(copy, args);
        if (info.clazz == "?") {
            // PairIP caches constructor IDs, so the registry cannot name the
            // class. The bulk allocations in the startup VMs are boxed Longs;
            // log the first 64-bit argument so the values can be inspected.
            static std::atomic<uint32_t> unknown_ctor{0};
            const uint32_t index = unknown_ctor.fetch_add(1);
            const jlong raw = va_arg(copy, jlong);
            TRACEI("jni: NewObject ? #%u class=%s arg0=%lld (%#llx)", index,
                   ClassName(env, clazz).c_str(),
                   static_cast<long long>(raw),
                   static_cast<unsigned long long>(raw));
        } else {
            TRACEI("jni: NewObject %s args=[%s]", info.clazz.c_str(),
                   FormatArgsVa(env, info.sig, copy).c_str());
        }
        va_end(copy);
    }
    jobject result = g_new_object_v(env, clazz, id, args);
    if (trace) LogObjectResult(env, "NewObject", result);
    return result;
}

jobject JNICALL HookNewObjectA(JNIEnv *env, jclass clazz, jmethodID id,
                               const jvalue *args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        TRACEI("jni: NewObjectA %s args=[%s]", info.clazz.c_str(),
               FormatArgsA(env, info.sig, args).c_str());
    }
    jobject result = g_new_object_a(env, clazz, id, args);
    if (trace) LogObjectResult(env, "NewObjectA", result);
    return result;
}

jobject JNICALL HookCallObjectMethodV(JNIEnv *env, jobject object,
                                      jmethodID id, va_list args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        va_list copy;
        va_copy(copy, args);
        TRACEI("jni: CallObjectMethod %s.%s args=[%s]",
               ClassName(env, object).c_str(), info.name.c_str(),
               FormatArgsVa(env, info.sig, copy).c_str());
        va_end(copy);
    }
    jobject result = g_call_object_v(env, object, id, args);
    if (trace) LogObjectResult(env, "CallObjectMethod", result);
    return result;
}

jobject JNICALL HookCallObjectMethodA(JNIEnv *env, jobject object,
                                      jmethodID id, const jvalue *args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        TRACEI("jni: CallObjectMethodA %s.%s args=[%s]",
               ClassName(env, object).c_str(), info.name.c_str(),
               FormatArgsA(env, info.sig, args).c_str());
    }
    jobject result = g_call_object_a(env, object, id, args);
    if (trace) LogObjectResult(env, "CallObjectMethodA", result);
    return result;
}

jboolean JNICALL HookCallBooleanMethodV(JNIEnv *env, jobject object,
                                        jmethodID id, va_list args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        va_list copy;
        va_copy(copy, args);
        TRACEI("jni: CallBooleanMethod %s.%s args=[%s]",
               ClassName(env, object).c_str(), info.name.c_str(),
               FormatArgsVa(env, info.sig, copy).c_str());
        va_end(copy);
    }
    const jboolean result = g_call_boolean_v(env, object, id, args);
    if (trace) {
        TRACEI("jni: CallBooleanMethod -> %s", result ? "true" : "false");
    }
    return result;
}

jboolean JNICALL HookCallBooleanMethodA(JNIEnv *env, jobject object,
                                        jmethodID id, const jvalue *args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        TRACEI("jni: CallBooleanMethodA %s.%s args=[%s]",
               ClassName(env, object).c_str(), info.name.c_str(),
               FormatArgsA(env, info.sig, args).c_str());
    }
    const jboolean result = g_call_boolean_a(env, object, id, args);
    if (trace) {
        TRACEI("jni: CallBooleanMethodA -> %s", result ? "true" : "false");
    }
    return result;
}

jint JNICALL HookCallIntMethodV(JNIEnv *env, jobject object, jmethodID id,
                                va_list args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        va_list copy;
        va_copy(copy, args);
        TRACEI("jni: CallIntMethod %s.%s args=[%s]",
               ClassName(env, object).c_str(), info.name.c_str(),
               FormatArgsVa(env, info.sig, copy).c_str());
        va_end(copy);
    }
    const jint result = g_call_int_v(env, object, id, args);
    if (trace) TRACEI("jni: CallIntMethod -> %d", result);
    return result;
}

jint JNICALL HookCallIntMethodA(JNIEnv *env, jobject object, jmethodID id,
                                const jvalue *args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        TRACEI("jni: CallIntMethodA %s.%s args=[%s]",
               ClassName(env, object).c_str(), info.name.c_str(),
               FormatArgsA(env, info.sig, args).c_str());
    }
    const jint result = g_call_int_a(env, object, id, args);
    if (trace) TRACEI("jni: CallIntMethodA -> %d", result);
    return result;
}

void JNICALL HookCallVoidMethodV(JNIEnv *env, jobject object, jmethodID id,
                                 va_list args) {
    void *caller = __builtin_return_address(0);
    if (IsPairIpCaller(caller)) {
        const MemberInfo info = LookupMethod(id);
        const std::string receiver = ClassName(env, object);
        std::string detail;
        if (receiver == "android.app.ContextImpl" ||
            receiver == "android.app.ContextWrapper" ||
            receiver == "android.content.ContextWrapper") {
            va_list detail_args;
            va_copy(detail_args, args);
            jobject intent = va_arg(detail_args, jobject);
            va_end(detail_args);
            detail = " arg0=" + ObjectText(env, intent);
        }
        va_list copy;
        va_copy(copy, args);
        TRACEI("jni: CallVoidMethod %s.%s args=[%s]%s", receiver.c_str(),
               info.name.c_str(), FormatArgsVa(env, info.sig, copy).c_str(),
               detail.c_str());
        va_end(copy);
    }
    g_call_void_v(env, object, id, args);
}

void JNICALL HookCallVoidMethodA(JNIEnv *env, jobject object, jmethodID id,
                                 const jvalue *args) {
    void *caller = __builtin_return_address(0);
    if (IsPairIpCaller(caller)) {
        const MemberInfo info = LookupMethod(id);
        const std::string receiver = ClassName(env, object);
        std::string detail;
        if (args != nullptr && (receiver == "android.app.ContextImpl" ||
                                receiver == "android.app.ContextWrapper" ||
                                receiver == "android.content.ContextWrapper")) {
            detail = " arg0=" + ObjectText(env, args[0].l);
        }
        TRACEI("jni: CallVoidMethodA %s.%s args=[%s]%s", receiver.c_str(),
               info.name.c_str(), FormatArgsA(env, info.sig, args).c_str(),
               detail.c_str());
    }
    g_call_void_a(env, object, id, args);
}

jobject JNICALL HookCallStaticObjectMethodV(JNIEnv *env, jclass clazz,
                                            jmethodID id, va_list args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        va_list copy;
        va_copy(copy, args);
        TRACEI("jni: CallStaticObjectMethod %s.%s args=[%s]",
               info.clazz.c_str(), info.name.c_str(),
               FormatArgsVa(env, info.sig, copy).c_str());
        va_end(copy);
    }
    jobject result = g_call_static_object_v(env, clazz, id, args);
    if (trace) LogObjectResult(env, "CallStaticObjectMethod", result);
    return result;
}

jobject JNICALL HookCallStaticObjectMethodA(JNIEnv *env, jclass clazz,
                                            jmethodID id,
                                            const jvalue *args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        TRACEI("jni: CallStaticObjectMethodA %s.%s args=[%s]",
               info.clazz.c_str(), info.name.c_str(),
               FormatArgsA(env, info.sig, args).c_str());
    }
    jobject result = g_call_static_object_a(env, clazz, id, args);
    if (trace) LogObjectResult(env, "CallStaticObjectMethodA", result);
    return result;
}

jboolean JNICALL HookCallStaticBooleanMethodV(JNIEnv *env, jclass clazz,
                                              jmethodID id, va_list args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        va_list copy;
        va_copy(copy, args);
        TRACEI("jni: CallStaticBooleanMethod %s.%s args=[%s]",
               info.clazz.c_str(), info.name.c_str(),
               FormatArgsVa(env, info.sig, copy).c_str());
        va_end(copy);
    }
    const jboolean result = g_call_static_boolean_v(env, clazz, id, args);
    if (trace) {
        TRACEI("jni: CallStaticBooleanMethod -> %s", result ? "true" : "false");
    }
    return result;
}

jboolean JNICALL HookCallStaticBooleanMethodA(JNIEnv *env, jclass clazz,
                                              jmethodID id,
                                              const jvalue *args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        TRACEI("jni: CallStaticBooleanMethodA %s.%s args=[%s]",
               info.clazz.c_str(), info.name.c_str(),
               FormatArgsA(env, info.sig, args).c_str());
    }
    const jboolean result = g_call_static_boolean_a(env, clazz, id, args);
    if (trace) {
        TRACEI("jni: CallStaticBooleanMethodA -> %s",
               result ? "true" : "false");
    }
    return result;
}

jint JNICALL HookCallStaticIntMethodV(JNIEnv *env, jclass clazz,
                                      jmethodID id, va_list args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        va_list copy;
        va_copy(copy, args);
        TRACEI("jni: CallStaticIntMethod %s.%s args=[%s]",
               info.clazz.c_str(), info.name.c_str(),
               FormatArgsVa(env, info.sig, copy).c_str());
        va_end(copy);
    }
    const jint result = g_call_static_int_v(env, clazz, id, args);
    if (trace) TRACEI("jni: CallStaticIntMethod -> %d", result);
    return result;
}

jint JNICALL HookCallStaticIntMethodA(JNIEnv *env, jclass clazz,
                                      jmethodID id, const jvalue *args) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    if (trace) {
        const MemberInfo info = LookupMethod(id);
        TRACEI("jni: CallStaticIntMethodA %s.%s args=[%s]",
               info.clazz.c_str(), info.name.c_str(),
               FormatArgsA(env, info.sig, args).c_str());
    }
    const jint result = g_call_static_int_a(env, clazz, id, args);
    if (trace) TRACEI("jni: CallStaticIntMethodA -> %d", result);
    return result;
}

void JNICALL HookCallStaticVoidMethodV(JNIEnv *env, jclass clazz,
                                       jmethodID id, va_list args) {
    void *caller = __builtin_return_address(0);
    if (IsPairIpCaller(caller)) {
        const MemberInfo info = LookupMethod(id);
        va_list copy;
        va_copy(copy, args);
        TRACEI("jni: CallStaticVoidMethod %s.%s args=[%s]",
               info.clazz.c_str(), info.name.c_str(),
               FormatArgsVa(env, info.sig, copy).c_str());
        va_end(copy);
    }
    g_call_static_void_v(env, clazz, id, args);
}

void JNICALL HookCallStaticVoidMethodA(JNIEnv *env, jclass clazz,
                                       jmethodID id, const jvalue *args) {
    void *caller = __builtin_return_address(0);
    if (IsPairIpCaller(caller)) {
        const MemberInfo info = LookupMethod(id);
        TRACEI("jni: CallStaticVoidMethodA %s.%s args=[%s]",
               info.clazz.c_str(), info.name.c_str(),
               FormatArgsA(env, info.sig, args).c_str());
    }
    g_call_static_void_a(env, clazz, id, args);
}

jobject JNICALL HookGetObjectArrayElement(JNIEnv *env, jobjectArray array,
                                          jsize index) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    jobject result = g_get_object_array_element(env, array, index);
    if (trace) {
        TRACEI("jni: GetObjectArrayElement %s[%d] -> %s",
               ClassName(env, array).c_str(), index,
               ClassName(env, result).c_str());
    }
    return result;
}

jint JNICALL HookThrow(JNIEnv *env, jthrowable throwable) {
    void *caller = __builtin_return_address(0);
    if (IsPairIpCaller(caller)) {
        TRACEI("jni: Throw %s: %s", ClassName(env, throwable).c_str(),
               ThrowableText(env, throwable).c_str());
    }
    return g_throw(env, throwable);
}

jint JNICALL HookThrowNew(JNIEnv *env, jclass clazz, const char *message) {
    void *caller = __builtin_return_address(0);
    if (IsPairIpCaller(caller)) {
        TRACEI("jni: ThrowNew %s: %s", ClassNameOfClassSafe(env, clazz).c_str(),
               message != nullptr ? Sanitize(message).c_str() : "(null)");
    }
    return g_throw_new(env, clazz, message);
}

// PairIP calls ExceptionOccurred and ExceptionClear immediately after each
// other on the same thread. Keep the raw local reference and inspect it only
// after the exception has been cleared: NewGlobalRef/Class.getName are not
// safe while a Java exception is pending.
jthrowable g_last_exception = nullptr;

jthrowable JNICALL HookExceptionOccurred(JNIEnv *env) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    jthrowable result = g_exception_occurred(env);
    if (trace) {
        TRACEI("jni: ExceptionOccurred -> %p", result);
        g_last_exception = result;
    }
    return result;
}

void JNICALL HookExceptionClear(JNIEnv *env) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    g_exception_clear(env);
    if (trace && g_last_exception != nullptr) {
        TRACEI("jni: ExceptionClear: %s: %s",
               ClassName(env, g_last_exception).c_str(),
               ThrowableText(env, g_last_exception).c_str());
        g_last_exception = nullptr;
    }
}

jboolean JNICALL HookExceptionCheck(JNIEnv *env) {
    void *caller = __builtin_return_address(0);
    const bool trace = IsPairIpCaller(caller);
    const jboolean result = g_exception_check(env);
    if (trace) TRACEI("jni: ExceptionCheck -> %s", result ? "true" : "false");
    return result;
}

// ---------------------------------------------------------------------------
// Table patching
// ---------------------------------------------------------------------------

struct Patch {
    size_t index;
    void *replacement;
    void **original;
};

int MappingProtection(uintptr_t address) {
    FILE *maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return PROT_READ;
    char line[512];
    int protection = PROT_READ;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long begin = 0;
        unsigned long end = 0;
        char perms[8] = {};
        if (sscanf(line, "%lx-%lx %7s", &begin, &end, perms) != 3) continue;
        if (address < begin || address >= end) continue;
        protection = 0;
        if (perms[0] == 'r') protection |= PROT_READ;
        if (perms[1] == 'w') protection |= PROT_WRITE;
        if (perms[2] == 'x') protection |= PROT_EXEC;
        break;
    }
    fclose(maps);
    return protection;
}

bool ApplyPatches(JNIEnv *env, std::vector<Patch> *patches) {
    auto *table = reinterpret_cast<void **>(
        const_cast<JNINativeInterface *>(env->functions));
    if (patches->empty()) return false;

    size_t min_index = patches->front().index;
    size_t max_index = patches->front().index;
    for (const Patch &patch : *patches) {
        if (patch.index < min_index) min_index = patch.index;
        if (patch.index > max_index) max_index = patch.index;
    }

    const uintptr_t begin =
        reinterpret_cast<uintptr_t>(&table[min_index]);
    const uintptr_t end =
        reinterpret_cast<uintptr_t>(&table[max_index]) + sizeof(void *);
    const long page_size = sysconf(_SC_PAGESIZE);
    const uintptr_t page_begin =
        begin & ~static_cast<uintptr_t>(page_size - 1);
    const uintptr_t page_end =
        (end + static_cast<uintptr_t>(page_size) - 1) &
        ~static_cast<uintptr_t>(page_size - 1);
    const int protection = MappingProtection(begin);

    if (mprotect(reinterpret_cast<void *>(page_begin),
                 page_end - page_begin,
                 protection | PROT_WRITE) != 0) {
        TRACEI("jni: mprotect RW failed: %s", std::strerror(errno));
        return false;
    }
    for (const Patch &patch : *patches) {
        *patch.original = table[patch.index];
        table[patch.index] = patch.replacement;
    }
    if (mprotect(reinterpret_cast<void *>(page_begin),
                 page_end - page_begin, protection) != 0) {
        TRACEI("jni: mprotect restore failed: %s", std::strerror(errno));
    }
    TRACEI("jni: patched %zu slots in [%p, %p) prot=%d", patches->size(),
           reinterpret_cast<void *>(page_begin),
           reinterpret_cast<void *>(page_end), protection);
    return true;
}

void InitializeHelpers(JNIEnv *env) {
    jclass class_class = g_find_class(env, "java/lang/Class");
    if (class_class != nullptr) {
        g_class_class = static_cast<jclass>(
            g_new_global_ref(env, class_class));
        g_delete_local_ref(env, class_class);
        g_class_get_name = g_get_method_id(
            env, g_class_class, "getName", "()Ljava/lang/String;");
    }

    jclass string_class = g_find_class(env, "java/lang/String");
    if (string_class != nullptr) {
        g_string_class = static_cast<jclass>(
            g_new_global_ref(env, string_class));
        g_delete_local_ref(env, string_class);
    }

    jclass throwable_class = g_find_class(env, "java/lang/Throwable");
    if (throwable_class != nullptr) {
        g_throwable_class = static_cast<jclass>(
            g_new_global_ref(env, throwable_class));
        g_throwable_to_string = g_get_method_id(
            env, g_throwable_class, "toString", "()Ljava/lang/String;");
        g_delete_local_ref(env, throwable_class);
    }

    jclass ste_class = g_find_class(env, "java/lang/StackTraceElement");
    if (ste_class != nullptr) {
        g_stack_trace_element_class = static_cast<jclass>(
            g_new_global_ref(env, ste_class));
        g_ste_get_class_name = g_get_method_id(
            env, g_stack_trace_element_class, "getClassName",
            "()Ljava/lang/String;");
        g_ste_get_method_name = g_get_method_id(
            env, g_stack_trace_element_class, "getMethodName",
            "()Ljava/lang/String;");
        g_ste_get_file_name = g_get_method_id(
            env, g_stack_trace_element_class, "getFileName",
            "()Ljava/lang/String;");
        g_ste_get_line_number = g_get_method_id(
            env, g_stack_trace_element_class, "getLineNumber", "()I");
        g_delete_local_ref(env, ste_class);
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallJniTrace(
    JNIEnv *env, jclass) {
    if (g_enabled.load()) return JNI_TRUE;
    if (env == nullptr || env->functions == nullptr) return JNI_FALSE;

    g_pairip_range = PairIpRange();
    if (g_pairip_range.begin == 0) {
        TRACEI("jni: libpairipcore.so is not mapped");
        return JNI_FALSE;
    }

    // Save the helpers we need for logging before patching anything. Every
    // helper call below goes through these saved originals, so the tracer
    // never recurses into itself.
    g_find_class = env->functions->FindClass;
    g_get_method_id = env->functions->GetMethodID;
    g_get_static_method_id = env->functions->GetStaticMethodID;
    g_get_field_id = env->functions->GetFieldID;
    g_get_static_field_id = env->functions->GetStaticFieldID;
    g_get_object_class = env->functions->GetObjectClass;
    g_is_instance_of = env->functions->IsInstanceOf;
    g_get_object_field = env->functions->GetObjectField;
    g_get_static_object_field = env->functions->GetStaticObjectField;
    g_get_static_boolean_field = env->functions->GetStaticBooleanField;
    g_get_static_int_field = env->functions->GetStaticIntField;
    g_get_boolean_field = env->functions->GetBooleanField;
    g_get_int_field = env->functions->GetIntField;
    g_new_object_v = env->functions->NewObjectV;
    g_new_object_a = env->functions->NewObjectA;
    g_call_object_v = env->functions->CallObjectMethodV;
    g_call_object_a = env->functions->CallObjectMethodA;
    g_call_boolean_v = env->functions->CallBooleanMethodV;
    g_call_boolean_a = env->functions->CallBooleanMethodA;
    g_call_int_v = env->functions->CallIntMethodV;
    g_call_int_a = env->functions->CallIntMethodA;
    g_call_void_v = env->functions->CallVoidMethodV;
    g_call_void_a = env->functions->CallVoidMethodA;
    g_call_static_object_v = env->functions->CallStaticObjectMethodV;
    g_call_static_object_a = env->functions->CallStaticObjectMethodA;
    g_call_static_boolean_v = env->functions->CallStaticBooleanMethodV;
    g_call_static_boolean_a = env->functions->CallStaticBooleanMethodA;
    g_call_static_int_v = env->functions->CallStaticIntMethodV;
    g_call_static_int_a = env->functions->CallStaticIntMethodA;
    g_call_static_void_v = env->functions->CallStaticVoidMethodV;
    g_call_static_void_a = env->functions->CallStaticVoidMethodA;
    g_get_array_length = env->functions->GetArrayLength;
    g_get_object_array_element = env->functions->GetObjectArrayElement;
    g_exception_occurred = env->functions->ExceptionOccurred;
    g_exception_clear = env->functions->ExceptionClear;
    g_exception_check = env->functions->ExceptionCheck;
    g_throw = env->functions->Throw;
    g_throw_new = env->functions->ThrowNew;
    g_get_string_utf = env->functions->GetStringUTFChars;
    g_release_string_utf = env->functions->ReleaseStringUTFChars;
    g_new_global_ref = env->functions->NewGlobalRef;
    g_delete_global_ref = env->functions->DeleteGlobalRef;
    g_delete_local_ref = env->functions->DeleteLocalRef;

    InitializeHelpers(env);
    if (g_class_get_name == nullptr) {
        TRACEI("jni: could not resolve Class.getName");
        return JNI_FALSE;
    }

    std::vector<Patch> patches;
    patches.push_back(
        {offsetof(JNINativeInterface, FindClass) / sizeof(void *),
         reinterpret_cast<void *>(&HookFindClass),
         reinterpret_cast<void **>(&g_find_class)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetMethodID) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetMethodID),
         reinterpret_cast<void **>(&g_get_method_id)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetStaticMethodID) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetStaticMethodID),
         reinterpret_cast<void **>(&g_get_static_method_id)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetFieldID) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetFieldID),
         reinterpret_cast<void **>(&g_get_field_id)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetStaticFieldID) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetStaticFieldID),
         reinterpret_cast<void **>(&g_get_static_field_id)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetObjectField) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetObjectField),
         reinterpret_cast<void **>(&g_get_object_field)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetStaticObjectField) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetStaticObjectField),
         reinterpret_cast<void **>(&g_get_static_object_field)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetStaticBooleanField) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetStaticBooleanField),
         reinterpret_cast<void **>(&g_get_static_boolean_field)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetStaticIntField) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetStaticIntField),
         reinterpret_cast<void **>(&g_get_static_int_field)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetBooleanField) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetBooleanField),
         reinterpret_cast<void **>(&g_get_boolean_field)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetIntField) / sizeof(void *),
         reinterpret_cast<void *>(&HookGetIntField),
         reinterpret_cast<void **>(&g_get_int_field)});
    patches.push_back(
        {offsetof(JNINativeInterface, NewObjectV) / sizeof(void *),
         reinterpret_cast<void *>(&HookNewObjectV),
         reinterpret_cast<void **>(&g_new_object_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, NewObjectA) / sizeof(void *),
         reinterpret_cast<void *>(&HookNewObjectA),
         reinterpret_cast<void **>(&g_new_object_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallObjectMethodV) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallObjectMethodV),
         reinterpret_cast<void **>(&g_call_object_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallObjectMethodA) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallObjectMethodA),
         reinterpret_cast<void **>(&g_call_object_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallBooleanMethodV) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallBooleanMethodV),
         reinterpret_cast<void **>(&g_call_boolean_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallBooleanMethodA) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallBooleanMethodA),
         reinterpret_cast<void **>(&g_call_boolean_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallIntMethodV) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallIntMethodV),
         reinterpret_cast<void **>(&g_call_int_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallIntMethodA) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallIntMethodA),
         reinterpret_cast<void **>(&g_call_int_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallVoidMethodV) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallVoidMethodV),
         reinterpret_cast<void **>(&g_call_void_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallVoidMethodA) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallVoidMethodA),
         reinterpret_cast<void **>(&g_call_void_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallStaticObjectMethodV) /
             sizeof(void *),
         reinterpret_cast<void *>(&HookCallStaticObjectMethodV),
         reinterpret_cast<void **>(&g_call_static_object_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallStaticObjectMethodA) /
             sizeof(void *),
         reinterpret_cast<void *>(&HookCallStaticObjectMethodA),
         reinterpret_cast<void **>(&g_call_static_object_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallStaticBooleanMethodV) /
             sizeof(void *),
         reinterpret_cast<void *>(&HookCallStaticBooleanMethodV),
         reinterpret_cast<void **>(&g_call_static_boolean_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallStaticBooleanMethodA) /
             sizeof(void *),
         reinterpret_cast<void *>(&HookCallStaticBooleanMethodA),
         reinterpret_cast<void **>(&g_call_static_boolean_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallStaticIntMethodV) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallStaticIntMethodV),
         reinterpret_cast<void **>(&g_call_static_int_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallStaticIntMethodA) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallStaticIntMethodA),
         reinterpret_cast<void **>(&g_call_static_int_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallStaticVoidMethodV) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallStaticVoidMethodV),
         reinterpret_cast<void **>(&g_call_static_void_v)});
    patches.push_back(
        {offsetof(JNINativeInterface, CallStaticVoidMethodA) / sizeof(void *),
         reinterpret_cast<void *>(&HookCallStaticVoidMethodA),
         reinterpret_cast<void **>(&g_call_static_void_a)});
    patches.push_back(
        {offsetof(JNINativeInterface, GetObjectArrayElement) /
             sizeof(void *),
         reinterpret_cast<void *>(&HookGetObjectArrayElement),
         reinterpret_cast<void **>(&g_get_object_array_element)});
    patches.push_back(
        {offsetof(JNINativeInterface, Throw) / sizeof(void *),
         reinterpret_cast<void *>(&HookThrow),
         reinterpret_cast<void **>(&g_throw)});
    patches.push_back(
        {offsetof(JNINativeInterface, ThrowNew) / sizeof(void *),
         reinterpret_cast<void *>(&HookThrowNew),
         reinterpret_cast<void **>(&g_throw_new)});
    patches.push_back(
        {offsetof(JNINativeInterface, ExceptionOccurred) / sizeof(void *),
         reinterpret_cast<void *>(&HookExceptionOccurred),
         reinterpret_cast<void **>(&g_exception_occurred)});
    patches.push_back(
        {offsetof(JNINativeInterface, ExceptionClear) / sizeof(void *),
         reinterpret_cast<void *>(&HookExceptionClear),
         reinterpret_cast<void **>(&g_exception_clear)});
    patches.push_back(
        {offsetof(JNINativeInterface, ExceptionCheck) / sizeof(void *),
         reinterpret_cast<void *>(&HookExceptionCheck),
         reinterpret_cast<void **>(&g_exception_check)});

    if (!ApplyPatches(env, &patches)) return JNI_FALSE;

    g_enabled.store(true);
    TRACEI("jni: trace installed, libpairipcore=[%p, %p)",
           reinterpret_cast<void *>(g_pairip_range.begin),
           reinterpret_cast<void *>(g_pairip_range.end));
    return JNI_TRUE;
}
