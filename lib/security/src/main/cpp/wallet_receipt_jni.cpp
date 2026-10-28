#include <jni.h>
#include <array>
#include <charconv>
#include <cstdint>
#include <vector>
#include <string>
#include "persisted.h"
#include "embedded.h"

namespace {
constexpr jsize limit = 1048576;
void fail(JNIEnv *env, const char *type, const char *message) {
  if (!env->ExceptionCheck()) {
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
  }
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_pq_QuantumWalletTransactionNative_nativeVerify(
    JNIEnv *env, jobject, jint workchain, jbyteArray account, jstring time,
    jbyteArray hash, jbyteArray boc) {
  if (workchain != 0 || !account || !hash || !boc || !time ||
      env->GetArrayLength(account) != 32 || env->GetArrayLength(hash) != 32 ||
      env->GetArrayLength(boc) < 1 || env->GetArrayLength(boc) > limit ||
      env->GetStringLength(time) < 1 || env->GetStringLength(time) > 20) {
    fail(env, "java/lang/IllegalArgumentException", "Invalid transaction input bounds");
    return nullptr;
  }
  // UTF-16 digits are checked before conversion; reject signs, leading zeroes,
  // non-ASCII numerals and UInt64 overflow without a signed Long conversion.
  std::array<jchar, 20> digits{};
  const jsize count = env->GetStringLength(time);
  env->GetStringRegion(time, 0, count, digits.data());
  if (env->ExceptionCheck()) return nullptr;
  std::array<char, 20> text{};
  for (jsize i = 0; i < count; ++i) {
    if (digits[i] < '0' || digits[i] > '9' || (i == 0 && digits[i] == '0')) {
      fail(env, "java/lang/IllegalArgumentException", "Invalid logical time");
      return nullptr;
    }
    text[i] = static_cast<char>(digits[i]);
  }
  uint64_t logical_time = 0;
  auto parsed = std::from_chars(text.data(), text.data() + count, logical_time);
  if (parsed.ec != std::errc{} || parsed.ptr != text.data() + count) {
    fail(env, "java/lang/IllegalArgumentException", "Invalid logical time");
    return nullptr;
  }
  try {
    std::array<uint8_t, 32> account_bytes{}, hash_bytes{};
    std::vector<uint8_t> input(env->GetArrayLength(boc));
    std::vector<char> output(limit);
    env->GetByteArrayRegion(account, 0, 32, reinterpret_cast<jbyte *>(account_bytes.data()));
    env->GetByteArrayRegion(hash, 0, 32, reinterpret_cast<jbyte *>(hash_bytes.data()));
    env->GetByteArrayRegion(boc, 0, input.size(), reinterpret_cast<jbyte *>(input.data()));
    if (env->ExceptionCheck()) return nullptr;
    size_t used = 0;
    int status = tos_proof_verify_wallet_transaction(workchain, account_bytes.data(), logical_time,
        hash_bytes.data(), input.data(), input.size(), output.data(), output.size(), &used);
    if (status != 0 || used == 0 || used > output.size()) {
      fail(env, "java/lang/IllegalArgumentException", "Transaction verification refused");
      return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(used));
    if (result) env->SetByteArrayRegion(result, 0, used, reinterpret_cast<const jbyte *>(output.data()));
    return result;
  } catch (...) {
    fail(env, "java/lang/IllegalStateException", "Transaction verifier allocation failed");
    return nullptr;
  }
}

namespace {
struct SimulationQuery { JNIEnv *env; jobject transport; jmethodID method; };
int simulation_query(void *opaque, const uint8_t *data, size_t size, uint8_t *output, size_t capacity, size_t *used) {
  auto &call = *static_cast<SimulationQuery *>(opaque);
  auto *env = call.env;
  *used = 0;
  if (!size || size > 16384 || !capacity || capacity > 67108864 || env->ExceptionCheck()) return -1;
  auto request = env->NewByteArray(static_cast<jsize>(size));
  if (!request) return -1;
  env->SetByteArrayRegion(request, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
  auto response = env->ExceptionCheck() ? nullptr : static_cast<jbyteArray>(env->CallObjectMethod(call.transport, call.method, request, static_cast<jint>(capacity)));
  env->DeleteLocalRef(request);
  if (env->ExceptionCheck() || !response) return -1;
  const auto length = env->GetArrayLength(response);
  if (length <= 0 || static_cast<size_t>(length) > capacity) { env->DeleteLocalRef(response); return -1; }
  env->GetByteArrayRegion(response, 0, length, reinterpret_cast<jbyte *>(output));
  env->DeleteLocalRef(response);
  if (env->ExceptionCheck()) return -1;
  *used = static_cast<size_t>(length);
  return 0;
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_pq_QuantumSimulationNative_nativeSimulate(
    JNIEnv *env, jobject, jstring directory, jbyteArray anchor, jbyteArray request,
    jlong now, jbyteArray message, jobject transport) {
  if (!directory || !anchor || !request || !message || !transport || now <= 0 || now > 0xffffffffLL ||
      env->GetStringLength(directory) == 0 || env->GetStringUTFLength(directory) > 4096 ||
      env->GetArrayLength(anchor) < 1 || env->GetArrayLength(anchor) > limit ||
      env->GetArrayLength(request) < 1 || env->GetArrayLength(request) > limit ||
      env->GetArrayLength(message) < 1 || env->GetArrayLength(message) > limit) {
    fail(env, "java/lang/IllegalArgumentException", "Invalid local simulation bounds"); return nullptr;
  }
  try {
    const auto count = env->GetStringLength(directory);
    std::vector<jchar> path_units(count);
    env->GetStringRegion(directory, 0, count, path_units.data());
    if (env->ExceptionCheck()) return nullptr;
    for (const auto unit : path_units) if (unit == 0) {
      fail(env, "java/lang/IllegalArgumentException", "Invalid checkpoint path"); return nullptr;
    }
    const char *chars = env->GetStringUTFChars(directory, nullptr);
    if (!chars) return nullptr;
    std::string path;
    try { path.assign(chars); } catch (...) { env->ReleaseStringUTFChars(directory, chars); throw; }
    env->ReleaseStringUTFChars(directory, chars);
    std::vector<uint8_t> a(env->GetArrayLength(anchor)), r(env->GetArrayLength(request)), m(env->GetArrayLength(message));
    env->GetByteArrayRegion(anchor, 0, static_cast<jsize>(a.size()), reinterpret_cast<jbyte *>(a.data()));
    env->GetByteArrayRegion(request, 0, static_cast<jsize>(r.size()), reinterpret_cast<jbyte *>(r.data()));
    env->GetByteArrayRegion(message, 0, static_cast<jsize>(m.size()), reinterpret_cast<jbyte *>(m.data()));
    if (env->ExceptionCheck()) return nullptr;
    auto klass = env->GetObjectClass(transport);
    if (!klass) return nullptr;
    auto method = env->GetMethodID(klass, "query", "([BI)[B"); env->DeleteLocalRef(klass);
    if (!method || env->ExceptionCheck()) return nullptr;
    SimulationQuery callback{env, transport, method};
    std::vector<char> output(2097152); size_t used = 0;
    const int status = tos_proof_acquire_simulate_self_pay_persisted(path.c_str(), reinterpret_cast<const char *>(a.data()), a.size(),
        reinterpret_cast<const char *>(r.data()), r.size(), now, simulation_query, &callback, m.data(), m.size(), output.data(), output.size(), &used);
    if (env->ExceptionCheck()) return nullptr; // Preserve the original transport exception.
    if (status != 0 || used == 0 || used > output.size()) {
      auto type = env->FindClass("network/tos/security/pq/QuantumSimulationNative$Refused");
      if (type) { auto init = env->GetMethodID(type, "<init>", "(I)V");
        if (init) { auto error = static_cast<jthrowable>(env->NewObject(type, init, status)); if (error) env->Throw(error); } }
      return nullptr;
    }
    auto result = env->NewByteArray(static_cast<jsize>(used));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(used), reinterpret_cast<const jbyte *>(output.data()));
    return env->ExceptionCheck() ? nullptr : result;
  } catch (...) { fail(env, "java/lang/IllegalStateException", "Local simulation allocation failed"); return nullptr; }
}
