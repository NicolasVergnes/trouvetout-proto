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

# Commande sans ses chaînes entre guillemets : les options git sont cherchées hors des messages de commit.
CMD_NOQ="$(printf '%s' "$CMD" | sed -E "s/\"[^\"]*\"//g; s/'[^']*'//g")"

# Destructions larges
if printf '%s' "$CMD" | grep -Eq '(^|[;&|]\s*)rm\s+-[a-zA-Z]*r[a-zA-Z]*f?\s+(/|~|\$HOME|\.\.|\*)(\s|$)'; then
  deny "rm -rf sur la racine, le home ou le dossier parent"
fi
# git : pas de push forcé (option courte même groupée, longue, --mirror/--delete, refspec +), même après des options
# globales (git -C . push) ; pas de contournement des hooks git (--no-verify, -n même groupé).
if printf '%s' "$CMD_NOQ" | grep -Eq 'git\b[^|;&]*\bpush\b[^|;&]*(\s-[a-zA-Z]*f[a-zA-Z]*(\s|$)|\s--force|\s--mirror|\s--delete|\s\+[A-Za-z0-9_./-])'; then
  deny "git push forcé interdit (demander explicitement si nécessaire)"
fi
if printf '%s' "$CMD_NOQ" | grep -Eq -- '--no-verify|git\b[^|;&]*\bcommit\b[^|;&]*\s-[a-zA-Z]*n[a-zA-Z]*(\s|$)'; then
  deny "--no-verify / -n interdit : les hooks git doivent s'exécuter"
fi
if printf '%s' "$CMD" | grep -Eq 'chmod\s+(-R\s+)?777'; then
  deny "chmod 777"
fi
# gradle properties affiche les propriétés utilisateur (~/.gradle), qui peuvent contenir des mots de passe de signature
if printf '%s' "$CMD" | grep -Eq '(^|[;&|]\s*)(\./)?gradlew?(\.bat)?\s+(-q\s+)?properties(\s|$)'; then
  deny "gradle properties interdit (propriétés utilisateur)"
fi
# Secrets : toute mention d'un fichier de secret/signature est refusée, quelle que soit la commande.
# Seule exemption : une commande d'une seule ligne, sans substitution ni redirection, qui ne fait que lister.
SECRET_RE='(\.jks|\.keystore|\.p12|\.pfx|\.p8|\.pem|\.cer|\.mobileprovision|keystore\.properties|local\.properties|\.gradle[/\\]gradle\.properties|(^|[/\\[:space:]"'"'"'=(])\.env([./\\[:space:]"'"'"';|&<>()`]|$)|secrets[/\\]|google-services\.json|GoogleService-Info\.plist|service-account[A-Za-z0-9_.-]*\.json)'
if printf '%s' "$CMD" | grep -Eqi "$SECRET_RE"; then
  if [ "$(printf '%s' "$CMD" | wc -l)" -gt 0 ] \
     || printf '%s' "$CMD" | grep -q '[$`<>]' \
     || ! printf '%s' "$CMD" | grep -Eqi '^\s*(ls|git\s+status|git\s+check-ignore|git\s+ls-files)(\s[^|;&]*)?$'; then
    deny "manipulation d'un fichier de signature ou de secret"
  fi
fi
# Téléchargements : seulement via les outils du projet
if printf '%s' "$CMD" | grep -Eq '(^|[;&|]\s*)(curl|wget)\s' && ! printf '%s' "$CMD" | grep -Eq 'tools/'; then
  echo "Avertissement : téléchargement direct hors tools/. Les poids de modèles ne doivent pas entrer dans le dépôt." >&2
fi
# Dépendances interdites (motifs insensibles à la casse, une seule passe grep)
FORBIDDEN_FILE="$(dirname "$0")/forbidden-deps.txt"
if [ -f "$FORBIDDEN_FILE" ]; then
  MATCH="$(printf '%s' "$CMD" | tr '[:upper:]' '[:lower:]' | grep -Fof <(grep -vE '^(#|$)' "$FORBIDDEN_FILE" | tr '[:upper:]' '[:lower:]') | head -n1 || true)"
  if [ -n "$MATCH" ]; then
    deny "référence à une dépendance interdite ($MATCH) — voir docs/02-securite-rgpd.md"
  fi
fi
exit 0
