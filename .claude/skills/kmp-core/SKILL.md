---
name: kmp-core
description: Structure et conventions du module Kotlin Multiplatform `shared` de TrouveTout — arborescence, expect/actual, injection, coroutines, exposition au Swift, build Gradle et framework iOS. Utiliser pour créer ou modifier des modules, des interfaces plateforme, la configuration Gradle/Xcode du noyau.
---

# Noyau Kotlin Multiplatform

## Arborescence cible
```
shared/
  build.gradle.kts            # kotlin("multiplatform"), android library, sqldelight, serialization
  src/commonMain/kotlin/<pkg>/
    domain/   model/, usecase/, policy/ (TrialPolicy)
    data/     db/ (SQLDelight), projection/, repository/, export/
    capture/  Parser, Intent, rules_fr.kt, rules_en.kt, PlaceResolver
    search/   Searcher, KeywordIndex, SemanticIndex, Fusion, synonyms
    ml/       TextEmbedder, ImageEmbedder, Rephraser (interfaces), ModelSelector, ModelPackManager
    licensing/ PurchaseGateway (interface), TrialPolicy
    platform/ expect : FileSystem, Clock, DeviceInfo (ram), SecureStore, Hasher, ZipIo
    log/      Logger
    di/       SharedModule (Koin) ou SharedFactory
  src/androidMain/kotlin/<pkg>/platform/   actual Android + LiteRT Android
  src/iosMain/kotlin/<pkg>/platform/       actual iOS (NSFileManager, Security, LiteRT iOS via cinterop ou pont Swift)
  src/commonTest/                          tests domaine, projection, parseur, recherche (embedder factice)
  src/androidUnitTest/, src/iosTest/
```
Package racine : un nom de code neutre (ex. `app.trouvetout.core`), à ne pas confondre avec le nom de marque (ADR-010).

## expect/actual — ce qui est plateforme
| Interface | Android | iOS |
| --- | --- | --- |
| `FileSystem` (répertoires privés non sauvegardés, lecture/écriture atomique) | `Context.noBackupFilesDir` | `Application Support` + `isExcludedFromBackup` |
| `DeviceInfo.totalRamBytes` | `ActivityManager.getMemoryInfo` | `NSProcessInfo.physicalMemory` |
| `SecureStore` (clé de base, trialStart) | Keystore + EncryptedSharedPreferences | Keychain |
| `Hasher.sha256` | `MessageDigest` | CommonCrypto |
| `ZipIo` | `java.util.zip` | `libarchive`/`minizip` via cinterop ou pont Swift (`ZIPFoundation`) |
| `SpeechRecognizer` | n'est **pas** dans shared : l'UI native transcrit et passe le texte au `Parser` | idem |
| `TextEmbedder`/`ImageEmbedder` | LiteRT Kotlin | LiteRT Swift (pont) ou cinterop C |
| `PurchaseGateway` | Play Billing (dans androidApp, injecté) | StoreKit 2 (dans iosApp, injecté) |

Préférer **interface commune + implémentation injectée depuis l'app** à `expect/actual` quand l'implémentation dépend d'un SDK d'app (Billing, StoreKit, LiteRT) : cela garde `shared` compilable sans ces SDK en test.

## Exposition à Swift
- Framework `Shared` statique, `-Xbinary=bundleId`, export des seuls packages publics ; `explicitApi()`.
- Utiliser **SKIE** (ou un wrapper manuel) pour que `Flow` devienne `AsyncSequence`, `sealed class` devienne `enum` Swift, `suspend` devienne `async`.
- Éviter les génériques complexes et les `value class` dans l'API publique ; exposer des types simples (data classes, listes, enums).
- Un fichier `Shared+Swift.swift` dans `iosApp` centralise les conversions.

## Concurrence
- `CoroutineDispatchers(io, default, main)` injecté ; aucune référence à `Dispatchers.Main` dans `shared` hors `di`.
- Les repositories exposent `Flow` ; les use cases sont `suspend` ; pas de `runBlocking` en production.

## Gradle
- `gradle/libs.versions.toml` : toutes les versions ; `kotlin`, `agp`, `sqldelight`, `kotlinx-*`, `litert` épinglés.
- `shared` : `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()` (+ `iosX64` si CI Intel), `jvm()` optionnel pour `tools/eval-*`.
- Tests JVM rapides en CI Linux ; tests iOS sur macOS.
- `tools/` est un module JVM qui dépend de `shared` (cible `jvm()`) pour rejouer les corpus avec le vrai modèle texte (LiteRT JVM ou ONNX Runtime en repli).

## Qualité
- ktlint + detekt (config dans `config/detekt/detekt.yml`) ; `allWarningsAsErrors` sur `shared`.
- Un `README.md` par sous-package de `shared` expliquant les invariants en 5 lignes.

## Pièges connus
- `kotlinx-datetime` : `Clock` injecté pour les tests ; stocker des `Instant` en epoch millis UTC.
- ULID : implémentation commune (pas de dépendance JVM-only).
- SQLDelight native : `NativeSqliteDriver` avec `maxReaderConnections` ≥ 2 pour les `Flow`.
- Ne pas exposer `kotlinx.serialization` `JsonElement` à Swift.
