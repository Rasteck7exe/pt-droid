#include "engine/core/resource_path.h"

#include <SDL3/SDL.h>

#include "engine/platform/android_support.h"

namespace pt {

std::filesystem::path ExecutableDir() {
#ifdef __ANDROID__
    // the APK's resources live in internal storage once copied out (pt::android::PrepareResources)
    return android::ResourceRoot();
#else
    const char* base = SDL_GetBasePath();
    return base ? std::filesystem::path(reinterpret_cast<const char8_t*>(base)) : std::filesystem::current_path();
#endif
}

std::filesystem::path ResourceDir(std::string_view name, const std::filesystem::path& build_dir) {
    std::error_code ec;
    const std::filesystem::path local = ExecutableDir() / name;
    if (std::filesystem::is_directory(local, ec)) {
        return local;
    }
    return build_dir;
}

}
