---
name: ios-ui
description: Bonnes pratiques iOS pour TrouveTout — SwiftUI, App Intents et WidgetKit (écran verrouillé, bouton Action), SFSpeechRecognizer sur l'appareil, Keychain, StoreKit 2 et AppTransaction, notifications locales, accessibilité, iOS 16. Utiliser pour tout fichier de iosApp/ ou shared/src/iosMain.
---

# iOS — patrons à suivre

Règles résumées dans `.claude/rules/ios.md`. Ici : comment faire.

## Structure de `iosApp`
```
iosApp/
  iosApp.xcodeproj
  iosApp/
    App.swift                      # @main, injection du framework Shared
    Shared+Swift.swift             # adaptateurs Flow→AsyncSequence, erreurs
    Features/Capture/ Search/ Places/ Item/ Settings/
    Capture/SpeechController.swift # SFSpeechRecognizer on-device
    Intents/CaptureIntent.swift    # AppIntent (bouton Action, Raccourcis, widgets)
    Platform/                      # FileSystem, Keychain, DeviceInfo, LiteRT embedder (Swift)
    Billing/StoreKitPurchaseGateway.swift
    Notifications/ReminderScheduler.swift
  CaptureWidget/                   # extension WidgetKit (home + lock screen)
```

## SwiftUI
- Vue = fonction de l'état ; `@Observable` (iOS 17) n'est pas disponible sur iOS 16 → `ObservableObject` + `@Published`, `@MainActor`.
- Un `ViewModel` par écran qui appelle les use cases Kotlin via l'adaptateur ; aucune règle métier en Swift.
- Thème système (I8) automatique ; mode simplifié (I7) = écran d'accueil alternatif deux boutons, `.dynamicTypeSize(.accessibility3)` testé.
- Images depuis le conteneur privé (`UIImage(contentsOfFile:)`) avec miniatures ; pas de `PHPhotoLibrary` sauf import explicite.

## Transcription sur l'appareil (B2)
```swift
let recognizer = SFSpeechRecognizer(locale: locale)
guard let recognizer, recognizer.supportsOnDeviceRecognition else { /* proposer la saisie texte */ }
let request = SFSpeechAudioBufferRecognitionRequest()
request.requiresOnDeviceRecognition = true
request.shouldReportPartialResults = true
```
- `AVAudioSession` catégorie `.record`, mode `.measurement` ; arrêt sur silence (≈ 1,2 s) ou bouton.
- Le texte part dans `Shared.Parser` ; l'audio n'est jamais écrit sur disque.
- Si `supportsOnDeviceRecognition == false` (langue non téléchargée, appareil ancien) : afficher un message et la saisie texte ; ne jamais retirer `requiresOnDeviceRecognition`.

## App Intents, bouton Action, widgets (B5)
- `CaptureIntent: AppIntent` avec `openAppWhenRun = true` (la reconnaissance vocale nécessite le premier plan) et un paramètre `mode: voice|text`.
- Bouton Action (iPhone 15 Pro+) et Raccourcis utilisent le même intent ; `AppShortcutsProvider` pour l'exposer avec des phrases FR/EN.
- WidgetKit : `accessoryCircular`/`accessoryRectangular` (écran verrouillé, iOS 16) et `systemSmall` ; contenu = bouton capture uniquement ; `widgetURL` → `trouvetout-proto://capture?mode=voice` (schéma interne neutre, ADR-010).
- Carte de confirmation (B6) : bannière 3 s en haut avec « Modifier » / « Annuler » ; l'événement est déjà enregistré.

## Fichiers, Keychain, protection
- `FileManager.urls(for: .applicationSupportDirectory)` + sous-dossier bundle ; `var rv = URLResourceValues(); rv.isExcludedFromBackup = true`.
- Protection fichier : `.completeUntilFirstUserAuthentication` (la base doit être lisible pour les notifications).
- Keychain : `SecItemAdd` avec `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `kSecAttrSynchronizable: false` ; clé AES-256 générée via `SecRandomCopyBytes` ; `trialStart` et `deviceId` dans le Keychain (survivent à la réinstallation).
- H3 : `ScenePhase` → overlay flou quand inactif ; `LAContext` (`.deviceOwnerAuthenticationWithBiometrics`, repli code) au retour au premier plan après délai.

## StoreKit 2 (J4, J5)
```swift
// Ancrage de l'essai
let result = try await AppTransaction.shared
if case .verified(let tx) = result { trialStart = tx.originalPurchaseDate }
// Droits
for await result in Transaction.currentEntitlements { if case .verified(let tx) = result { purchased = true } }
// Restauration
try await AppStore.sync()
```
- `StoreKitPurchaseGateway: PurchaseGateway` injecté dans `Shared.licensing` ; produit non consommable **ou** abonnement annuel selon la décision finale ; `Transaction.updates` écouté au lancement ; `finish()` après traitement.

## Notifications locales (E2, E6)
- `UNUserNotificationCenter` ; autorisation demandée au premier rappel ; `UNCalendarNotificationTrigger` ; contenu sans lieu ni objet `sensitive` ; `threadIdentifier` par objet.

## Accessibilité (I6)
- `.accessibilityLabel`, `.accessibilityHint` sur les boutons icônes ; cibles ≥ 44 pt ; Dynamic Type jusqu'à `accessibility5` sans troncature ; VoiceOver sur U1/U3 ; Accessibility Inspector sans erreur.

## Tests
- XCTest unitaires sur l'adaptateur Swift et les contrôleurs ; XCUITest pour U1 (texte), U3, U8, U11 ; test que l'extension widget ne lit aucun objet `sensitive`.
- `tools/check-network.sh` (macOS) : simulateur + proxy (`mitmproxy` ou `Network Link Conditioner` 100 % perte) → zéro requête hors achats/packs.
