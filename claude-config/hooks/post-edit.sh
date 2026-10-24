#!/usr/bin/env bash
# PostToolUse hook (Edit|Write) — formatage automatique du fichier modifié si l'outil est installé.
# Ne bloque jamais (exit 0).
set -uo pipefail
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
[ -z "$FILE" ] || [ ! -f "$FILE" ] && exit 0

case "$FILE" in
  *.kt|*.kts)
    if command -v ktlint >/dev/null 2>&1; then
      ktlint -F "$FILE" >/dev/null 2>&1 || echo "ktlint : problèmes restants dans $FILE (lancer ./gradlew ktlintCheck)" >&2
    fi;;
  *.swift)
    if command -v swiftformat >/dev/null 2>&1; then
      swiftformat --quiet "$FILE" >/dev/null 2>&1 || true
    fi
    if command -v swiftlint >/dev/null 2>&1; then
      swiftlint lint --quiet --path "$FILE" 2>/dev/null | head -20 >&2 || true
    fi;;
  *.json)
    if command -v jq >/dev/null 2>&1; then
      jq empty "$FILE" 2>/dev/null || echo "JSON invalide : $FILE" >&2
    fi;;
esac
exit 0
