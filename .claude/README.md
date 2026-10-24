# claude-config → .claude

Ce dossier contient la configuration Claude Code du projet (réglages, règles par chemin, skills, agents, hooks).
Les outils qui ont préparé le dépôt ne peuvent pas écrire directement dans `.claude/` ; copiez-le une fois :

- Windows PowerShell : `.\tools\install-claude-config.ps1`
- bash / WSL / macOS : `tools/install-claude-config.sh`

Prérequis des hooks : `bash` (Git for Windows suffit) ; `jq` est utilisé s'il est présent, sinon `python3`.
Puis lancez `claude` à la racine. Une fois copié, ce dossier peut être supprimé.
