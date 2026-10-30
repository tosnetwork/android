#!/usr/bin/env python3
"""Disposable emulator cold recovery. Displayed backup stays in memory, never an artifact."""
import argparse
import hashlib
import hmac
import json
import re
import secrets
import subprocess
import threading
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--app', required=True, type=Path)
    parser.add_argument('--test-app', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--global-id', required=True, type=int)
    parser.add_argument('--root-hash', required=True)
    parser.add_argument('--file-hash', required=True)
    parser.add_argument('--rpc', default='http://10.0.2.2:18545')
    parser.add_argument('--port', type=int, default=18853)
    parser.add_argument('--journey-recipient', help='Continue with restored-account funding/payment and force-stop recovery')
    a = parser.parse_args()
    if a.journey_recipient and not re.fullmatch(r'0:[0-9a-f]{64}', a.journey_recipient):
        raise ValueError('Requires a public raw localnet recipient')
    if not re.fullmatch(r'emulator-\d+', a.serial):
        raise ValueError('Only a disposable TOS emulator is supported')
    adb = ['adb', '-s', a.serial]
    def run(args):
        return subprocess.check_output(adb + args, timeout=60, stderr=subprocess.STDOUT)
    name = run(['emu', 'avd', 'name']).decode().splitlines()[0]
    if not name.startswith('TOS_'):
        raise ValueError('Requires a dedicated TOS_ emulator')
    a.out.mkdir(mode=0o700, parents=True, exist_ok=False)
    artifacts = {str(p.resolve()): hashlib.sha256(p.read_bytes()).hexdigest() for p in (a.app, a.test_app)}
    (a.out / 'apks.json').write_text(json.dumps(artifacts, indent=2))
    token = secrets.token_hex(32)
    state = {'words': None, 'public': None, 'reinstalled': False, 'restored': False, 'verified': False}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_POST(self):
            try:
                self.connection.settimeout(10)
                if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                    raise ValueError('unauthorized')
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 32768:
                    raise ValueError('request limit')
                body = json.loads(self.rfile.read(length))
                if self.path == '/stage':
                    if state['public'] is not None or set(body) != {'words', 'public'}:
                        raise ValueError('stage already consumed')
                    words = body['words']
                    if len(words) != 24 or any(not isinstance(w, str) or not re.fullmatch('[a-z]{1,20}', w) for w in words):
                        raise ValueError('invalid displayed backup')
                    state['words'], state['public'] = words, body['public']
                    (a.out / 'before-public.json').write_text(json.dumps(state['public'], indent=2))
                    result = {'staged': True}
                elif self.path == '/restore':
                    if body or not state['reinstalled'] or state['restored']:
                        raise ValueError('restore unavailable')
                    result = {'words': state['words']}
                    state['words'] = None
                    state['restored'] = True
                elif self.path == '/verify':
                    if not state['restored'] or state['verified']:
                        raise ValueError('verify unavailable')
                    if body != state['public']:
                        raise ValueError('public identity mismatch')
                    (a.out / 'after-public.json').write_text(json.dumps(body, indent=2))
                    state['verified'] = True
                    result = {'matched': True}
                else:
                    raise ValueError('unknown action')
                raw = json.dumps(result).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            except Exception:
                self.send_error(400, 'Cold recovery stage refused')
    server = HTTPServer(('127.0.0.1', a.port), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    def install():
        for path in (a.app, a.test_app):
            if hashlib.sha256(path.read_bytes()).hexdigest() != artifacts[str(path.resolve())]:
                raise ValueError('APK changed during cold recovery')
            if 'Success' not in run(['install', '-r', '-t', str(path.resolve())]).decode():
                raise RuntimeError('install failed')
    def test(method, extra=None):
        for path in (a.app, a.test_app):
            if hashlib.sha256(path.read_bytes()).hexdigest() != artifacts[str(path.resolve())]:
                raise ValueError("APK changed during the journey")
        command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'network.tos.wallet.QuantumCreationUiTest#' + method,
            '-e', 'quantumRpc', a.rpc, '-e', 'quantumGlobalId', str(a.global_id),
            '-e', 'quantumRootHash', a.root_hash, '-e', 'quantumFileHash', a.file_hash,
            '-e', 'quantumColdControl', f'http://10.0.2.2:{a.port}/',
            '-e', 'quantumColdToken', token,
            'network.tos.wallet.test/androidx.test.runner.AndroidJUnitRunner']
        for key, value in (extra or {}).items():
            command[-1:-1] = ['-e', key, str(value)]
        with (a.out / (method + '.log')).open('w') as output:
            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=300, check=True)
        log = (a.out / (method + '.log')).read_text()
        if 'OK (1 test)' not in log or 'FAILURES' in log:
            raise RuntimeError(method + ' did not pass; inspect its log')
    try:
        install()
        run(['shell', 'pm', 'clear', 'network.tos.wallet'])
        test('coldRecoveryCreatesAndStagesDisplayedBackup')
        if state['words'] is None:
            raise RuntimeError('No displayed backup staged')
        receipt = run(['uninstall', 'network.tos.wallet']).decode()
        if 'Success' not in receipt:
            raise RuntimeError('Uninstall failed')
        (a.out / 'uninstall.txt').write_text(receipt)
        install()
        state['reinstalled'] = True
        test('coldRecoveryRestoresAfterUninstallWithSameAddressAndKeys')
        if not state['verified']:
            raise RuntimeError('Identity verification missing')
        journey = {}
        if a.journey_recipient:
            def recipient_state():
                endpoint = urllib.parse.urlsplit(a.rpc)
                if endpoint.scheme != 'http' or endpoint.hostname != '10.0.2.2' or endpoint.username or endpoint.password:
                    raise ValueError('Full journey requires the emulator loopback RPC')
                host_url = urllib.parse.urlunsplit(('http', '127.0.0.1:' + str(endpoint.port or 80), '/jsonRPC', '', ''))
                body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'getAddressInformation',
                                   'params': {'address': a.journey_recipient}}).encode()
                with urllib.request.urlopen(urllib.request.Request(host_url, data=body,
                        headers={'Content-Type': 'application/json'}), timeout=30) as response:
                    reply = json.load(response)
                if 'error' in reply:
                    raise ValueError('Recipient read failed')
                value = reply['result']
                return {'balance': value['balance'], 'last_transaction_id': value.get('last_transaction_id')}
            before_recipient = recipient_state()
            test('journeyPaysFromRestoredWallet', {'quantumRecipient': a.journey_recipient,
                'quantumExpectedAddress': state['public']['address'],
                'quantumExpectedIdentity': state['public']['identity_cell_hash']})
            receipt = json.loads(run(['shell', 'run-as', 'network.tos.wallet', 'cat',
                                     'cache/quantum-app-payment-public-receipt.json']))
            if (receipt['wallet'] != state['public']['address'] or
                    receipt['birth_identity_hash'] != state['public']['identity_cell_hash']):
                raise ValueError('Payment came from a different restored identity')
            (a.out / 'payment-public-receipt.json').write_text(json.dumps(receipt, indent=2))
            # Sender verification and recipient credit remain independent observations.
            import time
            recipient_paid = recipient_state()
            deadline = time.monotonic() + 30
            while int(recipient_paid['balance']) <= int(before_recipient['balance']) and time.monotonic() < deadline:
                time.sleep(1)
                recipient_paid = recipient_state()
            if not 0 < int(recipient_paid['balance']) - int(before_recipient['balance']) <= 1000000000:
                raise ValueError('Recipient did not receive the bounded one-TOS payment')
            run(['shell', 'am', 'force-stop', 'network.tos.wallet'])
            test('localNetworkRestoresSubmittedPaymentAfterProcessRestart', {
                'quantumExpectedOperation': receipt['operation'], 'quantumExpectedBOC': receipt['message_sha256']})
            recipient_restarted = recipient_state()
            if recipient_restarted != recipient_paid:
                raise ValueError('Recipient changed during payment result recovery')
            restarted = json.loads(run(['shell', 'run-as', 'network.tos.wallet', 'cat',
                                        'cache/quantum-restart-public-receipt.json']))
            (a.out / 'restart-public-receipt.json').write_text(json.dumps(restarted, indent=2))
            (a.out / 'recipient-observations.json').write_text(json.dumps({
                'before': before_recipient, 'paid': recipient_paid, 'after_restart': recipient_restarted}, indent=2))
            journey = {'restored_account_funded_and_history_verified': True,
                       'restored_keys_signed_actual_payment': True, 'force_stop_result_recovery': True,
                       'same_operation_and_boc': True, 'recipient_unchanged_during_restart': True}
        for path in (a.app, a.test_app):
            if hashlib.sha256(path.read_bytes()).hexdigest() != artifacts[str(path.resolve())]:
                raise ValueError('APK changed before final result')
        (a.out / 'result.json').write_text(json.dumps({'passed': True, 'same_apk': True,
            'actual_uninstall': True, 'same_address_identity_and_keys': True, **journey}, indent=2))
        print('PASS: cold recovery' + (' and same-account payment/restart journey' if journey else ''))
    finally:
        state['words'] = None
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == '__main__':
    main()
