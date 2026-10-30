#!/usr/bin/env python3
"""Fail closed on skipped, incomplete or substituted expiry/outbox instrumentation."""
from collections import Counter
from pathlib import Path
import sys

PREFIX = 'network.tos.wallet.data.account.'
EXPECTED = {
    (PREFIX + 'QuantumExpiryCapabilityTest', name) for name in (
        'allCanonicalPhasesRequireStrictlyLaterFreshShardTimeAndHaveNoReleaseSideEffects',
        'emptyAuthenticatedHistoryNeverMintsAReceiptOrReleasesTheExpiredSlot',
        'localFutureClockCannotSubstituteForProvenShardTime',
        'operationPhaseAndMissingMessageChangesRejectOldCapabilities',
        'aliasCannotBypassTheSameBirthWorkerAndSlotBinding',
        'repeatedLoadsJsonKeyOrderAndEquivalentBirthEncodingKeepStableBindings',
        'sameCellRootsWithDifferentRequestOrMessageBytesRejectOldCapability',
        'malformedJournalAndUncertainPendingCannotBecomeExpiryAuthority',
    )
} | {(PREFIX + 'QuantumPaymentOutboxTest', name) for name in (
    'unsignedCancelAndSigningFailurePreserveSingleDurableAuthorization',
    'cancellationInsideCustodySigningKeepsDurableSlotAndNeverBroadcasts',
)}


def check(output):
    assert '\nOK (10 tests)\n' in '\n' + output + '\n', 'Expected exactly ten tests'
    assert not any(marker in output for marker in (
        'FAILURES!!!', 'INSTRUMENTATION_FAILED', 'initializationError',
        'Process crashed', 'INSTRUMENTATION_ABORTED',
    )), 'Instrumentation reported failure'
    starts, finishes = Counter(), Counter()
    fields, finals = {}, []
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, sep, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if sep and key in ('class', 'test', 'numtests', 'current'):
                assert key not in fields, 'Repeated event field'
                fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(': ')[2])
            assert code in (0, 1), 'Skipped, assumed, failed or unknown test status'
            assert fields.get('numtests') == '10' and fields.get('current') in {str(i) for i in range(1, 11)}
            key = fields.get('class'), fields.get('test')
            assert key in EXPECTED, 'Unexpected or missing test identity'
            (starts if code == 1 else finishes)[key] += 1
            fields = {}
        elif line.startswith('INSTRUMENTATION_CODE: '):
            finals.append(int(line.partition(': ')[2]))
    assert not fields and finals == [-1], 'Incomplete or unsuccessful instrumentation'
    assert starts == finishes == Counter({key: 1 for key in EXPECTED}), 'Missing or duplicate test events'


if __name__ == '__main__':
    try:
        check(Path(sys.argv[1]).read_text())
    except (AssertionError, ValueError) as error:
        sys.exit(f'quantum-expiry-device: FAILED: {error}')
    print('quantum-expiry-device: PASS (8 expiry + 2 outbox; no skips)')
