#!/usr/bin/env bash
# Installe la configuration Claude Code du projet : copie claude-config/ vers .claude/
# Usage (bash, WSL, macOS, Git Bash) :  tools/install-claude-config.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/claude-config"
DST="$ROOT/.claude"
[ -d "$SRC" ] || { echo "Dossier claude-config introuvable dans $ROOT" >&2; exit 1; }
mkdir -p "$DST"
cp -R "$SRC"/. "$DST"/
chmod +x "$DST"/hooks/*.sh "$ROOT"/tools/*.sh 2>/dev/null || true
echo "Configuration Claude Code installée dans $DST :"
find "$DST" -type f | sed "s|$DST/|  |" | sort
echo
echo "Vous pouvez supprimer claude-config/ ou le garder comme référence."
