---
paths:
  - "iosApp/**"
  - "shared/src/iosMain/**"
---
# Règles iOS

- Deployment target iOS 16 ; toute API plus récente derrière `if #available` avec repli (Foundation Models iOS 26 = bonus derrière une interface).
- SwiftUI ; `@MainActor` pour les vues et les view models ; concurrence structurée ; aucune logique métier en Swift (appeler le framework `Shared`).
- Adaptateur `Shared+Swift` : conversion `Flow` → `AsyncSequence` (via `SKIE` ou un wrapper maison), `KotlinThrowable` → `Error`, pas de `try!`.
- Fichiers : base et photos dans `Application Support/<bundle>/`, `isExcludedFromBackup = true`, protection `NSFileProtectionCompleteUntilFirstUserAuthentication` ; jamais dans la photothèque sans action explicite.
- Keychain : `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `kSecAttrSynchronizable = false` ; clé de base jamais exportée.
- Parole : `SFSpeechRecognizer` avec `requiresOnDeviceRecognition = true` ; vérifier `supportsOnDeviceRecognition` ; si faux, proposer la saisie texte ; jamais de reconnaissance serveur.
- App Intents (iOS 16) pour la capture, le bouton Action et les widgets ; WidgetKit écran d'accueil + verrouillé ; aucun objet `sensitive` dans les widgets ou les notifications.
- Info.plist : `NSMicrophoneUsageDescription`, `NSSpeechRecognitionUsageDescription`, `NSCameraUsageDescription` en FR/EN précis ; pas d'`NSAllowsArbitraryLoads` ; pas de localisation/contacts/calendrier en v1 (hook).
- StoreKit 2 : `Transaction.currentEntitlements`, `AppTransaction.shared` pour `originalPurchaseDate` (ancrage de l'essai), `AppStore.sync()` pour la restauration ; vérification locale (`VerificationResult`).
- Protection d'écran (H3) : vue de flou sur `scenePhase != .active` si activé.
- Accessibilité : labels VoiceOver, Dynamic Type sans troncature, contrastes AA, cibles ≥ 44 pt.
- Pas de `BGTaskScheduler` en v1 (N6) ; notifications locales via `UNUserNotificationCenter`.
- Charger le skill `ios-ui` pour les détails et les extraits de code.
