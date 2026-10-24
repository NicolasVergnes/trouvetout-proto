---
name: testing-corpus
description: Stratégie de tests de TrouveTout — tests de domaine et de projection (replay), corpus parser.jsonl et recherche.jsonl avec seuils, tests export/import, tests UI des parcours U1/U3/U8/U11, budgets de performance N1–N7, accessibilité, vérification zéro réseau. Utiliser pour écrire ou faire évoluer des tests, les outils tools/eval-*, ou la CI.
---

# Tests et corpus

## Pyramide
1. **Domaine (commonTest, rapide, JVM)** : use cases → événements attendus ; `Projector` ; `replay(events) == state` sur scénarios et par propriétés (kotest property ou générateur maison) ; fusion de journaux (commutativité, idempotence) même si la sync est en P3.
2. **Corpus (JVM, CI)** : `tools/eval-parser` (≥ 95 % intents, ≥ 90 % découpe) et `tools/eval-search` (≥ 80 % top-1, ≥ 95 % top-3, p95 < 1 s sur 5 000 fiches) ; les seuils sont des assertions.
3. **Intégration (JVM + instrumenté)** : SQLDelight réel (migrations, FTS), export → import identique, import malveillant refusé, suppression totale.
4. **UI (Compose test / XCUITest)** : parcours U1 (texte), U3, U8, U11 ; permission refusée ; widget sans donnée sensible ; mode simplifié ; Dynamic Type/fontScale max.
5. **Non fonctionnel** : perf (N1 audio préenregistré, N2 chrono, N7 jeu de 10 000 objets généré), mémoire (trace), batterie (aucun job de fond), réseau (`check-network`).

## Corpus
- Fichiers : `corpus/parser.jsonl`, `corpus/recherche.jsonl`, `corpus/fiches-reference.json` (jeu de ~400 fiches/60 lieux généré par `tools/gen-reference`, déterministe, seed fixe). Démarrer depuis `corpus/parser.seed.jsonl`.
- Chaque ligne : `id`, `lang`, `origin`, entrée, attendu, `kind` ; `xfail` optionnel avec raison.
- Règle : un bug = une ligne ajoutée ; un seuil ne baisse jamais.
- Rapports JSON dans `build/reports/eval/` ; la PR cite top-1/top-3/latence avant/après.

## Embedder en test
- `FakeEmbedder` déterministe (hachage de tokens → vecteur, synonymes « connus » mappés pour tester la fusion) pour les tests unitaires ; le vrai modèle texte seulement dans `tools/eval-search` et un test d'intégration marqué `@Slow`.

## Génération de volume (N7)
- `tools/gen-reference --items 10000 --events 100000` produit une base ; tests de temps : liste par lieu < 50 ms, recherche mot-clé < 100 ms, recherche hybride < 1 s, export < 30 s, import < 60 s.

## Audio préenregistré (N1)
- `corpus/audio/*.wav` (10 phrases FR, 5 EN, enregistrées par le développeur) injectées dans le test instrumenté via l'API de reconnaissance (Android : `SpeechRecognizer` ne prend pas de fichier → tester le pipeline après transcription + mesurer la transcription manuellement sur appareil ; iOS : `SFSpeechURLRecognitionRequest` avec `requiresOnDeviceRecognition`).

## CI
- Linux : `./gradlew :shared:allTests :tools:evalParser :tools:evalSearch :androidApp:assembleDebug ktlintCheck detekt tools:checkDeps`.
- macOS : `xcodebuild test` (unitaires + UI sur simulateur iPhone 15 et iPhone SE 3e gén. pour les petits écrans), SwiftLint.
- Artefacts : rapports d'évaluation, APK debug, résultats d'accessibilité.
- Un échec de seuil bloque la fusion.

## Accessibilité automatisée
- Android : `AccessibilityChecks.enable()` dans les tests Compose ; iOS : `XCUIApplication().performAccessibilityAudit()` (iOS 17+ simulateur) + revue manuelle VoiceOver sur U1/U3.

## Conventions
- Nom des tests : `should_<comportement>_when_<condition>` ; un fichier de test par classe de production ; données de test en FR et EN.
- Pas de `sleep` ; horloge injectée ; dispatchers de test.
