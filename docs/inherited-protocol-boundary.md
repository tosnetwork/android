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
