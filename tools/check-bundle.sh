#!/usr/bin/env bash
# Lot 12 (ADR-027): the App Bundle sent to Google Play keeps the models out of its base module.
#   tools/check-bundle.sh androidApp/build/outputs/bundle/release/androidApp-release.aab
# - the base module stays under Play's 200 MB limit (compressed sizes of its entries, an upper bound of the download);
# - no model file in the base module;
# - the install-time pack `models` holds both model files and the label catalog, at the paths the app reads.
set -euo pipefail
AAB="${1:?usage: $0 <file.aab>}"
LIMIT=$((200 * 1000 * 1000))
status=0

listing=$(unzip -v "$AAB")

base_bytes=$(echo "$listing" | awk '$NF ~ /^base\// { sum += $3 } END { print sum + 0 }')
echo "base module, compressed: $base_bytes bytes (limit $LIMIT)"
if [ "$base_bytes" -ge "$LIMIT" ]; then
  echo "VIOLATION: the base module is over Play's limit"; status=1
fi

if echo "$listing" | awk '{ print $NF }' | grep -E '^base/.*[.]litertlm$' >/dev/null; then
  echo "VIOLATION: a model file is still in the base module"; status=1
fi

for expected in \
  "models/assets/models/embeddinggemma-2-text-270m.litertlm" \
  "models/assets/models/embeddinggemma-2-text-vision-440m.litertlm" \
  "models/assets/models/labels_fr_en.catalog" \
  "models/manifest/AndroidManifest.xml"; do
  if ! echo "$listing" | awk '{ print $NF }' | grep -Fx "$expected" >/dev/null; then
    echo "VIOLATION: missing from the asset pack: $expected"; status=1
  fi
done

# The pack is delivered with the app (install-time) and the base module still asks for no network (protobuf
# manifests: the strings are stored as is).
# Read whole first: `grep -q` stopping early would make `unzip` fail with SIGPIPE under pipefail.
pack_manifest=$(unzip -p "$AAB" models/manifest/AndroidManifest.xml | LC_ALL=C tr -c '[:print:]' ' ')
base_manifest=$(unzip -p "$AAB" base/manifest/AndroidManifest.xml | LC_ALL=C tr -c '[:print:]' ' ')
if ! printf '%s' "$pack_manifest" | grep -F "install-time" >/dev/null; then
  echo "VIOLATION: the models pack is not install-time"; status=1
fi
if printf '%s' "$base_manifest" | grep -F "android.permission.INTERNET" >/dev/null; then
  echo "VIOLATION: the base module asks for INTERNET"; status=1
fi

if [ $status -eq 0 ]; then echo "OK: models in the install-time pack, base module within the limit"; else echo "FAILURE"; fi
exit $status
