#!/usr/bin/env python3
"""Local acceptance proxy: forward RPC, but drop the first send response once."""

import json
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "http://127.0.0.1:18545"
state = {"send_calls": 0, "dropped": 0, "replacement_boc": None}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path == "/stats":
            with lock:
                body = json.dumps({k: v for k, v in state.items() if k != "replacement_boc"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        payload = json.loads(body)
        if self.path == "/scenario":
            with lock:
                state.update(send_calls=0, dropped=0, replacement_boc=payload.get("replacement_boc"))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"ok":true}')
            return
        method = payload.get("method")
        if method == "sendBocReturnHash":
            with lock:
                replacement = state["replacement_boc"]
                state["replacement_boc"] = None
            if replacement:
                # Local test only: model another device winning the same nonce
                # while this device loses its send response.
                payload["params"]["boc"] = replacement
                body = json.dumps(payload).encode()
        request = urllib.request.Request(
            UPSTREAM + self.path,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                response_body = response.read()
                status = response.status
                content_type = response.headers.get("Content-Type", "application/json")
        except Exception as error:
            self.send_error(502, str(error))
            return

        drop = False
        if method == "sendBocReturnHash":
            with lock:
                state["send_calls"] += 1
                if state["dropped"] == 0:
                    state["dropped"] = 1
                    drop = True
        if drop:
            timeout_body = b'{"error":"upstream response timeout"}'
            self.send_response(504)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(timeout_body)))
            self.end_headers()
            self.wfile.write(timeout_body)
            return

        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(response_body)))
        self.end_headers()
        self.wfile.write(response_body)


ThreadingHTTPServer(("127.0.0.1", 18746), Handler).serve_forever()
