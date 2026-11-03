// Copyright 2026 TOS Network. SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <array>
#include <cstdint>
#include <memory>
#include <new>
#include <vector>
#include "tos_shielded_mobile.h"

namespace {
constexpr jsize kRequestLimit = 2 * 1024 * 1024;
constexpr size_t kResponseLimit = 1024 * 1024;
struct Seed {
  std::array<uint8_t, 32> bytes{};
  ~Seed() {
    volatile uint8_t *p = bytes.data();
    for (size_t i = 0; i < bytes.size(); ++i) p[i] = 0;
  }
};
using Response = std::unique_ptr<TosShieldedResponse, decltype(&tos_shielded_response_free)>;

jbyteArray copy_response(JNIEnv *env, TosShieldedResponse *raw, size_t exact_size = 0) {
  Response response(raw, tos_shielded_response_free);
  if (!response || tos_shielded_response_status(response.get()) != 0) return nullptr;
  const size_t size = tos_shielded_response_size(response.get());
  const uint8_t *data = tos_shielded_response_data(response.get());
  if (size == 0 || size > kResponseLimit || (exact_size != 0 && size != exact_size) || data == nullptr) {
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
  if (result == nullptr || env->ExceptionCheck()) return nullptr;
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
  if (env->ExceptionCheck()) return nullptr;
  return result;
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_descriptor(
    JNIEnv *env, jobject, jbyteArray key, jbyteArray domain, jlong index) {
  if (key == nullptr || domain == nullptr || env->GetArrayLength(key) != 32 || env->GetArrayLength(domain) != 32) {
    return nullptr;
  }
  Seed seed;
  std::array<uint8_t, 32> context{};
  env->GetByteArrayRegion(key, 0, 32, reinterpret_cast<jbyte *>(seed.bytes.data()));
  if (env->ExceptionCheck()) return nullptr;
  env->GetByteArrayRegion(domain, 0, 32, reinterpret_cast<jbyte *>(context.data()));
  if (env->ExceptionCheck()) return nullptr;
  return copy_response(env, tos_shielded_descriptor(seed.bytes.data(), 32, context.data(), 32,
      static_cast<uint64_t>(index)), 2573);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_scan(
    JNIEnv *env, jobject, jbyteArray key, jbyteArray request) {
  if (key == nullptr || request == nullptr || env->GetArrayLength(key) != 32) return nullptr;
  const jsize size = env->GetArrayLength(request);
  if (size <= 0 || size > kRequestLimit) return nullptr;
  Seed seed;
  env->GetByteArrayRegion(key, 0, 32, reinterpret_cast<jbyte *>(seed.bytes.data()));
  if (env->ExceptionCheck()) return nullptr;
  try {
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    return copy_response(env, tos_shielded_scan(seed.bytes.data(), 32, bytes.data(), bytes.size()));
  } catch (const std::bad_alloc &) {
    return nullptr;
  }
}
