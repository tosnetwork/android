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
| BRD-02 | Native asset symbol is TOS on balance, receive, send, confirmation, and history | Unit + UI | Passed | Home, receive, send confirmation, and real local-chain history/details assertions pin TOS; the history formatter bug that exposed TON was fixed |
| BRD-03 | Reachable V1 screens contain no TON or Tonkeeper product branding | Static + UI | Passed | Protocol/attribution allowlist plus runtime visible-copy scans cover onboarding, import, wallet home, receive, send, history, settings, and deferred deep links |
| BRD-04 | No TRON/TRC20 entry point is reachable | Static + UI | Passed | Offline flags and runtime visible-copy scans reject TRON/TRC20 across every retained V1 entry screen |
| BRD-05 | No Jetton/NFT/Swap/Staking/Buy/DApp/TonConnect entry point is reachable | Static + UI | Passed | Static gates, retained-screen inventories, runtime visible-copy scans, and direct deferred-deep-link denial cover every deferred feature class |
| BRD-06 | Supported RPC defaults use TOS-owned endpoints | Static + unit | Passed | `test_v1_static.sh` plus `TosRpcEndpointTest` |
| BRD-07 | Deferred deep links honor feature flags for supported schemes | Unit + static | Passed | Central route policy, fail-closed remote defaults, direct DApp entry gates, and unit/static regression coverage |

## C. Application lifecycle and persistence

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| APP-01 | Clean emulator launch reaches TOS onboarding | Emulator UI | Passed | `V1ProductUiTest.cleanLaunchUsesTosBrandAndOnlyV1EntryPoints` starts the real activity from cleared app data |
| APP-02 | Seeded wallet cold launch reaches wallet home | Emulator UI | Passed | Real Keystore/vault/account-repository fixture reaches the native funded wallet home |
| APP-03 | Relaunch preserves wallet and RPC settings | Emulator integration | Passed | Separate instrumentation processes prove encrypted-wallet persistence and custom RPC persistence before reset |
| APP-04 | Background and foreground protect sensitive content | Static + emulator | Passed | Root wallet window is pinned to `FLAG_SECURE`; runtime asserts the active window remains secure across home/resume |
| APP-05 | Offline launch shows a recoverable error | Emulator integration | Passed | The emulator routes the persisted wallet to a closed local endpoint and requires the history screen to expose `Unknown error` plus `Retry` without crashing |
| APP-06 | Reconnect refreshes native balance and history | Emulator integration | Passed | Resetting the failed endpoint and retrying restores funded history; a separate local transfer plus pull-to-refresh restores the updated node balance |

## D. Native TOS wallet creation and import

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| WAL-01 | Create Wallet opens passcode setup | Emulator UI | Passed | `createWalletOpensPasscodeAndCancelLeavesNoWallet` exercises the real onboarding flow |
| WAL-02 | Matching passcode creates a wallet; mismatch and cancel do not | Emulator UI + storage | Passed | Isolated import automation proves mismatch returns to creation with no wallet/PIN, matching codes proceed through wallet customization and persist both wallet and PIN, while existing cancellation scenarios leave neither |
| WAL-03 | Generated recovery phrase has valid words, count, and checksum | Unit | Passed | Repeated generator vectors assert uniqueness, 24 dictionary words, and native checksum validity |
| WAL-04 | Recovery phrase display and confirmation are authentication-gated | Emulator UI | Passed | The funded-wallet manual-backup flow requires a passcode dialog; a wrong PIN exposes no word, while the correct PIN reveals exact fixture words in a secure window |
| WAL-05 | Valid deterministic TOS phrase imports to the expected address | Unit + UI | Passed | Repository import through the encrypted vault derives and asserts the funded fixture address, then renders its home |
| WAL-06 | Whitespace and capitalization normalize safely | Unit | Passed | `TosV1MnemonicTest` pins canonical whitespace/case normalization |
| WAL-07 | Invalid word count, unknown words, and invalid checksum are rejected | Unit + UI | Passed | JVM vectors plus three isolated malformed-phrase emulator scenarios cover all three classes |
| WAL-08 | Cancelled creation/import leaves no partial wallet or secret | Emulator storage | Passed | Creation and valid-phrase import cancellation both return to clean onboarding with no account and no saved passcode |

## E. Passcode and secret protection

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| SEC-01 | Correct passcode unlocks and wrong passcode remains rejected | Emulator integration | Passed | `passcodeThrottleKeystoreAndRuntimeSecretPolicyHold` validates the persisted passcode through the real encrypted store |
| SEC-02 | Retry and lockout behavior matches policy | Emulator integration | Passed | Six failures trigger the encoded 30-second lockout; attempts during lockout fail and resetting the PIN clears the throttle |
| SEC-03 | Recovery phrase requires authentication | Emulator UI | Passed | `recoveryPhraseRequiresCorrectPasscode` proves wrong-PIN denial and correct-PIN disclosure through the real encrypted wallet repository |
| SEC-04 | Secret and passcode never appear in logs or clipboard unexpectedly | Runtime | Passed | Persistent-wallet emulator gate scans logcat and clipboard for the full fixture phrase and passcode |
| SEC-05 | Stored secrets use expected Android Keystore protections | Static + emulator | Passed | Runtime asserts account, vault, and passcode Android Keystore entries; the static boundary pins their TOS-native aliases and unlocked-device policy |
| SEC-06 | Sodium encryption and decryption execute through JNI | Native unit | Passed | Emulator JNI round trip; regression also pins the exact plaintext length after MAC removal |

## F. Native TOS home and receive

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| RCV-01 | Home shows exact fixture address, TOS symbol, and balance | Emulator integration | Passed | Cold launch reads the fixture balance from the local node, formats it with the product formatter, and asserts the exact UI value plus address and TOS symbol |
| RCV-02 | Zero balance and empty history render correctly | Emulator + localnet | Passed | A generated, unfunded V5R1 wallet is confirmed zero on the node and renders `0`, `Your activity will be shown here`, and `Make your first transaction!` |
| RCV-03 | Refresh updates balance after a local native transfer | Emulator + localnet | Passed | Instrumentation invokes the localnet controller, polls the node for the increased balance, performs pull-to-refresh, and asserts the exact newly formatted UI balance |
| RCV-04 | TOS formatting covers zero, fractions, and supported maximum | Emulator unit | Passed | Exact formatter vectors cover zero, fractions down to one nanoTOS, and the signed-long nano maximum |
| RCV-05 | Receive shows the exact address and a decodable QR payload | Emulator + QR decoder | Passed | Exact address is asserted and the generated `tos://transfer/` bitmap round-trips through ZXing decoding |
| RCV-06 | Copy and share contain the exact address | Emulator UI | Passed | Emulator clipboard assertion plus share-intent payload and reachable share-control checks |
| RCV-07 | Receive exposes native TOS only | Static + UI | Passed | Receive UI asserts `Receive TOS`, exact native address, and no deferred token selector |

## G. Send native TOS

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| SND-01 | Send opens a native TOS recipient/amount/comment form | Emulator UI | Passed | `walletSendAndSettingsExposeOnlyNativeV1Controls` asserts recipient, amount, comment, TOS, and continue controls |
| SND-02 | Valid typed/pasted addresses are accepted and invalid addresses rejected | Emulator UI | Passed | The send gate verifies exact clipboard paste, direct typing of the active localnet recipient, and invalid-address rejection |
| SND-03 | Whole/fractional amounts work; zero, negative, overflow, overprecision, and over-balance fail | Emulator UI | Passed | The send gate exercises valid whole and fractional values plus zero, negative, overflow, ten-decimal overprecision, and over-balance rejection |
| SND-04 | Max amount reserves the required fee | Unit + localnet | Passed | Real send-all broadcasts once, delivers a positive amount below the source balance, and proves the balance difference is a positive reserved network fee |
| SND-05 | Optional Unicode comment round-trips exactly | Unit + localnet | Passed | `nativeTransferSignsBroadcastsAndRoundTripsUnicodeComment` verifies the exact UTF-8 comment in confirmation and the local-node BOC-derived history |
| SND-06 | Confirmation shows exact recipient, amount, fee, and comment | Emulator UI | Passed | Confirmation asserts exact normalized recipient, 0.01 TOS, Unicode comment, and a resolved nonzero fee before enabling signing |
| SND-07 | Cancel never broadcasts | Emulator + localnet | Passed | Confirmation cancellation preserves the funded wallet's local-node seqno exactly |
| SND-08 | Passcode signs and broadcasts one native transfer | Emulator + localnet | Passed | Real passcode signing and local-node broadcast increase seqno exactly once and round-trip exact amount/comment history |
| SND-09 | Timeout/retry does not duplicate transfer and relaunch reconciles pending state | Emulator + fault proxy | Passed | Host fault proxy accepts the BOC then returns a one-shot 504; seqno reconciliation suppresses unsafe replay, seqno stays +1, and relaunch/history contains exactly one matching transfer |
| SND-10 | Send exposes no token, NFT, or TRC20 selector | Static + UI | Passed | Real funded-wallet send-form inventory plus the deferred-copy guard asserts native TOS-only UI |

## H. Native TOS transaction history and RPC

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| HIS-01 | Native account events map incoming/outgoing amounts and counterparties | Unit | Passed | Fixture mapper vectors plus mandatory real-transaction mapping in `test_v1_localnet.sh` |
| HIS-02 | History renders pending, confirmed, and failed states | Unit + UI | Passed | Deterministic state vectors cover pending/success/failed precedence; Compose accessibility semantics expose the state, confirmed local history renders, and error/retry UI is exercised |
| HIS-03 | Details show exact timestamp, fee, address, amount, and comment | Unit + UI | Passed | Real local-chain details assert the node timestamp, exact node fee, normalized recipient, 0.01 TOS amount, and Unicode comment; raw transaction fees are now mapped into details |
| HIS-04 | Pagination has no duplicate or missing records | Localnet + emulator UI | Passed | Three-node gate verifies the node's inclusive `lt` + `hash` cursor, a strict descending second page, and exactly one cursor overlap; the PagingSource filters that overlap and the emulator scrolls the funded history without an error/retry state |
| HIS-05 | Empty, loading, error, and retry states are deterministic | Unit + UI | Passed | Unfunded empty copy, funded loading, a closed-endpoint `Unknown error`/`Retry` state, and successful retry after endpoint restoration are all mandatory emulator scenarios |
| RPC-01 | RPC endpoint parsing and JSON-RPC path normalization are correct | Unit | Passed | `TosRpcEndpointTest` |
| RPC-02 | TOS address encoding and decoding are stable | Unit | Passed | `TosAddressCodecTest` |
| RPC-03 | Malformed responses, node errors, timeouts, and reconnects are safe | Unit | Passed | `TosRpcClientFailureTest` covers malformed JSON, TVM and JSON-RPC errors with preserved codes, a deterministic timeout followed by recovery, and runtime endpoint reconnection |
| RPC-04 | Three local validators converge before and after transfer | Localnet integration | Passed | `test_v1_localnet.sh` requires identical heads, performs a faucet transfer, and requires replicated balances on ports 18545-18547 |

## I. Settings, quality, and build

| ID | Automated requirement | Test layer | Status | Evidence or missing automation |
| --- | --- | --- | --- | --- |
| SET-01 | Settings opens and contains only retained V1 controls | Static + emulator UI | Passed | UI inventory asserts retained controls and rejects DApp, widget, battery, and wallet-version migration entries; static gate pins removal |
| SET-02 | RPC endpoint validates, persists, resets, and is used | Unit + emulator + localnet | Passed | Invalid input is rejected; custom validator 18546 is displayed, used, persisted across a process, then reset to validator 18545 |
| SET-03 | Delete wallet requires confirmation and returns to clean onboarding | Emulator storage | Passed | Sign-out remains disabled before confirmation, then deletes the persisted wallet and returns to clean onboarding |
| QLT-01 | Every reachable V1 control has a usable accessibility label | Emulator UI | Passed | Automated crawl covers onboarding, wallet, send, receive, history, settings, backup, security, currency, RPC, language, appearance, and legal; missing header, switch, input, action, filter, and history semantics were fixed |
| QLT-02 | Supported screen sizes, font scales, themes, and locales do not clip or crash | Emulator matrix | Passed | `test_v1_emulator_matrix.sh` runs four compact/standard/large, 1.0/1.3 font, light/dark, English/Japanese/German configurations and rejects clipped onboarding controls or crashes |
| QLT-03 | Launch, memory, and repeated refresh/send remain within budgets | Emulator performance | Passed | Mandatory instrumentation enforces wallet launch ≤30 s, PSS <512 MiB, and five refresh plus three send-open/cancel cycles ≤60 s without a fatal crash |
| QLT-04 | Runtime logs and telemetry contain no fixture secret | Runtime scan | Passed | Mandatory emulator gate scans runtime logcat and clipboard for the complete fixture phrase and passcode |
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
make test_v1_emulator_matrix
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
