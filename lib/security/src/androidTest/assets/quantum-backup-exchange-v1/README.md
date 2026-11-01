# Public Quantum backup exchange fixture

Never use these keys or addresses for funds. The password and every input secret are public test vectors.

`ios-public-mixed-role-v1.tosqbk` was produced by the iOS production re-export codec, through `TOSQuantumBackupExchangeTests/testProduceMixedRoleExchangeFile` at tosnetwork/ios `6a7b706` (production code `e2d29b6`). It was exported from the test's retained XCTest attachment, without re-encrypting or reconstructing its bytes on Android.

Password: `public exchange password 😀`. PRIMARY uses 23 `abandon` words followed by `chicken`, a phrase password with one ASCII space on each end of `public test password`, account 1/generation 19. RESCUE uses raw master bytes 0 through 31, account 2/generation 20. Both roles are included in the same file; this does not establish separate device custody.

Ciphertext: 4967 bytes, SHA256 `ee2b66fbd4b078b8d08284f0818f07d87390344f2fddb99433813dd1b23ea6ac`.
Metadata: 769 bytes, SHA256 `eac46b2319ce931c504762db23d3432f5e748017231e2861c8caf86402735c3e`.

The receiving test independently fixes the network profile and validates the original identity, complete native public keys, exact provenance, derivation contexts, ciphertext hash, and rejection controls. These are offline interoperability fixtures, not verified online chain state or a physical-device acceptance result. Regeneration uses fresh salt/nonce; replacing fixtures requires documented producer provenance and semantic/hash review, not weakening checks.
