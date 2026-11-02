#!/usr/bin/env python3
"""Host-only result-gate controls; these do not execute Android or prove expiry."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from check_quantum_expiry_device import EXPECTED, check


def transcript():
    lines = []
    for index, (klass, method) in enumerate(sorted(EXPECTED), 1):
        for code in (1, 0):
            lines.extend((f'INSTRUMENTATION_STATUS: class={klass}',
                          f'INSTRUMENTATION_STATUS: current={index}',
                          'INSTRUMENTATION_STATUS: numtests=10',
                          f'INSTRUMENTATION_STATUS: test={method}',
                          f'INSTRUMENTATION_STATUS_CODE: {code}'))
    return '\n'.join(lines) + '\nOK (10 tests)\nINSTRUMENTATION_CODE: -1\n'


class GateTests(unittest.TestCase):
    def test_exact_complete_suite(self):
        check(transcript())

    def test_partial_count_and_summary_only_rejected(self):
        for bad in (transcript().replace('OK (10 tests)', 'OK (9 tests)'), 'OK (10 tests)\n', 'OK (0 tests)\n'):
            with self.assertRaises(AssertionError): check(bad)

    def test_skip_assumption_and_failure_status_rejected(self):
        for code in (-1, -2, -3, -4, 2):
            with self.assertRaises(AssertionError):
                check(transcript().replace('INSTRUMENTATION_STATUS_CODE: 0', f'INSTRUMENTATION_STATUS_CODE: {code}', 1))

    def test_substitute_duplicate_or_missing_identity_rejected(self):
        method = sorted(EXPECTED)[0][1]
        for bad in (transcript().replace(method, 'anotherTest'),
                    transcript().replace(f'INSTRUMENTATION_STATUS: test={method}\n', '', 1),
                    transcript() + transcript()):
            with self.assertRaises(AssertionError): check(bad)

    def test_crash_and_unfinished_run_rejected(self):
        for bad in (transcript() + 'FAILURES!!!', transcript() + 'INSTRUMENTATION_FAILED',
                    transcript().replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
                    transcript().replace('INSTRUMENTATION_CODE: -1', '')):
            with self.assertRaises(AssertionError): check(bad)


class ShellGateTests(unittest.TestCase):
    def run_shell(self, output, status=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            for name in ('test_quantum_expiry_device.sh', 'check_quantum_expiry_device.py'):
                shutil.copyfile(Path(__file__).with_name(name), scripts / name)
            apk = root / 'apps/wallet/data/account/build/outputs/apk/androidTest/debug/account-debug-androidTest.apk'
            apk.parent.mkdir(parents=True)
            apk.touch()
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env python3
import os, sys
if sys.argv[1] == 'get-state': print('device')
elif sys.argv[1] == 'install': pass
elif sys.argv[1] == 'shell':
    assert '-r' in sys.argv
    assert sys.argv[-1] == 'network.tos.wallet.data.account.test/androidx.test.runner.AndroidJUnitRunner'
    assert sys.argv[-2] == 'network.tos.wallet.data.account.QuantumExpiryCapabilityTest,network.tos.wallet.data.account.QuantumPaymentOutboxTest'
    print(os.environ['RESULT'])
    sys.exit(int(os.environ['STATUS']))
else: sys.exit(99)
''')
            adb.chmod(0o755)
            result = subprocess.run(['bash', str(scripts / 'test_quantum_expiry_device.sh')],
                                    env=dict(os.environ, ADB_BIN=str(adb), RESULT=output, STATUS=str(status)),
                                    capture_output=True, text=True)
            self.assertEqual((root / 'build/quantum-expiry-device/instrumentation.log').read_text().strip(), output.strip())
            return result

    def test_complete_selected_shell_suite(self):
        result = self.run_shell(transcript())
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_nonzero_adb_cannot_pass_with_success_output(self):
        self.assertNotEqual(self.run_shell(transcript(), 1).returncode, 0)

    def test_zero_exit_does_not_hide_missing_or_skipped_tests(self):
        for output in ('OK (0 tests)', transcript().replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -3', 1)):
            self.assertNotEqual(self.run_shell(output).returncode, 0)


if __name__ == '__main__':
    unittest.main()
