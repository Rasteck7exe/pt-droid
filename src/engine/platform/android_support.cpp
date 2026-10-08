#include "engine/platform/android_support.h"

#ifdef __ANDROID__

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <signal.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_system.h>

#include <cstring>
#include <exception>
#include <fstream>
#include <sstream>
#include <string>

#include "engine/core/log.h"

namespace pt::android {
namespace {

constexpr const char* kAssetRoot = "pt/";
constexpr const char* kStampFile = ".stamp";

std::filesystem::path Utf8(const char* text) { return std::filesystem::path(reinterpret_cast<const char8_t*>(text)); }

std::string ReadAsset(const std::string& path) {
    size_t size = 0;
    void* data = SDL_LoadFile(path.c_str(), &size);
    if (!data) return {};
    std::string text(static_cast<const char*>(data), size);
    SDL_free(data);
    return text;
}

bool CopyAsset(const std::string& asset, const std::filesystem::path& target) {
    SDL_IOStream* in = SDL_IOFromFile(asset.c_str(), "rb");
    if (!in) {
        LogError("android: asset {} cannot be opened: {}", asset, SDL_GetError());
        return false;
    }
    std::error_code error;
    std::filesystem::create_directories(target.parent_path(), error);
    const std::filesystem::path partial = target.string() + ".part";
    std::ofstream out(partial, std::ios::binary | std::ios::trunc);
    bool ok = static_cast<bool>(out);
    std::vector<char> buffer(1 << 20);
    while (ok) {
        const size_t n = SDL_ReadIO(in, buffer.data(), buffer.size());
        if (n == 0) {
            ok = SDL_GetIOStatus(in) == SDL_IO_STATUS_EOF;
            break;
        }
        out.write(buffer.data(), static_cast<std::streamsize>(n));
        ok = static_cast<bool>(out);
    }
    SDL_CloseIO(in);
    out.close();
    if (ok) {
        std::filesystem::rename(partial, target, error);
        ok = !error;
    }
    if (!ok) {
        std::filesystem::remove(partial, error);
        LogError("android: {} could not be copied to {}", asset, target.string());
    }
    return ok;
}

// calls a no-argument method of the running activity (PTActivity); `boolean` methods return their value, `void` ones true
bool CallActivity(const char* name, const char* signature) {
    auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    auto activity = static_cast<jobject>(SDL_GetAndroidActivity());
    if (!env || !activity) return false;
    bool result = false;
    jclass type = env->GetObjectClass(activity);
    jmethodID method = env->GetMethodID(type, name, signature);
    if (method) {
        if (signature[2] == 'Z') {
            result = env->CallBooleanMethod(activity, method) == JNI_TRUE;
        } else {
            env->CallVoidMethod(activity, method);
            result = true;
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = false;
    }
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(activity);
    return result;
}

}

std::filesystem::path ResourceRoot() {
    const char* internal = SDL_GetAndroidInternalStoragePath();
    return internal ? Utf8(internal) / "res" : std::filesystem::path("res");
}

bool PrepareResources() {
    const std::string index = ReadAsset(std::string(kAssetRoot) + "index.txt");
    if (index.empty()) {
        LogError("android: the APK has no {}index.txt; the shaders and fonts are missing", kAssetRoot);
        return false;
    }
    std::istringstream lines(index);
    std::string stamp;
    std::getline(lines, stamp);
    const std::filesystem::path root = ResourceRoot();
    {
        std::ifstream current(root / kStampFile, std::ios::binary);
        std::string copied;
        if (current && std::getline(current, copied) && copied == stamp) {
            return true;
        }
    }
    LogInfo("android: copying the resources of this build to {}", root.string());
    std::error_code error;
    std::filesystem::remove_all(root, error);
    std::filesystem::create_directories(root, error);
    const auto start = SDL_GetTicks();
    size_t files = 0;
    bool ok = true;
    for (std::string path; std::getline(lines, path);) {
        if (!path.empty() && path.back() == '\r') path.pop_back();
        if (path.empty()) continue;
        ok = CopyAsset(kAssetRoot + path, root / Utf8(path.c_str())) && ok;
        ++files;
    }
    if (ok) {
        std::ofstream(root / kStampFile, std::ios::binary | std::ios::trunc) << stamp << '\n';
    }
    LogInfo("android: {} resource files copied in {} ms{}", files, SDL_GetTicks() - start, ok ? "" : " (with errors)");
    return ok;
}

std::filesystem::path SharedGameDir() { return "/storage/emulated/0/PT/CUSA01127"; }

std::filesystem::path AppGameDir() {
    const char* external = SDL_GetAndroidExternalStoragePath();
    return external ? Utf8(external) / "CUSA01127" : std::filesystem::path();
}

std::vector<std::filesystem::path> GameDirCandidates() {
    std::vector<std::filesystem::path> dirs{SharedGameDir(), "/storage/emulated/0/PT", "/sdcard/PT/CUSA01127"};
    if (const char* external = SDL_GetAndroidExternalStoragePath()) {
        const std::filesystem::path app = Utf8(external);
        dirs.push_back(app / "CUSA01127");
        dirs.push_back(app / "game" / "CUSA01127");
        dirs.push_back(app);
    }
    return dirs;
}

// ---- crash reporting ------------------------------------------------------------------------------------------------

namespace {

constexpr int kCrashSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
struct sigaction g_previous[NSIG];
char g_crash_text[24 * 1024];
size_t g_crash_length = 0;
alignas(16) char g_alt_stack[64 * 1024];

// the crash text is built without allocating or locking (a signal handler may run in the middle of either)
void Append(const char* text) {
    while (*text && g_crash_length + 1 < sizeof(g_crash_text)) g_crash_text[g_crash_length++] = *text++;
    g_crash_text[g_crash_length] = '\0';
}

void AppendHex(uintptr_t value) {
    char digits[19] = "0x";
    for (int i = 0; i < 16; ++i) {
        const int d = static_cast<int>((value >> (60 - 4 * i)) & 0xF);
        digits[2 + i] = static_cast<char>(d < 10 ? '0' + d : 'a' + d - 10);
    }
    digits[18] = '\0';
    Append(digits);
}

void AppendDecimal(long value) {
    char digits[24];
    int n = 0;
    bool negative = value < 0;
    unsigned long v = negative ? static_cast<unsigned long>(-value) : static_cast<unsigned long>(value);
    do {
        digits[n++] = static_cast<char>('0' + v % 10);
        v /= 10;
    } while (v && n < 22);
    if (negative) digits[n++] = '-';
    char out[24];
    for (int i = 0; i < n; ++i) out[i] = digits[n - 1 - i];
    out[n] = '\0';
    Append(out);
}

void AppendFrame(const char* label, uintptr_t pc) {
    Append("  ");
    Append(label);
    Append(" ");
    AppendHex(pc);
    Dl_info info{};
    if (pc && dladdr(reinterpret_cast<void*>(pc), &info) && info.dli_fname) {
        const char* name = info.dli_fname;
        for (const char* p = info.dli_fname; *p; ++p) {
            if (*p == '/') name = p + 1;
        }
        Append(" ");
        Append(name);
        Append(" +");
        AppendHex(pc - reinterpret_cast<uintptr_t>(info.dli_fbase));
        if (info.dli_sname) {
            Append(" (");
            Append(info.dli_sname);
            Append(")");
        }
    }
    Append("\n");
}

struct UnwindState {
    int count = 0;
};

_Unwind_Reason_Code UnwindStep(_Unwind_Context* context, void* arg) {
    auto* state = static_cast<UnwindState*>(arg);
    const uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        char label[8] = "#";
        int n = state->count;
        label[1] = static_cast<char>('0' + n / 10);
        label[2] = static_cast<char>('0' + n % 10);
        label[3] = '\0';
        AppendFrame(label, pc);
    }
    return ++state->count >= 64 ? _URC_END_OF_STACK : _URC_NO_REASON;
}

const char* SignalName(int signal) {
    switch (signal) {
    case SIGSEGV: return "SIGSEGV";
    case SIGABRT: return "SIGABRT";
    case SIGBUS: return "SIGBUS";
    case SIGFPE: return "SIGFPE";
    case SIGILL: return "SIGILL";
    case SIGTRAP: return "SIGTRAP";
    default: return "signal";
    }
}

void FlushCrashText() {
    const int fd = LogFileDescriptor();
    if (fd >= 0) {
        ssize_t ignored = write(fd, g_crash_text, g_crash_length);
        (void)ignored;
        fsync(fd);
    }
    // logcat takes about 4 KB per line: one line at a time
    char line[1024];
    size_t start = 0;
    while (start < g_crash_length) {
        size_t end = start;
        while (end < g_crash_length && g_crash_text[end] != '\n' && end - start < sizeof(line) - 1) ++end;
        std::memcpy(line, g_crash_text + start, end - start);
        line[end - start] = '\0';
        __android_log_write(ANDROID_LOG_FATAL, "pt", line);
        start = end < g_crash_length && g_crash_text[end] == '\n' ? end + 1 : end;
    }
}

void CrashHandler(int signal, siginfo_t* info, void* context) {
    static volatile sig_atomic_t entered = 0;
    if (!entered) {
        entered = 1;
        g_crash_length = 0;
        Append("\ncrash: ");
        Append(SignalName(signal));
        Append(" (");
        AppendDecimal(signal);
        Append("), code ");
        AppendDecimal(info ? info->si_code : 0);
        Append(", fault address ");
        AppendHex(info ? reinterpret_cast<uintptr_t>(info->si_addr) : 0);
        Append(", thread ");
        AppendDecimal(gettid());
        Append("\n");
#if defined(__aarch64__)
        if (context) {
            const auto* uc = static_cast<const ucontext_t*>(context);
            AppendFrame("pc", uc->uc_mcontext.pc);
            AppendFrame("lr", uc->uc_mcontext.regs[30]);
        }
#endif
        Append("backtrace:\n");
        UnwindState state;
        _Unwind_Backtrace(UnwindStep, &state);
        FlushCrashText();
    }
    // the previous handler (Android's debuggerd) then writes its tombstone; a fault re-raises on return
    sigaction(signal, &g_previous[signal], nullptr);
    if (signal == SIGABRT || signal == SIGTRAP) raise(signal);
}

[[noreturn]] void TerminateHandler() {
    std::string what = "unknown";
    if (const std::exception_ptr current = std::current_exception()) {
        try {
            std::rethrow_exception(current);
        } catch (const std::exception& e) {
            what = e.what();
        } catch (...) {
            what = "a non-standard exception";
        }
        LogError("crash: uncaught C++ exception: {}", what);
    } else {
        LogError("crash: std::terminate without an exception");
    }
    std::abort();
}

}

void InstallCrashHandler() {
    stack_t stack{};
    stack.ss_sp = g_alt_stack;
    stack.ss_size = sizeof(g_alt_stack);
    sigaltstack(&stack, nullptr);
    struct sigaction action {};
    action.sa_sigaction = CrashHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    for (int signal : kCrashSignals) {
        sigaction(signal, &action, &g_previous[signal]);
    }
    std::set_terminate(TerminateHandler);
}

bool HasAllFilesAccess() { return CallActivity("hasFilesAccess", "()Z"); }

void RequestAllFilesAccess() { CallActivity("requestFilesAccess", "()V"); }

}

#endif
