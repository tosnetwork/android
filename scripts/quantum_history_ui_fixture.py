"""Bounded loopback relay for disposable Quantum product history UI tests.

One explicit raw address can be funded from the public genesis test signer.
No phone custody is read. This is not mobile signing acceptance.
"""
import argparse
import base64
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--history', action='store_true', help='Falsify transaction metadata and enable raw-cell corruption controls')
    p.add_argument('--port', type=int, required=True)
    p.add_argument('--upstream', required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--tos-root', type=Path, required=True)
    p.add_argument('--fixture', type=Path)
    p.add_argument('--signer', type=Path)
    p.add_argument('--funding-control', help='Loopback localnet faucet instead of a predeployed fixture')
    p.set_defaults(history=True)
    a = p.parse_args()
    endpoint = urllib.parse.urlsplit(a.upstream)
    if (endpoint.scheme != 'http' or endpoint.hostname not in ('127.0.0.1', 'localhost')
            or endpoint.username or endpoint.password or endpoint.path or endpoint.query or endpoint.fragment):
        raise ValueError('requires loopback native test node')
    a.out.mkdir(parents=True, exist_ok=False, mode=0o700)
    sys.path.insert(0, str(a.tos_root / 'test/auth-extensions'))
    from cells import Cell, from_boc
    fixture = None
    source = None
    if a.funding_control:
        control = urllib.parse.urlsplit(a.funding_control)
        if (control.scheme != 'http' or control.hostname not in ('127.0.0.1', 'localhost')
                or control.username or control.password or control.path or control.query or control.fragment
                or a.fixture or a.signer):
            raise ValueError('requires an exclusive loopback localnet faucet')
    else:
        if not a.fixture or not a.signer:
            raise ValueError('requires fixture and signer, or localnet faucet')
        fixture = json.loads(a.fixture.read_text())
        if fixture['global_id'] != 1 or fixture['namespace'] != '42' * 32:
            raise ValueError('requires isolated public self-pay genesis')
        source = fixture['accounts']['active']
    faucet_attempted = False
    poisoned = False
    corrupt_history = False
    counts = {'proofs': 0, 'ordinary': 0, 'transactions': 0, 'legacy_history': 0}
    traffic = []
    funded = None
    funded_boc = None
    recipient = None

    def rpc(method, params):
        payload = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params}).encode()
        request = urllib.request.Request(a.upstream.rstrip('/') + '/jsonRPC', data=payload,
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=35) as response:
            raw = response.read(16 * 1024 * 1024 + 1)
        if len(raw) > 16 * 1024 * 1024:
            raise ValueError('response limit')
        result = json.loads(raw)
        if 'error' in result:
            raise ValueError('native RPC rejected ' + method + ': ' + str(result['error']))
        return result['result']

    def snapshot():
        if recipient is None:
            raise ValueError('requires an explicit test funding recipient')
        return {'address': recipient}

    def fund():
        nonlocal funded, funded_boc, faucet_attempted
        current = snapshot()
        if funded is not None:
            if funded['address'] != current['address']:
                raise ValueError('recipient changed after funding')
            if funded_boc is not None:
                rpc('sendBocReturnHash', {'boc': funded_boc})
            return funded
        if a.funding_control:
            if faucet_attempted:
                raise ValueError('previous faucet result is unknown; do not submit another transfer')
            faucet_attempted = True
            (a.out / 'faucet-attempt.json').write_text(json.dumps(current) + '\n')
            request = urllib.request.Request(a.funding_control.rstrip('/') + '/transfer',
                data=json.dumps({'address': current['address'], 'amount': 10}).encode(),
                headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=65) as response:
                reply = json.loads(response.read(65537))
            if int(reply['after']) - int(reply['before']) != 10_000_000_000:
                raise ValueError('faucet did not confirm the expected funding delta')
            funded = dict(current, nanos='10000000000', status='funded')
            (a.out / 'fund.json').write_text(json.dumps(funded, indent=2) + '\n')
            return funded
        # This seed is already public in the canonical regression vector.
        private = a.out / 'private-public-test-signer'
        private.mkdir(mode=0o700)
        pk, sk, digest, sig = [private / n for n in ('public.bin', 'secret.bin', 'digest.bin', 'signature.bin')]
        subprocess.run([str(a.signer), 'keygen', '11' * 32, str(pk), str(sk)],
                       check=True, capture_output=True, timeout=30)
        if pk.read_bytes().hex() != fixture['primary_key']:
            raise ValueError('public fixture signer mismatch')
        sender = (0, int(source['address'][2:], 16))
        recipient = (0, int(current['address'][2:], 16))
        authority = from_boc(base64.b64decode(source['data_boc'])).refs[0]
        message = (Cell().uint(4, 4).addr(sender).addr(recipient).coins(10_000_000_000).uint(0, 1)
                   .coins(0).coins(0).uint(0, 64).uint(int(time.time()), 32).uint(0, 1).uint(1, 1).ref(Cell()))
        actions = Cell().uint(0x0EC3C86D, 32).uint(3, 8).ref(Cell()).ref(message)
        request = (Cell().uint(0x51535031, 32).sint(1, 32).uint(int('42' * 32, 16), 256).addr(sender)
                   .raw(authority.hash).uint(1, 8).uint(0, 64).uint(0, 64).uint(int(time.time()) + 180, 32)
                   .uint(0, 8).ref(Cell().uint(0x45584543, 32).ref(actions)))
        digest.write_bytes(request.hash)
        subprocess.run([str(a.signer), 'sign', str(sk), b'TOS-QSP-MLDSA44-v1'.hex(), str(digest), str(sig)],
                       check=True, capture_output=True, timeout=30)
        signature = sig.read_bytes()
        if len(signature) != 2420:
            raise ValueError('signature length')
        tail = None
        for offset in reversed(range(0, len(signature), 127)):
            cell = Cell().raw(signature[offset:offset + 127])
            if tail is not None:
                cell.ref(tail)
            tail = cell
        external = Cell().uint(8, 4).addr(sender).coins(0).uint(0, 1).uint(1, 1).ref(Cell().ref(request).ref(tail))
        funded_boc = base64.b64encode(external.boc()).decode()
        # Store the exact message before first delivery: retries never re-sign nonce 0.
        (private / 'message.boc').write_bytes(external.boc())
        funded = dict(current, nanos='10000000000', message_hash=external.hash.hex(), status='submitted')
        (a.out / 'fund.json').write_text(json.dumps(funded, indent=2) + '\n')
        rpc('sendBocReturnHash', {'boc': funded_boc})
        return funded

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def send(self, code, value):
            raw = value if isinstance(value, bytes) else json.dumps(value).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_POST(self):
            nonlocal poisoned, corrupt_history, recipient
            try:
                self.connection.settimeout(30)
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= 2 * 1024 * 1024:
                    raise ValueError('request limit')
                raw = self.rfile.read(length)
                if self.path.startswith('/__quantum_balance/'):
                    action = self.path.rsplit('/', 1)[-1]
                    if action == 'fund':
                        value = json.loads(raw)
                        if set(value) != {'address'} or not isinstance(value['address'], str):
                            raise ValueError('requires one raw recipient')
                        address = value['address']
                        if len(address) != 66 or not address.startswith('0:') or any(c not in '0123456789abcdef' for c in address[2:]):
                            raise ValueError('invalid raw recipient')
                        if recipient is not None and recipient != address:
                            raise ValueError('test recipient already bound')
                        recipient = address
                    elif raw != b'{}':
                        raise ValueError('control requires empty object')
                    if action in ('poison', 'normal'):
                        poisoned = action == 'poison'
                        value = {'status': action}
                    elif a.history and action in ('history-corrupt', 'history-normal'):
                        corrupt_history = action == 'history-corrupt'
                        value = {'status': action}
                    elif action == 'snapshot':
                        value = snapshot()
                    elif action == 'fund':
                        value = fund()
                    elif action == 'status':
                        value = {key: str(value) for key, value in counts.items()}
                    else:
                        raise ValueError('unknown control')
                    (a.out / (action + '.json')).write_text(json.dumps(value, indent=2) + '\n')
                    self.send(200, value)
                    return
                query = json.loads(raw)
                method = query.get('method', self.path.lstrip('/'))
                if len(traffic) < 128:
                    traffic.append({'method': method, 'poisoned': poisoned})
                (a.out / 'traffic.json').write_text(json.dumps({'counts': counts, 'requests': traffic}, indent=2) + '\n')
                if method == 'getTransactions':
                    counts['transactions'] += 1
                elif method == 'getAccountEvents':
                    counts['legacy_history'] += 1
                if method == 'getProofQuery':
                    counts['proofs'] += 1
                    if poisoned:
                        self.send(200, {'jsonrpc': '2.0', 'id': query.get('id'), 'result': {'reply': 'AA=='}})
                        return
                elif method in ('getAddressInformation', 'getAccountInfo'):
                    counts['ordinary'] += 1
                    if poisoned:
                        self.send(200, {'jsonrpc': '2.0', 'id': query.get('id'), 'result':
                                       {'balance': '999999999000000000', 'state': 'uninitialized', 'status': 'uninit'}})
                        return
                request = urllib.request.Request(a.upstream.rstrip('/') + self.path, data=raw,
                                                 headers={'Content-Type': 'application/json'})
                with urllib.request.urlopen(request, timeout=35) as response:
                    raw = response.read(16 * 1024 * 1024 + 1)
                if len(raw) > 16 * 1024 * 1024:
                    raise ValueError('response limit')
                if a.history and method == 'getTransactions':
                    value = json.loads(raw)
                    for transaction in value.get('result', []):
                        transaction.update(fee='999999999999', utime=0, aborted=True,
                            transaction_id={'lt': '0', 'hash': 'AA=='})
                        if corrupt_history:
                            cell = from_boc(base64.b64decode(transaction['data'], validate=True))
                            # Flip one timestamp bit without changing the typed layout.
                            # Only the proof-anchored transaction hash can detect this.
                            if len(cell.bits) < 676:
                                raise ValueError('short transaction header')
                            cell.bits = cell.bits[:675] + str(1 - int(cell.bits[675])) + cell.bits[676:]
                            transaction['data'] = base64.b64encode(cell.boc()).decode()
                    raw = json.dumps(value).encode()
                self.send(200, raw)
            except Exception as error:
                value = {'error': type(error).__name__ + ': ' + str(error)}
                (a.out / 'last-error.json').write_text(json.dumps(value, indent=2) + '\n')
                self.send(500, value)

    server = HTTPServer(('127.0.0.1', a.port), Handler)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
