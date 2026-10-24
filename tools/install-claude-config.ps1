# Installe la configuration Claude Code du projet : copie claude-config\ vers .claude\
# Usage (PowerShell, à la racine du dépôt) :  .\tools\install-claude-config.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "claude-config"
$dst = Join-Path $root ".claude"
if (-not (Test-Path $src)) { Write-Error "Dossier claude-config introuvable dans $root"; exit 1 }
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item -Path (Join-Path $src "*") -Destination $dst -Recurse -Force
Write-Host "Configuration Claude Code installée dans $dst"
Write-Host "Contenu :"
Get-ChildItem -Recurse -File $dst | ForEach-Object { "  " + $_.FullName.Substring($dst.Length + 1) }
Write-Host ""
Write-Host "Sous WSL/Git Bash, rendre les hooks exécutables : chmod +x .claude/hooks/*.sh tools/*.sh"
Write-Host "Vous pouvez ensuite supprimer claude-config\ ou le garder comme référence (il est ignoré par Claude Code)."
