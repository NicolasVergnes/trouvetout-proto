#!/usr/bin/env bash
# Vérifie « zéro réseau en usage normal ».
# Partie statique (toujours) : recherche de clients HTTP hors des emplacements autorisés.
# Partie dynamique (lot 12, ADR-027), automatisée en CI : Android `NoNetworkTest` (compteurs de trafic de l'uid, API 29),
# iOS `tools/watch-ios-network.sh` (sockets de l'app sur le simulateur pendant xcodebuild test). --android | --ios
# rappellent les commandes ; sur un vrai téléphone, la procédure est dans docs/07-recette.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
status=0

echo "== Analyse statique des accès réseau =="
ALLOWED_PATHS='shared/src/commonMain/kotlin/.*/ml/ModelPackDownloader|androidApp/src/main/java/.*/billing/|iosApp/.*/Billing/|iosApp/.*/Platform/ModelPackDownloader'
MATCHES=$(grep -rnE "HttpClient|URLSession|OkHttpClient|io\.ktor\.client|URLRequest\(|HttpURLConnection|NSURLSession|Socket\(|WebSocket|java\.net\.URL|openConnection\(|NWConnection|import Network|WebView|WKWebView" \
  "$ROOT/shared" "$ROOT/androidApp" "$ROOT/iosApp" 2>/dev/null \
  --include='*.kt' --include='*.swift' | grep -vE "$ALLOWED_PATHS" | grep -vE "/build/|/(commonTest|jvmTest|androidHostTest|androidDeviceTest|androidUnitTest|androidInstrumentedTest|iosTest|iosSimulatorArm64Test|iosArm64Test|iosAppTests|androidTest|test)/" || true)
if [ -n "$MATCHES" ]; then
  echo "VIOLATION : accès réseau hors emplacements autorisés :"; echo "$MATCHES"; status=1
else
  echo "OK : aucun client réseau hors ModelPackDownloader / Billing."
fi

echo "== Reconnaissance vocale sur l'appareil seulement (B2, ADR-020) =="
# The speech service is another process: no network counter of the app would see a server recogniser, so the
# guarantee is checked in the code itself.
SERVER_SPEECH=$(grep -rnF "createSpeechRecognizer(" "$ROOT/androidApp/src/main" "$ROOT/shared/src" --include='*.kt' 2>/dev/null || true)
if [ -n "$SERVER_SPEECH" ]; then
  echo "VIOLATION : reconnaissance vocale sans garantie hors ligne (createOnDeviceSpeechRecognizer seulement) :"
  echo "$SERVER_SPEECH"; status=1
fi
RELAXED=$(grep -rnF "requiresOnDeviceRecognition = false" "$ROOT/iosApp" --include='*.swift' 2>/dev/null || true)
if [ -n "$RELAXED" ]; then
  echo "VIOLATION : requiresOnDeviceRecognition relâché :"; echo "$RELAXED"; status=1
fi
for f in $(grep -rlF "SFSpeechAudioBufferRecognitionRequest(" "$ROOT/iosApp" --include='*.swift' 2>/dev/null || true); do
  if ! grep -qF "requiresOnDeviceRecognition = true" "$f"; then
    echo "VIOLATION : requête de reconnaissance sans requiresOnDeviceRecognition = true dans $f"; status=1
  fi
done

echo "== Widgets sans accès aux données (H4) =="
if grep -rqF "import Shared" "$ROOT/iosApp/CaptureWidget" --include='*.swift' 2>/dev/null; then
  echo "VIOLATION : l'extension widget iOS importe le noyau"; status=1
fi
if grep -rqE "Repository|SharedFactory|readyShared" "$ROOT/androidApp/src/main/java/app/trouvetout/android/widget" 2>/dev/null; then
  echo "VIOLATION : le widget ou la tuile Android lit des données"; status=1
fi

echo "== Notifications sans lieu (H4, lot 9) =="
# Le texte d'une notification vient de ScheduledReminder (nom ou rien) : le code des notifications ne lit aucun lieu.
if grep -rnE "LocationPath|LocationResolver|\.labels|placeId|containerId|browse\." \
  "$ROOT/androidApp/src/main/java/app/trouvetout/android/notif" "$ROOT/iosApp/iosApp/Notifications" 2>/dev/null; then
  echo "VIOLATION : le code des notifications lit un lieu"; status=1
fi

echo "== Manifeste / plist =="
if grep -rq 'android.permission.INTERNET' "$ROOT/androidApp" --include=AndroidManifest.xml 2>/dev/null && [ ! -f "$ROOT/docs/adr-permissions.ok" ]; then
  echo "VIOLATION : permission INTERNET sans ADR"; status=1
fi

case "${1:-}" in
  --android)
    echo "== Test dynamique Android =="
    echo "Automatisé : ./gradlew :androidApp:api29DebugAndroidTest (NoNetworkTest : permission INTERNET refusée, zéro octet"
    echo "pour l'uid pendant capture, recherche avec le modèle chargé, export/import, magasin). Vrai téléphone : docs/07-recette.md." ;;
  --ios)
    echo "== Test dynamique iOS (simulateur) =="
    echo "Automatisé en CI : tools/watch-ios-network.sh start|stop autour de xcodebuild test (aucune socket internet de"
    echo "l'app ni du widget, LiteRT-LM chargé compris). Vrai iPhone : docs/07-recette.md." ;;
esac

[ $status -eq 0 ] && echo "OK" || echo "ÉCHEC"
exit $status
