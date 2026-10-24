#!/usr/bin/env bash
# PreToolUse hook (Bash) — bloque les commandes dangereuses ou contraires aux règles du projet.
# Entrée : JSON sur stdin ; sortie : exit 2 + message sur stderr pour bloquer.
set -euo pipefail
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
CMD="$(json_get '.tool_input.command // empty' 'tool_input.command')"
[ -z "$CMD" ] && exit 0

deny() { echo "Bloqué par .claude/hooks/guard-bash.sh : $1" >&2; exit 2; }

# Destructions larges
if printf '%s' "$CMD" | grep -Eq '(^|[;&|]\s*)rm\s+-[a-zA-Z]*r[a-zA-Z]*f?\s+(/|~|\$HOME|\.\.|\*)(\s|$)'; then
  deny "rm -rf sur la racine, le home ou le dossier parent"
fi
if printf '%s' "$CMD" | grep -Eq 'git\s+push\s+.*(--force|-f)(\s|$)'; then
  deny "git push --force interdit (demander explicitement si nécessaire)"
fi
if printf '%s' "$CMD" | grep -Eq -- '--no-verify'; then
  deny "--no-verify interdit : les hooks git doivent s'exécuter"
fi
if printf '%s' "$CMD" | grep -Eq 'chmod\s+(-R\s+)?777'; then
  deny "chmod 777"
fi
# Secrets : jamais affichés ni copiés (signature, clés, .env, secrets/, gradle.properties utilisateur)
if printf '%s' "$CMD" | grep -Eq '(\.jks|\.keystore|\.p12|\.p8|\.pem|\.cer|\.mobileprovision|keystore\.properties|local\.properties|\.gradle/gradle\.properties|(^|[/[:space:]])\.env([./[:space:]]|$)|secrets/)' \
   && printf '%s' "$CMD" | grep -Eq '(^|[;&|]\s*)(cat|less|more|head|tail|base64|xxd|cp|scp|curl|git\s+add)\s'; then
  deny "manipulation d'un fichier de signature ou de secret"
fi
# Téléchargements : seulement via les outils du projet
if printf '%s' "$CMD" | grep -Eq '(^|[;&|]\s*)(curl|wget)\s' && ! printf '%s' "$CMD" | grep -Eq 'tools/'; then
  echo "Avertissement : téléchargement direct hors tools/. Les poids de modèles ne doivent pas entrer dans le dépôt." >&2
fi
# Ajout de dépendances interdites par ligne de commande
FORBIDDEN_FILE="$(dirname "$0")/forbidden-deps.txt"
if [ -f "$FORBIDDEN_FILE" ]; then
  while IFS= read -r pat; do
    [ -z "$pat" ] && continue
    case "$pat" in \#*) continue ;; esac
    if printf '%s' "$CMD" | tr '[:upper:]' '[:lower:]' | grep -Fq -- "$(printf '%s' "$pat" | tr '[:upper:]' '[:lower:]')"; then
      deny "référence à une dépendance interdite ($pat) — voir docs/02-securite-rgpd.md"
    fi
  done < "$FORBIDDEN_FILE"
fi
exit 0
