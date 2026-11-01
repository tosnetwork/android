#!/usr/bin/env python3
"""Local test relay: forward RPC unchanged, retain only public getProofQuery exchanges.

Use an unused port and point the dedicated emulator's quantumRpc at this relay.
Never use this with a production wallet. No retries or proof verification changes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer
import time
import urllib.error
import urllib.request

MAX_REQUEST = 1_048_576
MAX_RESPONSE = 90_000_000
MAX_CAPTURE = 268_435_456


def serve(port, upstream, output):
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    sequence = 0
    retained = 0

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            nonlocal sequence, retained
            self.connection.settimeout(30)
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= MAX_REQUEST or self.headers.get('Transfer-Encoding'):
                    raise ValueError('invalid request size')
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError('incomplete request')
                parsed = json.loads(body)
                public_proof = (isinstance(parsed, dict) and parsed.get('method') == 'getProofQuery'
                                and set(parsed) <= {'jsonrpc', 'id', 'method', 'params'}
                                and isinstance(parsed.get('params'), dict)
                                and set(parsed['params']) == {'query'})
                request = urllib.request.Request(upstream, data=body,
                                                 headers={'Content-Type': 'application/json'})
                # HTTP errors are responses too; preserve their status and bytes.
                try:
                    response = urllib.request.urlopen(request, timeout=25)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    status = response.code
                    content_type = response.headers.get('Content-Type', 'application/json')
                    reply = response.read(MAX_RESPONSE + 1)
                if len(reply) > MAX_RESPONSE:
                    raise ValueError('response size limit')
                if public_proof:
                    if retained + len(body) + len(reply) > MAX_CAPTURE:
                        raise ValueError('capture budget exhausted; stop trial')
                    sequence += 1
                    stem = f'{sequence:06d}'
                    (output / (stem + '.request.json')).write_bytes(body)
                    (output / (stem + '.response.json')).write_bytes(reply)
                    with (output / 'index.jsonl').open('a') as index:
                        index.write(json.dumps({'sequence': sequence, 'time_ns': time.time_ns(),
                            'status': status, 'request_sha256': hashlib.sha256(body).hexdigest(),
                            'response_sha256': hashlib.sha256(reply).hexdigest()}) + '\n')
                    retained += len(body) + len(reply)
                self.send_response(status)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(reply)))
                self.end_headers()
                self.wfile.write(reply)
            except (ValueError, OSError, urllib.error.URLError):
                self.send_error(502, 'Recording relay refused; inspect trial evidence')

    HTTPServer(('127.0.0.1', port), Handler).serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18549)
    parser.add_argument('--upstream-port', type=int, default=18545)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535 or not 1024 <= args.upstream_port <= 65535 or args.port == args.upstream_port:
        parser.error('distinct unprivileged loopback ports required')
    os.umask(0o077)
    serve(args.port, f'http://127.0.0.1:{args.upstream_port}', args.out)
