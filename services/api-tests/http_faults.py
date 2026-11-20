"""HTTP proxy for dependency failures. Regular requests go to the real services."""

import http.client
import json
import socket
import threading
import time
from itertools import count
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TARGETS = {8081: ("cms", "card-management"), 8083: ("auth", "authorization"),
           8082: ("switch", "switch"), 8080: ("gateway", "gateway")}
RULES = []
LOCK = threading.Lock()
CLIENTS = count(1)


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, body, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            if name.lower() not in {"content-length", "transfer-encoding", "connection", "content-type"}:
                self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def dispatch(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                length = int(self.rfile.readline().split(b";", 1)[0], 16)
                if not length:
                    while self.rfile.readline().strip():
                        pass
                    break
                chunks.append(self.rfile.read(length))
                self.rfile.read(2)
            body = b"".join(chunks)
        else:
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.server.server_port == 8089 and self.path == "/__rules":
            with LOCK:
                RULES.clear()
                if self.command == "POST":
                    RULES.extend(json.loads(body))
            self.reply(200, b'{"updated":true}')
            return
        if self.server.server_port == 8089 and self.path == "/health":
            self.reply(200, b'{"status":"ok"}')
            return
        prefix, target = TARGETS[self.server.server_port]
        with LOCK:
            rule = next((r for r in RULES if r["path"] == "/" + prefix + self.path
                         and r["method"] == self.command), None)
        if rule:
            if rule.get("disconnect"):
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            time.sleep(rule.get("delaySeconds", 0))
            self.reply(rule["status"], json.dumps(rule.get("body", {})).encode())
            return
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in {"host", "connection", "transfer-encoding", "content-length"}}
        headers["Content-Length"] = str(len(body))
        if prefix == "gateway" and not any(k.lower() == "x-forwarded-for" for k in headers):
            client = next(CLIENTS)
            headers["X-Forwarded-For"] = f"10.254.{(client >> 8) & 255}.{client & 255}"
        connection = http.client.HTTPConnection(target, 8080, timeout=30)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            self.reply(response.status, response.read(), dict(response.getheaders()))
        except (OSError, http.client.HTTPException) as error:
            self.reply(502, json.dumps({"error": str(error), "service": "http-faults"}).encode())
        finally:
            connection.close()

    do_GET = dispatch
    do_POST = dispatch
    do_PATCH = dispatch
    do_DELETE = dispatch

    def log_message(self, *_):
        pass


for port in TARGETS:
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
ThreadingHTTPServer(("0.0.0.0", 8089), Handler).serve_forever()
