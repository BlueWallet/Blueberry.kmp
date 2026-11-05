#!/usr/bin/env python3
"""Sets the TestFlight "What to Test" text on an uploaded build.

Uses the App Store Connect API with a JWT signed by openssl (ES256), so it
needs no third-party Python packages. Never prints the key or the token.

The build is matched by its build number (CFBundleVersion) alone: CI makes it
a UTC timestamp, so it is unique per app. The marketing version is not used
because Apple strips leading zeros from it (2026.11.04 is stored as 2026.11.4).

Transient errors (5xx, 429, network) are retried a few times with backoff;
other 4xx fail fast. The notes write is an upsert retried as a unit: each try
re-reads the build's localizations, so a retry after an uncertain POST (or a
409 because en-US appeared meanwhile) updates instead of duplicating.

Notes are cleaned before upload: App Store Connect rejects emoji and "<" in
whatsNew (fastlane pilot strips them too), so they are removed first.

Exit codes: 0 notes set, 2 the build was not listed before the timeout (Apple
can be slow, so CI only warns), 1 anything else (bad key or role, rejected
notes, missing app: CI fails the job so it gets noticed).

Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, BUNDLE_ID, BUILD_NUMBER,
     NOTES_FILE, TIMEOUT_SECONDS (default 2700), POLL_SECONDS (default 30).
"""
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.appstoreconnect.apple.com"
MAX_NOTES_BYTES = 4000  # Apple's limit for whatsNew, counted in UTF-8 bytes.


class BuildNotListed(RuntimeError):
    pass


class ApiError(RuntimeError):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


# BMP code points with the Unicode Emoji property (emoji-data.txt, Unicode 16),
# minus "#", "*", the digits and (c) (R) TM, which are normal text; those go only
# when written in emoji style, followed by U+FE0F (see sanitize). Ordinary
# symbols such as the command key or a ballot box are kept.
_BMP_EMOJI_RANGES = (
    "203C 2049 2139 2194-2199 21A9-21AA 231A-231B 2328 23CF 23E9-23F3 23F8-23FA "
    "24C2 25AA-25AB 25B6 25C0 25FB-25FE 2600-2604 260E 2611 2614-2615 2618 261D "
    "2620 2622-2623 2626 262A 262E-262F 2638-263A 2640 2642 2648-2653 265F-2660 "
    "2663 2665-2666 2668 267B 267E-267F 2692-2697 2699 269B-269C 26A0-26A1 26A7 "
    "26AA-26AB 26B0-26B1 26BD-26BE 26C4-26C5 26C8 26CE-26CF 26D1 26D3-26D4 "
    "26E9-26EA 26F0-26F5 26F7-26FA 26FD 2702 2705 2708-270D 270F 2712 2714 2716 "
    "271D 2721 2728 2733-2734 2744 2747 274C 274E 2753-2755 2757 2763-2764 "
    "2795-2797 27A1 27B0 27BF 2934-2935 2B05-2B07 2B1B-2B1C 2B50 2B55 3030 303D "
    "3297 3299 "
    "200D 20E3 FE0E FE0F"  # Joiner, keycap and variation selectors.
)


def _parse_ranges(spec):
    out = set()
    for part in spec.split():
        lo, _, hi = part.partition("-")
        out.update(range(int(lo, 16), int(hi or lo, 16) + 1))
    return frozenset(out)


_BMP_EMOJI = _parse_ranges(_BMP_EMOJI_RANGES)


def _is_emoji(ch):
    cp = ord(ch)
    return (0x1F000 <= cp <= 0x1FAFF  # Emoji, flags, skin tones, emoji symbols.
            or 0xE0020 <= cp <= 0xE007F  # Tag characters of subdivision flags.
            or cp in _BMP_EMOJI)


def sanitize(text):
    """Removes what App Store Connect rejects in whatsNew: emoji and "<"."""
    # A character followed by U+FE0F is shown as an emoji (e.g. a keycap
    # digit or a heart), so it goes too, along with the selector.
    cleaned = "".join(ch for i, ch in enumerate(text)
                      if ch != "<" and not _is_emoji(ch)
                      and text[i + 1:i + 2] != "\uFE0F")
    # Drop spaces left behind, e.g. "feat: \u2728 x" -> "feat: x".
    return "\n".join(re.sub(r" {2,}", " ", line).rstrip() for line in cleaned.split("\n"))


def truncate_utf8(text, limit=MAX_NOTES_BYTES):
    data = text.encode()
    if len(data) <= limit:
        return text
    return data[:limit - 3].decode(errors="ignore") + "..."


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(der):
    """Converts a DER ECDSA signature to the raw 64-byte r||s JWS form."""
    if der[0] != 0x30:
        raise ValueError("not a DER sequence")
    i = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    out = b""
    for _ in range(2):
        if der[i] != 0x02:
            raise ValueError("not a DER integer")
        n = der[i + 1]
        v = der[i + 2:i + 2 + n].lstrip(b"\x00")
        if len(v) > 32:
            raise ValueError("integer too long")
        out += v.rjust(32, b"\x00")
        i += 2 + n
    return out


def make_jwt(key_id, issuer_id, key_path, now=None):
    now = int(now if now is not None else time.time())
    header = {"alg": "ES256", "kid": key_id, "typ": "JWT"}
    payload = {"iss": issuer_id, "iat": now, "exp": now + 15 * 60,
               "aud": "appstoreconnect-v1"}
    signing_input = (b64url(json.dumps(header, separators=(",", ":")).encode())
                     + "." + b64url(json.dumps(payload, separators=(",", ":")).encode()))
    proc = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_path],
                          input=signing_input.encode(), capture_output=True)
    if proc.returncode != 0:
        err = proc.stderr.decode(errors="replace").strip()[:1000]
        raise RuntimeError(f"openssl could not sign the token with {key_path} "
                           f"(exit {proc.returncode}): {err}")
    der = proc.stdout
    return signing_input + "." + b64url(der_to_raw(der))


def is_transient(e):
    if isinstance(e, ApiError):
        return e.status >= 500 or e.status == 429
    return isinstance(e, (urllib.error.URLError, TimeoutError, ConnectionError))


def with_retries(what, fn, retryable=is_transient, attempts=5):
    """Calls fn, retrying retryable errors with 2, 4, 8, 16s backoff."""
    for attempt in range(1, attempts + 1):
        try:
            return fn()
        except Exception as e:  # noqa: BLE001 - filtered by retryable
            if attempt == attempts or not retryable(e):
                raise
            delay = 2 ** attempt
            print(f"{what} failed (try {attempt}/{attempts}), retrying in {delay}s: {e}")
            time.sleep(delay)


def upsert_notes(client, build_id, notes):
    locs = client.request("GET", f"/v1/builds/{build_id}/betaBuildLocalizations",
                          {"limit": "50"})["data"]
    en = next((l for l in locs if l["attributes"].get("locale") == "en-US"), None)
    if en:
        client.request("PATCH", f"/v1/betaBuildLocalizations/{en['id']}", body={"data": {
            "type": "betaBuildLocalizations", "id": en["id"],
            "attributes": {"whatsNew": notes}}})
        print("Updated en-US What to Test")
    else:
        client.request("POST", "/v1/betaBuildLocalizations", body={"data": {
            "type": "betaBuildLocalizations",
            "attributes": {"locale": "en-US", "whatsNew": notes},
            "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}})
        print("Created en-US What to Test")


class Client:
    def __init__(self, key_id, issuer_id, key_path):
        self.args = (key_id, issuer_id, key_path)
        self.token, self.made = None, 0

    def request(self, method, path, params=None, body=None):
        if self.token is None or time.time() - self.made > 10 * 60:
            self.token, self.made = make_jwt(*self.args), time.time()
        url = API + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:2000]
            raise ApiError(e.code, f"{method} {path} -> HTTP {e.code}: {detail}") from None


def main():
    env = os.environ
    client = Client(env["ASC_KEY_ID"], env["ASC_ISSUER_ID"], env["ASC_KEY_PATH"])
    bundle_id, build_number = env["BUNDLE_ID"].strip(), env["BUILD_NUMBER"].strip()
    # An empty value would drop the filter and could match some other build.
    if not bundle_id or not build_number:
        raise RuntimeError("BUNDLE_ID and BUILD_NUMBER must not be empty")
    with open(env["NOTES_FILE"], encoding="utf-8") as f:
        notes = truncate_utf8(sanitize(f.read()).strip())
    timeout = int(env.get("TIMEOUT_SECONDS", "2700"))
    poll = int(env.get("POLL_SECONDS", "30"))

    # filter[bundleId] is not an exact match, so pick the exact one.
    apps = with_retries("App lookup", lambda: client.request(
        "GET", "/v1/apps", {"filter[bundleId]": bundle_id})["data"])
    app_id = next((a["id"] for a in apps if a["attributes"].get("bundleId") == bundle_id), None)
    if not app_id:
        raise RuntimeError(f"no app with bundle id {bundle_id}")
    print(f"App id {app_id}")

    # Ask for every state explicitly so a build that is still processing is listed.
    params = {"filter[app]": app_id, "filter[version]": build_number,
              "filter[processingState]": "PROCESSING,FAILED,INVALID,VALID", "limit": "1"}
    deadline = time.time() + timeout
    while True:
        last_error = None
        try:
            builds = client.request("GET", "/v1/builds", params)["data"]
        except Exception as e:  # noqa: BLE001 - filtered by is_transient
            if not is_transient(e):
                raise  # Auth, permission or bad request: retrying will not help.
            print(f"Build lookup failed, retrying: {e}")
            builds, last_error = [], e
        if builds:
            break
        if time.time() > deadline:
            # Only a real "Apple has not listed it yet" is soft; an API that is
            # still failing at the deadline is an outage and fails the job.
            if last_error is not None:
                raise RuntimeError(f"build lookup still failing after {timeout}s: {last_error}")
            raise BuildNotListed(f"build {build_number} not listed after {timeout}s")
        print(f"Build {build_number} not visible yet, waiting {poll}s")
        time.sleep(poll)
    build = builds[0]
    # Never write notes to a build other than the one just uploaded.
    if build["attributes"].get("version") != build_number:
        raise RuntimeError(f"lookup for build {build_number} returned build "
                           f"{build['attributes'].get('version')!r}, not writing notes")
    print(f"Build id {build['id']}, state {build['attributes'].get('processingState')}")

    # A 409 means en-US already exists: the next try re-reads it and PATCHes.
    with_retries("Setting notes", lambda: upsert_notes(client, build["id"], notes),
                 retryable=lambda e: is_transient(e)
                 or (isinstance(e, ApiError) and e.status == 409))


if __name__ == "__main__":
    try:
        main()
    except BuildNotListed as e:
        print(f"Setting What to Test timed out: {e}", file=sys.stderr)
        sys.exit(2)
    except Exception as e:  # noqa: BLE001 - reported as a CI failure below
        print(f"Setting What to Test failed: {e}", file=sys.stderr)
        sys.exit(1)
