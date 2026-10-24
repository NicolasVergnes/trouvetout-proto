#!/usr/bin/env bash
# PreToolUse hook (Edit|Write) — refuse les modifications qui violent les règles sécurité/RGPD.
# Le garde échoue fermé : toute erreur interne (JSON illisible, outil absent, variable manquante) refuse (exit 2).
set -euo pipefail
trap 'echo "guard-edit.sh : erreur interne, modification refusée par précaution" >&2; exit 2' ERR
shopt -s nocasematch
: "${CLAUDE_PROJECT_DIR:=$(cd "$(dirname "$0")/../.." && pwd)}"
INPUT="$(cat)"
has_jq() { command -v jq >/dev/null 2>&1; }
# Lecture d'un champ JSON depuis stdin : jq si présent, sinon python3.
json_get() { # $1 = expression jq, $2 = chemin python équivalent (séparé par des points)
  if has_jq; then printf '%s' "$INPUT" | jq -r "$1";
  else printf '%s' "$INPUT" | python3 -c "import sys,json
d=json.load(sys.stdin)
for k in '$2'.split('.'):
    d=d.get(k,'') if isinstance(d,dict) else ''
print(d if isinstance(d,str) else '')"; fi
}
# Tout le texte ajouté : Write.content, Edit.new_string et chaque edits[].new_string (éditions groupées).
json_new() {
  if has_jq; then
    printf '%s' "$INPUT" | jq -r '[.tool_input.content // empty, .tool_input.new_string // empty, ((.tool_input.edits // [])[] | .new_string // empty)] | join("\n")'
  else
    printf '%s' "$INPUT" | python3 -c "import sys,json
d=json.load(sys.stdin).get('tool_input',{})
parts=[d.get('content',''), d.get('new_string','')]+[e.get('new_string','') for e in d.get('edits',[]) if isinstance(e,dict)]
print('\n'.join(p for p in parts if isinstance(p,str)))"
  fi
}
FILE="$(json_get '.tool_input.file_path // empty' 'tool_input.file_path')"
# Les outils passent des chemins Windows (D:\...) : normaliser les séparateurs avant les motifs */.../*.
FILE="${FILE//\\//}"
NEW="$(json_new)"
[ -z "$FILE" ] && exit 0

deny() { echo "Bloqué par .claude/hooks/guard-edit.sh : $1" >&2; exit 2; }

# 1. Fichiers de secrets et de signature (comparaison insensible à la casse)
case "$FILE" in
  *.jks|*.keystore|*.p12|*.pfx|*.p8|*.key|*.mobileprovision|*.pem|*.cer|*/secrets/*|*/.env|*/.env.*|*keystore.properties|*local.properties|*/.gradle/gradle.properties|*google-services.json|*GoogleService-Info.plist|*service-account*.json)
    deny "écriture dans un fichier de secret/signature ($FILE)";;
esac

# 2. Poids de modèles dans le dépôt
case "$FILE" in
  *.tflite|*.litertlm|*.task|*.gguf|*.onnx|*.mlmodel|*.mlpackage|*.safetensors|*.bin)
    deny "les poids de modèles ne sont pas versionnés ; utiliser un pack à la demande (voir docs/01-architecture.md §5.2)";;
esac

# 3. Dépendances interdites dans les fichiers de build (dont le projet XcodeGen, qui porte les paquets SPM)
case "$FILE" in
  *.gradle|*.gradle.kts|*libs.versions.toml|*Package.swift|*Podfile|*Cartfile|*.podspec|*Package.resolved|*project.yml|*.xcconfig|*.entitlements)
    FORBIDDEN_FILE="$(dirname "$0")/forbidden-deps.txt"
    if [ -f "$FORBIDDEN_FILE" ]; then
      # Une seule passe grep sur le texte en minuscules (les boucles par motif coûtaient plusieurs secondes).
      MATCH="$(printf '%s' "$NEW" | tr '[:upper:]' '[:lower:]' | grep -Fof <(grep -vE '^(#|$)' "$FORBIDDEN_FILE" | tr '[:upper:]' '[:lower:]') | head -n1 || true)"
      if [ -n "$MATCH" ]; then
        deny "dépendance interdite ($MATCH) dans $FILE — aucune télémétrie ni SDK tiers (docs/02-securite-rgpd.md)"
      fi
    fi;;
esac

# 4. Permissions Android : INTERNET et localisation/contacts interdits en v1 sans ADR explicite
case "$FILE" in
  *AndroidManifest.xml)
    if printf '%s' "$NEW" | grep -Eq 'android\.permission\.(INTERNET|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|READ_CONTACTS|READ_EXTERNAL_STORAGE|READ_MEDIA_IMAGES|READ_CALENDAR)'; then
      if [ ! -f "$CLAUDE_PROJECT_DIR/docs/adr-permissions.ok" ]; then
        deny "permission réseau/localisation/contacts/médias ajoutée au manifeste sans ADR. Écrire l'ADR dans docs/04-decisions.md puis créer docs/adr-permissions.ok pour autoriser."
      fi
    fi
    if printf '%s' "$NEW" | grep -Eq 'allowBackup\s*=\s*["'"'"']true'; then
      deny "android:allowBackup=\"true\" interdit : la sauvegarde est gérée par l'app (A9), chiffrée"
    fi
    if printf '%s' "$NEW" | grep -Eq 'usesCleartextTraffic\s*=\s*["'"'"']true'; then
      deny "trafic en clair interdit"
    fi;;
  *Info.plist|*project.yml|*.xcconfig|*.entitlements)
    if printf '%s' "$NEW" | grep -Eq 'NSAllowsArbitraryLoads|NSExceptionAllowsInsecureHTTPLoads'; then
      deny "NSAllowsArbitraryLoads interdit (ATS doit rester strict)"
    fi
    if printf '%s' "$NEW" | grep -Eq 'NSLocation(WhenInUse|Always)|NSContactsUsageDescription|NSCalendarsUsageDescription|INFOPLIST_KEY_NS(Location|Contacts|Calendars)'; then
      if [ ! -f "$CLAUDE_PROJECT_DIR/docs/adr-permissions.ok" ]; then
        deny "permission localisation/contacts/calendrier ajoutée sans ADR (docs/adr-permissions.ok absent)"
      fi
    fi;;
esac

# 5. Journalisation de données métier dans le code de prod (heuristique : appel de log + valeur métier sur la même ligne)
case "$FILE" in
  */src/commonMain/*|*/src/androidMain/*|*/src/iosMain/*|*/androidApp/src/main/*|*/iosApp/iosApp/*)
    if printf '%s' "$NEW" | grep -Eq '(\.log\(|logger\.[a-z]+\(|Log\.[dviwe]\(|println\(|print\(|debugPrint\(|NSLog\(|os_log\(|\.(debug|info|notice|warning|error|fault)\().*(item\.name|place\.name|container\.label|rawInput|transcript|phrase|query|placePath|\$\{?[A-Za-z_.]*(name|Name|label|Label|path|Path|input|Input|text|Text|query|Query|description|Description)\b|\\\([A-Za-z_.]*(name|label|path|input|text|query|description)\))'; then
      deny "log contenant une donnée métier (nom d'objet, lieu, phrase, requête, chemin). Logger des identifiants seulement."
    fi;;
esac

exit 0
