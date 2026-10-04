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
target_link_libraries(tos_mobile_pq PRIVATE tospq)
set_target_properties(tos_mobile_pq PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
