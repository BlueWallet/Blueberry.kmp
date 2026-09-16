#!/usr/bin/env python3
"""Print store versions for a UTC instant.

Marketing: YYYY.MM.DD
iOS build (CFBundleVersion): YYYYMMDDHHMM
Android versionCode: YYYYMMDDHH (fits under Play's 2_100_000_000 max)

Usage: scripts/app-version.py [epoch_seconds]
"""

from __future__ import annotations

import sys
from datetime import datetime, timezone

ANDROID_VERSION_CODE_MAX = 2_100_000_000


def versions_at(epoch_seconds: float) -> tuple[str, str, int]:
    utc = datetime.fromtimestamp(epoch_seconds, timezone.utc)
    marketing = utc.strftime("%Y.%m.%d")
    ios_build = utc.strftime("%Y%m%d%H%M")
    android_code = int(utc.strftime("%Y%m%d%H"))
    if android_code > ANDROID_VERSION_CODE_MAX:
        raise ValueError("ANDROID_VERSION_CODE exceeds Play max")
    return marketing, ios_build, android_code


def main(argv: list[str]) -> int:
    if argv[1:] == ["--self-test"]:
        marketing, ios_build, android_code = versions_at(1789566628)
        assert marketing == "2026.09.16", marketing
        assert ios_build == "202609161350", ios_build
        assert android_code == 2026091613, android_code
        print("app-version self-test ok")
        return 0
    epoch = float(argv[1]) if len(argv) > 1 else datetime.now(timezone.utc).timestamp()
    marketing, ios_build, android_code = versions_at(epoch)
    print(f"MARKETING_VERSION={marketing}")
    print(f"CURRENT_PROJECT_VERSION={ios_build}")
    print(f"ANDROID_VERSION_CODE={android_code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
