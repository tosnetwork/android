# TOS Android Wallet V1 Acceptance Test Report

## Run information

- Date: 2026-08-07
- Branch: `codex/tos-native-architecture`
- Host: macOS, arm64
- Java: OpenJDK 17
- Android compile SDK: 36
- Aggregate command: `make test_v1_acceptance`
- Result: Passed for all currently implemented gates

## Executed evidence

| Check | Result | Evidence |
| --- | --- | --- |
| Brand and architecture boundary | Passed | No legacy first-party package/import declarations; stable IDs and attribution asserted |
| V1 static feature defaults | Passed | Deferred service flags, TOS endpoint, deep links, and workflow boundary asserted |
| JVM unit tests | Passed | 9 tests, 0 failures/errors; 1 optional local-node test skipped because no endpoint was configured |
| Wallet Debug build | Passed | `main-default-debug.apk`, application ID `network.tos.wallet` |
| Signer Debug build | Passed | `signer-debug.apk`, debug ID `com.tonapps.signer.debug` |
| Wallet Release/R8 build | Passed | Unsigned Site Release APK built after full lint and shrinking |
| Signer Release/R8 build | Passed | Unsigned Release APK built after full lint and shrinking |
| Release artifact inspection | Passed | Wallet ID, signer compatibility ID, and Sodium libraries for four Android ABIs asserted |

The first uncached combined Release build executed 1,535 Gradle tasks and completed
successfully in 16 minutes 12 seconds. Subsequent aggregate verification reused the
configuration/build cache and also passed.

## Defects found and corrected

### AND-REN-001: application/API namespace collision

The initial rename placed application implementation classes in the same
`network.tos.wallet` package as reusable wallet API classes. DEX packaging detected a
duplicate class. Application implementation classes now use
`network.tos.wallet.app`, while the published application ID remains unchanged.

### AND-REN-002: missed binary-classified Kotlin source

`PasscodeStore.kt` contains a NUL character in a character literal. Text-oriented
search skipped the file, leaving one old import after the first mechanical pass. The
source was migrated with a binary-safe scan, and the boundary gate now scans all
source files independently of Git text classification.

### AND-REN-003: stale JNI export names

`Sodium.kt` moved to `network.tos.security`, but the native C++ exports still used the
old Java package. APK compilation alone would not detect this and the first native
call would fail at runtime. All JNI exports now match the new class name, four ABIs
build, and the static/artifact gates protect the mapping.

### AND-REN-004: signer identity migration risk

A mechanical rename changed the separate Signer application ID. That would install a
new application instead of upgrading the existing one. Its published ID is now
explicitly pinned to `com.tonapps.signer`; only its source namespace and product copy
use TOS naming.

### AND-REN-005: artifact check pipeline false failure

The first artifact script streamed `unzip` into an early-exiting `grep` under
`pipefail`, which treated `unzip`'s closed pipe as a failure. The script now writes a
temporary listing, checks every ABI deterministically, and cleans the file on exit.

## Remaining automated coverage

The formal matrix currently contains 74 requirements: 15 `Passed`, 9 `Partial`, and
50 `Not covered`. Most remaining work is emulator UI, upgrade-fixture, fault-proxy,
and local three-node integration automation. These are explicitly recorded in
`docs/android-product-test-matrix.md`; no physical-device or human-only work is
included.
