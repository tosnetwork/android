#include <jni.h>
#include <array>
#include <vector>
#include "pq/include/tos_pq.h"
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
