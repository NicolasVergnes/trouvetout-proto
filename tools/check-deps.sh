#!/usr/bin/env bash
# Vérifie qu'aucune dépendance interdite n'est référencée et que les permissions Android/iOS restent minimales.
# Usage : tools/check-deps.sh   (exit 1 en cas de violation)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FORBIDDEN="$ROOT/.claude/hooks/forbidden-deps.txt"
status=0

echo "== Dépendances interdites =="
# Le projet XcodeGen (project.yml) porte les paquets SPM : il est scanné comme un fichier de build.
BUILD_FILES=$(find "$ROOT" -type f \( -name "*.gradle" -o -name "*.gradle.kts" -o -name "libs.versions.toml" -o -name "Package.swift" -o -name "Podfile" -o -name "Package.resolved" -o -name "project.yml" -o -name "*.xcconfig" -o -name "*.entitlements" \) -not -path "*/build/*" -not -path "*/.gradle/*" -not -path "*/.git/*" 2>/dev/null || true)
PATTERNS="$(grep -vE '^(#|$)' "$FORBIDDEN" | tr '[:upper:]' '[:lower:]')"
for f in $BUILD_FILES; do
  MATCHES="$(tr '[:upper:]' '[:lower:]' < "$f" | grep -Fof <(printf '%s\n' "$PATTERNS") | sort -u || true)"
  if [ -n "$MATCHES" ]; then
    for pat in $MATCHES; do echo "VIOLATION: '$pat' dans $f"; done
    status=1
  fi
done

echo "== Permissions Android =="
ALLOWED_PERMS="android.permission.RECORD_AUDIO|android.permission.CAMERA|android.permission.POST_NOTIFICATIONS|android.permission.RECEIVE_BOOT_COMPLETED|android.permission.VIBRATE|android.permission.USE_BIOMETRIC|com.android.vending.BILLING|android.permission.SCHEDULE_EXACT_ALARM|android.permission.USE_EXACT_ALARM"
for m in $(find "$ROOT/androidApp" -name AndroidManifest.xml -not -path "*/build/*" 2>/dev/null || true); do
  # Boucle dans le shell courant (substitution de processus) pour que status=1 soit conservé.
  while read -r p; do
    [ -z "$p" ] && continue
    if ! echo "$p" | grep -Eq "^($ALLOWED_PERMS)$"; then
      if [ "$p" = "android.permission.INTERNET" ] && [ -f "$ROOT/docs/adr-permissions.ok" ]; then
        echo "INFO: INTERNET autorisé par ADR"
      else
        echo "VIOLATION: permission non prévue '$p' dans $m"; status=1
      fi
    fi
  done < <({ grep -o 'android:name="[^"]*permission[^"]*"' "$m" || true; } | sed 's/android:name="//;s/"//')
  grep -Eq 'allowBackup\s*=\s*["'"'"']true' "$m" && { echo "VIOLATION: allowBackup=true dans $m"; status=1; }
  grep -Eq 'usesCleartextTraffic\s*=\s*["'"'"']true' "$m" && { echo "VIOLATION: usesCleartextTraffic=true dans $m"; status=1; }
done

echo "== Info.plist / project.yml / entitlements =="
for p in $(find "$ROOT/iosApp" \( -name Info.plist -o -name project.yml -o -name "*.xcconfig" -o -name "*.entitlements" \) -not -path "*/build/*" -not -path "*.xcodeproj/*" 2>/dev/null || true); do
  grep -Eq 'NSAllowsArbitraryLoads|NSExceptionAllowsInsecureHTTPLoads' "$p" && { echo "VIOLATION: ATS assoupli dans $p"; status=1; }
  grep -Eq 'NSLocation|NSContactsUsageDescription|NSCalendarsUsageDescription|INFOPLIST_KEY_NS(Location|Contacts|Calendars)' "$p" && [ ! -f "$ROOT/docs/adr-permissions.ok" ] && { echo "VIOLATION: permission non prévue dans $p"; status=1; }
done

echo "== Travail en arrière-plan (N6) =="
# Aucun travail en arrière-plan en v1 : ni WorkManager, ni JobScheduler, ni service au premier plan, ni BGTask.
# Les rappels du lot 9 passeront par AlarmManager inexact, permis.
BACKGROUND='androidx\.work|WorkManager|JobScheduler|JobService|foregroundServiceType|FOREGROUND_SERVICE|startForegroundService|BGTaskScheduler|UIBackgroundModes'
if grep -rEn "$BACKGROUND" "$ROOT/androidApp/src/main" "$ROOT/shared/src" "$ROOT/iosApp/iosApp" "$ROOT/iosApp/CaptureWidget" "$ROOT/iosApp/project.yml" "$ROOT/gradle/libs.versions.toml" 2>/dev/null; then
  echo "VIOLATION: travail en arrière-plan (N6)"; status=1
fi

echo "== Manifeste fusionné et APK de la release (si construits) =="
# Une bibliothèque peut ajouter une permission au manifeste fusionné : on vérifie celui de la release.
MERGED=$(find "$ROOT/androidApp/build/intermediates" -path "*release*" -name AndroidManifest.xml -path "*merged_manifest*" 2>/dev/null | head -1 || true)
if [ -n "$MERGED" ]; then
  while read -r p; do
    [ -z "$p" ] && continue
    if ! echo "$p" | grep -Eq "^($ALLOWED_PERMS)$"; then
      # Permission propre à l'app ajoutée par androidx.core pour les récepteurs non exportés (API 33+).
      case "$p" in *.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) continue ;; esac
      echo "VIOLATION: permission '$p' dans le manifeste fusionné de la release"; status=1
    fi
  done < <({ grep -o '<uses-permission[^>]*android:name="[^"]*"' "$MERGED" || true; } | sed 's/.*android:name="//;s/"//')
else
  echo "INFO: manifeste fusionné de la release absent (assembleRelease non lancé)"
fi
APK=$(find "$ROOT/androidApp/build/outputs/apk/release" -name "*.apk" 2>/dev/null | head -1 || true)
if [ -n "$APK" ]; then
  # Repli 32 bits exclu (ADR-021) : seules arm64-v8a et x86_64 embarquent du code natif.
  ABIS=$(unzip -Z1 "$APK" 'lib/*' 2>/dev/null | cut -d/ -f2 | sort -u || true)
  for abi in $ABIS; do
    case "$abi" in arm64-v8a|x86_64) ;; *) echo "VIOLATION: ABI '$abi' dans l'APK de la release"; status=1 ;; esac
  done
else
  echo "INFO: APK de la release absent"
fi

echo "== Poids de modèles dans le dépôt =="
if git -C "$ROOT" ls-files 2>/dev/null | grep -Eq '\.(tflite|litertlm|task|gguf|onnx|mlmodel|mlpackage|safetensors|bin)(/|$)'; then
  echo "VIOLATION: des poids de modèles sont versionnés"; status=1
fi

[ $status -eq 0 ] && echo "OK : aucune violation." || echo "ÉCHEC : voir les violations ci-dessus."
exit $status
