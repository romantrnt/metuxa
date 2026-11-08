#!/usr/bin/env python3
"""Test real server pairing IPC using synthetic video in a private namespace.

Run through scripts/test-pairing-control.sh. No desktop, audio or input devices
are opened. Only standard-library Python modules are required.
"""
import hashlib
import json
import os
import queue
import signal
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time


def require(condition, message):
    if not condition:
        raise AssertionError(message)


require(len(sys.argv) >= 2, "Pass the server executable and optional fixed arguments")
require(not any(os.environ.get(key) for key in
                ("DISPLAY", "WAYLAND_DISPLAY", "WAYLAND_SOCKET", "DBUS_SESSION_BUS_ADDRESS")),
        "Desktop and session bus access must be disabled")
parent_namespace = os.environ.get("METUXA_TEST_PARENT_NETNS", "")
require(parent_namespace.startswith("net:[") and
        os.readlink("/proc/self/ns/net") != parent_namespace,
        "Run only through the private-network-namespace wrapper")
require([name for _, name in socket.if_nameindex()] == ["lo"],
        "The test namespace must contain loopback only")
require(os.environ.get("PIPEWIRE_REMOTE") == "metuxa-test-no-host-connection" and
        os.environ.get("PULSE_SERVER") == "unix:/nonexistent",
        "Host audio connections must be disabled")
SERVER = [os.path.abspath(sys.argv[1]), *sys.argv[2:]]
PORT = 27879


class Server:
    def __init__(self, invalid_control=False):
        self.events = queue.Queue()
        self.history = []
        self.logs = []
        self.control = None
        self.credentials = None
        self.last_attempt = 0.0
        status_read, status_write = os.pipe()
        temporary = None
        if invalid_control:
            temporary = tempfile.NamedTemporaryFile()
            # Read-only, owned by this user and private, but not a FIFO. This
            # reaches the file-type check rather than failing access-mode first.
            control_read = os.open(temporary.name, os.O_RDONLY | os.O_CLOEXEC)
        else:
            control_read, self.control = os.pipe()
        arguments = SERVER + [
            "--source", "test", "--width", "320", "--height", "240", "--fps", "15",
            "--bitrate", "1000", "--no-hw", "--codec", "h264", "--audio", "none",
            "--no-input", "--monitors", "1", "--port", str(PORT), "--no-discovery",
            "--status-fd", str(status_write), "--control-fd", str(control_read),
        ]
        try:
            self.process = subprocess.Popen(arguments, pass_fds=(status_write, control_read),
                                            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                            stderr=subprocess.PIPE, text=True)
        finally:
            os.close(status_write)
            os.close(control_read)
            if temporary is not None:
                temporary.close()

        def statuses():
            with os.fdopen(status_read) as source:
                for line in source:
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        event = {"invalid_status": True}
                    self.history.append(event)
                    self.events.put(event)

        def logs():
            with self.process.stderr as source:
                for line in source:
                    self.logs.append(line)
                    if len(self.logs) > 100:
                        del self.logs[0]

        self.status_thread = threading.Thread(target=statuses, daemon=True)
        self.log_thread = threading.Thread(target=logs, daemon=True)
        self.status_thread.start()
        self.log_thread.start()
        if not invalid_control:
            try:
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    event = self.event(lambda _: True, deadline=deadline)
                    if event.get("type") == "security":
                        self.credentials = event
                    if event.get("state") == "listening":
                        require(self.credentials is not None, "Missing run credentials")
                        return
                raise AssertionError("Synthetic server startup timed out")
            except BaseException:
                self.cleanup()
                raise

    def event(self, predicate, timeout=5, deadline=None):
        end = deadline if deadline is not None else time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                event = self.events.get(timeout=min(0.1, max(0.001, end - time.monotonic())))
            except queue.Empty:
                require(self.process.poll() is None, "Synthetic server exited before expected event")
                continue
            require(not event.get("invalid_status"), "Malformed private status event")
            if predicate(event):
                return event
        raise AssertionError("Expected private status event did not arrive")

    def command(self, value):
        require(self.control is not None, "Private command pipe is closed")
        data = (value + "\n").encode("ascii")
        require(os.write(self.control, data) == len(data), "Incomplete private pipe write")

    def arm(self):
        time.sleep(max(0, self.last_attempt + 2.05 - time.monotonic()))
        self.command("PAIR_OPEN")
        self.event(lambda e: e.get("type") == "pairing_window" and e.get("open") is True)

    def close_control(self):
        if self.control is not None:
            os.close(self.control)
            self.control = None

    def stop(self):
        if self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            require(self.process.wait(timeout=5) == 0, "Synthetic server did not stop cleanly")
        self.status_thread.join(timeout=2)
        self.log_thread.join(timeout=2)
        self.close_control()
        require(any(e.get("state") == "stopped" for e in self.history),
                "Missing orderly stop status")

    def cleanup(self):
        self.close_control()
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait(timeout=5)
        self.status_thread.join(timeout=2)
        self.log_thread.join(timeout=2)


class Client:
    def __init__(self, server):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        context.maximum_version = ssl.TLSVersion.TLSv1_3
        self.socket = context.wrap_socket(socket.socket(), server_hostname="metuxa-pairing-test")
        self.socket.settimeout(4)
        self.socket.connect(("127.0.0.1", PORT))
        self.fingerprint = hashlib.sha256(self.socket.getpeercert(binary_form=True)).digest()
        require(self.fingerprint.hex() == server.credentials["fingerprint"],
                "Negotiated certificate differs from private server identity")

    def send(self, value):
        self.socket.sendall((value + "\n").encode("ascii"))

    def line(self):
        data = bytearray()
        while True:
            byte = self.socket.recv(1)
            if not byte:
                require(not data, "Truncated TLS line")
                return ""
            if byte == b"\n":
                return data.decode("ascii")
            data += byte
            require(len(data) <= 4096, "Oversized TLS line")

    def no_data(self):
        self.socket.settimeout(0.25)
        try:
            self.socket.recv(1)
        except socket.timeout:
            return
        finally:
            self.socket.settimeout(4)
        raise AssertionError("Wrong or stale approval changed the pairing connection")

    def close(self):
        self.socket.close()


def closed_request(server):
    client = Client(server)
    try:
        nonce = os.urandom(32)
        commitment = hashlib.sha256(b"metuxa-pair1-client-commit\0" + client.fingerprint + nonce)
        client.send("PAIR1 " + commitment.hexdigest())
        require(client.line() == "ERROR PAIRING_CLOSED", "Closed gate admitted a request")
    finally:
        client.close()


def request(server):
    client = Client(server)
    try:
        nonce = os.urandom(32)
        commitment = hashlib.sha256(b"metuxa-pair1-client-commit\0" + client.fingerprint + nonce)
        server.last_attempt = time.monotonic()
        client.send("PAIR1 " + commitment.hexdigest())
        challenge = client.line().split(" ")
        require(len(challenge) == 3 and challenge[0] == "PAIR_CHALLENGE1",
                "Expected versioned pairing challenge")
        identifier, server_nonce = bytes.fromhex(challenge[1]), bytes.fromhex(challenge[2])
        require(len(identifier) == 16 and len(server_nonce) == 32, "Wrong challenge dimensions")
        client.send("PAIR_REVEAL1 " + nonce.hex())
        require(client.line() == "PAIR_WAIT1", "Server released data before confirmation")
        event = server.event(lambda e: e.get("type") == "pairing_request")
        require(event["id"] == identifier.hex() and event["peer"] == "127.0.0.1",
                "Private approval request is not bound to this TLS peer")
        digest = hashlib.sha256(b"metuxa-pair1-compare\0" + client.fingerprint + nonce +
                                server_nonce + identifier).digest()
        digits = f"{int.from_bytes(digest[:4], 'big'):010d}"
        require(event["code"] == digits[:5] + " " + digits[5:], "Independent comparison differs")
        require(0 < event["expires_seconds"] <= 60, "Invalid approval deadline")
        return client, event["id"]
    except BaseException:
        client.close()
        raise


def ended(server, identifier, reason):
    server.event(lambda e: e.get("type") == "pairing_closed" and
                 e.get("id") == identifier and e.get("reason") == reason)


def exercise_control():
    server = Server()
    try:
        closed_request(server)
        server.arm()
        client, approved_id = request(server)
        try:
            wrong = ("0" if approved_id[0] != "0" else "1") + approved_id[1:]
            server.command("PAIR_ACCEPT " + wrong)
            client.no_data()
            server.command("PAIR_ACCEPT " + approved_id)
            require(client.line() == "PAIRED1 " + server.credentials["pairing_code"],
                    "Exact private approval did not issue the run token")
            require(client.line() == "", "Pairing connection entered streaming")
            ended(server, approved_id, "approved")
        finally:
            client.close()
        # The approved credentials still use the existing authenticated stream
        # path; the synthetic session cannot advertise remote input or audio.
        stream = Client(server)
        try:
            stream.send("AUTH " + server.credentials["pairing_code"])
            hello = stream.line()
            require(hello.startswith("HELLO "), "Approved token failed authenticated reconnect")
            parameters = json.loads(hello[6:])
            require(parameters["version"] == 5 and parameters["input"] is False and
                    "audio" not in parameters, "Unexpected synthetic stream capability")
            stream.send("BYE")
        finally:
            stream.close()

        server.arm()
        client, identifier = request(server)
        try:
            server.command("PAIR_ACCEPT " + approved_id)
            client.no_data()
            server.command("PAIR_REJECT " + identifier)
            require(client.line() == "ERROR PAIRING", "Exact private reject failed closed")
            ended(server, identifier, "rejected")
        finally:
            client.close()

        server.arm()
        client, identifier = request(server)
        try:
            server.command("PAIR_CLOSE")
            require(client.line() == "ERROR PAIRING", "PAIR_CLOSE did not cancel pending approval")
            ended(server, identifier, "rejected")
        finally:
            client.close()
        closed_request(server)

        server.arm()
        client, identifier = request(server)
        try:
            server.close_control()
            require(client.line() == "ERROR PAIRING", "Control pipe EOF did not cancel approval")
            ended(server, identifier, "rejected")
        finally:
            client.close()
        closed_request(server)
        server.stop()
    finally:
        server.cleanup()


def reject_regular_file():
    server = Server(invalid_control=True)
    try:
        require(server.process.wait(timeout=5) == 2, "Non-pipe control fd was not rejected")
        server.status_thread.join(timeout=2)
        require(any(e.get("state") == "error" and
                    e.get("message") == "Control fd must be a private read-only pipe"
                    for e in server.history), "Missing invalid-control rejection")
        require(not any(e.get("state") == "listening" or e.get("type") in ("stream", "capability")
                        for e in server.history), "Invalid control fd reached capture or networking")
        with socket.socket() as probe:
            probe.settimeout(0.5)
            require(probe.connect_ex(("127.0.0.1", PORT)) != 0,
                    "Invalid-control server left a listener open")
    finally:
        server.cleanup()


exercise_control()
reject_regular_file()
print("PASS: real server private control pipe, PAIR_OPEN, independent TLS comparison, wrong/stale "
      "approval ignored, exact accept, authenticated reconnect, reject, close, pipe EOF, "
      "non-pipe fd rejected before networking. Synthetic software video only; no audio/input/desktop.")
