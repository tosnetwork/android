#include <jni.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

namespace {
void fail(JNIEnv *env, const char *message) {
  auto type = env->FindClass("java/lang/IllegalStateException");
  if (type) env->ThrowNew(type, message);
}
struct CloseDirectory { void operator()(DIR *directory) const { closedir(directory); } };
bool legal(const char *name, size_t length) {
  for (size_t i = 0; i < length; ++i) {
    const auto c = static_cast<unsigned char>(name[i]);
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) return false;
  }
  return length > 0 && length <= NAME_MAX;
}
}

// Local storage only. No protocol, cryptographic or TOS dependency.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_network_tos_security_pq_QuantumLocalDirectory_nativeNames(JNIEnv *env, jobject, jstring path, jint limit) {
 try {
  if (!path || limit < 1 || limit > 256 || env->GetStringUTFLength(path) > 4096) {
    fail(env, "Invalid directory enumeration bounds"); return nullptr;
  }
  const char *text = env->GetStringUTFChars(path, nullptr);
  if (!text) return nullptr;
  const int fd = open(text, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
  env->ReleaseStringUTFChars(path, text);
  if (fd < 0) { fail(env, "Cannot open protected directory"); return nullptr; }
  struct stat st {};
  if (fstat(fd, &st) || !S_ISDIR(st.st_mode) || st.st_uid != getuid() || (st.st_mode & 077)) {
    close(fd); fail(env, "Unprotected directory"); return nullptr;
  }
  DIR *raw = fdopendir(fd);
  if (!raw) { close(fd); fail(env, "Cannot enumerate protected directory"); return nullptr; }
  std::unique_ptr<DIR, CloseDirectory> directory(raw);
  std::vector<std::string> names;
  names.reserve(static_cast<size_t>(limit));
  for (;;) {
    errno = 0;
    auto entry = readdir(directory.get());
    if (!entry) {
      if (errno) { fail(env, "Directory enumeration failed"); return nullptr; }
      break;
    }
    const size_t length = strnlen(entry->d_name, NAME_MAX + 1);
    if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
    if (!legal(entry->d_name, length) || names.size() == static_cast<size_t>(limit)) {
      fail(env, "Directory enumeration bound or basename refused"); return nullptr;
    }
    names.emplace_back(entry->d_name, length);
  }
  auto string_type = env->FindClass("java/lang/String");
  if (!string_type) return nullptr;
  auto result = env->NewObjectArray(static_cast<jsize>(names.size()), string_type, nullptr);
  if (!result) return nullptr;
  for (size_t i = 0; i < names.size(); ++i) {
    auto name = env->NewStringUTF(names[i].c_str());
    if (!name) return nullptr;
    env->SetObjectArrayElement(result, static_cast<jsize>(i), name);
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck()) return nullptr;
  }
  return result;
 } catch (const std::exception &) {
   fail(env, "Directory enumeration allocation failed"); return nullptr;
 }
}
