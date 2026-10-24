# Sécurité et RGPD — exigences et liste de contrôle

Objectif : que la fiche « App Privacy » (Apple) et « Data safety » (Google) puissent déclarer **« aucune donnée collectée »** honnêtement, et que l'app résiste aux menaces réalistes pour un inventaire domestique (vol du téléphone, curieux du foyer, app tierce malveillante).

## 1. Position RGPD

- TrouveTout ne traite **aucune donnée personnelle côté éditeur** : pas de serveur, pas de compte, pas de télémétrie, pas d'identifiant publicitaire. L'éditeur n'est pas responsable de traitement pour les données saisies, qui restent sous le contrôle exclusif de l'utilisateur sur son appareil.
- Conséquences pratiques : pas de bannière de consentement, pas de registre de traitement pour l'app elle-même, mais une **politique de confidentialité** publique (obligatoire sur les deux magasins) qui dit exactement cela et décrit les seuls flux réseau : téléchargement optionnel des packs de modèles (domaine, ce qui est envoyé : rien d'autre que la requête HTTP), achats via les magasins (données gérées par Apple/Google), sauvegarde optionnelle A9 dans l'espace cloud **de l'utilisateur** (chiffrée par l'app avant envoi).
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
- **Aucun appel réseau** dans `shared` en dehors de `ml/ModelPackDownloader`. Un test d'intégration lance l'app derrière un proxy bloquant et vérifie zéro requête en usage normal.
- Android : pas de permission `INTERNET` tant que le téléchargement de pack n'est pas implémenté via Play Asset Delivery ; si téléchargement HTTPS direct, `network_security_config` avec `cleartextTrafficPermitted="false"` et domaine unique.
- iOS : pas d'`NSAppTransportSecurity` assoupli.
- Interdits absolus (bloqués par hook) : SDK Firebase Analytics/Crashlytics, Google Analytics, Facebook SDK, Sentry, Mixpanel, Amplitude, AppsFlyer, Adjust, Branch, OneSignal, tout SDK publicitaire.

### Stockage
- Base dans `noBackupFilesDir/db/` (Android, `Context.getNoBackupFilesDir()` : exclu d'Auto Backup et du transfert appareil-à-appareil ; jamais `filesDir/`) et `Application Support/<bundle>/` avec `isExcludedFromBackup = true` (iOS). Photos idem. En défense en profondeur, le manifeste déclare `allowBackup="false"` **et** des règles `dataExtractionRules` / `fullBackupContent` qui excluent tous les domaines.
- Les exceptions levées par `shared` ne portent que des identifiants ou des compteurs dans leur message : un `Throwable.message` peut finir dans un log de debug ou un `LocalizedError` affiché par l'OS.
- Aucune donnée métier dans les logs. Les logs de debug sont désactivés en release (`BuildConfig.DEBUG`, `#if DEBUG`). Pas de `Log.d(…)` contenant un nom d'objet ou de lieu, même en debug : utiliser des identifiants.
- Clés : Android Keystore (AES-256-GCM, `setUserAuthenticationRequired(false)` pour la clé de base, StrongBox si disponible) ; iOS Keychain `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `kSecAttrSynchronizable = false`.
- Suppression totale (H6) : fermer la base, supprimer les fichiers, supprimer les clés, effacer `meta` et les préférences, annuler les notifications programmées, puis redémarrer l'état initial.

### Entrées
- Le parseur traite du texte libre : pas d'injection possible côté SQL (requêtes paramétrées SQLDelight uniquement ; FTS5 : échapper les guillemets et opérateurs de la requête utilisateur).
- Import ZIP (A8) : taille maximale par entrée, refus des chemins avec `..`, validation JSON stricte, limites de nombre d'événements, décompression en flux (zip bomb).
- Photos importées : re-décodage et ré-encodage (pas de copie brute d'un fichier d'origine inconnue).

### UI
- iOS : `UIApplication.willResignActive` → vue de protection (flou) si H3 activé ; Android : `FLAG_SECURE` si H3 activé.
- Les objets `sensitive` n'apparaissent ni dans les widgets, ni dans les notifications, ni dans les suggestions de requête (C12), ni dans l'index système (G3, P3).

### Dépendances et chaîne de build
- Versions épinglées (`gradle/libs.versions.toml`, Swift Package `Package.resolved`), vérification des sommes des packs de modèles, pas de scripts de build qui téléchargent du code à la volée.
- `./gradlew dependencies` revu à chaque ajout ; un hook refuse l'ajout d'une dépendance de la liste interdite.
- Signature : clé de release hors du dépôt (`*.jks`, `*.p12`, `*.mobileprovision` dans `.gitignore` et refusés en lecture à Claude Code).

## 4. Liste de contrôle avant chaque release

- [ ] `tools/check-network.sh` : zéro requête en usage normal.
- [ ] `tools/check-deps.sh` : aucune dépendance interdite ; permissions Android = micro, caméra, notifications (+ `INTERNET` seulement si téléchargement de pack hors magasin).
- [ ] Fiches App Privacy / Data safety : « aucune donnée collectée » ; politique de confidentialité en ligne (J7) à jour.
- [ ] H6 testée : après suppression totale, `ls filesDir` vide, Keystore/Keychain vides, notifications annulées.
- [ ] Export → réinstallation → import : identique (test automatisé, N9).
- [ ] Objets sensibles absents des widgets/notifications (test UI).
- [ ] Aucun log métier en release (grep `Log.|print(|NSLog` sur les chemins métier).
- [ ] Revue `/security-review` de Claude Code sur la branche, et passage de l'agent `security-reviewer` du projet.

## 5. Textes à fournir (J7)

- Politique de confidentialité (FR/EN) : « L'application ne collecte aucune donnée. Vos objets, lieux et photos restent sur votre téléphone. Les seules connexions sortantes sont : (1) le téléchargement, à votre demande, d'un pack de modèle d'IA depuis <domaine>, (2) les achats intégrés gérés par Apple/Google, (3) si vous l'activez, une sauvegarde chiffrée dans votre propre iCloud Drive / Google Drive, que nous ne pouvons pas lire. »
- Description du format d'export (schéma JSON) pour que l'utilisateur puisse lire ses données sans l'app.
