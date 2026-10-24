#!/usr/bin/env bash
# Vérifie qu'aucune dépendance interdite n'est référencée et que les permissions Android restent minimales.
# Usage : tools/check-deps.sh   (exit 1 en cas de violation)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FORBIDDEN="$ROOT/.claude/hooks/forbidden-deps.txt"
status=0

echo "== Dépendances interdites =="
BUILD_FILES=$(find "$ROOT" -type f \( -name "*.gradle" -o -name "*.gradle.kts" -o -name "libs.versions.toml" -o -name "Package.swift" -o -name "Podfile" -o -name "Package.resolved" \) -not -path "*/build/*" -not -path "*/.gradle/*" 2>/dev/null || true)
while IFS= read -r pat; do
  [ -z "$pat" ] && continue
  case "$pat" in \#*) continue ;; esac
  for f in $BUILD_FILES; do
    if tr '[:upper:]' '[:lower:]' < "$f" | grep -Fq -- "$(printf '%s' "$pat" | tr '[:upper:]' '[:lower:]')"; then
      echo "VIOLATION: '$pat' dans $f"; status=1
    fi
  done
done < "$FORBIDDEN"

echo "== Permissions Android =="
ALLOWED_PERMS="android.permission.RECORD_AUDIO|android.permission.CAMERA|android.permission.POST_NOTIFICATIONS|android.permission.RECEIVE_BOOT_COMPLETED|android.permission.VIBRATE|android.permission.USE_BIOMETRIC|com.android.vending.BILLING|android.permission.SCHEDULE_EXACT_ALARM|android.permission.USE_EXACT_ALARM"
for m in $(find "$ROOT/androidApp" -name AndroidManifest.xml -not -path "*/build/*" 2>/dev/null || true); do
  { grep -o 'android:name="[^"]*permission[^"]*"' "$m" || true; } | sed 's/android:name="//;s/"//' | while read -r p; do
    if ! echo "$p" | grep -Eq "^($ALLOWED_PERMS)$"; then
      if [ "$p" = "android.permission.INTERNET" ] && [ -f "$ROOT/docs/adr-permissions.ok" ]; then
        echo "INFO: INTERNET autorisé par ADR"
      else
        echo "VIOLATION: permission non prévue '$p' dans $m"; status=1
      fi
    fi
  done
  grep -q 'allowBackup="true"' "$m" && { echo "VIOLATION: allowBackup=true dans $m"; status=1; }
  grep -q 'usesCleartextTraffic="true"' "$m" && { echo "VIOLATION: usesCleartextTraffic=true dans $m"; status=1; }
done

echo "== Info.plist =="
for p in $(find "$ROOT/iosApp" -name Info.plist -not -path "*/build/*" 2>/dev/null || true); do
  grep -q 'NSAllowsArbitraryLoads' "$p" && { echo "VIOLATION: NSAllowsArbitraryLoads dans $p"; status=1; }
  grep -Eq 'NSLocation|NSContactsUsageDescription|NSCalendarsUsageDescription' "$p" && [ ! -f "$ROOT/docs/adr-permissions.ok" ] && { echo "VIOLATION: permission non prévue dans $p"; status=1; }
done

echo "== Poids de modèles dans le dépôt =="
if git -C "$ROOT" ls-files 2>/dev/null | grep -Eq '\.(tflite|litertlm|task|gguf|onnx|mlmodel|safetensors)$'; then
  echo "VIOLATION: des poids de modèles sont versionnés"; status=1
fi

[ $status -eq 0 ] && echo "OK : aucune violation." || echo "ÉCHEC : voir les violations ci-dessus."
exit $status
