-keep class network.tos.security.Sodium {
    *;
}
-keep class network.tos.security.pq.PqNative { *; }

-keep class network.tos.security.pq.QuantumProofNative { *; }

-keep interface network.tos.security.pq.QuantumProofTransport { *; }
-keepclassmembers class * implements network.tos.security.pq.QuantumProofTransport { byte[] query(byte[], int); }
