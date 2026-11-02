# Classic wallet recovery without version entry points

New wallets still use the Quantum creation flow. Existing classical wallets keep
their keys, versions, addresses and storage format. Recovery does not present a
v3/v4/v5 picker, automatically append version labels, show W5 badges, or offer
W5 promotion/creation routes from settings or token banners. Existing user
labels are preserved verbatim; they are not rewritten by a version-name heuristic.
Ledger account-index selection remains available without version titles.

Mnemonic derivation is a separate choice: ambiguous native/legacy phrases still
require an explicit format, and an unambiguous legacy phrase keeps its existing
confirmation. Silently applying the wrong salt would restore a different key.

The importer derives candidate addresses locally from the original format's key.
Native TOS format uses the network-bound TOS V5 address; legacy format scans V5,
V5 beta, V4R2/V4R1 and V3R2/V3R1. It does not depend on a public-key index.
Native balances retain BigInteger precision while deciding whether to restore.
Token/NFT discovery uses the current node methods getAccountTokens/getAccountNfts,
including their explicit index completeness status. Nonempty index rows are
retained conservatively even if a token wallet's present balance may be zero.
These are discovery hints, not authenticated balance or signing capabilities.

Every deployed active/frozen candidate, including zero balances, and every
candidate with native balance, indexed assets, or unknown/incomplete asset
status is retained. Unknown status may therefore restore additional empty
accounts; it never silently excludes a possible asset holder. Only when all
candidates are known empty is the original format's default V5 selected alone.
Failures to read account state stop recovery instead of guessing a zero balance.
Recovery requires an explicit recognized state and a decimal-string balance
within unsigned120-bit bounds; missing/malformed fields cannot become absence.
Recovery of a watch wallet selects its exact original address or fails; it must
not choose another discovered wallet. Multiple restored accounts use numeric
labels, while their contract versions remain internal signing metadata.

Validation includes seven selection/identity/naming unit cases, six RPC discovery
cases, the full API/App unit suites and a device UI flow. The bounded public RPC
fixture exercises native-balance, token-only, NFT-only and incomplete-index
accounts plus active/frozen zero-balance accounts, then checks all six imported
addresses and private-key derivations.
It does not claim actual-chain transfer, proof verification or mobile Quantum
payment acceptance. The existing legacy on-chain signing tests remain separate.

Run make test_classic_recovery_controls to remove and restore deployed-account, unknown-asset and
original-watch-address protections. The runner requires the intended JUnit
assertion failure, restores the exact source bytes in finally, then runs the
complete App unit suite. Logs stay under the supplied build/artifact directory.
The device case is included in scripts/test_v1_emulator.sh.
