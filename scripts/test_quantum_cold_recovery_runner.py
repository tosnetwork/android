"""Exercise real child-process failure modes without a wallet or emulator."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from quantum_cold_recovery import run_instrumentation


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


if __name__ == '__main__':
    unittest.main()
