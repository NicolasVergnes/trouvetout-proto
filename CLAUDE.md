# TrouveTout — instructions projet pour Claude Code

Application mobile « où ai-je rangé ça ? » : on déclare en deux secondes (voix, photo, texte) où l'on pose un objet, on le retrouve des mois plus tard avec ses propres mots. **100 % local, sans compte ni serveur**, iOS + Android. « TrouveTout » est un nom de prototype.

## À lire avant de coder
- @docs/00-cahier-des-charges.md — périmètre v1 (P0 + P1), décisions, critères d'acceptation
- @docs/01-architecture.md — modules, modèle de données, journal d'événements, recherche, modèles IA
- @docs/02-securite-rgpd.md — règles non négociables de sécurité et de vie privée
- @docs/03-plan-de-lots.md — ordre de construction et statut (à tenir à jour)
- `docs/04-decisions.md` (ADR), `docs/05-conventions.md`, `docs/06-corpus-recherche.md` — à consulter au besoin

## Règles absolues (enforcées par hooks et permissions, pas seulement par ce texte)
1. **Aucun réseau en usage normal.** Pas d'appel HTTP hors `shared/ml/ModelPackDownloader` (packs de modèles, SHA-256 vérifié) et des achats via les magasins. Pas de permission `INTERNET` sur Android sans ADR.
2. **Aucune télémétrie, aucun SDK analytique/publicitaire/crash tiers.** Liste interdite dans `.claude/hooks/forbidden-deps.txt`.
3. **Aucune donnée métier dans les logs** (noms d'objets, lieux, phrases dictées, photos). Identifiants seulement.
4. **Toute logique métier dans `shared` (KMP).** Les apps natives font l'UI, les permissions, les APIs système et les `actual`.
5. **Le journal d'événements est la source de vérité** ; l'état courant est une projection ; rien n'est écrasé ; une migration SQL n'est jamais destructive.
6. **Les modèles IA sont embarqués** (EmbeddingGemma 2, variante selon la RAM) ; les APIs système (Foundation Models, Gemini Nano, ML Kit) sont des bonus derrière des interfaces, jamais une condition.
7. **Fonctionne sur iOS 16 / Android 10, 2–4 Go de RAM** : chaque fonction IA a un repli non-IA.
8. **Jamais de secret dans le dépôt** (keystore, certificats, clés d'API, poids de modèles).

## Pile technique
- `shared/` Kotlin Multiplatform : SQLDelight (SQLite, FTS5), kotlinx.serialization, kotlinx.coroutines, LiteRT (embeddings). `explicitApi()`.
- `androidApp/` Jetpack Compose, Material 3, minSdk 29, Glance (widgets), TileService, SpeechRecognizer on-device, Play Billing, Keystore.
- `iosApp/` SwiftUI, iOS 16, WidgetKit + App Intents, SFSpeechRecognizer on-device, StoreKit 2, Keychain.
- Tests : kotlin.test (commonTest), Compose tests, XCTest ; corpus rejoués par `tools/eval-parser` et `tools/eval-search`.

## Commandes
- Android/shared : `./gradlew :shared:allTests`, `./gradlew :androidApp:assembleDebug`, `./gradlew ktlintCheck detekt`
- iOS : `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 15' test`
- Qualité : `tools/check-deps.sh`, `tools/check-network.sh`, `./gradlew :tools:evalParser :tools:evalSearch`
- Avant tout commit : tests du module touché + lint ; avant toute PR : `/code-review`, puis agents `security-reviewer`, `privacy-auditor`, et `android-reviewer` / `ios-reviewer` selon les fichiers.

## Façon de travailler
- Suivre `docs/03-plan-de-lots.md` dans l'ordre ; un lot = une branche `lot/NN-nom`, une PR, le statut mis à jour dans le fichier.
- Pour une tâche d'un lot : lire les sections du cahier des charges et de l'architecture citées, écrire d'abord les tests (domaine, corpus), puis l'implémentation, puis l'UI.
- Ne pas inventer de fonctionnalité hors P0/P1 ; si quelque chose manque au cahier des charges, l'écrire dans `docs/04-decisions.md` comme question et demander.
- Toute décision structurante (bibliothèque, format, API) = une entrée ADR dans `docs/04-decisions.md`.
- Préférer les API stables aux nouveautés ; vérifier la disponibilité sur iOS 16 / API 29 avant d'utiliser une API.
- Commits : Conventional Commits, en anglais ; ne pas pousser sans y être invité.

## Skills du projet (`.claude/skills/`)
`local-first-data`, `kmp-core`, `android-ui`, `ios-ui`, `on-device-ml`, `capture-parser`, `privacy-rgpd`, `secure-mobile`, `testing-corpus`, `release-checklist`, `lot` (slash command `/lot NN` pour démarrer un lot). Les charger quand le sujet correspond.

## Agents du projet (`.claude/agents/`)
`security-reviewer`, `privacy-auditor`, `android-reviewer`, `ios-reviewer`, `search-quality-evaluator`. Les invoquer en fin de lot et avant toute PR.
