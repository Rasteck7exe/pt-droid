# Stages the game's resources as APK assets (cmake -P, run by the pt_android_assets target in CMakeLists.txt):
#   OUT/pt/shaders/*.spv   the compiled shaders
#   OUT/pt/fonts/*         the bundled fonts
#   OUT/pt/voice/*.bin     the Whisper and Silero models of the voice part
#   OUT/pt/index.txt       a stamp line, then one path per line relative to OUT/pt
#   OUT/licenses/          the notices of the fonts and the voice models (kept in the APK, not copied out)
# src/engine/platform/android_support.cpp reads the index and copies the files to internal storage when the stamp changes.
foreach(var SHADERS FONTS VOICE OUT)
  if(NOT DEFINED ${var})
    message(FATAL_ERROR "android_assets.cmake: ${var} is not set")
  endif()
endforeach()

set(root "${OUT}/pt")
file(REMOVE_RECURSE "${root}" "${OUT}/licenses")
file(MAKE_DIRECTORY "${root}/shaders" "${root}/fonts" "${root}/voice" "${OUT}/licenses")

file(GLOB shaders "${SHADERS}/*.spv")
file(COPY ${shaders} DESTINATION "${root}/shaders")
file(GLOB fonts "${FONTS}/*")
foreach(font ${fonts})
  get_filename_component(name "${font}" NAME)
  if(name MATCHES "^OFL-.*\\.txt$")
    file(COPY "${font}" DESTINATION "${OUT}/licenses")
  elseif(NOT IS_DIRECTORY "${font}")
    file(COPY "${font}" DESTINATION "${root}/fonts")
  endif()
endforeach()
file(GLOB models "${VOICE}/*.bin")
if(models)
  file(COPY ${models} DESTINATION "${root}/voice")
endif()
if(EXISTS "${VOICE}/licenses")
  file(COPY "${VOICE}/licenses/" DESTINATION "${OUT}/licenses/voice")
endif()

# the stamp: the size and time of every staged file, so a new build with changed resources is copied out again
file(GLOB_RECURSE staged RELATIVE "${root}" "${root}/*")
list(SORT staged)
set(stamp_source "")
set(lines "")
foreach(path ${staged})
  file(SIZE "${root}/${path}" size)
  file(TIMESTAMP "${root}/${path}" time "%Y%m%d%H%M%S" UTC)
  string(APPEND stamp_source "${path}:${size}:${time}\n")
  string(APPEND lines "${path}\n")
endforeach()
string(SHA256 stamp "${stamp_source}")
file(WRITE "${root}/index.txt" "${stamp}\n${lines}")
list(LENGTH staged count)
message(STATUS "android assets: ${count} files staged in ${root}")
