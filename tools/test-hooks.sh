#!/usr/bin/env bash
# Tests des hooks Claude Code (.claude/hooks) avec des charges JSON synthétiques :
# cas bloqués → exit 2, cas autorisés → exit 0, erreurs internes → exit 2 (le garde échoue fermé).
# Vérifie aussi que claude-config/hooks (copie de référence) est identique à .claude/hooks.
# Usage : tools/test-hooks.sh   (exit 1 si un cas échoue)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOKS="$ROOT/.claude/hooks"
export CLAUDE_PROJECT_DIR="$ROOT"
fail=0
count=0

json_str() { jq -n --arg s "$1" '$s'; }
bash_input() { printf '{"tool_input":{"command":%s}}' "$(json_str "$1")"; }
write_input() { printf '{"tool_input":{"file_path":%s,"content":%s}}' "$(json_str "$1")" "$(json_str "$2")"; }
edits_input() { printf '{"tool_input":{"file_path":%s,"edits":[{"old_string":"a","new_string":%s}]}}' "$(json_str "$1")" "$(json_str "$2")"; }

check() { # $1 = hook, $2 = exit attendu, $3 = description, $4 = JSON
  local got
  printf '%s' "$4" | bash "$HOOKS/$1" >/dev/null 2>&1
  got=$?
  count=$((count + 1))
  if [ "$got" -ne "$2" ]; then
    echo "ÉCHEC [$1] $3 : attendu $2, obtenu $got"; fail=1
  fi
}

# --- guard-bash : bloqués
for cmd in \
  'cat local.properties' \
  'jq -R . local.properties' \
  "find . -name '*.jks' -exec cat {} ;" \
  'sed -n p keystore.properties' \
  'mv keystore.properties androidApp/notes.txt' \
  'python3 -c "print(open(\".env\").read())"' \
  'cat ~/.gradle/gradle.properties' \
  'cp secrets/key.p12 /tmp/' \
  'cat GoogleService-Info.plist' \
  './gradlew properties' \
  './gradlew -q properties' \
  'git push --force origin main' \
  'git push origin +main' \
  'git commit -n -m x' \
  'git commit --no-verify -m x' \
  'git commit -anm x' \
  'rm -rf /' \
  'rm -rf ~' \
  './gradlew build -PaddFirebaseCrashlytics' \
  'echo com.mixpanel >> androidApp/build.gradle.kts' \
  'ls; cat release.jks'; do
  check guard-bash.sh 2 "bloque : $cmd" "$(bash_input "$cmd")"
done
# --- guard-bash : autorisés
for cmd in \
  'git status' \
  'ls secrets/' \
  'git check-ignore local.properties' \
  './gradlew :shared:allTests ktlintCheck detekt' \
  'cat README.md' \
  'git commit -q -m "feat(shared): add parser"' \
  'git push -u origin lot/01-socle' \
  'grep -n TODO shared/src/commonMain/kotlin/App.kt' \
  'git add -A && git commit -m "docs: notes"' \
  'tools/check-deps.sh'; do
  check guard-bash.sh 0 "autorise : $cmd" "$(bash_input "$cmd")"
done
# --- guard-bash : erreurs internes → refus
check guard-bash.sh 2 "JSON mal formé" '{"tool_input":'
check guard-bash.sh 0 "sans commande" '{"tool_input":{}}'

# --- guard-edit : bloqués
check guard-edit.sh 2 "secret .jks (chemin Windows, majuscules)" "$(write_input 'D:\x\release.JKS' 'x')"
check guard-edit.sh 2 "dossier secrets/ (chemin Windows)" "$(write_input 'D:\x\secrets\k.txt' 'x')"
check guard-edit.sh 2 "fichier .env" "$(write_input '/p/.env' 'x')"
check guard-edit.sh 2 "google-services.json" "$(write_input '/p/app/google-services.json' '{}')"
check guard-edit.sh 2 "poids de modèle" "$(write_input '/p/model.tflite' 'x')"
check guard-edit.sh 2 "SDK interdit dans gradle (content)" "$(write_input '/p/build.gradle.kts' 'implementation("com.google.firebase:firebase-analytics:1.0")')"
check guard-edit.sh 2 "SDK interdit dans gradle (edits[])" "$(edits_input '/p/build.gradle.kts' 'implementation("io.sentry:sentry-android:7.0")')"
check guard-edit.sh 2 "SDK interdit dans project.yml" "$(write_input '/p/iosApp/project.yml' 'packages: { Sentry: { url: x } }')"
check guard-edit.sh 2 "INTERNET sans ADR" "$(write_input '/p/AndroidManifest.xml' '<uses-permission android:name="android.permission.INTERNET" />')"
check guard-edit.sh 2 "allowBackup true" "$(write_input '/p/AndroidManifest.xml' 'android:allowBackup = "true"')"
check guard-edit.sh 2 "ATS assoupli" "$(write_input '/p/Info.plist' '<key>NSAllowsArbitraryLoads</key><true/>')"
check guard-edit.sh 2 "localisation via project.yml" "$(write_input '/p/iosApp/project.yml' 'INFOPLIST_KEY_NSLocationWhenInUseUsageDescription: x')"
check guard-edit.sh 2 "log métier via Logger" "$(write_input '/p/shared/src/commonMain/kotlin/A.kt' 'logger.log(LogLevel.DEBUG, "T", "stored ${item.name}")')"
check guard-edit.sh 2 "log métier via Log.d" "$(write_input '/p/androidApp/src/main/java/A.kt' 'Log.d(TAG, "saved " + place.name)')"
check guard-edit.sh 2 "log métier Swift" "$(write_input '/p/iosApp/iosApp/A.swift' 'os_log("%@", item.name)')"
check guard-edit.sh 2 "log métier interpolation Swift" "$(write_input '/p/iosApp/iosApp/A.swift' 'logger.debug("stored \(item.name)")')"
# --- guard-edit : autorisés
check guard-edit.sh 0 "gradle.properties du projet" "$(write_input 'D:\p\gradle.properties' 'org.gradle.caching=true')"
check guard-edit.sh 0 "log d'identifiant" "$(write_input '/p/shared/src/commonMain/kotlin/A.kt' 'logger.log(LogLevel.DEBUG, "T", "stored item ${item.id}")')"
check guard-edit.sh 0 "fichier doc" "$(write_input '/p/README.md' 'firebase is forbidden here')"
check guard-edit.sh 0 "manifeste sans permission" "$(write_input '/p/AndroidManifest.xml' '<application android:allowBackup="false" />')"
# --- guard-edit : erreurs internes → refus
check guard-edit.sh 2 "JSON mal formé" '{"tool_input":'
check guard-edit.sh 0 "sans chemin" '{"tool_input":{}}'
# --- CLAUDE_PROJECT_DIR absent : le repli calcule la racine et refuse toujours INTERNET sans ADR
count=$((count + 1))
if printf '%s' "$(write_input '/p/AndroidManifest.xml' 'android.permission.INTERNET')" | env -u CLAUDE_PROJECT_DIR bash "$HOOKS/guard-edit.sh" >/dev/null 2>&1; then
  echo "ÉCHEC [guard-edit.sh] INTERNET sans CLAUDE_PROJECT_DIR : attendu 2, obtenu 0"; fail=1
fi

# --- Les deux copies des hooks doivent être identiques
if ! diff -rq "$ROOT/.claude/hooks" "$ROOT/claude-config/hooks" >/dev/null; then
  echo "ÉCHEC : .claude/hooks et claude-config/hooks diffèrent"; diff -rq "$ROOT/.claude/hooks" "$ROOT/claude-config/hooks"; fail=1
fi

if [ $fail -eq 0 ]; then echo "OK : $count cas de hooks passent."; else echo "ÉCHEC : voir ci-dessus ($count cas)."; fi
exit $fail
