# TOS Wallet for Android

TOS Wallet is the official Android wallet for the [TOS blockchain](https://tos.network), maintained by the TOS Network team.

Create or recover a wallet, view its TOS balance, and send or receive TOS on Android.

## Privacy wallet

The dedicated Privacy Wallet screen can show the shielded TOS balance for a
verified pool, deposit ordinary TOS into that pool, and prepare and submit
private transfers through a configured Tor relayer. The app reviews the
recipient and quote before submission and can resume a persisted payment request.
Withdrawals to ordinary addresses are still in development.

Both platforms have exercised the Tor payment flow and restart recovery in
isolated simulator or emulator environments. Production relayer availability
and real-network acceptance remain separate validation steps.

## Releases

Download the signed `TOS-Wallet.apk` from [GitHub Releases](https://github.com/tosnetwork/android/releases).
The [release workflow](.github/workflows/release.yml) builds the APK when a
version tag is pushed and verifies its signing certificate and version before
publishing it.

## Build and test

Use JDK 17 and the Android SDK, including CMake 3.22.1 and NDK 27.0.12077973.
The [Android CI workflow](.github/workflows/ci.yml) lists the native build tools
and shows how to install the pinned Rust toolchain and Android targets.

```sh
make test_ci
```

## Origin and compatibility

TOS Wallet is derived from the open-source Tonkeeper Android wallet. See
[NOTICE](NOTICE) for attribution and
[the inherited protocol boundary](docs/inherited-protocol-boundary.md) for the
legacy protocol and migration identifiers that are intentionally retained.

Native wallets use the current TOS V5R1 code and TOS recovery phrase salts.
Legacy recovery preserves the original wallet address and key. PQ validator
consensus is supported independently of Ed25519 wallet authentication. Settings
now also exposes separate ML-DSA-44 and experimental Falcon-512 padded wallets;
see [PQ signing and local validation](docs/mobile-pq-signing-20261004.md) and
[the modernization report](docs/android-pq-modernization-20261004.md) for the
protocol profile and validation scope.

## License

See the [LICENSE](LICENSE) file.
