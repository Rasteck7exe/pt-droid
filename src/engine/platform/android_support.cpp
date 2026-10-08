#include "engine/platform/android_support.h"

#ifdef __ANDROID__

#include <jni.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_system.h>

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

bool HasAllFilesAccess() { return CallActivity("hasFilesAccess", "()Z"); }

void RequestAllFilesAccess() { CallActivity("requestFilesAccess", "()V"); }

}

#endif
