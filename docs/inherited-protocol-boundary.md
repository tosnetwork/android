# Inherited Protocol Boundary

## Purpose

TOS Wallet is a TOS-branded fork of the Tonkeeper Android codebase. Because neither
the wallet nor the TOS chain has shipped, there is no installed-product upgrade or
persisted-data compatibility baseline. All first-party application identities,
storage identifiers, links, configuration fields, and source packages therefore use
TOS-native names from the first release.

## TOS-native identities

- The wallet application ID is `network.tos.wallet`.
- The standalone signer application ID is `network.tos.signer`.
- Wallet implementation classes use `network.tos.wallet.app`; reusable modules use
  `network.tos.*`.
- Encrypted storage aliases, database and preference names, first-party deep links,
  remote configuration fields, and JNI symbols use `network.tos` or TOS naming.
- No Tonkeeper application ID, deep-link scheme, key alias, or migration shim is
  retained for a release that never existed.

## Protocol and generated-model boundary

The following inherited names describe external wire formats or imported/generated
types. They may remain only at protocol adapters and dependency boundaries:

- `io.tonapi.*` generated DTOs currently used behind internal repository interfaces
- TON-family blockchain primitives, address encodings, opcodes, wallet contracts,
  and the Ledger TON application protocol where required by the inherited libraries
- TonConnect protocol messages, JavaScript bridge keys, headers, and `tc://` links
- `ton://` and `tonsite://` protocol links
- upstream OpenAPI specifications and generated sources under `tonapi/`

These names do not authorize calls to Tonkeeper or tonapi.io production services.
TOS runtime traffic must use configured TOS endpoints. Deferred V1 services remain
disabled until a TOS-owned implementation and dedicated acceptance coverage exist.

## Brand and attribution boundary

Reachable product copy, application labels, first-party links, source packages, and
new APIs use TOS Wallet, TOS, TosAPI, and TosConnect terminology as appropriate.
The Tonkeeper name remains only in `NOTICE`, license attribution, repository history,
and upstream dependency metadata that this project does not control.

## Change policy

Before the first public release, compatibility aliases must not be introduced without
a concrete external contract. After release, application IDs and persisted schemas
become stable and future changes require explicit, versioned migration tests.

## TOS wallet and recovery formats

TOS V5R1 is stored as wallet version `6` (`tosV5R1`). Its signed request includes
the node's `global_id` separately from the plain subwallet number, and uses the
frozen TOS contract code. The network ID is discovered from chain configuration
and persisted with the wallet; the development chain ID `3` is not a production
default. One native send holds one RPC endpoint and credential through sequence
lookup, fee preview, signing, broadcast, and retry reconciliation.

Development wallets stored as version `5` keep the inherited V5 code, packed wallet
ID, address and key. Database version `5` adds a nullable `network_global_id`
column; existing legacy rows remain null. Recovery explicitly distinguishes native
TOS salts from inherited recovery salts. A phrase valid in both formats requires
the user to select TOS or Legacy; automatic recovery rejects the ambiguity. Vault
metadata binds the derivation format to the saved public key.

PQ validator consensus does not change Ed25519 wallet authentication into PQ
authentication. The native V5 wallet remains an Ed25519 wallet. Config8 capability
discovery distinguishes ML-DSA primitive support from the experimental Falcon
profile, which requires VM version `19`. This release does not create or sign PQ
relayer wallets, activate validators, or enable a gasless backend. Offline TOS V5
signing only accepts ordinary send mode `3`; hidden carry or sweep modes fail
before confirmation.
