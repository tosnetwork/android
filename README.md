# TOS Wallet

TOS Wallet is the official Android wallet for the [TOS blockchain](https://tos.network), maintained by the TOS Network team.

It provides a secure and convenient way to manage TOS accounts and send or receive TOS on Android devices.

## Origin and compatibility

TOS Wallet is derived from the open-source Tonkeeper Android wallet. See
[NOTICE](NOTICE) for attribution and
[the inherited protocol boundary](docs/inherited-protocol-boundary.md) for the
legacy protocol and migration identifiers that are intentionally retained.

Native wallets use the current TOS V5R1 code and TOS recovery phrase salts.
Legacy recovery preserves the original wallet address and key. PQ validator
consensus is supported independently of Ed25519 wallet authentication; see
[the modernization report](docs/android-pq-modernization-20261004.md) for the
protocol profile and validation scope.

## License

See the [LICENSE](LICENSE) file.
