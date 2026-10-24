#!/usr/bin/env bash
# Vérifie « zéro réseau en usage normal ».
# Partie statique (toujours) : recherche de clients HTTP hors des emplacements autorisés.
# Partie dynamique (optionnelle, --android | --ios) : à compléter au lot 0/5 avec un proxy bloquant.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
status=0

echo "== Analyse statique des accès réseau =="
ALLOWED_PATHS='shared/src/commonMain/kotlin/.*/ml/ModelPackDownloader|androidApp/src/main/java/.*/billing/|iosApp/.*/Billing/|iosApp/.*/Platform/ModelPackDownloader'
MATCHES=$(grep -rnE "HttpClient|URLSession|OkHttpClient|io\.ktor\.client|URLRequest\(|HttpURLConnection|NSURLSession|Socket\(|WebSocket|java\.net\.URL|openConnection\(|NWConnection|import Network|WebView|WKWebView" \
  "$ROOT/shared" "$ROOT/androidApp" "$ROOT/iosApp" 2>/dev/null \
  --include='*.kt' --include='*.swift' | grep -vE "$ALLOWED_PATHS" | grep -vE "/build/|Test\.kt|Tests\.swift|/commonTest/|/androidHostTest/|/androidDeviceTest/|/iosTest/|Tests/" || true)
if [ -n "$MATCHES" ]; then
  echo "VIOLATION : accès réseau hors emplacements autorisés :"; echo "$MATCHES"; status=1
else
  echo "OK : aucun client réseau hors ModelPackDownloader / Billing."
fi

echo "== Manifeste / plist =="
if grep -rq 'android.permission.INTERNET' "$ROOT/androidApp" --include=AndroidManifest.xml 2>/dev/null && [ ! -f "$ROOT/docs/adr-permissions.ok" ]; then
  echo "VIOLATION : permission INTERNET sans ADR"; status=1
fi

case "${1:-}" in
  --android)
    echo "== Test dynamique Android (nécessite un émulateur/appareil en débogage) =="
    echo "Procédure : adb shell settings put global http_proxy 127.0.0.1:1 ; lancer les parcours U1/U3/U8 ; adb shell dumpsys netstats detail | grep <uid>."
    echo "À automatiser au lot 5 (test instrumenté + vérification des compteurs réseau de l'UID)." ;;
  --ios)
    echo "== Test dynamique iOS (simulateur) =="
    echo "Procédure : lancer mitmproxy en mode blocage ou Network Link Conditioner 100 % perte ; rejouer U1/U3/U8 ; vérifier zéro requête."
    echo "À automatiser au lot 5." ;;
esac

[ $status -eq 0 ] && echo "OK" || echo "ÉCHEC"
exit $status
