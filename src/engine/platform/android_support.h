#pragma once

// The Android side of the platform layer (docs/android.md). Everything here exists only in Android builds.

#ifdef __ANDROID__

#include <filesystem>
#include <string>
#include <vector>

namespace pt::android {

// The folder in the app's internal storage that holds the resources copied out of the APK (shaders/, fonts/, voice/).
// pt::ExecutableDir() returns it on Android, so the desktop lookups (ResourceDir) find the resources there.
std::filesystem::path ResourceRoot();

// Copies the resources the APK carries as assets (pt/index.txt and the files it lists) to ResourceRoot() when the
// index's stamp differs from the copy's, that is on the first start of each new build. False if the APK has no index or
// a file could not be written.
bool PrepareResources();

// The folders searched for the game files (chunk1.psarc, texture.qar, pathid_list_ps4.bin), most expected first.
std::vector<std::filesystem::path> GameDirCandidates();

// /storage/emulated/0/PT/CUSA01127: the folder the person is told to copy the game files to (needs all files access).
std::filesystem::path SharedGameDir();
// <app external files>/CUSA01127: the folder that works without any permission (filled by USB or adb).
std::filesystem::path AppGameDir();

// Writes a native crash (signal, faulting address, registers and a backtrace with library offsets) and uncaught C++
// exceptions to pt.log and logcat before the process dies; the setup screen offers that log to share
// (android/.../SetupActivity.java). The offsets resolve against the unstripped libmain.so the CI attaches to each release.
void InstallCrashHandler();

// The "all files access" permission (MANAGE_EXTERNAL_STORAGE), through the activity (android/.../PTActivity.java).
bool HasAllFilesAccess();
void RequestAllFilesAccess();


// A custom Vulkan driver the person installed on the setup screen (android/.../SetupActivity.java) instead of the phone's
// own, opened through libadrenotools. Returns the driver's vkGetInstanceProcAddr, or null (the reason in `error`, empty when
// none is installed) to use the system driver. The driver is on trial from here until EndGpuDriverTrial(): if the app dies
// in between, the next start drops the driver so it cannot trap the person in a crash loop.
// True when the Vulkan device in use is an Adreno (Qualcomm's PCI vendor id 0x5143, also reported by Turnip): the only GPUs
// libadrenotools and the custom drivers support, so the settings page offers the driver rows only there.
// vk::Context::Init records it once the device is chosen.
void SetGpuVendor(unsigned vendor_id);
bool IsAdrenoGpu();
// The installed driver's name for the settings page (meta.json's "name", else the library's file name); empty when none.
std::string GpuDriverName();
// Opens Android's file picker for a driver .zip (PTActivity.pickGpuDriver); the install happens there and applies at the
// next start. RemoveGpuDriver() goes back to the phone's own driver at the next start.
void PickGpuDriver();
void RemoveGpuDriver();
void* OpenCustomGpuDriver(std::string& error);
bool CustomGpuDriverActive();
// Closes the driver (the one that would not create an instance) so the system driver can be loaded instead.
void CloseCustomGpuDriver();
void EndGpuDriverTrial();

}

#endif
