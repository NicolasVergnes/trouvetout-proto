#!/usr/bin/env bash
# PreToolUse hook (Edit|Write) — refuse les modifications qui violent les règles sécurité/RGPD.
set -euo pipefail
shopt -s nocasematch
INPUT="$(cat)"
# Lecture d'un champ JSON depuis stdin : jq si présent, sinon python3.
json_get() { # $1 = expression jq, $2 = chemin python équivalent (séparé par des points)
  if command -v jq >/dev/null 2>&1; then printf '%s' "$INPUT" | jq -r "$1";
  else printf '%s' "$INPUT" | python3 -c "import sys,json
d=json.load(sys.stdin)
for k in '$2'.split('.'):
    d=d.get(k,'') if isinstance(d,dict) else ''
print(d if isinstance(d,str) else '')"; fi
}
FILE="$(json_get '.tool_input.file_path // empty' 'tool_input.file_path')"
# Les outils passent des chemins Windows (D:\...) : normaliser les séparateurs avant les motifs */.../*.
FILE="${FILE//\\//}"
# Contenu ajouté : Write.content, Edit.new_string et chaque edits[].new_string (éditions groupées).
NEW="$(json_get '.tool_input.content // empty' 'tool_input.content')
$(json_get '.tool_input.new_string // empty' 'tool_input.new_string')
$(if command -v jq >/dev/null 2>&1; then printf '%s' "$INPUT" | jq -r '[.tool_input.edits[]?.new_string // empty] | join("\n")'; fi)"
[ -z "$FILE" ] && exit 0

deny() { echo "Bloqué par .claude/hooks/guard-edit.sh : $1" >&2; exit 2; }

# 1. Fichiers de secrets et de signature (comparaison insensible à la casse)
case "$FILE" in
  *.jks|*.keystore|*.p12|*.p8|*.mobileprovision|*.pem|*.cer|*/secrets/*|*/.env|*/.env.*|*keystore.properties|*local.properties|*/.gradle/gradle.properties)
    deny "écriture dans un fichier de secret/signature ($FILE)";;
esac

# 2. Poids de modèles dans le dépôt
case "$FILE" in
  *.tflite|*.litertlm|*.task|*.gguf|*.onnx|*.mlmodel|*.mlpackage|*.safetensors|*.bin)
    deny "les poids de modèles ne sont pas versionnés ; utiliser un pack à la demande (voir docs/01-architecture.md §5.2)";;
esac

# 3. Dépendances interdites dans les fichiers de build
case "$FILE" in
  *.gradle|*.gradle.kts|*libs.versions.toml|*Package.swift|*Podfile|*Cartfile|*.podspec|*Package.resolved)
    FORBIDDEN_FILE="$(dirname "$0")/forbidden-deps.txt"
    if [ -f "$FORBIDDEN_FILE" ]; then
      while IFS= read -r pat; do
        [ -z "$pat" ] && continue
        case "$pat" in \#*) continue ;; esac
        if printf '%s' "$NEW" | tr '[:upper:]' '[:lower:]' | grep -Fq -- "$(printf '%s' "$pat" | tr '[:upper:]' '[:lower:]')"; then
          deny "dépendance interdite ($pat) dans $FILE — aucune télémétrie ni SDK tiers (docs/02-securite-rgpd.md)"
        fi
      done < "$FORBIDDEN_FILE"
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
    if printf '%s' "$NEW" | grep -Eq 'allowBackup\s*=\s*"true"'; then
      deny "android:allowBackup=\"true\" interdit : la sauvegarde est gérée par l'app (A9), chiffrée"
    fi
    if printf '%s' "$NEW" | grep -Eq 'usesCleartextTraffic\s*=\s*"true"'; then
      deny "trafic en clair interdit"
    fi;;
  *Info.plist)
    if printf '%s' "$NEW" | grep -Eq 'NSAllowsArbitraryLoads'; then
      deny "NSAllowsArbitraryLoads interdit (ATS doit rester strict)"
    fi
    if printf '%s' "$NEW" | grep -Eq 'NSLocation(WhenInUse|Always)|NSContactsUsageDescription|NSCalendarsUsageDescription'; then
      if [ ! -f "$CLAUDE_PROJECT_DIR/docs/adr-permissions.ok" ]; then
        deny "permission localisation/contacts/calendrier ajoutée sans ADR (docs/adr-permissions.ok absent)"
      fi
    fi;;
esac

# 5. Journalisation de données métier dans le code de prod (heuristique)
case "$FILE" in
  */src/commonMain/*|*/src/androidMain/*|*/src/iosMain/*|*/androidApp/src/main/*|*/iosApp/*)
    if printf '%s' "$NEW" | grep -Eq '(Log\.[dviwe]|println|NSLog|print)\(.*\b(item\.name|place\.name|rawInput|transcript|phrase|query)\b'; then
      deny "log contenant une donnée métier (nom d'objet, lieu, phrase, requête). Logger des identifiants seulement."
    fi;;
esac

exit 0
