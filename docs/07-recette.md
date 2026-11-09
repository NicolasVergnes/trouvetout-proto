# Recette de la v1 — critères d'acceptation et vérifications restantes

Référence : `00-cahier-des-charges.md` §9, `02-securite-rgpd.md` §4, ADR-027. État au lot 12 (8 nov. 2026) : tout ce
qui se vérifie sans téléphone réel ni compte de magasin est automatisé en CI ; ce document liste, pour le reste, la
procédure à suivre et ce qui la bloque.

## 1. Critères et preuves automatisées

| Critère | Preuve automatisée (CI) | Reste à faire |
| --- | --- | --- |
| **U1** widget → phrase → fiche | `CaptureSmokeTest`, `QuickCaptureTest` (API 29, texte), `CaptureEntryTests`, `CaptureBridgeTests` (iOS) | Voix réelle sur un Android 12+ avec pack hors ligne et sur iPhone (§2) |
| **U3** « chargeur du Kindle » → « câble USB-C blanc », < 1 s hors ligne | `KeywordSearchTest`, `SearchServiceTest`, `TextModelTest` (API 29), `SearchModelTests` (iOS), `:tools:evalSearch` (latence p95 132 ms / 5 000 objets) | Latence sur Android 10 à 4 Go et iPhone 8 |
| **U5** prendre / remettre, historique sans doublon | `ProjectorTest`, `UndoServiceTest`, corpus `parser.jsonl` | — |
| **U6** prêt à une personne | `ParserTest`, `ProjectorTest` | — |
| **U7** rappel à une date | `ParserTest`, `CaptureServiceTest`, `RemindersTest`, `ReminderNotificationTest` (API 29), `RemindersTests`, `ReminderFlowTests` (iOS) | Notification réelle et toucher sur Android 12+ et iPhone, Doze, hibernation (§2) |
| **U8** export → réinstallation → import | `ExportImportTest`, `ZipInteropTest`, `NoNetworkTest` (aller-retour sur API 29) | Feuille de partage réelle sur téléphone |
| **U11** fin d'essai | `TrialPolicyTest`, `LicensingServiceTest`, `PurchaseTest` (API 29), `PurchaseTests`, `TrialEndedFlowTests` (iOS) | Achat réel et restauration sur un second appareil (§3) |
| **N1** capture < 3 s (récents), < 5 s ailleurs | `QuickCaptureTest.a_typed_capture_to_a_known_place_is_confirmed_within_the_N1_budget` (texte, < 5 s, API 29) | 3 s et voix sur iPhone 15 Pro et Pixel 9 |
| **N2** recherche < 1 s / 5 000 objets | `:tools:evalSearch` (job `search-eval`) | Mesure sur les téléphones de référence |
| **N3** 80 % top-1, 95 % top-3 (FR) | `:tools:evalSearch`, plancher `SearchCorpus.FLOORS` | **Top-3 FR 92,3 % pour 95 %** (lot 6, suite (a)) : ouvert |
| **N4** aucun réseau | `tools/check-network.sh`, `tools/check-deps.sh` (manifeste fusionné), `NoNetworkTest` (API 29 : `INTERNET` refusée, aucun octet), `tools/watch-ios-network.sh` (aucune socket observée sur simulateur), `tools/check-native-network.sh` | Proxy bloquant sur vrai téléphone, question au magasin comprise (§2) |
| **N9** un export v1 s'importe toujours | `ExportImportTest` (champs et types inconnus tolérés) | — |
| **I10** prise en main sans tutoriel bloquant | `WelcomeGuideTest`, `WelcomeTest` (API 29), `WelcomeFlowTests` (XCUITest, après une suppression totale) | — |
| **I6** accessibilité | `AccessibilityTest`, `AccessibilityComponentsTest` (ATF, réglages défilés compris), `AccessibilityAuditTests` (iOS, réglages défilés compris) | TalkBack et VoiceOver à la main sur téléphone |
| Fiches « aucune donnée collectée » | `store/play/data-safety.md`, `store/appstore/app-privacy.md` prêts | Saisie dans les consoles (§3) |
| Livraison Play < 200 Mo pour le module de base | `tools/check-bundle.sh` sur l'App Bundle de la CI | Installation depuis Play (piste de test interne) |
| Lot 7 : nom proposé dans le top 3 ≥ 70 % (100 photos maison) | — | `./gradlew :tools:evalPhotos -PevalArgs="--local <dossier>"` sur les photos de l'utilisateur |

## 2. Sur les téléphones de référence

Appareils : iPhone 8 (iOS 16), iPhone 15 Pro, Android 10 à 4 Go, Pixel 9 (§9). Installer le build de release (R8 pour
Android), données vierges, puis :

1. **U1 complet** : widget (et tuile / bouton Action) → dicter « passeport dans le tiroir du haut de la commode » →
   carte de confirmation ; chronométrer du geste à la carte (N1 : < 3 s sur les récents, < 5 s ailleurs). Android 10 :
   la tuile ouvre le clavier, avec l'explication.
2. **U3** en mode avion, modèle chargé : « le chargeur du Kindle » → réponse en une ligne ; mesurer la mémoire
   (Android Studio / Instruments : < 350 Mo texte seul, < 900 Mo texte+vision).
3. **U5, U6, U7** : prendre / remettre, prêter ; rappel « la veille du 12 mars » avancé à dans 5 minutes : la
   notification sonne téléphone en veille, son toucher ouvre la fiche après le déverrouillage. Android 12+ : un rappel
   à plus d'un mois propose une fois l'exemption d'hibernation ; vérifier avec
   `adb shell cmd app_hibernation set-state <paquet> true` puis `get-state`.
4. **U8** : exporter (feuille de partage réelle), désinstaller, réinstaller, importer : tout revient, photos comprises.
5. **Zéro réseau** : téléphone derrière un proxy bloquant (Android : `adb shell settings put global http_proxy
   127.0.0.1:1` ; iPhone : proxy HTTP manuel vers une adresse morte, ou mitmproxy en mode blocage) ; rejouer 1 à 4 ;
   aucune requête ne doit partir de l'app hormis l'interrogation du magasin, qui passe par l'app du magasin. Android :
   `adb shell dumpsys netstats detail` ne montre aucun octet pour l'uid de l'app.
6. **Verrouillage** : empreinte, visage, code ; objets sensibles masqués puis révélés ; vue de protection dans le
   sélecteur d'apps.
7. **Accessibilité** : parcours U1 et U3 avec TalkBack / VoiceOver, texte au plus grand.

## 3. Avec les comptes des magasins

Préalable : nom définitif et identifiants (ADR-010 ; `com.glopglopdev.finder` envisagé), site en ligne
(`site/README.md`), `{{PRIVACY_URL}}` et `{{SITE_URL}}` remplacés dans `store/`.

- **Google Play** : créer l'app avec le nom de paquet définitif ; produits `unlock_lifetime` et `subscription_yearly`
  (plan `yearly`) ; App Bundle signé (`./gradlew :androidApp:bundleRelease -Ptrouvetout.modelDelivery=pack
  -Ptrouvetout.playLicenseKey=<clé publique de la console>`) envoyé sur la piste de test interne ; achat réel de test
  sur ce build R8 (Play Billing sans sa télémétrie, acquittement), restauration sur un second appareil, remboursement ;
  fiche « Sécurité des données » selon `store/play/data-safety.md`.
- **App Store** : produits identiques ; `AppTransaction` réel ; questionnaire d'export et déclaration ANSSI (SQLCipher,
  AES-GCM, HKDF ; `store/appstore/app-privacy.md`) ; « Données non collectées » ; notes d'examen
  (`store/appstore/review-notes.md`) ; liens conditions et confidentialité sur l'écran d'achat.
- **RGPD** : relire les rapports que les consoles envoient (plantages, statistiques des magasins) ; avis humain sur la
  date d'essai conservée après la suppression totale (ADR-025).

## 4. Après publication

Installer depuis les magasins sur un appareil vierge : prise en main, U1, U3, U8 en conditions réelles, mode avion.
Surveiller les rapports de plantage des magasins (seule source de diagnostic) et l'adresse glopglopdev@gmail.com.
