# Sécurité et RGPD — exigences et liste de contrôle

Objectif : que la fiche « App Privacy » (Apple) et « Data safety » (Google) puissent déclarer **« aucune donnée collectée »** honnêtement, et que l'app résiste aux menaces réalistes pour un inventaire domestique (vol du téléphone, curieux du foyer, app tierce malveillante).

## 1. Position RGPD

- TrouveTout ne traite **aucune donnée personnelle côté éditeur** : pas de serveur, pas de compte, pas de télémétrie, pas d'identifiant publicitaire. L'éditeur n'est pas responsable de traitement pour les données saisies, qui restent sous le contrôle exclusif de l'utilisateur sur son appareil.
- Conséquences pratiques : pas de bannière de consentement, pas de registre de traitement pour l'app elle-même, mais une **politique de confidentialité** publique (obligatoire sur les deux magasins) qui dit exactement cela et décrit les seuls flux réseau : achats via les magasins (données gérées par Apple/Google), sauvegarde optionnelle A9 dans l'espace cloud **de l'utilisateur** (chiffrée par l'app avant envoi). Le modèle de recherche est livré avec l'app (ADR-021) : l'app ne télécharge rien.
- Droits des personnes (accès, portabilité, effacement) sont satisfaits par construction : export complet lisible (A7), suppression totale (H6).
- Si un jour un relais (F9) ou une télémétrie opt-in apparaît, cette analyse est à refaire **avant** le développement.

## 2. Modèle de menaces (v1)

| Menace | Impact | Contrôle |
| --- | --- | --- |
| Téléphone perdu/volé, déverrouillé | Lecture de l'inventaire (où sont les objets de valeur, les papiers) | H3 verrouillage biométrique optionnel, H4 objets sensibles masqués des widgets/notifications, délai de verrouillage |
| Téléphone volé, verrouillé, extraction du stockage | Lecture du fichier SQLite et des photos | Conteneur privé de l'app + H2 chiffrement SQLCipher avec clé Keystore/Keychain non exportable |
| Sauvegarde système copiée (iCloud/Google) | Fuite de la base en clair | Base et photos **exclues** des sauvegardes système ; A9 chiffre avant envoi avec une clé locale |
| App tierce malveillante sur l'appareil | Accès aux fichiers | Conteneur privé, pas de `FileProvider`/`UIDocument` exposant la base ; export via feuille de partage uniquement sur action utilisateur |
| Export partagé par erreur | Fuite | Avertissement avant export, nom de fichier explicite, pas d'export automatique |
| Dépendance tierce compromise | Exfiltration | Liste blanche de dépendances, pas de SDK analytics/ads, vérification des sommes des packs de modèles, revue des `INTERNET` usages |
| Pack de modèle altéré (MITM) | Code/poids malveillants | HTTPS + épinglage de la somme SHA-256 publiée dans le binaire ; les packs ne contiennent que des poids, jamais de code exécutable |
| Widget / écran verrouillé | Affichage d'objets sensibles sans déverrouillage | H4 : jamais d'objet `sensitive` dans les widgets ; contenu du widget réduit à l'action « capturer » |
| Permissions abusives | Perte de confiance, rejet des magasins | H9 : micro, caméra, notifications demandés au premier usage avec explication ; aucune permission de localisation, contacts, réseau local en v1 |

## 3. Règles de développement (à appliquer par Claude Code et vérifiées en revue)

### Réseau
- **Aucun appel réseau** dans `shared` ni dans les apps (le modèle est livré avec l'app, ADR-021 ; `ml/ModelPackDownloader` reste l'unique emplacement autorisé si un pack téléchargé revenait un jour, après ADR). Un test d'intégration lance l'app derrière un proxy bloquant et vérifie zéro requête en usage normal, recherche avec le modèle chargé comprise.
- Android : pas de permission `INTERNET` (une livraison Play *fast-follow* n'en demande pas) ; si un téléchargement HTTPS direct était un jour décidé, `network_security_config` avec `cleartextTrafficPermitted="false"` et domaine unique.
- iOS : pas d'`NSAppTransportSecurity` assoupli.
- Interdits absolus (bloqués par hook) : SDK Firebase Analytics/Crashlytics, Google Analytics, Facebook SDK, Sentry, Mixpanel, Amplitude, AppsFlyer, Adjust, Branch, OneSignal, tout SDK publicitaire.

### Stockage
- Base dans `noBackupFilesDir/db/` (Android, `Context.getNoBackupFilesDir()` : exclu d'Auto Backup et du transfert appareil-à-appareil ; jamais `filesDir/`) et `Application Support/<bundle>/` avec `isExcludedFromBackup = true` (iOS). Photos idem. En défense en profondeur, le manifeste déclare `allowBackup="false"` **et** des règles `dataExtractionRules` / `fullBackupContent` qui excluent tous les domaines.
- Les exceptions levées par `shared` ne portent que des identifiants ou des compteurs dans leur message : un `Throwable.message` peut finir dans un log de debug ou un `LocalizedError` affiché par l'OS.
- Aucune donnée métier dans les logs. Les logs de debug sont désactivés en release (`BuildConfig.DEBUG`, `#if DEBUG`). Pas de `Log.d(…)` contenant un nom d'objet ou de lieu, même en debug : utiliser des identifiants.
- Base chiffrée (H2, lot 8, ADR-023) : SQLCipher, clé brute de 256 bits aléatoire ; une base en clair d'un lot précédent est chiffrée une fois, vérifiée, puis remplacée ; chaque ouverture exige `PRAGMA cipher_version` (jamais le SQLite du système à la place). Les photos ne sont pas chiffrées par l'app : conteneur privé et chiffrement du système.
- Clés : Android Keystore (AES-256-GCM, `setUserAuthenticationRequired(false)` pour la clé de base, StrongBox si disponible) qui enveloppe la clé de la base (`no_backup/keys/db.key`) ; iOS Keychain `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `kSecAttrSynchronizable = false`. Une base chiffrée sans sa clé n'est jamais remplacée : l'app l'explique et propose l'effacement total.
- Suppression totale (H6) : fermer la base, supprimer les fichiers, supprimer les clés (Keystore et `keys/`, élément Keychain), effacer `meta` et les préférences, vider les caches (`cacheDir` Android, `Caches/litertlm` iOS), annuler les notifications programmées, puis redémarrer l'état initial.

### Entrées
- Le parseur traite du texte libre : pas d'injection possible côté SQL (requêtes paramétrées SQLDelight uniquement ; FTS5 : échapper les guillemets et opérateurs de la requête utilisateur).
- Import ZIP (A8) : taille maximale par entrée, refus des chemins avec `..`, validation JSON stricte, limites de nombre d'événements, décompression en flux (zip bomb).
- Photos importées : re-décodage et ré-encodage (pas de copie brute d'un fichier d'origine inconnue).
- Photos et modèle (lot 7, ADR-022) : le modèle reçoit un JPEG ré-encodé par le codec de la plateforme (orientation appliquée, sans métadonnées EXIF/GPS) ; les vecteurs des photos sont un cache local recalculable (table `embedding`), jamais exporté, effacé par la suppression totale ; la photo d'une recherche par photo et la photo en attente d'une capture ne sont jamais conservées : en mémoire seulement, sauf le fichier temporaire de l'application Appareil photo d'Android (`cacheDir/camera`, privé, effacé dès sa lecture, purgé au démarrage et par la suppression totale) ; seule la photo attachée à un objet est stockée, comme toute photo ; les vecteurs d'une photo détachée ou purgée sont effacés au passage d'indexation suivant, même quand les photos sont désactivées.

### UI
- Verrouillage (H3, optionnel) : authentification du propriétaire du téléphone (biométrie ou code, `BiometricPrompt` du système / `LAContext`), aucun mot de passe propre à l'app ; contenu couvert tant que l'état n'est pas « déverrouillé » ; délai immédiat, 1 min ou 5 min, mesuré par l'horloge murale et une horloge monotone (une horloge reculée verrouille). Verrou actif : iOS → vue de protection dès que la scène n'est plus active ; Android → `FLAG_SECURE` sur l'accueil et la capture rapide.
- Les objets `sensitive` (H4) n'apparaissent ni dans les widgets, ni dans les notifications, ni dans les suggestions (C12, étiquettes), ni dans la liste d'accueil, ni en miniature, ni dans l'index système (G3, P3) ; dans un lieu ou un contenant, une ligne « N objets sensibles masqués » les montre sur demande ; la recherche les trouve ; verrou actif, les afficher demande l'authentification, valable jusqu'au passage en arrière-plan.
- Rappels (E2, E6, lot 9, ADR-024) : notification « Rappel : passeport » au plus, jamais le lieu ; objet sensible → « Rappel TrouveTout » ; écran verrouillé → texte générique quand le réglage du téléphone masque le contenu, le nom s'il affiche tout (Android comme iPhone, l'explication le dit) ; permission demandée après le premier rappel avec explication ; aucune alarme exacte, rien ne tourne en arrière-plan (le système réveille l'app au moment voulu) ; toucher ouvre la fiche après le déverrouillage. L'impression (C16) n'imprime un objet sensible que s'il est affiché ; le service d'impression appartient au système.
- Clavier : réglage « empêcher le clavier d'apprendre ce que je tape » (Android, `IME_FLAG_NO_PERSONALIZED_LEARNING` sur tous les champs) ; iOS n'offre pas ce levier, le réglage le dit.

### Dépendances et chaîne de build
- Versions épinglées (`gradle/libs.versions.toml`, Swift Package `Package.resolved`), vérification des sommes des packs de modèles, pas de scripts de build qui téléchargent du code à la volée. Seule exception, des données et non du code : le fichier du modèle d'embeddings, téléchargé au build depuis une révision épinglée et refusé si son SHA-256 diffère de `models/manifest.json` (ADR-021).
- `./gradlew dependencies` revu à chaque ajout ; un hook refuse l'ajout d'une dépendance de la liste interdite.
- Signature : clé de release hors du dépôt (`*.jks`, `*.p12`, `*.mobileprovision` dans `.gitignore` et refusés en lecture à Claude Code).

## 4. Liste de contrôle avant chaque release

État au lot 8 : chaque point vérifiable sans magasin est automatisé en CI ; les fiches des magasins et la page J7 arrivent au lot 12.

- [x] `tools/check-network.sh` : zéro requête en usage normal (vérification statique en CI ; le test avec proxy bloquant arrive avec les parcours dynamiques du lot 12). Bibliothèques natives iOS : `tools/check-native-network.sh` (aucun symbole réseau dans SQLCipher ; dans LiteRT-LM, `connect`, `getaddrinfo` et `socket` acceptés par ADR-023 en attendant la preuve dynamique du lot 12).
- [x] `tools/check-deps.sh` : aucune dépendance interdite ; permissions Android déclarées aujourd'hui = micro (`RECORD_AUDIO`), biométrie (`USE_BIOMETRIC`), notifications (`POST_NOTIFICATIONS`) et redémarrage (`RECEIVE_BOOT_COMPLETED`) ; la liste blanche admet en plus `CAMERA`, `VIBRATE` et `BILLING` (lot 10) ; aucune alarme exacte ; jamais `INTERNET`, **manifeste fusionné de la release compris** ; ABI `arm64-v8a`, `armeabi-v7a`, `x86_64` ; aucun travail en arrière-plan (N6).
- [ ] Fiches App Privacy / Data safety : « aucune donnée collectée » ; politique de confidentialité en ligne (J7) à jour — lot 12 (avec la déclaration de chiffrement d'Apple, ADR-023).
- [x] H6 testée : après suppression totale, données, clé de la base, caches et préférences d'affichage supprimés (`WipeTest` par `App.wipeAll()` sur API 29, `DatabaseEncryptionTests` sur iOS, `SharedFactoryTest`) ; alarmes et notifications des rappels supprimées (`ReminderNotificationTest` sur API 29, `RemindersTests` sur iOS).
- [x] Export → réinstallation → import : identique (test automatisé, N9, `ExportImportTest`).
- [x] Objets sensibles absents des widgets et des notifications : par construction (widgets et tuile sans accès au noyau, vérifiés par `tools/check-network.sh`) ; notifications des rappels (lot 9, ADR-024) : le nom de l'objet au plus, jamais son lieu (`tools/check-network.sh`), texte générique pour un objet sensible, et sur l'écran verrouillé sauf si le téléphone est réglé pour tout afficher, relues au moment où elles sonnent sur Android (`RemindersTest`, `ReminderNotificationTest`, `RemindersTests`) ; la capture rapide qu'ils ouvrent ne nomme aucun objet sensible et, app verrouillée, ne pose aucune question ni ne liste de lieu (`CaptureServiceTest`) ; absents de l'accueil, des suggestions et des miniatures (`ItemRepositoryTest`, `BrowseRepositoryTest`, `SearchServiceTest`) ; écran verrouillé sans contenu lisible (`LockGateTest`).
- [x] Aucun log métier en release : `tools/check-logs.sh` (aucun appel de journalisation hors du `Logger` injecté, `NoopLogger` en release) et règle SwiftLint `no_logging`.
- [ ] Revue `/security-review` de Claude Code sur la branche, et passage de l'agent `security-reviewer` du projet — à chaque release.

## 5. Textes à fournir (J7)

- Politique de confidentialité (FR/EN) : « L'application ne collecte aucune donnée. Vos objets, lieux et photos restent sur votre téléphone. La recherche, y compris son modèle d'intelligence artificielle, fonctionne entièrement sur votre téléphone ; le modèle est fourni avec l'application et ne télécharge rien. Les seules connexions sortantes sont : (1) les achats intégrés gérés par Apple/Google, (2) si vous l'activez, une sauvegarde chiffrée dans votre propre iCloud Drive / Google Drive, que nous ne pouvons pas lire. »
- Recherche : « L'app compte, sur le téléphone, les fiches ouvertes depuis la recherche pour mieux les classer ; ce compteur n'est ni exporté ni envoyé, et la suppression totale l'efface. Le modèle de recherche (EmbeddingGemma 2, licence Apache 2.0) et son moteur LiteRT-LM (Apache 2.0, avec sa liste de composants tiers) sont mentionnés dans les réglages. »
- Rappels : « Les rappels sont des notifications locales, programmées sur votre téléphone ; rien ne passe par un serveur. Une notification nomme l'objet au plus, jamais son lieu ; pour un objet sensible, elle reste générique. Sur l'écran verrouillé, votre téléphone décide : le nom reste visible s'il est réglé pour tout afficher. Votre téléphone peut conserver ce texte (historique des notifications, montre connectée, app autorisée à lire les notifications) ; la suppression totale n'efface pas ces copies. Après un redémarrage ou une mise à jour, l'application s'ouvre un instant en arrière-plan pour remettre vos rappels en place ; rien n'est envoyé. »
- Impression : « Si vous imprimez un lieu ou un contenant, le document part vers l'imprimante ou le service d'impression que vous choisissez, gérés par votre téléphone. Une fois imprimé ou enregistré en PDF, il est hors de l'application et la suppression totale ne l'atteint pas. Les objets sensibles n'y figurent que si vous les avez affichés. »
- Description du format d'export (schéma JSON) pour que l'utilisateur puisse lire ses données sans l'app.
