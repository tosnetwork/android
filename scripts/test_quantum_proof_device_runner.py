#!/usr/bin/env python3
"""Exercise the instrumentation result gate, not the native verifier itself."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class InstrumentationGateTests(unittest.TestCase):
    def run_gate(self, output, status=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            runner = scripts / 'test_quantum_proof_device.sh'
            shutil.copyfile(Path(__file__).with_name(runner.name), runner)
            apk = root / 'lib/security/build/outputs/apk/androidTest/debug/security-debug-androidTest.apk'
            apk.parent.mkdir(parents=True)
            apk.touch()  # Only the result gate is tested; fake adb never installs it.
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env python3
import os,sys
if sys.argv[1] == 'get-state': print('device')
elif sys.argv[1] == 'install': pass
elif sys.argv[1] == 'shell':
    print(os.environ['PROOF_RUNNER_OUTPUT'])
    sys.exit(int(os.environ['PROOF_RUNNER_STATUS']))
else: sys.exit(99)
''')
            adb.chmod(0o755)
            env = dict(os.environ, ADB_BIN=str(adb), PROOF_RUNNER_OUTPUT=output, PROOF_RUNNER_STATUS=str(status))
            result = subprocess.run(['bash', str(runner)], env=env, capture_output=True, text=True)
            self.assertTrue((root / 'build/quantum-proof-device/instrumentation.log').is_file())
            return result

    def test_zero_tests_cannot_pass(self):
        self.assertNotEqual(self.run_gate('OK (0 tests)').returncode, 0)

    def test_incomplete_suite_cannot_pass(self):
        self.assertNotEqual(self.run_gate('OK (4 tests)').returncode, 0)

    def test_failure_output_cannot_pass(self):
        self.assertNotEqual(self.run_gate('OK (7 tests)\nFAILURES!!!').returncode, 0)

    def test_crash_cannot_pass(self):
        self.assertNotEqual(self.run_gate('INSTRUMENTATION_FAILED: process crashed').returncode, 0)

    def test_failed_command_cannot_pass(self):
        self.assertNotEqual(self.run_gate('OK (7 tests)', 1).returncode, 0)

    def test_complete_selected_suite_passes(self):
        result = self.run_gate('OK (7 tests)')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('quantum-proof-device: PASS', result.stdout)


if __name__ == '__main__':
    unittest.main()
