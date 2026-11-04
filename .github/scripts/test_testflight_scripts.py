#!/usr/bin/env python3
"""Tests for the TestFlight "What to Test" scripts. Standard library + openssl.

Runs against a local fake App Store Connect server with a throwaway key made
per run, so no real key or network is involved.
Run: python3 -B .github/scripts/test_testflight_scripts.py
"""
import base64
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "testflight_what_to_test.py")
NOTES_SH = os.path.join(HERE, "testflight_notes.sh")

spec = importlib.util.spec_from_file_location("wtt", SCRIPT)
wtt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wtt)

BUNDLE = "io.bluewallet.blueberry"


def b64url_decode(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def raw_to_der(raw):
    def integer(v):
        v = v.lstrip(b"\x00") or b"\x00"
        if v[0] & 0x80:
            v = b"\x00" + v
        return b"\x02" + bytes([len(v)]) + v
    body = integer(raw[:32]) + integer(raw[32:])
    return b"\x30" + bytes([len(body)]) + body


class FakeAsc:
    """Scriptable fake of the few App Store Connect endpoints used."""

    def __init__(self):
        self.reset()
        handler = self._handler()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def reset(self, **kw):
        self.log, self.writes, self.locs = [], [], []
        self.app_fail, self.post_fail, self.builds_fail = [], [], []
        self.builds = [{"id": "B1", "attributes": {"processingState": "PROCESSING"}}]
        for k, v in kw.items():
            setattr(self, k, v)

    def _handler(self):
        fake = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def send(self, code, obj=None):
                b = json.dumps(obj).encode() if obj is not None else b""
                self.send_response(code)
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def handle_any(self, m):
                p = self.path.split("?")[0]
                fake.log.append(f"{m} {p}")
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n)) if n else None
                if not self.headers.get("Authorization", "").startswith("Bearer "):
                    return self.send(401, {})
                if p == "/v1/apps":
                    if fake.app_fail:
                        return self.send(fake.app_fail.pop(0), {"errors": []})
                    return self.send(200, {"data": [
                        {"id": "A2", "attributes": {"bundleId": BUNDLE + ".x"}},
                        {"id": "A1", "attributes": {"bundleId": BUNDLE}}]})
                if p == "/v1/builds":
                    if fake.builds_fail:
                        return self.send(fake.builds_fail.pop(0), {"errors": []})
                    return self.send(200, {"data": fake.builds})
                if p == "/v1/builds/B1/betaBuildLocalizations":
                    return self.send(200, {"data": fake.locs})
                if m == "POST" and p == "/v1/betaBuildLocalizations":
                    if fake.post_fail:
                        code = fake.post_fail.pop(0)
                        if code == 409:  # en-US appeared meanwhile.
                            fake.locs.append({"id": "L9", "attributes": {"locale": "en-US"}})
                        return self.send(code, {"errors": []})
                    fake.writes.append(("POST", body["data"]["attributes"]["whatsNew"]))
                    return self.send(201, {"data": {}})
                if m == "PATCH" and p.startswith("/v1/betaBuildLocalizations/"):
                    fake.writes.append(("PATCH", p.rsplit("/", 1)[1],
                                        body["data"]["attributes"]["whatsNew"]))
                    return self.send(200, {"data": {}})
                return self.send(404, {})

            def do_GET(self):
                self.handle_any("GET")

            def do_POST(self):
                self.handle_any("POST")

            def do_PATCH(self):
                self.handle_any("PATCH")

        return H


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.key = os.path.join(cls.tmp.name, "key.p8")
        cls.pub = os.path.join(cls.tmp.name, "pub.pem")
        subprocess.run(["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt",
                        "ec_paramgen_curve:P-256", "-out", cls.key], check=True,
                       capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", cls.key, "-pubout", "-out", cls.pub],
                       check=True, capture_output=True)
        cls.notes = os.path.join(cls.tmp.name, "notes.txt")
        cls.fake = FakeAsc()

    @classmethod
    def tearDownClass(cls):
        cls.fake.server.shutdown()
        cls.fake.server.server_close()
        cls.tmp.cleanup()

    def env(self, notes="Build commit abc1234\n\n- feat: x", **extra):
        with open(self.notes, "w", encoding="utf-8") as f:
            f.write(notes)
        return dict(ASC_KEY_ID="K", ASC_ISSUER_ID="I", ASC_KEY_PATH=self.key,
                    BUNDLE_ID=BUNDLE, BUILD_NUMBER="202611040900",
                    NOTES_FILE=self.notes, POLL_SECONDS="0", **extra)

    def run_main(self, env):
        old = (wtt.API, wtt.time.sleep, dict(os.environ))
        wtt.API, wtt.time.sleep = self.fake.url, (lambda s: None)
        os.environ.update(env)
        try:
            wtt.main()
        finally:
            wtt.API, wtt.time.sleep = old[0], old[1]
            os.environ.clear()
            os.environ.update(old[2])

    def run_cli(self, env):
        """Runs the script as CI does and returns its exit code."""
        with open(SCRIPT, encoding="utf-8") as f:
            src = f.read()
        src = src.replace(
            'API = "https://api.appstoreconnect.apple.com"', f'API = "{self.fake.url}"')
        patched = os.path.join(self.tmp.name, "patched.py")
        with open(patched, "w", encoding="utf-8") as f:
            f.write(src)
        return subprocess.run([sys.executable, "-B", patched], capture_output=True,
                              env=dict(os.environ, **env)).returncode


class PureFunctions(Base):
    def test_der_to_raw_round_trips(self):
        for r, s in ((b"\x01" * 32, b"\x02" * 32), (b"\x80" + b"\x00" * 31, b"\x00" * 31 + b"\x05")):
            self.assertEqual(wtt.der_to_raw(raw_to_der(r + s)), r + s)

    def test_der_to_raw_rejects_garbage(self):
        with self.assertRaises(ValueError):
            wtt.der_to_raw(b"\x31\x00")

    def test_jwt_claims_and_signature_verify(self):
        token = wtt.make_jwt("KID", "ISS", self.key, now=1000)
        head, payload, sig = token.split(".")
        self.assertEqual(json.loads(b64url_decode(head)), {"alg": "ES256", "kid": "KID", "typ": "JWT"})
        self.assertEqual(json.loads(b64url_decode(payload)),
                         {"iss": "ISS", "iat": 1000, "exp": 1900, "aud": "appstoreconnect-v1"})
        raw = b64url_decode(sig)
        self.assertEqual(len(raw), 64)
        sig_path = os.path.join(self.tmp.name, "sig.der")
        with open(sig_path, "wb") as f:
            f.write(raw_to_der(raw))
        ok = subprocess.run(["openssl", "dgst", "-sha256", "-verify", self.pub, "-signature", sig_path],
                            input=f"{head}.{payload}".encode(), capture_output=True)
        self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)

    def test_jwt_bad_key_reports_openssl_error(self):
        bogus = os.path.join(self.tmp.name, "bogus.p8")
        with open(bogus, "w") as f:
            f.write("not a key\n")
        with self.assertRaises(RuntimeError) as cm:
            wtt.make_jwt("K", "I", bogus)
        self.assertIn("openssl could not sign", str(cm.exception))

    def test_truncate_utf8_counts_bytes(self):
        self.assertEqual(wtt.truncate_utf8("abc", 10), "abc")
        out = wtt.truncate_utf8("\u00e9" * 3000)  # 6000 bytes.
        self.assertLessEqual(len(out.encode()), 4000)
        self.assertTrue(out.endswith("..."))

    def test_sanitize(self):
        cases = {
            "feat: \u2728 sparkle": "feat: sparkle",
            "fix: A < B": "fix: A B",
            "- \U0001F468\u200D\U0001F4BB dev": "- dev",
            "love \u2764\uFE0F": "love",
            "time \u23F1\uFE0F": "time",
            "key 1\uFE0F\u20E3 x": "key x",
            "\u65e5\u672c caf\u00e9 #1 v2.0": "\u65e5\u672c caf\u00e9 #1 v2.0",
        }
        for given, want in cases.items():
            self.assertEqual(wtt.sanitize(given), want, given)


class ApiFlow(Base):
    def test_transient_app_lookup_retried_then_created(self):
        self.fake.reset(app_fail=[503, 429])
        self.run_main(self.env())
        self.assertEqual(self.fake.log.count("GET /v1/apps"), 3)
        self.assertEqual(self.fake.writes, [("POST", "Build commit abc1234\n\n- feat: x")])

    def test_permission_error_fails_fast(self):
        self.fake.reset(app_fail=[403])
        with self.assertRaises(wtt.ApiError) as cm:
            self.run_main(self.env())
        self.assertEqual(cm.exception.status, 403)
        self.assertEqual(self.fake.log, ["GET /v1/apps"])

    def test_post_409_rereads_and_patches(self):
        self.fake.reset(post_fail=[409])
        self.run_main(self.env())
        self.assertEqual(self.fake.writes, [("PATCH", "L9", "Build commit abc1234\n\n- feat: x")])

    def test_existing_en_us_is_patched(self):
        self.fake.reset(locs=[{"id": "L1", "attributes": {"locale": "de-DE"}},
                              {"id": "L2", "attributes": {"locale": "en-US"}}])
        self.run_main(self.env())
        self.assertEqual(self.fake.writes, [("PATCH", "L2", "Build commit abc1234\n\n- feat: x")])

    def test_uploaded_notes_are_sanitized(self):
        self.fake.reset()
        self.run_main(self.env(notes="Build commit abc1234\n\n- feat: \U0001F680 a < b\n"))
        self.assertEqual(self.fake.writes, [("POST", "Build commit abc1234\n\n- feat: a b")])

    def test_exit_codes(self):
        self.fake.reset(builds=[])
        self.assertEqual(self.run_cli(self.env(TIMEOUT_SECONDS="0")), 2, "not listed yet")
        self.fake.reset(builds_fail=[503] * 5)
        self.assertEqual(self.run_cli(self.env(TIMEOUT_SECONDS="0")), 1, "outage at deadline")
        self.fake.reset(app_fail=[403])
        self.assertEqual(self.run_cli(self.env()), 1, "permission error")
        self.fake.reset()
        self.assertEqual(self.run_cli(self.env()), 0, "success")


class NotesScript(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        self.git("init", "-q", "-b", "master")
        self.shas = []
        for i in range(12):
            self.git("commit", "-q", "--allow-empty", "-m", f"commit {i}")
            self.shas.append(self.git("rev-parse", "HEAD"))

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t",
                   GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@t")
        return subprocess.run(["git", "-C", self.repo, *args], check=True, env=env,
                              capture_output=True, text=True).stdout.strip()

    def notes(self, before):
        return subprocess.run(["bash", NOTES_SH, before, self.shas[-1]], cwd=self.repo, check=True,
                              capture_output=True, text=True).stdout

    def test_range(self):
        out = self.notes(self.shas[9])
        self.assertEqual(out, f"Build commit {self.shas[-1][:7]}\n\n- commit 11\n- commit 10\n")

    def test_fallbacks_list_latest_ten(self):
        for before in ("", "0" * 40, "deadbeef" * 5):
            lines = self.notes(before).splitlines()
            self.assertEqual(len([l for l in lines if l.startswith("- ")]), 10, before)

    def test_empty_range_uses_head_subject(self):
        self.assertTrue(self.notes(self.shas[-1]).endswith("- commit 11\n"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
