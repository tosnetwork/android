cmake_minimum_required(VERSION 3.10.2)

set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)

project("SodiumLib")

find_package(sodium REQUIRED CONFIG)

add_library(libsodium SHARED sodium_lib.cpp)
set_target_properties(libsodium PROPERTIES LINK_FLAGS
        "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

find_library(log-lib log)

# Relink the bundled static implementation with the wrapper's 16KB flags.
target_link_libraries(libsodium sodium::sodium-static ${log-lib})

add_subdirectory(pq)
add_library(tos_mobile_pq SHARED pq_jni.cpp)
if(NOT TOS_FEE_STATE_TARGET_DIR)
  message(FATAL_ERROR "The V5R2 fee state source build must supply TOS_FEE_STATE_TARGET_DIR")
endif()
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(FEE_STATE_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(FEE_STATE_TARGET armv7-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86")
  set(FEE_STATE_TARGET i686-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(FEE_STATE_TARGET x86_64-linux-android)
else()
  message(FATAL_ERROR "Unsupported V5R2 fee state ABI")
endif()
add_library(tos_fee_state STATIC IMPORTED)
set_target_properties(tos_fee_state PROPERTIES IMPORTED_LOCATION
  "${TOS_FEE_STATE_TARGET_DIR}/${FEE_STATE_TARGET}/release/liblms_fee_state.a")
target_link_libraries(tos_mobile_pq PRIVATE tospq tos_fee_state ${CMAKE_DL_LIBS} log android)
set_target_properties(tos_mobile_pq PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
