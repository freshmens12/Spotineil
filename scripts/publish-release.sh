#!/usr/bin/env bash
# Publishes an APK as a GitHub release of this repository. The app checks the latest release
# when it opens, so a release with a higher version number shows up as an update.
#
# Usage: scripts/publish-release.sh <version> <path-to-apk> ["release notes"]
#   e.g. scripts/publish-release.sh 1.3.0 app/build/outputs/apk/debug/app-debug.apk "New: History"
#
# Uses the GitHub sign-in that git already has stored (no extra token needed).
set -euo pipefail

REPO="${REPO:-freshmens12/Spotineil}"
VERSION="${1:?version, e.g. 1.3.0}"
APK="${2:?path to the APK}"
NOTES="${3:-Spotineil $VERSION}"
TAG="v${VERSION#v}"

[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p')
[ -n "$TOKEN" ] || { echo "No stored GitHub sign-in found. Push to GitHub once with git first." >&2; exit 1; }
auth=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json")

# JSON-escape the notes (backslashes, quotes, newlines).
esc=$(printf '%s' "$NOTES" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk '{printf "%s\\n", $0}')
body=$(printf '{"tag_name":"%s","name":"Spotineil %s","body":"%s","draft":false,"prerelease":false}' "$TAG" "${TAG#v}" "$esc")

out=$(mktemp)
# Sent from a file: on Windows, non-ASCII text (→, …) in a command-line argument gets garbled.
bodyfile=$(mktemp)
printf '%s' "$body" > "$bodyfile"
code=$(curl -s -o "$out" -w '%{http_code}' -X POST "https://api.github.com/repos/$REPO/releases" "${auth[@]}" --data-binary @"$bodyfile")
rm -f "$bodyfile"
if [ "$code" != "201" ]; then
  # Maybe an earlier run created the release but did not finish uploading: continue with it.
  code=$(curl -s -o "$out" -w '%{http_code}' "https://api.github.com/repos/$REPO/releases/tags/$TAG" "${auth[@]}")
  [ "$code" = "200" ] || { echo "Could not create the release (HTTP $code):" >&2; cat "$out" >&2; exit 1; }
  echo "Release $TAG already exists, uploading the APK to it."
fi
id=$(grep -o '"id": *[0-9]*' "$out" | head -1 | grep -o '[0-9]*')

code=$(curl -s -o "$out" -w '%{http_code}' -X POST "https://uploads.github.com/repos/$REPO/releases/$id/assets?name=Spotineil.apk" \
  "${auth[@]}" -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$APK")
[ "$code" = "201" ] || { echo "Could not upload the APK (HTTP $code):" >&2; cat "$out" >&2; exit 1; }

echo "Published $TAG"
echo "Download: https://github.com/$REPO/releases/download/$TAG/Spotineil.apk"
