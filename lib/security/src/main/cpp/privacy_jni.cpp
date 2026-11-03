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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_poolState(
    JNIEnv *env, jobject, jbyteArray request) {
  if (request == nullptr) return nullptr;
  const jsize size = env->GetArrayLength(request);
  if (size <= 0 || size > kRequestLimit) return nullptr;
  try {
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    return copy_response(env, tos_shielded_pool_state(bytes.data(), bytes.size()));
  } catch (const std::bad_alloc &) {
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_historyTransaction(
    JNIEnv *env, jobject, jbyteArray request) {
  if (request == nullptr) return nullptr;
  const jsize size = env->GetArrayLength(request);
  if (size <= 0 || size > kRequestLimit) return nullptr;
  try {
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    return copy_response(env, tos_shielded_history_transaction(bytes.data(), bytes.size()));
  } catch (const std::bad_alloc &) {
    return nullptr;
  }
}

namespace {
TosShieldedReplay *replay_handle(jlong handle) {
  return reinterpret_cast<TosShieldedReplay *>(static_cast<uintptr_t>(handle));
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_createReplay(JNIEnv *env, jobject, jbyteArray request) {
  if (request == nullptr) return 0;
  const jsize size = env->GetArrayLength(request);
  if (size <= 0 || size > kRequestLimit) return 0;
  try {
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) return 0;
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(tos_shielded_replay_create(bytes.data(), bytes.size())));
  } catch (const std::bad_alloc &) { return 0; }
}
extern "C" JNIEXPORT jint JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_pushReplay(JNIEnv *env, jobject, jlong handle, jbyteArray request) {
  auto *session = replay_handle(handle);
  if (request == nullptr) return tos_shielded_replay_push(session, nullptr, 0);
  const jsize size = env->GetArrayLength(request);
  if (size <= 0 || size > kRequestLimit) return tos_shielded_replay_push(session, nullptr, 0);
  try {
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) return tos_shielded_replay_push(session, nullptr, 0);
    return tos_shielded_replay_push(session, bytes.data(), bytes.size());
  } catch (const std::bad_alloc &) { return tos_shielded_replay_push(session, nullptr, 0); }
}
extern "C" JNIEXPORT jint JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_finishReplay(JNIEnv *, jobject, jlong handle) {
  return tos_shielded_replay_finish(replay_handle(handle));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_replayInfo(JNIEnv *env, jobject, jlong handle) {
  return copy_response(env, tos_shielded_replay_info(replay_handle(handle)));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_scanReplay(JNIEnv *env, jobject, jlong handle, jbyteArray seed, jint offset, jint count) {
  if (seed == nullptr || env->GetArrayLength(seed) != 32 || offset < 0 || count <= 0 || count > 512) return nullptr;
  Seed key;
  env->GetByteArrayRegion(seed, 0, 32, reinterpret_cast<jbyte *>(key.bytes.data()));
  if (env->ExceptionCheck()) return nullptr;
  return copy_response(env, tos_shielded_replay_scan(replay_handle(handle), key.bytes.data(), 32, static_cast<size_t>(offset), static_cast<size_t>(count)));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_summaryReplay(JNIEnv *env, jobject, jlong handle, jbyteArray seed) {
  if (seed == nullptr || env->GetArrayLength(seed) != 32 || replay_handle(handle) == nullptr) return nullptr;
  Seed key;
  env->GetByteArrayRegion(seed, 0, 32, reinterpret_cast<jbyte *>(key.bytes.data()));
  if (env->ExceptionCheck()) return nullptr;
  return copy_response(env, tos_shielded_replay_summary(replay_handle(handle), key.bytes.data(), key.bytes.size()));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_replayIntentAccepted(
    JNIEnv *env, jobject, jlong handle, jbyteArray digest) {
  std::array<uint8_t, 32> bytes{};
  if (!handle || !digest || env->GetArrayLength(digest) != static_cast<jsize>(bytes.size())) return nullptr;
  env->GetByteArrayRegion(digest, 0, bytes.size(), reinterpret_cast<jbyte *>(bytes.data()));
  if (env->ExceptionCheck()) return nullptr;
  return copy_response(env, tos_shielded_replay_intent_accepted(replay_handle(handle), bytes.data(), bytes.size()), 1);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_validateRecipient(
    JNIEnv *env, jobject, jlong handle, jbyteArray descriptor) {
  std::array<uint8_t, 2573> bytes{};
  if (!handle || !descriptor || env->GetArrayLength(descriptor) != static_cast<jsize>(bytes.size())) return JNI_FALSE;
  env->GetByteArrayRegion(descriptor, 0, bytes.size(), reinterpret_cast<jbyte *>(bytes.data()));
  if (env->ExceptionCheck()) return JNI_FALSE;
  Response result(tos_shielded_replay_validate_recipient(replay_handle(handle), bytes.data(), bytes.size()),
                  tos_shielded_response_free);
  return result && tos_shielded_response_status(result.get()) == 0;
}

static jbyteArray wallet_replay_operation(JNIEnv *env, jlong handle, jbyteArray seed, jbyteArray root,
                                          jbyteArray binding, decltype(&tos_shielded_replay_receive) operation,
                                          size_t result_size) {
  if (replay_handle(handle) == nullptr || seed == nullptr || root == nullptr ||
      binding == nullptr || env->GetArrayLength(seed) != 32 ||
      env->GetArrayLength(binding) != 228) return nullptr;
  const jsize root_length = env->GetArrayLength(root);
  if (root_length <= 0 || root_length > 4096) return nullptr;

  Seed key;
  std::array<uint8_t, 4096> path{};
  std::array<uint8_t, 228> pins{};
  env->GetByteArrayRegion(seed, 0, 32, reinterpret_cast<jbyte *>(key.bytes.data()));
  if (env->ExceptionCheck()) return nullptr;
  env->GetByteArrayRegion(root, 0, root_length, reinterpret_cast<jbyte *>(path.data()));
  if (env->ExceptionCheck()) return nullptr;
  env->GetByteArrayRegion(binding, 0, 228, reinterpret_cast<jbyte *>(pins.data()));
  if (env->ExceptionCheck()) return nullptr;
  return copy_response(env, operation(
      replay_handle(handle), key.bytes.data(), key.bytes.size(), path.data(),
      static_cast<size_t>(root_length), pins.data(), pins.size()), result_size);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_receiveReplay(
    JNIEnv *env, jobject, jlong handle, jbyteArray seed, jbyteArray root, jbyteArray binding) {
  return wallet_replay_operation(env, handle, seed, root, binding, tos_shielded_replay_receive, 2573);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_pendingStatus(
    JNIEnv *env, jobject, jlong handle, jbyteArray seed, jbyteArray root, jbyteArray binding) {
  return wallet_replay_operation(env, handle, seed, root, binding, tos_shielded_replay_pending_status, 1);
}

extern "C" JNIEXPORT void JNICALL
Java_network_tos_security_privacy_PrivacyNativeBridge_freeReplay(JNIEnv *, jobject, jlong handle) {
  tos_shielded_replay_free(replay_handle(handle));
}
