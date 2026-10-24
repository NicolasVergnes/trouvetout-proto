---
name: android-ui
description: Bonnes pratiques Android pour TrouveTout — Jetpack Compose/Material 3, capture vocale hors ligne, widgets Glance et tuile, permissions au moment de l'usage, Keystore, Play Billing, notifications locales, accessibilité, minSdk 29. Utiliser pour tout fichier de androidApp/ ou shared/src/androidMain.
---

# Android — patrons à suivre

Règles résumées dans `.claude/rules/android.md`. Ici : comment faire.

## Structure de `androidApp`
```
androidApp/src/main/
  AndroidManifest.xml
  java/<pkg>/
    App.kt                      # initialisation DI, logger release
    MainActivity.kt             # NavHost Compose
    ui/capture/  ui/search/  ui/places/  ui/item/  ui/settings/  ui/theme/
    capture/CaptureActivity.kt  # activité transparente lancée par widget/tuile
    capture/SpeechController.kt # SpeechRecognizer hors ligne
    widget/CaptureWidget.kt     # Glance
    widget/CaptureTileService.kt
    platform/                   # actual : FileSystem, SecureStore, DeviceInfo, LiteRT embedder
    billing/PlayPurchaseGateway.kt
    notif/ReminderScheduler.kt
```

## Compose
- Écran = `@Composable fun XScreen(state: XUiState, onEvent: (XEvent) -> Unit)` + `XRoute` qui branche le `ViewModel`. Les composables purs sont prévisualisables.
- `ViewModel` : `StateFlow<XUiState>` ; appelle les use cases de `shared` ; aucune règle métier.
- Material 3, thème dynamique (`dynamicColorScheme` API 31+, repli palette statique), mode sombre système (I8).
- Mode simplifié (I7) = même NavHost, écran d'accueil alternatif à deux boutons, typographie agrandie.
- Pas de bibliothèque d'images lourde si Coil suffit ; images chargées depuis le conteneur privé via `File`.

## Capture vocale hors ligne (B2)
```kotlin
val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context) // API 31+
val recognizer = if (available) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                 else SpeechRecognizer.createSpeechRecognizer(context)
val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
  putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
  putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
  putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)   // API 23+
  putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
}
```
- Sur API 29–30, `EXTRA_PREFER_OFFLINE` suffit mais ne garantit pas le hors ligne : tester en mode avion au premier lancement et mémoriser le résultat ; si la reconnaissance échoue hors ligne, basculer sur la saisie texte avec une explication (jamais de reconnaissance réseau).
- Le texte transcrit part dans `shared.capture.Parser` ; l'audio n'est jamais stocké.
- Repli Whisper (pack optionnel) seulement si la mesure du lot 5 montre un taux d'indisponibilité élevé.

## Point d'entrée en un geste (B5)
- Widget Glance : un bouton micro + un bouton texte → `CaptureActivity` (`Theme.Transparent`, `excludeFromRecents`, `showWhenLocked=false`). Pas de contenu d'inventaire dans le widget.
- `TileService` (API 24+) : `onClick` → `startActivityAndCollapse` vers `CaptureActivity`.
- Carte de confirmation (B6) : `Snackbar`/bottom sheet 3 s avec « Modifier » et « Annuler » ; l'événement est déjà enregistré, « Annuler » émet l'événement inverse.

## Permissions (H9)
- `RECORD_AUDIO` demandé au premier tap sur le micro, avec une phrase d'explication dans l'UI avant la boîte système ; `CAMERA` idem ; `POST_NOTIFICATIONS` (API 33+) au premier rappel.
- Aucune autre permission en v1. `INTERNET` absent du manifeste tant que le pack passe par Play Asset Delivery.

## Stockage et clés
- `context.noBackupFilesDir` pour `db/` et `photos/` ; `allowBackup=false`.
- Clé de base : `KeyGenParameterSpec` AES/GCM/NoPadding 256, `setIsStrongBoxBacked(true)` avec repli `StrongBoxUnavailableException`, `setUserAuthenticationRequired(false)`.
- `EncryptedSharedPreferences` pour `trialStart`, `deviceId` ; **ce fichier-là** est inclus dans Auto Backup (clé/valeur) pour survivre à la réinstallation ; rien d'autre.

## Play Billing (J5)
- `BillingClient` avec `enablePendingPurchases` ; au démarrage `queryPurchasesAsync(INAPP)` et `(SUBS)` → `purchased` ; `acknowledgePurchase` obligatoire ; vérification de la signature avec la clé publique de l'app (hors serveur).
- `PlayPurchaseGateway : PurchaseGateway` injecté dans `shared.licensing`.

## Notifications locales (E2, E6)
- `AlarmManager.setAndAllowWhileIdle` (inexact) + `BroadcastReceiver` ; reprogrammation après `BOOT_COMPLETED` (permission `RECEIVE_BOOT_COMPLETED`, normale).
- Contenu : « Rappel : passeport » + action « Voir » ; jamais le lieu ni un objet `sensitive` dans la notification.

## Accessibilité (I6)
- `Modifier.semantics { contentDescription = … }` sur icônes ; `Modifier.minimumInteractiveComponentSize()` ; `fontScale` jusqu'à 2.0 testé ; TalkBack sur U1 et U3 ; `Accessibility Scanner` sans erreur.

## Tests
- `androidTest` Compose : parcours U1 (texte), U3, U8, U11 ; test de permission refusée ; test que le widget ne contient aucun texte d'inventaire.
- `tools/check-network.sh` : lance l'app en débogage avec un proxy bloquant (ou `adb shell settings put global http_proxy`) et vérifie zéro connexion (`adb shell dumpsys netstats` / logs `NetworkSecurityConfig`).
