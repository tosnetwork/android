#include <jni.h>
#include <array>
#include <vector>
#include "pq/include/tos_pq.h"
#include "pq/include/tos_v5r2.h"
#include "pq/include/tos_fee_state.h"
static std::vector<uint8_t> bytes(JNIEnv *e,jbyteArray a,size_t max) {
 if(!a) return {}; jsize n=e->GetArrayLength(a);
 if(n<0 || (size_t)n>max) return {};
 std::vector<uint8_t> v(n); e->GetByteArrayRegion(a,0,n,(jbyte*)v.data()); return v;
}
static jbyteArray out(JNIEnv *e,const uint8_t *p,size_t n) {
 auto a=e->NewByteArray(n); if(a) e->SetByteArrayRegion(a,0,n,(const jbyte*)p); return a;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_pq_PqNative_publicKey(JNIEnv *e,jobject,jint a,jbyteArray seed) {
 auto s=bytes(e,seed,32); std::array<uint8_t,1312> pk{}; auto n=tos_pq_public_key_size(a);
 int rc=tos_pq_public_key(a,s.data(),s.size(),pk.data(),n);
 tos_pq_clear(s.data(),s.size()); return rc?nullptr:out(e,pk.data(),n);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_pq_PqNative_sign(JNIEnv *e,jobject,jint a,jbyteArray seed,jbyteArray entropy,jbyteArray message) {
 auto s=bytes(e,seed,32), r=bytes(e,entropy,48),m=bytes(e,message,97);
 std::array<uint8_t,2420> sig{}; auto n=tos_pq_signature_size(a);
 int rc=tos_pq_sign(a,s.data(),s.size(),r.data(),r.size(),m.data(),m.size(),sig.data(),n);
 tos_pq_clear(s.data(),s.size());tos_pq_clear(r.data(),r.size());
 auto result=rc?nullptr:out(e,sig.data(),n);tos_pq_clear(sig.data(),sig.size());return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_network_tos_security_pq_PqNative_verify(JNIEnv *e,jobject,jint a,jbyteArray key,jbyteArray message,jbyteArray signature) {
 auto pk=bytes(e,key,1312),m=bytes(e,message,97),s=bytes(e,signature,2420);
 return tos_pq_verify(a,pk.data(),pk.size(),m.data(),m.size(),s.data(),s.size())==1;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_pq_V5R2Native_publicKey(JNIEnv *e,jobject,jint role,jbyteArray seed) {
 const size_t n=tos_v5r2_public_key_size(role); if(!n) return nullptr;
 auto s=bytes(e,seed,48); std::array<uint8_t,1312> pk{};
 int rc=tos_v5r2_public_key(role,s.data(),s.size(),pk.data(),n);
 tos_pq_clear(s.data(),s.size()); return rc?nullptr:out(e,pk.data(),n);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_pq_V5R2Native_sign(JNIEnv *e,jobject,jint role,jint purpose,jbyteArray seed,jbyteArray entropy,jbyteArray digest) {
 const size_t n=tos_v5r2_signature_size(role); if(!n) return nullptr;
 auto s=bytes(e,seed,48),r=bytes(e,entropy,32),d=bytes(e,digest,32);
 std::array<uint8_t,7856> sig{};
 int rc=tos_v5r2_sign(role,purpose,s.data(),s.size(),r.data(),r.size(),d.data(),d.size(),sig.data(),n);
 tos_pq_clear(s.data(),s.size()); tos_pq_clear(r.data(),r.size());
 auto result=rc?nullptr:out(e,sig.data(),n);tos_pq_clear(sig.data(),sig.size());return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_network_tos_security_pq_V5R2Native_verify(JNIEnv *e,jobject,jint role,jint purpose,jbyteArray key,jbyteArray digest,jbyteArray signature) {
 auto pk=bytes(e,key,1312),d=bytes(e,digest,32),s=bytes(e,signature,7856);
 return tos_v5r2_verify(role,purpose,pk.data(),pk.size(),d.data(),d.size(),s.data(),s.size())==1;
}
static bool state_u32(jlong value) { return value>=0 && (uint64_t)value<=UINT32_MAX; }
static jlongArray state_result(JNIEnv *e,int32_t status,uint64_t value) {
 if(e->ExceptionCheck()) return nullptr;
 jlong fields[2]={(jlong)status,(jlong)value};auto result=e->NewLongArray(2);
 if(result) e->SetLongArrayRegion(result,0,2,fields);return result;
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_network_tos_security_pq_FeeStateNative_open(JNIEnv *e,jobject,jbyteArray path,jint globalId,jbyteArray network,jbyteArray vault,jbyteArray tree,jlong epoch,jlong time) {
 auto p=bytes(e,path,4096),n=bytes(e,network,32),v=bytes(e,vault,32),t=bytes(e,tree,32);
 if(p.empty()||n.size()!=32||v.size()!=32||t.size()!=32||!state_u32(epoch)||!state_u32(time))return state_result(e,-1,0);
 uint64_t handle=0;int32_t status=tos_fee_state_open(p.data(),p.size(),globalId,n.data(),v.data(),t.data(),(uint32_t)epoch,(uint32_t)time,&handle);
 return state_result(e,status,handle);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_network_tos_security_pq_FeeStateNative_preview(JNIEnv *e,jobject,jlong handle,jlong time,jlong chainNext) {
 if(!state_u32(time)||!state_u32(chainNext))return state_result(e,-1,UINT32_MAX);
 uint32_t leaf=UINT32_MAX;int32_t status=tos_fee_state_preview((uint64_t)handle,(uint32_t)time,(uint32_t)chainNext,&leaf);
 return state_result(e,status,leaf);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_network_tos_security_pq_FeeStateNative_reserve(JNIEnv *e,jobject,jlong handle,jlong time,jlong chainNext,jlong leaf,jbyteArray digest) {
 auto d=bytes(e,digest,32);
 if(!state_u32(time)||!state_u32(chainNext)||!state_u32(leaf)||d.size()!=32)return state_result(e,-1,0);
 uint64_t receipt=0;int32_t status=tos_fee_state_reserve((uint64_t)handle,(uint32_t)time,(uint32_t)chainNext,(uint32_t)leaf,d.data(),&receipt);
 return state_result(e,status,receipt);
}
extern "C" JNIEXPORT jint JNICALL
Java_network_tos_security_pq_FeeStateNative_close(JNIEnv *,jobject,jlong handle) {
 return tos_fee_state_close((uint64_t)handle);
}

extern "C" int tos_wallet_lms_fee_verify(uint32_t,const unsigned char*,size_t,const unsigned char*,size_t,const unsigned char*,size_t) noexcept;
static int32_t fee_verify(void*,const uint8_t* key,uint32_t leaf,const uint8_t* digest,const uint8_t* sig,size_t size) {
 return tos_wallet_lms_fee_verify(leaf,digest,32,sig,size,key,60);
}
extern "C" JNIEXPORT jint JNICALL
Java_network_tos_security_pq_FeeStateNative_cache(JNIEnv* e,jobject,jlong handle,jlong token,jbyteArray key,jbyteArray signature) {
 auto k=bytes(e,key,60),s=bytes(e,signature,2832);
 if(e->ExceptionCheck()||k.size()!=60||s.size()!=2832)return -1;
 return tos_fee_state_cache_verified((uint64_t)handle,(uint64_t)token,k.data(),s.data(),s.size(),fee_verify,nullptr);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_tos_security_pq_FeeStateNative_cached(JNIEnv* e,jobject,jlong handle,jlong leaf,jbyteArray digest,jbyteArray key) {
 auto d=bytes(e,digest,32),k=bytes(e,key,60);std::array<uint8_t,2832> signature{};
 if(e->ExceptionCheck()||!state_u32(leaf)||d.size()!=32||k.size()!=60)return nullptr;
 int32_t rc=tos_fee_state_cached_verified((uint64_t)handle,(uint32_t)leaf,d.data(),k.data(),fee_verify,nullptr,signature.data(),signature.size());
 return rc==0?out(e,signature.data(),signature.size()):nullptr;
}
