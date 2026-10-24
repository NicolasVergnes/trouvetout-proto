# Conventions de développement

## Langue
- Code, identifiants, commits, commentaires techniques : **anglais**.
- Documentation produit (`docs/`), chaînes utilisateur : **français et anglais** (FR source, EN traduit, les deux testés).
- Les noms de domaine métier sont fixés : `Item` (objet), `Place` (lieu), `Container` (contenant déplaçable), `Event` (événement du journal), `LocationState`, `Reminder`, `Photo`, `Embedding`, `TrialPolicy`, `PurchaseGateway`.

## Kotlin (shared, androidApp)
- Kotlin stable courant, `explicitApi()` activé dans `shared` : tout ce qui est public est volontairement public.
- Coroutines : `suspend` pour les opérations ponctuelles, `Flow` pour les observations ; jamais de `GlobalScope` ; dispatchers injectés.
- Immutabilité par défaut (`val`, `data class`, collections immuables). Les entités de domaine ne dépendent d'aucune bibliothèque de plateforme.
- `expect/actual` uniquement dans `shared/platform` ; une interface commune + une implémentation par plateforme quand c'est plus clair.
- Erreurs : types de résultat explicites (`sealed interface`) pour les cas métier ; exceptions seulement pour les bugs.
- Pas de réflexion, pas de code généré à l'exécution.
- Style : ktlint (officiel) + detekt avec la config du dépôt ; 0 warning en CI.
- Compose : `@Composable` sans effet de bord hors `LaunchedEffect`/`remember` ; état remonté (state hoisting) ; prévisualisations `@Preview` pour chaque écran ; `Modifier` en premier paramètre optionnel.
- Android : `minSdk 29`, `targetSdk` = dernière stable ; pas de `INTERNET` sans justification écrite dans l'ADR ; `allowBackup=false` pour le conteneur principal (sauvegarde gérée par A9 uniquement).

## Swift (iosApp)
- Swift 5.9+, SwiftUI, concurrence structurée (`async/await`, `@MainActor` pour l'UI).
- Le framework KMP est consommé via un fin adaptateur Swift (`Shared+Swift.swift`) qui convertit `Flow` en `AsyncSequence` et les types KMP en types Swift idiomatiques ; aucune logique métier en Swift.
- SwiftLint + SwiftFormat avec la config du dépôt ; 0 warning en CI.
- Deployment target iOS 16 ; `@available` pour les API plus récentes (App Intents iOS 16 ok ; Foundation Models iOS 26 derrière une interface optionnelle).
- Pas d'`UIKit` sauf nécessité (impression, partage, protection d'écran).
- Le projet Xcode est généré par XcodeGen (`iosApp/project.yml`, ADR-012) : les paquets SPM y sont déclarés avec `exactVersion:` ; ce fichier, les `.xcconfig` et les `.entitlements` sont scannés par les hooks et `tools/check-deps.sh` comme des fichiers de build. `ENABLE_USER_SCRIPT_SANDBOXING=NO` est requis par le script Gradle de pré-build (portée build uniquement).

## Tests
- `shared/commonTest` : domaine, journal, projection, parseur, recherche (avec un `TextEmbedder` factice déterministe + un test d'intégration avec le vrai modèle sur JVM).
- Corpus : `corpus/parser.jsonl`, `corpus/recherche.jsonl` rejoués par `tools/eval-*` ; les seuils sont des tests qui échouent.
- UI : tests Compose (androidTest) et XCTest UI pour les parcours U1, U3, U8, U11 ; tests d'accessibilité automatisés.
- Aucune dépendance réseau dans les tests.

## Git
- Branche principale `main` protégée ; une branche par lot `lot/NN-nom` ; PR obligatoire avec CI verte.
- Commits : Conventional Commits (`feat(capture): …`, `fix(search): …`, `docs: …`, `test: …`, `chore: …`), message en anglais, corps expliquant le pourquoi.
- Pas de secrets, de keystore, de profil de provisionnement, de poids de modèle dans le dépôt (`.gitignore`).
- Le fichier `docs/03-plan-de-lots.md` est mis à jour dans la PR qui termine un lot.

## Revue
- Chaque PR passe : `/code-review` de Claude Code, puis les agents du projet `security-reviewer` et `privacy-auditor`, et `android-reviewer` ou `ios-reviewer` selon les fichiers touchés.
- Une objection bloquante = la PR ne se merge pas.

## Journalisation
- Logger commun `shared/log` avec niveaux ; en release, niveau `warn` minimum et aucune donnée métier (noms d'objets, lieux, phrases dictées) à aucun niveau. Utiliser des identifiants.
- Même règle pour les messages d'exception produits par `shared` (identifiants et compteurs seulement) : ils remontent dans les logs de debug et dans les erreurs Swift.
- Outillage : detekt interdit l'import d'`android.util.Log` hors `LogcatLogger` ; SwiftLint interdit `print`/`NSLog`/`os_log` dans `iosApp/iosApp`.

## Chaînes et i18n
- Clés de chaînes stables, `snake_case`, préfixées par écran (`capture_confirm_title`).
- Dates relatives via les formatters système ; pas de concaténation de chaînes traduites.
- Le parseur et les synonymes ont un fichier par langue (`capture/rules_fr.kt`, `rules_en.kt`, `search/synonyms_fr.txt`, `synonyms_en.txt`).
