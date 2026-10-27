# Native transaction verification boundary

`QuantumWalletTransactionNative.verify` calls the portable TOS transaction C ABI
through an app-owned JNI adapter, `libtoswalletreceipt.so`. It links directly to
the source-built `libtosproofverify.so` at `scripts/quantum-proof-revision.txt`;
there is no dynamic symbol lookup or older-code fallback. Both libraries are
built for API24 or later, with 16KiB load alignment. The adapter hides static C++
runtime exports and exports only its JNI entry point. A partial debug ABI build
packages only the requested ABIs; release builds require all four.

The call validates a basechain transaction against an expected account hash,
transaction hash and unsigned logical time. All native inputs have independent
owned buffers. BOC and result sizes are limited to 1MiB. Logical time is a
canonical decimal string, parsed as UInt64 without a signed Long or floating
point intermediate. `QuantumVerifiedRead.provenTransactionHead` obtains its
integer from the original verified JSON token, before JSONObject's lossy
large-number conversion.

The codec does not authenticate inclusion or authorize signing. A history reader
must obtain the starting hash/time from a fresh authenticated account proof and
walk the predecessor commitments. Message bodies remain opaque. Successful
compute/action phases do not prove a transfer: a +2 send may be skipped, with no
outgoing message. Payment confirmation additionally requires matching the
committed outbound message and, when needed, authenticated recipient state.

`QuantumWalletTransactionDeviceTest` checks the frozen three-transaction public
fixture (including an opaque body and a skipped send), bindings and corruption,
full UInt64 parsing, and JNI checks invoked without Kotlin preconditions.
`QuantumProofDeviceTest` and `QuantumProofSessionDeviceTest` retain the existing
proof/session regression coverage. The public fixture is codec/hash-chain test
material, not phone custody signing or a fresh chain inclusion proof.

This increment does not yet connect authenticated history or receipts to the
product UI, and does not implement signing, an outbox, fee budgeting, recovery
or migration. Those remain separate delivery requirements.
