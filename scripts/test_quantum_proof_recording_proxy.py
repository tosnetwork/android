import http.client
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest


class RecordingRelayTest(unittest.TestCase):
    def test_explicit_close_preserves_sequential_requests_without_payload_retention(self):
        class Echo(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                if json.loads(body).get('method') == 'sendBocReturnHash':
                    self.server.submission_received_ns = time.time_ns()
                    time.sleep(0.03)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(body)

        upstream = HTTPServer(('127.0.0.1', 0), Echo)
        worker = threading.Thread(target=upstream.serve_forever, daemon=True)
        worker.start()
        try:
            with socket.socket() as lease:
                lease.bind(('127.0.0.1', 0))
                port = lease.getsockname()[1]
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'capture'
                process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('quantum_proof_recording_proxy.py')),
                    '--port', str(port), '--upstream-port', str(upstream.server_port), '--out', str(output)],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                try:
                    for _ in range(100):
                        self.assertIsNone(process.poll(), 'relay exited before listening')
                        try:
                            with socket.create_connection(('127.0.0.1', port), timeout=0.1):
                                break
                        except OSError:
                            time.sleep(0.02)
                    else:
                        self.fail('relay did not listen')
                    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
                    try:
                        proof = b'{"id":1,"method":"getProofQuery","params":{"query":"AQ=="}}'
                        submission = b'{"id":2,"method":"sendBocReturnHash","params":{"boc":"DO-NOT-RETAIN"}}'
                        for body in (proof, submission):
                            connection.request('POST', '/jsonRPC', body, {'Content-Type': 'application/json'})
                            response = connection.getresponse()
                            self.assertEqual(response.version, 11)
                            self.assertEqual(response.getheader('Connection'), 'close')
                            self.assertEqual(response.read(), body)
                            self.assertIsNone(connection.sock)
                        self.assertEqual((output / '000001.request.json').read_bytes(), proof)
                        self.assertEqual((output / '000001.response.json').read_bytes(), proof)
                        self.assertTrue((output / 'submission-metadata.jsonl').is_file())
                        event = json.loads((output / 'submission-metadata.jsonl').read_text())
                        self.assertLessEqual(event['forward_started_ns'], upstream.submission_received_ns)
                        self.assertGreaterEqual(event['time_ns'], upstream.submission_received_ns + 20_000_000)
                        self.assertGreaterEqual(event['exchange_duration_ns'], 20_000_000)
                        self.assertEqual(event['method'], 'sendBocReturnHash')
                        for file in output.iterdir():
                            self.assertNotIn(b'DO-NOT-RETAIN', file.read_bytes())
                    finally:
                        connection.close()
                finally:
                    process.terminate()
                    process.wait(timeout=5)
        finally:
            upstream.shutdown()
            upstream.server_close()
            worker.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
