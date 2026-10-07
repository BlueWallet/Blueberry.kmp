#!/usr/bin/env python3
"""Upload one Android App Bundle to the Play internal testing track."""

import argparse
import json
import sys
import urllib.error
import urllib.request

from google.auth.transport.requests import Request
from google.oauth2 import service_account

SCOPE = "https://www.googleapis.com/auth/androidpublisher"
PUBLISHER = "https://androidpublisher.googleapis.com"


def play_request(token, method, url, data=None, headers=None):
    hdrs = {"Authorization": "Bearer " + token}
    if headers:
        hdrs.update(headers)
    request = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(request) as response:
            body = response.read()
            if not body:
                return {}
            return json.loads(body)
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            message = json.loads(raw)["error"]["message"]
        except (json.JSONDecodeError, KeyError, TypeError):
            message = raw[:500]
        path = url.split("?", 1)[0]
        raise SystemExit(f"Play API {error.code} {method} {path}: {message}") from error


def upload_internal(token, package_name, aab_path, version_name):
    base = f"{PUBLISHER}/androidpublisher/v3/applications/{package_name}"
    edit = play_request(
        token,
        "POST",
        f"{base}/edits",
        data=b"{}",
        headers={"Content-Type": "application/json"},
    )
    edit_id = edit["id"]
    with open(aab_path, "rb") as bundle_file:
        bundle_bytes = bundle_file.read()
    bundle = play_request(
        token,
        "POST",
        f"{PUBLISHER}/upload/androidpublisher/v3/applications/{package_name}/edits/{edit_id}/bundles?uploadType=media",
        data=bundle_bytes,
        headers={"Content-Type": "application/octet-stream"},
    )
    version_code = str(bundle["versionCode"])
    track = {
        "track": "internal",
        "releases": [
            {
                "name": version_name,
                "status": "completed",
                "versionCodes": [version_code],
                "releaseNotes": [
                    {
                        "language": "en-US",
                        "text": "Internal testing build.",
                    }
                ],
            }
        ],
    }
    play_request(
        token,
        "PUT",
        f"{base}/edits/{edit_id}/tracks/internal",
        data=json.dumps(track).encode(),
        headers={"Content-Type": "application/json"},
    )
    play_request(
        token,
        "POST",
        f"{base}/edits/{edit_id}:commit",
        data=b"{}",
        headers={"Content-Type": "application/json"},
    )
    return version_code


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--aab", required=True)
    parser.add_argument("--service-account", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--package", default="io.bluewallet.blueberry")
    args = parser.parse_args()

    credentials = service_account.Credentials.from_service_account_file(
        args.service_account,
        scopes=[SCOPE],
    )
    credentials.refresh(Request())
    version_code = upload_internal(
        credentials.token,
        args.package,
        args.aab,
        args.version_name,
    )
    print(f"Uploaded versionCode {version_code} to internal")


if __name__ == "__main__":
    sys.exit(main())
