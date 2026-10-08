#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: release-version.sh vYYYY.MM.DD HH" >&2
  exit 1
fi

tag="$1"
hour="$2"

if [[ ! "$tag" =~ ^v([0-9]{4})\.([0-9]{2})\.([0-9]{2})$ ]]; then
  echo "tag must match vYYYY.MM.DD, got: ${tag}" >&2
  exit 1
fi

year="${BASH_REMATCH[1]}"
month="${BASH_REMATCH[2]}"
day="${BASH_REMATCH[3]}"

if [[ ! "$hour" =~ ^[0-9]{2}$ ]] || (( 10#$hour > 23 )); then
  echo "hour must be 00-23, got: ${hour}" >&2
  exit 1
fi

year_short="$((10#$year - 2000))"
month_short="$((10#$month))"
day_short="$((10#$day))"

printf 'VERSION_NAME=%s.%s.%s\n' "$year" "$month" "$day"
printf 'VERSION_CODE=%s%s%s%s\n' "$year" "$month" "$day" "$hour"
printf 'PACKAGE_VERSION=%s.%s.%s\n' "$year_short" "$month_short" "$day_short"
printf 'FILE_DATE=%s-%s-%s\n' "$year" "$month" "$day"
