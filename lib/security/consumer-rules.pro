-keep class network.tos.security.Sodium {
    *;
}
-keep class network.tos.security.pq.PqNative { *; }

-keep class network.tos.security.pq.V5R2ProofNative { *; }

-keep interface network.tos.security.pq.V5R2ProofTransport { *; }
-keepclassmembers class * implements network.tos.security.pq.V5R2ProofTransport { byte[] query(byte[], int); }
