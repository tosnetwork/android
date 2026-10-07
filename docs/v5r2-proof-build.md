# V5R2 embedded proof development build

The security module builds `libtosproofverify.so` from the immutable TOS revision
in `scripts/v5r2-proof-revision.txt`. `preBuild` packages generated libraries for
arm64-v8a, armeabi-v7a, x86 and x86_64. The script checks the source revision and
working tree before invoking the canonical source build; no downloaded binary is
accepted. Native JNI names are retained by the module consumer rules.

For local reuse, set `TOS_PROOF_ROOT` to a clean checkout at the pinned commit,
`TOS_PROOF_HOST_BUILD` to its configured host CMake build, and
`TOS_PROOF_BUILD_ROOT` to a directory for ABI builds. Without overrides the script
creates a source checkout and build directories under `.gradle`. A development
instrumentation run can use `-PtosProofAbis=arm64-v8a`; release invocations require
all four ABIs. The generated directory must not be committed.

This package currently targets Android API 26. Compatibility with the wallet's
API 24 minimum remains open, together with testing the other three ABIs, release
shrinking, iOS packaging, and physical-device acceptance. The raw API does not
provide transport, independently provision anchors, serialize concurrent reads,
persist anti-rollback state, or authorize wallet signing. Those operations must
be implemented and validated before enabling the complete wallet workflow.
