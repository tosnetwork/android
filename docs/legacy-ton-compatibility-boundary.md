# Legacy TON Compatibility Boundary

## Purpose

TOS Wallet is a TOS-branded fork of the Tonkeeper Android codebase. First-party
application code uses the `network.tos` namespace and TOS Wallet product language.
This document identifies the inherited names that must not be mechanically renamed.

## Stable Android identities

- The wallet application ID is `network.tos.wallet`. It is the published identity
  and must remain stable across upgrades.
- The optional standalone signer retains `com.tonapps.signer`. Changing it would
  install a second application and break discovery by existing wallet installations.
- Wallet implementation classes use `network.tos.wallet.app`; reusable modules use
  `network.tos.*`. This separation prevents duplicate JVM and DEX class names.

## Persisted data

The `_com_tonapps_*_master_key_` aliases are storage schema, not visible branding.
They protect existing encrypted account, vault, event, dApp, biometric, and passcode
data. They remain unchanged until a versioned, rollback-safe migration exists.

Remote configuration keys such as `tonkeeperNewsUrl` are wire-format fields. They
may be mapped to neutral first-party properties in a future schema version, but the
legacy JSON spelling must remain readable while old configuration payloads exist.

## Protocol and generated-model boundary

The following names describe external wire formats or imported/generated types and
are allowed only at adapters and compatibility surfaces:

- `io.tonapi.*` generated model types used as the current internal compatibility DTOs
- TON blockchain primitives, wallet contracts, address encodings, opcodes, and the
  Ledger TON application protocol
- TonConnect protocol messages, JavaScript bridge keys, headers, and `tc://` links
- `ton://`, `tonsite://`, and legacy `tonkeeper://` inbound deep links
- upstream OpenAPI specifications and generated sources under `tonapi/`

These names do not authorize calls to Tonkeeper or tonapi.io production services.
TOS runtime traffic must use configured TOS endpoints. Deferred V1 services remain
disabled unless a TOS-owned implementation and dedicated acceptance coverage exist.

## Brand and attribution boundary

Reachable product copy, application labels, first-party links, source packages, and
new APIs use TOS Wallet, TOS, TosAPI, and TosConnect terminology as appropriate.
The Tonkeeper name remains in `NOTICE`, license attribution, historical migration
comments, and explicitly documented compatibility constants only.

## Change policy

Any change to an application ID, key alias, database name, preference key, serialized
field, JNI symbol, deep-link scheme, or protocol identifier requires an upgrade test
from the previously released application. A source-wide search-and-replace is not an
acceptable migration mechanism.
