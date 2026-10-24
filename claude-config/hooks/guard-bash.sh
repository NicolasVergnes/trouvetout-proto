#!/usr/bin/env bash
# PreToolUse hook (Bash) — bloque les commandes dangereuses ou contraires aux règles du projet.
# Entrée : JSON sur stdin ; sortie : exit 2 + message sur stderr pour bloquer.
# Le garde échoue fermé : toute erreur interne refuse la commande (exit 2).
set -euo pipefail
trap 'echo "guard-bash.sh : erreur interne, commande refusée par précaution" >&2; exit 2' ERR
: "${CLAUDE_PROJECT_DIR:=$(cd "$(dirname "$0")/../.." && pwd)}"
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
# git : pas de force push (option ou refspec +), pas de contournement des hooks git (--no-verify, -n)
if printf '%s' "$CMD" | grep -Eq 'git\s+push\s+[^|;&]*(--force|--force-with-lease|-f(\s|$)|\s\+[A-Za-z0-9_./-])'; then
  deny "git push forcé interdit (demander explicitement si nécessaire)"
fi
if printf '%s' "$CMD" | grep -Eq -- '--no-verify|git\s+commit(\s[^|;&]*)?\s-[a-zA-Z]*n[a-zA-Z]*(\s|$)'; then
  deny "--no-verify / -n interdit : les hooks git doivent s'exécuter"
fi
if printf '%s' "$CMD" | grep -Eq 'chmod\s+(-R\s+)?777'; then
  deny "chmod 777"
fi
# gradle properties affiche les propriétés utilisateur (~/.gradle), qui peuvent contenir des mots de passe de signature
if printf '%s' "$CMD" | grep -Eq '(^|[;&|]\s*)(\./)?gradlew?(\.bat)?\s+(-q\s+)?properties(\s|$)'; then
  deny "gradle properties interdit (propriétés utilisateur)"
fi
# Secrets : toute mention d'un fichier de secret/signature est refusée, quelle que soit la commande,
# sauf les commandes purement descriptives (ls, git status, git check-ignore, git ls-files) sans enchaînement.
SECRET_RE='(\.jks|\.keystore|\.p12|\.pfx|\.p8|\.pem|\.cer|\.mobileprovision|keystore\.properties|local\.properties|\.gradle[/\\]gradle\.properties|(^|[/\\[:space:]"'"'"'=])\.env([./\\[:space:]"'"'"']|$)|secrets[/\\]|google-services\.json|GoogleService-Info\.plist|service-account[A-Za-z0-9_.-]*\.json)'
if printf '%s' "$CMD" | grep -Eqi "$SECRET_RE"; then
  if ! printf '%s' "$CMD" | grep -Eqi '^\s*(ls|git\s+status|git\s+check-ignore|git\s+ls-files)[^|;&]*$'; then
    deny "manipulation d'un fichier de signature ou de secret"
  fi
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
