#!/usr/bin/env python3
"""Require runtime sensitivity for recovery retention and original-watch selection."""
import argparse
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--log-dir', type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
source = repo / 'apps/wallet/instance/app/src/main/java/network/tos/wallet/app/ui/screen/init/LegacyRecoverySelection.kt'
original = source.read_bytes()
args.log_dir.mkdir(parents=True, exist_ok=True)
command = [str(repo / 'gradlew'), '--no-daemon', '--max-workers=2', '-Dorg.gradle.parallel=false',
           '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8',
           ':apps:wallet:instance:app:testDebugUnitTest', '--tests', '*LegacyRecoverySelectionTest']
report = repo / 'apps/wallet/instance/app/build/test-results/testDebugUnitTest/TEST-network.tos.wallet.app.ui.screen.init.LegacyRecoverySelectionTest.xml'


def run(name, full=False):
    with (args.log_dir / f'{name}.log').open('w') as output:
        result = subprocess.run(command[:-2] if full else command, cwd=repo, stdout=output, stderr=subprocess.STDOUT)
    return result.returncode


controls = [('unknown-assets', ' || !candidates[it].assetsKnown', '',
             'missingOrIncompleteDiscoveryCannotHidePotentialAssets'),
            ('deployed-account', 'candidates[it].deployed || ', '',
             'deployedZeroBalanceAccountKeepsItsOriginalAddress'),
            ('watch-address', 'if (recoveryAddress != null)', 'if (false)',
             'watchRecoveryRetainsItsExactAddressEvenIfUnfunded')]
try:
    assert run('baseline') == 0, 'Baseline failed; no sensitivity result'
    for name, before, after, test in controls:
        text = original.decode()
        assert text.count(before) == 1, f'Missing/repeated control target: {name}'
        source.write_text(text.replace(before, after))
        code = run(name)
        assert code != 0, f'Guard removal stayed green: {name}'
        root = ET.parse(report).getroot()
        failed = [case for case in root.findall('testcase') if case.attrib['name'] == test and case.find('failure') is not None]
        assert len(failed) == 1, f'No intended runtime assertion: {name}'
        assert failed[0].find('failure').attrib['type'] in ('java.lang.AssertionError', 'org.junit.ComparisonFailure'), name
        print(f'CLASSIC_RECOVERY_CONTROL {name} RUNTIME_RED', flush=True)
finally:
    source.write_bytes(original)
    restored = run('restored', full=True)
    assert source.read_bytes() == original
    assert restored == 0, 'Restored tests failed'
print('CLASSIC_RECOVERY_CONTROL restored GREEN')
