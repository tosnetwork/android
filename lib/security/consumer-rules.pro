-keep class network.tos.security.Sodium {
    *;
}
-keep class network.tos.security.pq.PqNative { *; }

-keep class network.tos.security.pq.QuantumProofNative { *; }

-keep interface network.tos.security.pq.QuantumProofTransport { *; }
-keepclassmembers class * implements network.tos.security.pq.QuantumProofTransport { byte[] query(byte[], int); }

# The JNI entry point uses the class/method names at the C ABI boundary.
-keep class network.tos.security.pq.QuantumWalletTransactionNative { *; }
-keep class network.tos.security.pq.QuantumSimulationNative { *; }
-keep class network.tos.security.pq.QuantumSimulationNative$Refused { *; }
