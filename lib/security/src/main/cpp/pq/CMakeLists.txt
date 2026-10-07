cmake_minimum_required(VERSION 3.16)
project(tos_mobile_pq LANGUAGES C CXX)
set(F vendor/falcon)
add_library(tospq STATIC tos_quantum_kdf.c tos_quantum.c vendor/slhdsa/slh_dsa.c vendor/slhdsa/slh_sha2.c
 vendor/slhdsa/sha2_256.c vendor/slhdsa/sha2_512.c tos_pq.c falcon512-native.c vendor/mldsa/mldsa_native.c
 ${F}/falcon.c ${F}/codec.c ${F}/common.c ${F}/shake.c ${F}/vrfy.c
 ${F}/keygen.c ${F}/sign.c ${F}/fft.c ${F}/fpr.c ${F}/rng.c)
set_target_properties(tospq PROPERTIES C_STANDARD 99 POSITION_INDEPENDENT_CODE YES)
target_include_directories(tospq PRIVATE . vendor/mldsa vendor/falcon vendor/slhdsa PUBLIC include)
target_compile_definitions(tospq PRIVATE MLD_CONFIG_FILE="mldsa-config.h"
 FALCON_FPEMU=1 FALCON_FPNATIVE=0 FALCON_AVX2=0 FALCON_FMA=0
 FALCON_PREFIX=tos_mobile_falcon_inner FALCON_RAND_GETENTROPY=0
 FALCON_RAND_URANDOM=0 FALCON_RAND_WIN32=0)

add_library(tos_mobile_lms STATIC vendor/lms/lms-fee.cpp vendor/lms/wallet-lms-fee-c.cpp)
set_target_properties(tos_mobile_lms PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED YES POSITION_INDEPENDENT_CODE YES)
target_compile_definitions(tos_mobile_lms PRIVATE TOS_LMS_PORTABLE_SHA256=1)
target_include_directories(tos_mobile_lms PRIVATE vendor/slhdsa)
target_link_libraries(tos_mobile_lms PUBLIC tospq)

add_library(tos_mobile_lms_primitives STATIC vendor/lms-reference/hss_derive.c
 vendor/lms-reference/hss_zeroize.c vendor/lms-reference/lm_common.c
 vendor/lms-reference/lm_ots_common.c vendor/lms-reference/lm_ots_sign.c
 vendor/lms-reference/endian.c vendor/lms-reference/hash.c)
set_target_properties(tos_mobile_lms_primitives PROPERTIES C_STANDARD 99 POSITION_INDEPENDENT_CODE YES)
target_include_directories(tos_mobile_lms_primitives PRIVATE vendor/lms-reference vendor/slhdsa include)
target_compile_definitions(tos_mobile_lms_primitives PRIVATE EXT_SHA256_H="tos_lms_sha256.h")
target_link_libraries(tos_mobile_lms_primitives PUBLIC tospq)
target_sources(tos_mobile_lms PRIVATE vendor/lms/wallet-lms-sign-c.cpp)
target_include_directories(tos_mobile_lms PRIVATE vendor/lms-reference include)
target_link_libraries(tos_mobile_lms PUBLIC tos_mobile_lms_primitives)
