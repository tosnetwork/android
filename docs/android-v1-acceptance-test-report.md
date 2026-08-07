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
| Brand and architecture boundary | Passed | No legacy first-party identities; TOS-native IDs and attribution asserted |
| V1 static feature defaults | Passed | Deferred service flags, TOS endpoint, deep links, and workflow boundary asserted |
| JVM unit tests | Passed | 12 tests, 0 failures/errors; 1 optional local-node test skipped because no endpoint was configured |
| Wallet Debug build | Passed | `main-default-debug.apk`, application ID `network.tos.wallet` |
| Signer Debug build | Passed | `signer-debug.apk`, debug ID `network.tos.signer.debug` |
| Wallet Release/R8 build | Passed | Unsigned Site Release APK built after full lint and shrinking |
| Signer Release/R8 build | Passed | Unsigned Release APK built after full lint and shrinking |
| Release artifact inspection | Passed | TOS-native wallet/signer IDs and Sodium libraries for four Android ABIs asserted |

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

### AND-REN-004: unnecessary signer compatibility identity

The initial implementation retained the upstream Signer application ID on the
assumption that an installed release needed upgrading. The product has not shipped,
so that premise does not exist. Signer now uses `network.tos.signer`, matching its
source namespace and first-release product identity.

### AND-REN-005: artifact check pipeline false failure

The first artifact script streamed `unzip` into an early-exiting `grep` under
`pipefail`, which treated `unzip`'s closed pipe as a failure. The script now writes a
temporary listing, checks every ABI deterministically, and cleans the file on exit.

## Remaining automated coverage

The formal matrix currently contains 72 requirements: 16 `Passed`, 9 `Partial`, and
47 `Not covered`. Most remaining work is emulator UI, fault-proxy,
and local three-node integration automation. These are explicitly recorded in
`docs/android-product-test-matrix.md`; no physical-device or human-only work is
included.

## Post-review V1 reachability correction

Independent review found that supported deep links could reach deferred
staking, battery, DApp, purchase, and exchange screens because their flags were
not consulted centrally. `DeepLinkFeaturePolicy` now gates every deferred route
family, both DApp shortcut/push bypasses consume `disableDApps`, omitted remote
configuration keys fail closed, and JVM/static regression tests enforce every
reviewed flag-controlled route family. Because no public build exists, upstream
WorkManager class names, key aliases, application IDs, and Tonkeeper deep links have
no upgrade contract and were replaced outright with TOS-native identities.
