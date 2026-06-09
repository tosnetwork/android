# TOS Wallet

A native Android wallet for the **TOS blockchain** (The Open System).

TOS Wallet talks **only to a TOS node**. There is no dependency on any external
indexer or third-party API service — the wallet speaks JSON-RPC directly to a
TOS node, and the node itself serves the data the wallet needs (balances,
transactions, and jetton/NFT enumeration via an in-process index).

## Why

The broader ecosystem of TON-style chains relies on fragmented, centralized
indexer APIs. TOS Wallet removes that dependency: anyone running a TOS node can
back the wallet with their own infrastructure, and the wallet works against a
single, self-hosted endpoint.

## Features

- Open a wallet and view the TOS balance
- Send and receive TOS
- Transaction history
- Jetton balances with TEP-64 (on-chain) metadata
- NFT listing
- Standard wallet UX: passcode, multi-account, address book, QR

Everything above is served by the TOS node over JSON-RPC. Features that
inherently require external services (fiat rates, swaps, on-ramp, staking,
battery/gasless relayer) are disabled by default and can be re-enabled by
pointing the relevant endpoints at a provider you trust.

## Architecture

The wallet's data layer is wired to the TOS node through a small JSON-RPC
client:

| Component | Responsibility |
| --- | --- |
| `TosRpcClient` | Low-level JSON-RPC transport to the TOS node |
| `TosSource` | Typed wallet operations: account state, seqno, `runGetMethod`, `sendBoc`, fee estimation, transactions, jetton/NFT enumeration, token data |
| `TosEventMapper` | Builds account events from raw node transactions |

These live in
`apps/wallet/api/src/main/java/com/tonapps/wallet/api/tos/`, and `API.kt`
routes the wallet's core operations through them.

Jetton and NFT enumeration is backed by an **in-process `wc=0` index built into
the TOS node** (no external indexer): the node indexes ownership on block apply
and exposes it via JSON-RPC.

## Build

Requirements: JDK 17, Android SDK (compileSdk 36), Android NDK, AGP 8.12.x,
Kotlin 2.2.x.

```bash
./gradlew :apps:wallet:instance:app:assembleDebug
```

Configure the TOS node endpoint the wallet connects to in the API constants /
config (`apps/wallet/api/.../Constants.kt`).

## License

This project is derived from the Tonkeeper codebase; see the upstream license
for the inherited portions.
