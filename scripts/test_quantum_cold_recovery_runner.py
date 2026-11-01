"""Exercise real child-process failure modes without a wallet or emulator."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from quantum_cold_recovery import run_instrumentation, redact_failure_logcat
import json


class InstrumentationEvidenceTest(unittest.TestCase):
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
