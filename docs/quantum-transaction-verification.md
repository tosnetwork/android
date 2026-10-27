# Native transaction verification boundary

`QuantumWalletTransactionNative.verify` calls the portable TOS transaction C ABI
through an app-owned JNI adapter, `libtoswalletreceipt.so`. It links directly to
the source-built `libtosproofverify.so` at `scripts/quantum-proof-revision.txt`;
there is no dynamic symbol lookup or older-code fallback. Both libraries are
built for API24 or later, with 16KiB load alignment. The adapter hides static C++
runtime exports and exports only its JNI entry point. A partial debug ABI build
packages only the requested ABIs; release builds require all four.

The call validates a basechain transaction against an expected account hash,
transaction hash and unsigned logical time. All native inputs have independent
owned buffers. BOC and result sizes are limited to 1MiB. Logical time is a
canonical decimal string, parsed as UInt64 without a signed Long or floating
point intermediate. `QuantumVerifiedRead.provenTransactionHead` obtains its
integer from the original verified JSON token, before JSONObject's lossy
large-number conversion.

The codec does not authenticate inclusion or authorize signing. A history reader
must obtain the starting hash/time from a fresh authenticated account proof and
walk the predecessor commitments. Message bodies remain opaque. Successful
compute/action phases do not prove a transfer: a +2 send may be skipped, with no
outgoing message. Payment confirmation additionally requires matching the
committed outbound message and, when needed, authenticated recipient state.

`QuantumWalletTransactionDeviceTest` checks the frozen three-transaction public
fixture (including an opaque body and a skipped send), bindings and corruption,
full UInt64 parsing, and JNI checks invoked without Kotlin preconditions.
`QuantumProofDeviceTest` and `QuantumProofSessionDeviceTest` retain the existing
proof/session regression coverage. The public fixture is codec/hash-chain test
material, not phone custody signing or a fresh chain inclusion proof.

## Authenticated history and product routing

`QuantumHistoryReader` starts from `QuantumAccountReader.observe`, which loads
the retained UUID, QSI1 birth identity and network anchor. The native account
proof supplies the head; caller/RPC hashes cannot replace it. Every predecessor
is checked through the native codec, with strictly decreasing UInt64 time,
consistent zero termination, non-increasing timestamps and a shard-time bound.
Freshness is checked before and after the walk. The opaque cursor retains that
original observation and is bound to the same wallet and identity.

There are at most two active history reads globally and one per wallet; busy
requests refuse instead of queuing. A page has at most 20 transactions. The
walk has a checked monotonic 60-second deadline; transaction HTTP calls have a
10-second timeout, no redirects, a 2MiB response limit, depth64 JSON bound and
an exact response ID. Cancellation interrupts the worker; the deadline is
checked again before publishing. Raw RPC metadata is discarded.

The actual Compose History route and Quantum native-coin details use
`AccountRepository.readQuantumHistory`; the ordinary event VM/cache is not
instantiated for these routes. The current page and at most 128 small pagination
positions are retained. Hidden/background pages cancel their reads. Failure
removes old rows and numeric balance; refresh starts a new authenticated read.
Coin details obtain balance from the same authenticated observation as history.
Money is formatted as canonical decimal nano-units without Double conversion.

An incoming message to an undeployed account can skip compute yet credit the
account. The record is therefore Included, rather than incorrectly reporting a
failed incoming payment from phase flags. A successful external request with a
skipped send remains a Wallet operation / Send skipped, not an outgoing transfer.
Details display native transaction fees and opaque message identities; these are
neither total-debit estimates nor recipient-credit/finality claims.

The public receiver fixture and `QuantumHistoryTransactionTest` cover those
states, exact 120-bit money and UInt64 time. `QuantumAccountReaderTest` checks
an authenticated empty head and rejects invalid page limits before IO. The
optional `QuantumHistoryLiveTest` requires explicit fresh-chain endpoint/root/
file arguments; an assumption skip is not live acceptance. Product UI acceptance
uses `QuantumCreationUiTest.freshNetworkProductHistoryVerifiesIncomingAndRecoversFromRawCorruption`
and the loopback-only `scripts/quantum_history_ui_fixture.py`. Its Window-frame
visual capture requires API26+; native transaction verification remains API24+.
Funding comes from
the public genesis test signer, not phone custody. The test verifies corruption
refusal/recovery, coin details and restoration of FLAG_SECURE after an
instrumentation-only capture of the public page.

AAR consumers do not inherit the library's `ndk.abiFilters`. Generated JNI inputs
are consequently isolated under a `selection-` directory for the sorted ABI
selection, so another selection's cached libraries cannot leak into an AAR.
Both explicit and aggregate release graphs still require all four ABIs.

Protected-custody deployment/payment, an outbox, fee budgeting, complete backup,
rescue/migration and final release acceptance remain separate unfinished work.
