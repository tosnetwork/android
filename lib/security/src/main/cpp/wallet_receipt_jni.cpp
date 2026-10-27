#include <jni.h>
#include <array>
#include <charconv>
#include <cstdint>
#include <vector>
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
