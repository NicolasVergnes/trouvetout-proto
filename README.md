# TrouveTout (nom de prototype)

Application mobile « où ai-je rangé ça ? » : on déclare en deux secondes (voix, photo, texte) où l'on pose un objet, on le retrouve des mois plus tard avec ses propres mots. **100 % local, sans compte ni serveur**, iOS 16+ et Android 10+.

## État
Dossier de démarrage préparé le 24 octobre 2026 : documentation, décisions, plan de lots et configuration Claude Code.
Le lot 0 (fondations) a mis en place le monorepo Kotlin Multiplatform (`shared`, `androidApp`, `iosApp`, `tools`), la CI
GitHub Actions (Linux pour shared/Android, macOS pour iOS) et les scripts de contrôle. Statut détaillé : `docs/03-plan-de-lots.md`.
Dépôt : <https://github.com/NicolasVergnes/trouvetout-proto> (public, voir ADR-013).

## Construire
- Prérequis : JDK 17, Android SDK (plateforme 36, build-tools 36) ; iOS : macOS + Xcode 16 + `brew install xcodegen`.
- Android / shared : `./gradlew :shared:allTests :androidApp:assembleDebug :tools:build ktlintCheck detekt`
- iOS : `cd iosApp && xcodegen generate && xcodebuild test -project iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16'`
- Contrôles : `tools/check-deps.sh`, `tools/check-network.sh` ; corpus : `./gradlew :tools:evalParser :tools:evalSearch`

## Lire dans l'ordre
1. `docs/00-cahier-des-charges.md` — périmètre v1 (P0 + P1), décisions, critères d'acceptation
2. `docs/01-architecture.md` — modules KMP, modèle de données (journal d'événements), recherche, modèles IA embarqués
3. `docs/02-securite-rgpd.md` — règles de sécurité et de vie privée
4. `docs/03-plan-de-lots.md` — ordre de construction et statut
5. `docs/04-decisions.md` (ADR), `docs/05-conventions.md`, `docs/06-corpus-recherche.md`

## Démarrer avec Claude Code
1. Installer la configuration du projet (une seule fois) : la configuration Claude Code est livrée dans `claude-config/` parce que les outils de préparation ne peuvent pas écrire dans `.claude/`.
   - Windows PowerShell : `.\tools\install-claude-config.ps1`
   - bash / WSL / macOS : `tools/install-claude-config.sh`
2. Initialiser git si ce n'est pas fait : `git init && git add -A && git commit -m "chore: project bootstrap (docs, Claude Code config)"`.
3. Lancer `claude` à la racine, puis coller le contenu de `docs/PROMPT-DEMARRAGE.md`, ou taper `/lot 0`.

La configuration Claude Code (`CLAUDE.md`, `.claude/`) fournit : règles par chemin (shared, Android, iOS), 11 skills métier, 5 agents de revue (sécurité, vie privée, Android, iOS, qualité de recherche), des hooks qui bloquent les dépendances de télémétrie, les permissions non prévues, les secrets et les poids de modèles, et des permissions d'outils restreintes.

Plugins et commandes Claude Code recommandés (à installer par le développeur) :
- `claude plugin install security-guidance@claude-plugins-official --scope project` — revue de sécurité continue des modifications ;
- `claude plugin install commit-commands@claude-plugins-official --scope project` — commits et PR normalisés ;
- commandes intégrées `/security-review`, `/code-review` avant chaque PR.

## Pile
Kotlin Multiplatform (SQLDelight, kotlinx, LiteRT) · Jetpack Compose · SwiftUI · EmbeddingGemma 2 embarqué (variante selon la RAM) · StoreKit 2 / Play Billing.

## Licence et nom
Nom définitif, licence du code et identifiants publics à décider avant publication (voir ADR-010).
