# Plan de lots — v1 (P0 + P1 d'un seul tenant)

Le périmètre v1 est livré en une seule version, mais construit par lots ordonnés : chaque lot laisse le dépôt dans un état compilable et testé sur les deux plateformes. Un lot n'est « terminé » que si ses critères passent en CI et que les trois revues (sécurité, Android, iOS) n'ont pas d'objection bloquante.

Statut à tenir à jour dans ce fichier (☐ à faire, ◐ en cours, ☑ terminé).

## Lot 0 — Fondations du dépôt ☑
- Monorepo KMP : `shared` (commonMain/androidMain/iosMain), `androidApp` (Compose, minSdk 29, targetSdk courant), `iosApp` (SwiftUI, iOS 16, framework KMP via SPM/CocoaPods ou embedAndSignAppleFrameworkForXcode).
- Versions épinglées (`libs.versions.toml`), ktlint/detekt, SwiftLint, SwiftFormat, `.editorconfig`.
- CI (GitHub Actions ou équivalent) : `./gradlew :shared:allTests :androidApp:assembleDebug` sur Linux ; `xcodebuild test` sur macOS.
- Scripts `tools/check-deps.sh` (dépendances interdites, permissions) et `tools/check-network.sh` (squelette).
- Critères : les deux apps démarrent sur un écran vide ; CI verte ; hooks Claude Code actifs.

## Lot 1 — Socle de données (A1–A4, A6, A10, N9) ☑
- Entités, journal d'événements, projection `location_state`, SQLDelight, migrations, `meta`.
- Vérification des dépendances Gradle (`gradle/verification-metadata.xml`) : recommandée par la revue sécurité du lot 0, **reportée** — un fichier généré sous Windows ne couvre pas les artefacts Kotlin/Native résolus seulement sur macOS et ferait échouer le job iOS ; voir la question ouverte dans `docs/04-decisions.md`.
- Stockage des photos (compression, miniatures, exclusion des sauvegardes système).
- `docs/format-export.md` : schéma SQL et JSON publiés.
- Tests : `replay(events) == state` sur 50 scénarios ; propriétés (un item a toujours un état) ; performance N7 (10 000 objets / 100 000 événements) en test JVM.
- Critères : couverture des use cases Store/Take/Return/MoveTo/Lend ; aucune requête SQL non paramétrée.

## Lot 2 — Export / import (A7, A8, A13 corbeille, H6 suppression) ◐
- ZIP `manifest + events.jsonl + snapshot.json + photos/`, feuille de partage système ; import en flux avec validations (taille, chemins, zip bomb) ; fusion par `id` d'événement.
- Corbeille 30 jours (purge des photos), annulation de la dernière action (événement inverse).
- Suppression totale : fichiers (base + `-wal`/`-shm`, `photos/`, `exports/`, `imports/`) ; l'instance `SharedFactory` est épuisée après `wipeAllData()` et l'hôte en recrée une. Clés (lot 8), préférences et notifications (lot 9) s'ajouteront à la procédure quand ils existeront.
- Vérification des sommes des dépendances Gradle (`gradle/verification-metadata.xml`) générée par la CI Linux, stricte sur Linux, `lenient` sur macOS — voir la question ouverte de `docs/04-decisions.md`.
- Critères : export → suppression totale → import = état identique (test automatisé sur les deux plateformes) ; un export v1 fabriqué à la main avec des champs inconnus s'importe (N9).
- Suites consignées par les revues du lot : (a) inflater `events.jsonl` ligne à ligne au lieu d'une entrée en mémoire (plafond 64 Mio en attendant) ; (b) ZIP64 ou export multi-volumes avant le lot 7 (les photos pleine taille peuvent dépasser 4 Gio) ; (c) tests Compose de l'écran Réglages (`busy`, confirmation d'effacement, textes de refus) et aller-retour export → effacement → import sur appareil Android (avec les tests de plateforme du lot 4) ; (d) tests manquants : `EVENTS_TOO_LARGE`, `TOO_MANY_EVENTS`, photo > 48 Mio, ligne > 64 Kio ; (e) `viewModel {}` hors NavHost : le NavHost du lot 3 doit porter le ViewModel des réglages (état consommé en événement unique).

## Lot 3 — Capture texte et parseur (B4, B7, B11, B12, B14, A14, I4) ☐
- `shared/capture` : normalisation, intents FR/EN, extraction objet/lieu/quantité/personne, résolution de lieu par similarité (distance d'édition d'abord ; embeddings branchés au lot 6), alias, multi-objets.
- Détection de doublons à la saisie (A14) par similarité de nom.
- UI minimale de capture texte (Compose + SwiftUI) avec autocomplétion des lieux et carte de confirmation (B6) ; désambiguïsation (B7).
- Corpus `corpus/parser.jsonl` (≥ 150 phrases FR, ≥ 60 EN) et test de non-régression : ≥ 95 % d'intents corrects, ≥ 90 % de découpe objet/lieu exacte.
- Critères : U1 en texte, U5, U6 passent ; seuils du corpus atteints.

## Lot 4 — Lieux et navigation (A2, A3, D1, D2, D4, D5, D6, C6, C7) ☐
- Arborescence des lieux, contenants déplaçables (déplacement = un événement), modèles de lieux proposés, photo du lieu, renommage/fusion sans perte d'historique.
- Écrans : navigation par lieu, fil d'Ariane, fiche objet (photos, tags A11, quantités A12, historique optionnel A5, archivage D11, épinglage D7 en option).
- Tests de plateforme Android (revue du lot 1) : `withDeviceTest` dans `shared` ou `androidTest` dans l'app, exécutés sur un Gradle Managed Device API 29 en CI — `AndroidDriverFactory` (fichier sous `no_backup/db`, WAL), `AndroidImageCodec` (orientation EXIF 6, PNG avec alpha, HEIC), smoke test de la release ; activer `lint { checkDependencies = true }` côté app pour couvrir `shared/androidMain` ; message « format non pris en charge » quand le codec renvoie null.
- Critères : U2 (photo attachée à un contenant) ; déplacer un carton met à jour tous ses objets en un événement ; fusion de deux lieux conserve tous les événements.

## Lot 5 — Capture vocale et points d'entrée (B1, B2, B5, B6, B10, C5, N1) ☐
- Transcription sur l'appareil (SFSpeech on-device / SpeechRecognizer offline), détection d'indisponibilité, repli texte ; pack Whisper optionnel seulement si nécessaire après mesure.
- Widgets écran d'accueil et verrouillé, bouton Action (App Intent), tuile Android, activité/scène de capture transparente.
- Mode « je range » par lot (B10). Question vs affirmation (C5) : « où est… » déclenche la recherche.
- Mesure N1 sur appareils de référence (test instrumenté avec audio préenregistré).
- Critères : U1 complet (widget → voix → fiche) ; aucun flux audio ne quitte l'appareil (vérifié par `check-network`).

## Lot 6 — Recherche hybride et modèle embarqué (C1–C4, C11, C12, N2, N3) ☐
- FTS5 tolérant, synonymes FR/EN, `TextEmbedder` (interface) + implémentation EmbeddingGemma 2 texte via LiteRT sur les deux plateformes, `ModelSelector` (RAM), pack à la demande avec SHA-256, réindexation sur changement de modèle.
- Fusion RRF + récence/fréquence ; réponse en une ligne (C4) ; filtres (C11) ; suggestions (C12, sans objets sensibles).
- `corpus/recherche.jsonl` (300 questions) et `tools/eval-search` en CI : 80 % top-1, 95 % top-3, < 1 s / 5 000 objets.
- Critères : U3 ; recherche utilisable sans pack (mot-clé) avec message explicite ; mémoire < 350 Mo en texte seul.

## Lot 7 — Variante omnimodale et photos (B8, C10, N5) ☐
- `ImageEmbedder` EmbeddingGemma 2 omnimodal pour les appareils ≥ 6 Go ; embedding des photos à l'ajout ; recherche par photo (option) ; nommage par similarité avec `labels_fr_en.txt` (~2 000 libellés).
- Réglage « forcer texte seul » ; réindexation ; budgets mémoire (< 900 Mo pic).
- Critères : sur Pixel 9 / iPhone 15 Pro, photographier un objet courant propose le bon libellé dans le top 3 ≥ 70 % des cas sur un jeu de 100 photos maison ; sur un appareil 4 Go, la variante texte seul est choisie automatiquement.

## Lot 8 — Vie privée, sécurité, accessibilité (H1–H4, H9, I6, I7, I8, N6) ☐
- SQLCipher + clés Keystore/Keychain (H2) avec migration depuis la base en clair du lot 1 ; verrouillage biométrique et masquage (H3) ; objets sensibles (H4) ; permissions au premier usage avec explication (H9).
- Accessibilité complète (VoiceOver/TalkBack, Dynamic Type, contrastes), mode simplifié deux boutons (I7), thème système (I8).
- Audit batterie : aucun travail en arrière-plan (N6).
- Réévaluer `ITSAppUsesNonExemptEncryption` (iOS) et `PrivacyInfo.xcprivacy` quand SQLCipher et les APIs à raison déclarée (UserDefaults, horodatages de fichiers) arrivent ; consigner la réponse au questionnaire d'export dans l'ADR du lot.
- Critères : liste de contrôle `02-securite-rgpd.md §4` entièrement verte ; audit d'accessibilité (Accessibility Scanner / Xcode Accessibility Inspector) sans erreur bloquante.

## Lot 9 — Rappels et impression (E2, E6, C16) ☐
- Notifications locales : rappel à une date (depuis la fiche ou la phrase « rappelle-moi… »), rappel de vérification optionnel tous les 6 mois pour les objets marqués critiques.
- Vue imprimable PDF d'un contenant ou d'un lieu (C16) via les API d'impression natives.
- Critères : U7 ; les notifications n'affichent jamais un objet sensible.

## Lot 10 — Essai, achats, sauvegarde (J1, J4, J5, A9) ☐
- `TrialPolicy` (6 mois), ancrage iOS `AppTransaction.originalPurchaseDate` / Android date locale + Auto Backup, écran d'échéance non bloquant, blocage des seuls ajouts.
- `PurchaseGateway` StoreKit 2 / Play Billing, restauration, vérification locale ; produit retenu (10 € unique ou 2 €/an) configuré dans les consoles.
- Sauvegarde chiffrée optionnelle (A9) dans le dossier app iCloud Drive / Google Drive : chiffrement AES-GCM avec clé locale, restauration depuis les réglages.
- Critères : U11 ; après échéance, tout fonctionne sauf « ajouter » ; restauration d'achat sur un second appareil de test.

## Lot 11 — Réponse générative optionnelle (C13) ☐ (dernier ; peut basculer en P2)
- Pack « LLM » optionnel (Qwen3-0.6B ou Gemma 3 1B via LiteRT-LM), interface `Rephraser`, usage limité à la reformulation d'un résultat trouvé et aux questions composées sur un sous-ensemble de fiches.
- Critères : jamais sur le chemin critique ; désactivable ; si latence > 3 s ou mémoire > budget sur les appareils de référence, le lot est reporté sans impact.

## Lot 12 — Finition et publication (I10, J7, N10) ☐
- Prise en main sans tutoriel bloquant ; page web statique (présentation, politique de confidentialité, format d'export) ; fiches des magasins « aucune donnée collectée » ; captures ; `tools/check-*` en CI ; tests d'accessibilité automatisés.
- Critères d'acceptation globaux de `00-cahier-des-charges.md §9`.

## Dépendances entre lots

```
0 → 1 → 2
      1 → 3 → 5
      1 → 4
      3,4 → 6 → 7
      1..7 → 8 → 9 → 10 → 11 → 12
```

Les lots 3 et 4 peuvent avancer en parallèle (branches distinctes), de même que 9 et 10.
