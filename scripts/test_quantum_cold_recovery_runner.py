"""Exercise real child-process failure modes without a wallet or emulator."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from quantum_cold_recovery import run_instrumentation, redact_failure_logcat, redact_failure_environment, instrumentation_timeout
from quantum_cold_recovery import validate_migration_signing_evidence
import json


class MigrationSigningEvidenceTest(unittest.TestCase):
    def evidence(self):
        return dict(operation='12345678-1234-1234-1234-123456789abc', request_hash='ab' * 32,
            signature_digest='cd' * 32, deadline=1120, pin_cancelled_without_reservation=True,
            background_cleared_review=True, signature_verified=True, duplicate_rejected=True,
            isolation_retained=True, state_and_balance_unchanged=True)

    def restart(self, signed):
        return {**{key: signed[key] for key in ('operation', 'request_hash', 'signature_digest', 'deadline')},
            'signature_verified': True, 'restart': True, 'isolation_retained': True}

    def test_matching_original_signature_and_restart_evidence(self):
        signed = self.evidence()
        validate_migration_signing_evidence(signed)
        validate_migration_signing_evidence(signed, self.restart(signed))

    def test_missing_or_false_product_controls_and_invalid_identity_are_rejected(self):
        for key, value in self.evidence().items():
            changed = self.evidence(); del changed[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                validate_migration_signing_evidence(changed)
            if value is True:
                for invalid in (False, 1, 'true'):
                    changed = {**self.evidence(), key: invalid}
                    with self.subTest(key=key, invalid=invalid), self.assertRaises(ValueError):
                        validate_migration_signing_evidence(changed)
        for key, value in [('operation', '00000000-0000-0000-0000-000000000000'),
                ('request_hash', 'bad'), ('signature_digest', 'EF' * 32), ('deadline', True),
                ('deadline', 0), ('deadline', 0x100000000)]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate_migration_signing_evidence({**self.evidence(), key: value})

    def test_restart_cannot_replace_original_operation_request_signature_or_deadline(self):
        signed = self.evidence()
        for key in self.restart(signed):
            changed = self.restart(signed); del changed[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                validate_migration_signing_evidence(signed, changed)
            changed = self.restart(signed)
            changed[key] = False if changed[key] is True else (1121 if key == 'deadline' else 'ef' * 32)
            with self.subTest(changed=key), self.assertRaises(ValueError):
                validate_migration_signing_evidence(signed, changed)


class InstrumentationEvidenceTest(unittest.TestCase):
    def test_only_explicit_real_freshness_wait_receives_longer_timeout(self):
        self.assertEqual(instrumentation_timeout('fundingCompletionRejectsChangedRecordsAndExpiredProof'), 420)
        for method in ('createdWalletExportsEncryptedFileThroughSystemPicker',
                       'confirmedFundingCompletionRetainsIsolationAndRejectsRepeatedWrite',
                       'fundingCompletionRejectsChangedRecordsAndExpiredProofExtra', ''):
            self.assertEqual(instrumentation_timeout(method), 300)
    def invoke(self, source, collect, timeout=5):
        with tempfile.TemporaryDirectory() as directory:
            run_instrumentation([sys.executable, '-c', source],
                                Path(directory) / 'instrumentation.log', collect, timeout)

    def test_success_does_not_collect_failure(self):
        evidence = []
        self.invoke("print('OK (1 test)')", lambda: evidence.append(True))
        self.assertEqual(evidence, [])

    def test_nonzero_exit_still_collects_and_preserves_exit_status(self):
        evidence = []
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            self.invoke('raise SystemExit(7)', lambda: evidence.append(True))
        self.assertEqual(caught.exception.returncode, 7)
        self.assertEqual(evidence, [True])

    def test_timeout_still_collects_and_remains_timeout(self):
        evidence = []
        with self.assertRaises(subprocess.TimeoutExpired):
            self.invoke('import time; time.sleep(30)', lambda: evidence.append(True), timeout=0.1)
        self.assertEqual(evidence, [True])

    def test_junit_failure_is_not_overridden_by_diagnostic_failure(self):
        def broken_capture():
            raise OSError('diagnostic unavailable')
        with self.assertRaisesRegex(RuntimeError, 'Instrumentation did not pass'):
            self.invoke("print('FAILURES!!!')", broken_capture)

    def test_success_marker_does_not_hide_failure_marker(self):
        evidence = []
        with self.assertRaises(RuntimeError):
            self.invoke("print('OK (1 test)\\nFAILURES!!!')", lambda: evidence.append(True))
        self.assertEqual(evidence, [True])

    def test_success_marker_does_not_hide_process_crash(self):
        evidence = []
        with self.assertRaises(RuntimeError):
            self.invoke("print('OK (1 test)\\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.')", lambda: evidence.append(True))
        self.assertEqual(evidence, [True])


class CrashRedactionTest(unittest.TestCase):
    def test_environment_keeps_only_numbers_and_known_wake_state(self):
        result = redact_failure_environment(b'MemTotal: 2000 kB\nMemAvailable: 1000 kB\nSecret: password\n',
            b'mWakefulness=Asleep\nsecret-wake-lock-tag', b'60000\n')
        self.assertEqual({'guest_memory_kb': {'MemTotal': 2000, 'MemAvailable': 1000},
                          'wakefulness': 'Asleep', 'screen_off_timeout_ms': 60000}, result)
        self.assertIsNone(redact_failure_environment(b'', b'private', b'secret')['screen_off_timeout_ms'])
        with self.assertRaises(ValueError):
            redact_failure_environment(b'x' * 65537, b'', b'')

    def test_owned_process_signal_is_numeric_without_freeform_text(self):
        raw = b'I/am_proc_died( 10): [0,123,network.tos.wallet,0,secret]\nI/Process( 123): Sending signal. PID: 123 SIG: 9\n'
        self.assertIn({'event': 'process_signal', 'pid': 123, 'signal': 9}, redact_failure_logcat(raw)['records'])

    def test_runner_tag_frames_are_bound_by_owned_exit_pid_without_messages(self):
        raw = b'''I/am_proc_died( 10): [0,123,network.tos.wallet,0,secret reason]
E/TestRunner( 123): java.lang.IllegalStateException: secret password
E/TestRunner( 123): at network.tos.wallet.Step.run(Step.kt:77)
E/TestRunner( 456): java.lang.Error: other-secret
E/TestRunner( 456): at java.lang.Other.run(Other.java:1)
'''
        result = json.dumps(redact_failure_logcat(raw))
        self.assertIn('java.lang.IllegalStateException', result)
        self.assertIn('Step.kt:77', result)
        for forbidden in ('secret', 'password', 'Other.java', '456'):
            self.assertNotIn(forbidden, result)

    def test_owned_java_frames_survive_without_messages_inputs_or_other_processes(self):
        raw = b'''E/AndroidRuntime( 123): FATAL EXCEPTION: secret-thread
E/AndroidRuntime( 123): Process: network.tos.wallet, PID: 123
E/AndroidRuntime( 123): java.lang.IllegalStateException: secret password abandon ability
E/AndroidRuntime( 123):     at network.tos.wallet.Step.run(Step.kt:42)
E/AndroidRuntime( 123):     at network.tos.wallet.Step.run(secret password)
E/AndroidRuntime( 456): Process: other.app, PID: 456
E/AndroidRuntime( 456): java.lang.Error: other-secret
E/AndroidRuntime( 456):     at java.lang.Other.run(Other.java:1)
'''
        result = json.dumps(redact_failure_logcat(raw))
        self.assertIn('java.lang.IllegalStateException', result)
        self.assertIn('Step.kt:42', result)
        for forbidden in ('secret', 'abandon', 'ability', 'Other.java', '456'):
            self.assertNotIn(forbidden, result)

    def test_native_frames_drop_symbols_registers_paths_and_next_process(self):
        raw = b'''F/DEBUG( 9): pid: 123, tid: 123, name: wallet >>> network.tos.wallet <<<
F/DEBUG( 9): #00 pc 00001234 /data/app/private/lib/arm/libtoswalletreceipt.so (secret_symbol+4)
F/DEBUG( 9): register x1 secret-memory
F/DEBUG( 9): pid: 456, tid: 456, name: other >>> other.app <<<
F/DEBUG( 9): #01 pc 00009999 /data/app/private/lib/arm/libother.so (other_secret)
'''
        result = json.dumps(redact_failure_logcat(raw))
        self.assertIn('libtoswalletreceipt.so', result)
        self.assertIn('00001234', result)
        for forbidden in ('secret', 'private', 'register', 'libother', '00009999'):
            self.assertNotIn(forbidden, result)

    def test_owned_exit_events_drop_freeform_reasons(self):
        raw = b'I/am_kill( 10): [0,123,network.tos.wallet,900,secret reason]\nI/am_proc_died( 10): [0,456,other.app,900,secret]\n'
        self.assertEqual({'process': 'network.tos.wallet', 'records': [{'event': 'am_kill', 'pid': 123}]}, redact_failure_logcat(raw))

    def test_input_and_output_are_bounded(self):
        with self.assertRaises(ValueError):
            redact_failure_logcat(b'x' * 1_048_577)
        raw = b'E/AndroidRuntime( 123): Process: network.tos.wallet, PID: 123\n' + b'E/AndroidRuntime( 123): at java.lang.Thread.run(Thread.java:1)\n' * 200
        result = redact_failure_logcat(raw)
        self.assertEqual(128, len(result['records']))
        self.assertLess(len(json.dumps(result).encode()), 65536)


if __name__ == '__main__':
    unittest.main()
