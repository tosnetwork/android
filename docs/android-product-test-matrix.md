# TOS Android Wallet V1 Automated Product Test Matrix

- Product: TOS Wallet for Android
- Release scope: V1, native TOS only
- Baseline date: 2026-08-07
- Code baseline: `codex/tos-native-architecture`
- Execution boundary: every row is decidable by source checks, JVM tests, an Android emulator, a local TOS network, or build-artifact inspection; no physical device, Play Console, distribution signing, external production service, or human visual judgment is included
- Brand rule: reachable product copy, first-party packages, links, persisted identifiers, and assets use TOS Wallet and TOS; inherited names are allowed only at the documented external-protocol boundary

## Scope and completion rule

V1 supports wallet creation, recovery-phrase import, passcode protection, native TOS
address and balance, native TOS receive/send/history, and TOS JSON-RPC configuration.
TRON/TRC20, Jetton, NFT, Swap, Staking, DNS, Battery, Buy/Sell/Ramp, DApps,
TonConnect, and hardware-wallet features are deferred and must be unreachable.

The matrix reports evidence, not intent. A row is `Passed` only when a repeatable
machine test covers the complete requirement, its latest execution passed, and a
regression makes an authoritative command fail. Compiling code is not UI coverage.

## Status definitions

| Status | Meaning |
| --- | --- |
| `Passed` | Complete repeatable automation exists, is part of a gate, and last passed. |
| `Partial` | Automation covers only part of the stated behavior. |
| `Not covered` | No complete autonomous test exists yet. |
| `Failed` | The autonomous test ran and detected a defect. |

## A. Product identity and architecture

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| ARC-01 | Wallet application ID remains `network.tos.wallet` | Static + build artifact | Passed | `scripts/test_brand_boundary.sh`; Debug APK build |
| ARC-02 | First-party namespaces and source packages use `network.tos` | Static + compile | Passed | Boundary scan rejects legacy package/import declarations; full Debug compile passes |
| ARC-03 | App implementation and public wallet API cannot emit duplicate DEX classes | Build | Passed | `:apps:wallet:instance:main:assembleDefaultDebug` packages successfully |
| ARC-04 | Native Sodium JNI symbols match the renamed Kotlin class | Static + native build | Passed | Boundary gate checks the symbol prefix; four-ABI native build passes |
| ARC-05 | Standalone signer uses the TOS-native `network.tos.signer` identity | Static + build artifact | Passed | Boundary and release-artifact gates pin the ID; signer builds in `make compile` |
| ARC-06 | `tos://` is the first-party application scheme and no Tonkeeper scheme remains | Static | Passed | Manifest and source assertions in both static gates |
| ARC-07 | Workflows contain no upstream Tonkeeper checkout or push target | Static | Passed | Recursive workflow guard in both static gates |
| ARC-08 | Upstream origin is attributed without presenting Tonkeeper as the product | Static | Passed | `NOTICE`, README, and protocol-boundary checks |

## B. Branding and V1 feature gating

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| BRD-01 | Application label and onboarding identify TOS Wallet | Static + UI | Passed | Static brand gate plus `cleanLaunchUsesTosBrandAndOnlyV1EntryPoints` on the emulator |
| BRD-02 | Native asset symbol is TOS on balance, receive, send, confirmation, and history | Unit + UI | Partial | Source uses TOS; complete screen assertions are absent |
| BRD-03 | Reachable V1 screens contain no TON or Tonkeeper product branding | Static + UI | Not covered | Protocol/attribution allowlist exists; reachability-aware UI scan is absent |
| BRD-04 | No TRON/TRC20 entry point is reachable | Static + UI | Partial | Offline defaults disable TRON; no emulator reachability inventory |
| BRD-05 | No Jetton/NFT/Swap/Staking/Buy/DApp/TonConnect entry point is reachable | Static + UI | Partial | Static gate pins most offline defaults; no emulator reachability inventory |
| BRD-06 | Supported RPC defaults use TOS-owned endpoints | Static + unit | Passed | `test_v1_static.sh` plus `TosRpcEndpointTest` |
| BRD-07 | Deferred deep links honor feature flags for supported schemes | Unit + static | Passed | Central route policy, fail-closed remote defaults, direct DApp entry gates, and unit/static regression coverage |

## C. Application lifecycle and persistence

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| APP-01 | Clean emulator launch reaches TOS onboarding | Emulator UI | Passed | `V1ProductUiTest.cleanLaunchUsesTosBrandAndOnlyV1EntryPoints` starts the real activity from cleared app data |
| APP-02 | Seeded wallet cold launch reaches wallet home | Emulator UI | Not covered | Deterministic seed fixture and test hook are absent |
| APP-03 | Relaunch preserves wallet and RPC settings | Emulator UI | Not covered | No persistence scenario exists |
| APP-04 | Background and foreground protect sensitive content | Emulator UI | Not covered | No lifecycle/privacy-shield automation exists |
| APP-05 | Offline launch shows a recoverable error | Emulator integration | Not covered | No local fault proxy or UI assertion exists |
| APP-06 | Reconnect refreshes native balance and history | Emulator integration | Not covered | No controlled reconnect scenario exists |

## D. Native TOS wallet creation and import

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| WAL-01 | Create Wallet opens passcode setup | Emulator UI | Passed | `createWalletOpensPasscodeAndCancelLeavesNoWallet` exercises the real onboarding flow |
| WAL-02 | Matching passcode creates a wallet; mismatch and cancel do not | Unit + UI | Not covered | No complete creation state-machine test |
| WAL-03 | Generated recovery phrase has valid words, count, and checksum | Unit | Not covered | Blockchain helper has no dedicated test |
| WAL-04 | Recovery phrase display and confirmation are authentication-gated | Emulator UI | Not covered | No secret-display UI test |
| WAL-05 | Valid deterministic TOS phrase imports to the expected address | Unit + UI | Not covered | No end-to-end deterministic import assertion |
| WAL-06 | Whitespace and capitalization normalize safely | Unit | Passed | `TosV1MnemonicTest` pins canonical whitespace/case normalization |
| WAL-07 | Invalid word count, unknown words, and invalid checksum are rejected | Unit + UI | Passed | JVM vectors plus three isolated malformed-phrase emulator scenarios cover all three classes |
| WAL-08 | Cancelled creation/import leaves no partial wallet or secret | Emulator storage | Partial | Creation cancellation returns to clean onboarding; import storage cancellation still needs a complete assertion |

## E. Passcode and secret protection

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| SEC-01 | Correct passcode unlocks and wrong passcode remains rejected | Unit + UI | Not covered | No passcode behavior tests |
| SEC-02 | Retry and lockout behavior matches policy | Unit + UI | Not covered | No deterministic clock/attempt fixture |
| SEC-03 | Recovery phrase requires authentication | Emulator UI | Not covered | No instrumentation coverage |
| SEC-04 | Secret and passcode never appear in logs or clipboard unexpectedly | Static + runtime | Not covered | No logcat/clipboard secret scanner |
| SEC-05 | Stored secrets use expected Android Keystore protections | Unit + emulator | Not covered | TOS-native aliases exist; cryptographic policy is not asserted |
| SEC-06 | Sodium encryption and decryption execute through JNI | Native unit | Passed | Emulator JNI round trip; regression also pins the exact plaintext length after MAC removal |

## F. Native TOS home and receive

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| RCV-01 | Home shows exact fixture address, TOS symbol, and balance | Emulator integration | Not covered | No funded emulator fixture |
| RCV-02 | Zero balance and empty history render correctly | Emulator UI | Not covered | No zero-state UI test |
| RCV-03 | Refresh updates balance after a local native transfer | Emulator + localnet | Not covered | No localnet controller wired to Android tests |
| RCV-04 | TOS formatting covers zero, fractions, and supported maximum | Unit | Not covered | No formatter boundary suite |
| RCV-05 | Receive shows the exact address and a decodable QR payload | Emulator + QR decoder | Not covered | No QR image extraction/decoder assertion |
| RCV-06 | Copy and share contain the exact address | Emulator UI | Not covered | No clipboard/intent capture test |
| RCV-07 | Receive exposes native TOS only | Static + UI | Partial | Deferred flags pass; no recursive UI inventory |

## G. Send native TOS

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| SND-01 | Send opens a native TOS recipient/amount/comment form | Emulator UI | Not covered | No instrumentation test |
| SND-02 | Valid typed/pasted addresses are accepted and invalid addresses rejected | Unit + UI | Not covered | No address-input matrix |
| SND-03 | Whole/fractional amounts work; zero, negative, overflow, overprecision, and over-balance fail | Unit + UI | Not covered | No amount boundary matrix |
| SND-04 | Max amount reserves the required fee | Unit + localnet | Not covered | No fee/send-all scenario |
| SND-05 | Optional Unicode comment round-trips exactly | Unit + localnet | Not covered | No payload/event round-trip test |
| SND-06 | Confirmation shows exact recipient, amount, fee, and comment | Emulator UI | Not covered | No confirmation assertion |
| SND-07 | Cancel never broadcasts | Emulator + localnet | Not covered | No before/after event-ID assertion |
| SND-08 | Passcode signs and broadcasts one native transfer | Emulator + localnet | Not covered | No end-to-end send scenario |
| SND-09 | Timeout/retry does not duplicate transfer and relaunch reconciles pending state | Emulator + fault proxy | Not covered | No deterministic response-drop/delay proxy |
| SND-10 | Send exposes no token, NFT, or TRC20 selector | Static + UI | Partial | Deferred defaults pass; UI inventory absent |

## H. Native TOS transaction history and RPC

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| HIS-01 | Native account events map incoming/outgoing amounts and counterparties | Unit | Passed | Fixture mapper vectors plus mandatory real-transaction mapping in `test_v1_localnet.sh` |
| HIS-02 | History renders pending, confirmed, and failed states | Unit + UI | Partial | Mapper coverage exists; screen assertions are absent |
| HIS-03 | Details show exact timestamp, fee, address, amount, and comment | Unit + UI | Not covered | No complete details fixture |
| HIS-04 | Pagination has no duplicate or missing records | Unit + integration | Not covered | No multipage dataset test |
| HIS-05 | Empty, loading, error, and retry states are deterministic | Unit + UI | Not covered | No fault-state matrix |
| RPC-01 | RPC endpoint parsing and JSON-RPC path normalization are correct | Unit | Passed | `TosRpcEndpointTest` |
| RPC-02 | TOS address encoding and decoding are stable | Unit | Passed | `TosAddressCodecTest` |
| RPC-03 | Malformed responses, node errors, timeouts, and reconnects are safe | Unit | Not covered | Client failure matrix is absent |
| RPC-04 | Three local validators converge before and after transfer | Localnet integration | Passed | `test_v1_localnet.sh` requires identical heads, performs a faucet transfer, and requires replicated balances on ports 18545-18547 |

## I. Settings, quality, and build

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| SET-01 | Settings opens and contains only retained V1 controls | Emulator UI | Not covered | No settings inventory test |
| SET-02 | RPC endpoint validates, persists, resets, and is used | Unit + UI | Partial | Endpoint unit tests pass; persistence and routing are untested |
| SET-03 | Delete wallet requires confirmation and returns to clean onboarding | Emulator storage | Not covered | No destructive-flow test fixture |
| QLT-01 | Every reachable V1 control has a usable accessibility label | Emulator UI | Partial | Onboarding reachable-control inventory passes; retained wallet screens remain to be crawled |
| QLT-02 | Supported screen sizes, font scales, themes, and locales do not clip or crash | Emulator matrix | Not covered | No multi-configuration suite |
| QLT-03 | Launch, memory, and repeated refresh/send remain within budgets | Performance | Not covered | No encoded performance budgets |
| QLT-04 | Runtime logs and telemetry contain no fixture secret | Runtime scan | Not covered | No logcat gate |
| BLD-01 | Wallet Debug APK builds | Build | Passed | `make compile` / `assembleDefaultDebug` |
| BLD-02 | Standalone signer Debug APK builds | Build | Passed | Included in `make compile` |
| BLD-03 | JVM unit suite passes | Unit | Passed | `make test_unit`; 12 tests, 0 failures, 1 optional local-node test skipped on 2026-08-07 |
| BLD-04 | Unsigned release APKs build with R8 and retain expected application IDs and native ABIs | Build artifact | Passed | `test_release_artifacts.sh`; wallet and signer Release builds passed on 2026-08-07 |

## Excluded human and external validation

The following are intentionally outside this matrix and do not affect its automated
completion percentage:

- Physical-device installation, biometric hardware, Bluetooth, NFC, USB, and camera behavior
- Manual TalkBack, visual, color, animation, or usability review
- Distribution keystore and production signing validation
- Google Play Console upload, review, staged rollout, and upgrade
- External production-service availability or subjective human approval

## Authoritative commands

```sh
make test_brand_boundary
make test_v1_static
make test_unit
make compile
make test_v1_localnet
make test_v1_emulator
make test_release_artifacts
make test_v1_acceptance
```

Future automation must be wired into `make test_v1_acceptance`. The matrix reaches
100% only when every row is `Passed`; `Partial` and `Not covered` are explicit work,
not implied success.

## Current evidence

- Unit tests: `apps/wallet/api/src/test/`
- Mnemonic tests: `lib/blockchain/src/test/`
- Emulator suite: `apps/wallet/instance/main/src/androidTest/`
- Three-validator gate: `scripts/test_v1_localnet.sh`
- Brand gate: `scripts/test_brand_boundary.sh`
- V1 static gate: `scripts/test_v1_static.sh`
- Protocol boundary: `docs/inherited-protocol-boundary.md`
- Test entry points: `Makefile`
