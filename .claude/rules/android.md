---
paths:
  - "androidApp/**"
  - "shared/src/androidMain/**"
---
# Règles Android

- `minSdk 29`, `targetSdk` = dernière stable ; toute API > 29 est protégée par `Build.VERSION.SDK_INT` et a un repli.
- Compose + Material 3 ; état remonté ; `ViewModel` par écran ; aucune logique métier dans les composables ni les ViewModels (appeler `shared`).
- Manifeste : `android:allowBackup="false"`, pas de `INTERNET` sans ADR (hook), `exported="false"` par défaut, `FLAG_SECURE` optionnel (H3).
- Fichiers : base et photos dans `context.noBackupFilesDir` ; jamais dans le stockage externe ni la galerie ; pas de `FileProvider` sur la base.
- Clés : `AndroidKeyStore`, AES-256-GCM, `setIsStrongBoxBacked(true)` avec repli ; jamais de clé en dur.
- Parole : `SpeechRecognizer` avec `EXTRA_PREFER_OFFLINE=true` ; vérifier la disponibilité hors ligne avant d'écouter ; si indisponible, proposer la saisie texte ; ne jamais utiliser l'intent `RecognizerIntent` réseau.
- Widgets Glance / `TileService` : contenu réduit à l'action « capturer » et aux objets non sensibles ; jamais d'objet `sensitive`.
- Notifications : canal dédié, contenu sans objet sensible ; `POST_NOTIFICATIONS` demandé au premier rappel, pas au lancement.
- Permissions demandées au moment de l'usage avec une explication dans l'UI (H9) ; l'app reste utilisable si refusées.
- Play Billing : `BillingClient` v6+, vérification de signature locale, `queryPurchasesAsync` au démarrage pour la restauration ; aucun serveur.
- Pas de `WorkManager`/`JobScheduler`/services au premier plan en v1 (N6) hormis les notifications locales programmées par `AlarmManager` inexact.
- Accessibilité : `contentDescription`, tailles ≥ 48 dp, support du texte agrandi, TalkBack testé.
- Charger le skill `android-ui` pour les détails et les extraits de code.
