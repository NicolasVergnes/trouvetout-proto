#!/usr/bin/env bash
# Aucune donnée métier dans les logs (CLAUDE.md, règle 3 ; docs/02 §4) : les sources de l'app et du noyau
# n'appellent aucune API de journalisation directement. Seul LogcatLogger (Android, debug uniquement) écrit dans
# logcat, et il ne reçoit que des identifiants et des noms de classes par le Logger injecté.
# Usage : tools/check-logs.sh   (exit 1 en cas de violation)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
status=0

PATTERN='Log\.(d|i|v|w|e|wtf)\(|println\(|(^|[^A-Za-z0-9_.])print\(|debugPrint\(|NSLog\(|os_log\(|Logger\(subsystem|printStackTrace\('
ALLOWED='shared/src/androidMain/kotlin/app/trouvetout/core/log/LogcatLogger.kt'

echo "== Appels de journalisation dans les sources de l'app =="
while IFS= read -r hit; do
  file="${hit%%:*}"
  case "$file" in "$ALLOWED") continue ;; esac
  echo "VIOLATION: $hit"
  status=1
done < <(cd "$ROOT" && grep -rnE "$PATTERN" \
  androidApp/src/main shared/src/commonMain shared/src/androidMain shared/src/iosMain shared/src/jvmCommonMain \
  iosApp/iosApp iosApp/CaptureWidget 2>/dev/null || true)

echo "== Logger injecté : désactivé en release =="
if ! grep -q 'if (BuildConfig.DEBUG) LogcatLogger else NoopLogger' "$ROOT/androidApp/src/main/java/app/trouvetout/android/App.kt"; then
  echo "VIOLATION: le logger Android doit être NoopLogger hors debug"; status=1
fi
if ! grep -q 'logger: NoopLogger.shared' "$ROOT/iosApp/iosApp/App.swift"; then
  echo "VIOLATION: le logger iOS doit être NoopLogger"; status=1
fi

[ $status -eq 0 ] && echo "OK : aucun log hors du Logger injecté." || echo "ÉCHEC : voir ci-dessus."
exit $status
