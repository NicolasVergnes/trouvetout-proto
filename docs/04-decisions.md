# Journal des décisions (ADR)

Format : date, décision, raison, conséquences. Ajouter une entrée à chaque choix structurant ; ne jamais modifier une entrée passée, en ajouter une nouvelle qui la remplace.

## ADR-001 — 2026-10-23 — Local d'abord, sans compte ni serveur
**Décision** : toutes les données vivent sur le téléphone ; aucun backend ; aucune télémétrie.
**Raison** : positionnement défendable face aux plateformes (Google Find Hub est cloud), RGPD par construction, coût d'exploitation nul permettant un achat unique.
**Conséquences** : partage multi-appareils plus complexe (reporté, voir ADR-005) ; essai gratuit ancré sur les magasins ou en local (ADR-007).

## ADR-002 — 2026-10-24 — Kotlin Multiplatform + interfaces natives
**Décision** : moteur (base, journal, capture, recherche, modèles, licence) en KMP ; UI Jetpack Compose sur Android, SwiftUI sur iOS.
**Raison** : une seule implémentation de la logique et du format de données ; rendu et intégrations système natifs (widgets, App Intents, parole, achats).
**Conséquences** : build iOS via framework KMP ; `expect/actual` pour fichiers, RAM, crypto, parole ; pas de Flutter/React Native.

## ADR-003 — 2026-10-24 — EmbeddingGemma 2 embarqué, variante selon la RAM
**Décision** : un seul modèle d'embeddings dans l'app ; omnimodal (740 M) si RAM ≥ 6 Go, texte seul (270 M) sinon ; APIs système en bonus, jamais requises.
**Raison** : couvrir ~75 % du parc (dont les téléphones de plus de 4 ans) au lieu des 15–20 % équipés d'IA système ; même espace vectoriel texte/image sans second modèle.
**Conséquences** : packs téléchargés à la demande ; `model_id` sur chaque vecteur ; réindexation depuis le texte source si la variante change ; validation FR à faire sur le corpus.

## ADR-004 — 2026-10-24 — Pas de LLM pour l'extraction en v1
**Décision** : le parseur de phrases est à base de règles + similarité avec les lieux connus ; l'extraction par LLM (B3) est en P3 ; la réponse générative (C13) est un pack optionnel en dernier lot.
**Raison** : déterminisme, latence, couverture des appareils anciens, testabilité par corpus.
**Conséquences** : corpus `parser.jsonl` obligatoire ; formulations exotiques traitées par la désambiguïsation (B7) plutôt que par la génération.

## ADR-005 — 2026-10-24 — Foyer et partage reportés en P3
**Décision** : la v1 est mono-utilisateur, mono-appareil ; la section F entière est reportée.
**Raison** : livrer vite une v1 utile ; le partage sans serveur demande une architecture dédiée.
**Conséquences** : le journal d'événements (deviceId, actor, horodatage) est néanmoins construit dès la v1 pour permettre la fusion plus tard sans migration ; architecture retenue pour la v2 : synchronisation de proximité d'abord, relais chiffré aveugle ensuite.

## ADR-006 — 2026-10-24 — Versions minimales iOS 16 / Android 10
**Décision** : iOS 16 (iPhone 8/X, 2017) et Android 10 (API 29, 2019).
**Raison** : toucher un large public qui garde son téléphone plus de 4 ans ; ~90 % du parc Android, ~95 % des iPhones actifs.
**Conséquences** : repli non-IA (mot-clé + règles) obligatoire partout ; budgets mémoire serrés sur 2–4 Go ; APIs récentes (Foundation Models, Gemini Nano) encapsulées derrière des interfaces optionnelles.

## ADR-007 — 2026-10-24 — Essai gratuit 6 mois puis blocage des seuls ajouts
**Décision** : 6 mois d'usage complet sans compte ; ensuite, les nouvelles fiches sont bloquées, tout le reste reste libre (recherche, modification, export, suppression). Tarif à trancher : 10 € unique ou 2 €/an.
**Raison** : ne jamais prendre les données de l'utilisateur en otage ; modèle compatible avec l'absence de serveur ; ancrage fiable côté Apple (`AppTransaction.originalPurchaseDate`), approximatif côté Android (accepté).
**Conséquences** : StoreKit 2 / Play Billing dès la v1 (J5 remonté en P1) ; écran d'échéance non bloquant ; pas de validation serveur des reçus.

## ADR-008 — 2026-10-24 — Pas de plafond strict de taille d'app
**Décision** : viser léger, mais ne pas sacrifier de fonction pour rester sous 60 Mo ; les modèles restent hors binaire, en packs à la demande.
**Raison** : la contrainte était arbitraire ; les mécanismes de livraison (Play Asset Delivery, On-Demand Resources, téléchargement HTTPS vérifié) existent.

## ADR-009 — 2026-10-24 — Pas d'enquête utilisateurs préalable
**Décision** : le corpus de test (300 questions, 150+ phrases de capture) est construit par le développeur à partir de phrases synthétiques et de son usage.
**Conséquences** : marquer dans le corpus l'origine des phrases pour pouvoir, plus tard, remplacer les synthétiques par des réelles.

## ADR-010 — 2026-10-24 — « TrouveTout » est un nom de prototype
**Décision** : ne pas figer de nom de marque dans les identifiants techniques exposés (bundle id, package name, nom de schéma d'URL, domaine) avant le choix définitif ; utiliser un identifiant neutre interne (ex. `app.trouvetout.proto` ou un nom de code) et une constante unique `AppName`.

## ADR-011 — 2026-10-24 — Identifiants techniques neutres
**Décision** : packages Kotlin `app.trouvetout.core` (shared) et `app.trouvetout.android` (androidApp) ; `applicationId` et bundle id `app.trouvetout.proto` ; schéma d'URL `trouvetout-proto://` ; le nom affiché vient d'un seul objet `AppInfo`.
**Raison** : application de l'ADR-010 — rien de la marque dans les identifiants exposés avant le choix définitif.
**Conséquences** : renommer l'app = changer `AppInfo`, les chaînes et les identifiants des magasins ; le code ne change pas.

## ADR-012 — 2026-10-24 — Chaîne de build et pont iOS
**Décision** : Kotlin 2.4.21, Gradle 9.7.0, AGP 9.3.x ; `shared` utilise le plugin `com.android.kotlin.multiplatform.library` (AGP 9 interdit `com.android.library` + KMP dans un même module) ; `androidApp` utilise le Kotlin intégré d'AGP 9. Injection **manuelle** (`SharedFactory`), pas de Koin. Pont Swift **manuel** (`FlowWatcher` en commonMain + `Shared+Swift.swift`), pas de SKIE. Projet Xcode **généré par XcodeGen** (`iosApp/project.yml` versionné) ; framework statique via `embedAndSignAppleFrameworkForXcode`, pas de CocoaPods.
**Raison** : développement sur Windows sans Xcode — le moins de pièces possible que l'on ne peut pas tester localement ; un `.pbxproj` écrit à la main serait fragile.
**Conséquences** : la compilation et les tests iOS n'ont lieu que sur la CI macOS ; `swift`/`xcodebuild` ne sont jamais lancés en local ; chaque exposition d'un `Flow` à Swift passe par `FlowWatcher`.

## ADR-013 — 2026-10-24 — Dépôt public et flux git sans PR
**Décision** : dépôt GitHub public `trouvetout-proto` (minutes GitHub Actions illimitées, macOS compris) ; une branche `lot/NN-*` par lot, poussée pour la CI, fusionnée localement dans `main` (`--no-ff`) sans pull request ; revues faites par `/code-review` et les agents du projet avant la fusion.
**Raison** : la CI macOS est la seule vérification iOS ; les PR n'apportent rien tant qu'il n'y a qu'un développeur et un agent.
**Conséquences** : le code du prototype est visible ; pas de licence pour l'instant (tous droits réservés) — voir « Questions ouvertes ».

## ADR-014 — 2026-10-24 — Deux produits d'achat
**Décision** : précise l'ADR-007 — l'écran d'échéance propose **les deux** produits : déblocage non consommable à 10 € et abonnement annuel à 2 €/an ; `purchased` est vrai si l'un des deux est actif.
**Raison** : laisser le choix à l'utilisateur ; coût d'implémentation acceptable avec StoreKit 2 / Play Billing.
**Conséquences** : deux identifiants de produit à configurer dans chaque console (lot 10) ; les deux états à tester (y compris abonnement expiré → ajouts bloqués de nouveau).

## ADR-015 — 2026-10-24 — Socle de données (lot 1)
**Décision** : SQLDelight 2.4 (dialecte SQLite 3.25 = plancher d'Android 10) avec un fichier `.sq` par table et des requêtes nommées ; payloads d'événements typés (`EventPayload` scellé) sérialisés en JSON par kotlinx.serialization avec discriminant `type` et lecture tolérante (`ignoreUnknownKeys`) ; identifiants ULID générés en Kotlin commun (générateur monotone par écrivain) ; instants `kotlin.time.Instant` (stable depuis Kotlin 2.3) stockés en millisecondes UTC ; `EventStore.append` = transaction « insérer + indexer les sujets + projeter », rejeu complet si un événement plus ancien que la tête arrive ; projection écrite uniquement par `Projector` avec des `INSERT OR REPLACE` idempotents ; photos ré-encodées par un `ImageCodec` de plateforme (Android `BitmapFactory` + orientation EXIF, iOS `UIImage` via l'adaptateur `NSDataImageCodec`, JVM `ImageIO` pour les outils) et stockées via une abstraction `FileStore` ; source set `jvmCommon` (Android + JVM) pour n'écrire qu'une fois les `actual` java.*.
**Raison** : même schéma et même format sur les deux plateformes, rejouabilité prouvée par tests (50 scénarios aléatoires, convergence de deux journaux), aucune dépendance de plateforme dans `commonMain`.
**Conséquences** : la table FTS5 arrive au lot 6 par migration additive ; `docs/format-export.md` publie le schéma et le format d'événements ; toute nouvelle table ou colonne = migration `.sqm` additive + mise à jour du document.
**Précisions après revue** : (a) un payload inconnu ou indécodable devient `EventPayload.Unknown` (type + JSON brut), conservé dans le journal, ignoré par la projection, réexporté tel quel — le décodage ne lève jamais et ne met jamais de texte métier dans un message d'exception ; (b) l'archivage est un drapeau de la fiche, pas un état de lieu : `location_state.state` vaut `STORED | TAKEN | LENT` (écart assumé avec `01-architecture.md` §3.1, qui listait `archived`) pour qu'archiver ne perde jamais un prêt en cours ; (c) l'ordre canonique du journal est le triplet `(at, deviceId, id)`, y compris pour détecter un événement arrivé en retard ; (d) `SharedConfig.storageReady = false` fait refuser l'ouverture de la base quand l'hôte n'a pas pu garantir le répertoire hors sauvegarde.

## ADR-016 — 2026-10-26 — Export, import, corbeille, annulation, suppression totale (lot 2)
**Décision** : (a) l'archive ZIP est écrite et lue par un lecteur/écrivain **en Kotlin commun** (`data/export/Zip.kt` : entrées stockées ou deflate, CRC-32 vérifié, répertoire central lu d'abord), avec deflate/inflate bruts en `expect/actual` (`java.util.zip` côté JVM/Android, `zlib` côté iOS) ; aucune bibliothèque tierce. (b) Lecture et écriture **en flux** via `FileStore.sink` / `FileStore.source` (`ByteSink`, `RandomAccessSource`) : l'archive n'est jamais chargée entière en mémoire ; une entrée l'est (photo ≤ 48 Mio, `events.jsonl` ≤ 64 Mio — l'inflation ligne à ligne est un suivi noté dans `03-plan-de-lots.md`) ; un échec d'écriture abandonne le fichier temporaire (`ByteSink.abort`), la cible n'apparaît jamais tronquée. (c) L'import est **borné et strict** : 200 000 entrées, manifeste ≤ 1 Mio, `events.jsonl` ≤ 64 Mio et ≤ 1 000 000 lignes de ≤ 64 Kio, photo ≤ 48 Mio, archive copiée par l'hôte ≤ 1 Gio, noms de photos validés dans l'archive **et** dans les événements `PhotoAttached` (`<ULID>.jpg` / `<ULID>_t.jpg`), identifiants ≤ 64 caractères, instants dans [1970, 3000] ; une ligne invalide est comptée et ignorée, un payload inconnu est conservé (`EventPayload.Unknown`), un `formatVersion` supérieur est refusé (`FORMAT_TOO_NEW`), les entrées inconnues sont ignorées (N9). (d) Chaque photo importée est **décodée puis ré-encodée** par l'`ImageCodec` de la plateforme et sa miniature régénérée : aucun octet d'origine inconnue n'est copié tel quel ; une photo indécodable est comptée et ignorée. (e) Les événements importés sont insérés par lots sans projection (`appendWithoutProjection`) puis la projection est **reconstruite une fois** ; la fusion est l'union des journaux par `id` (ADR-015). (f) L'export (photos pleine taille seulement, les miniatures sont régénérées à l'import ; pas de ZIP64 : au-delà de 65 535 entrées ou 4 Gio l'export échoue proprement — suivi noté) est écrit dans `exports/` du répertoire privé puis partagé par la feuille système (Android : copie dans `cache/share/` servie par un `FileProvider` non exporté, supprimée au prochain partage, au prochain lancement ou à l'effacement ; iOS : `ShareLink`) ; `SharedFactory.housekeeping()`, appelée par les hôtes après `warmUp()` à chaque lancement, purge les exports de plus de 24 h, les fichiers de la corbeille expirée et les restes d'`imports/`. (g) L'import prend un document choisi par l'utilisateur, **copié** par l'hôte dans `imports/` (le noyau ne lit jamais une URI étrangère), puis supprimé quel que soit le résultat. (h) **Annuler** (A13) = ajouter les événements inverses de la dernière écriture de cet appareil (groupe des événements partageant le même instant, par ex. `ItemCreated` + `ItemStored`) ; les événements importés et les opérations sans inverse exact (fusion/suppression de lieu, suppression de contenant, rappel accompli) sont refusés `NOT_UNDOABLE`. (i) **Corbeille** = 30 jours après `ItemDeleted` ou `PhotoDetached`, seuls les **fichiers** photo sont purgés ; journal et lignes restent, une restauration tardive rend la fiche sans ses images. (j) **Suppression totale** (H6, partie données) = `SharedFactory.wipeAllData()` ferme la base, supprime `db/`, `photos/`, `exports/`, `imports/` et rend l'instance inutilisable (`warmUp()` → `false`) ; l'hôte recrée une `SharedFactory` ; clés, préférences et notifications seront ajoutées à la procédure aux lots 8 et 9.
**Raison** : zéro dépendance et un seul format sur les deux plateformes ; une archive est une entrée hostile (zip bomb, chemins, images piégées) et doit être traitée comme telle ; le journal reste la seule source de vérité (annuler et corbeille n'effacent rien).
**Conséquences** : `docs/format-export.md` §5 décrit la sémantique d'import ; le `FileProvider` ne sert que `cache/share/` (jamais la base) ; toute nouvelle entrée d'archive doit être ajoutée aux limites et aux tests `ExportImportTest`.

## ADR-017 — 2026-10-26 — Vérification des sommes des dépendances Gradle
**Décision** : `gradle/verification-metadata.xml` (SHA-256 de chaque artefact et de ses métadonnées, plugins de build compris, signatures PGP non vérifiées) est versionné et la vérification est **stricte partout** (comportement par défaut de Gradle dès que le fichier existe), y compris sur le job macOS. Le fichier est produit par le workflow `Dependency verification metadata` (`--write-verification-metadata sha256 … --dry-run`, qui résout toutes les configurations des tâches de la CI sans les exécuter) enchaîné sur Linux puis macOS pour couvrir les artefacts Kotlin/Native et Apple, puis fusionné localement sous Windows pour les artefacts propres à ce poste (aapt2, distribution Kotlin/Native Windows). Le workflow se relance de lui-même quand le graphe de dépendances change (`libs.versions.toml`, `build.gradle.kts`, wrapper) ; la procédure est alors : télécharger l'artefact, l'installer dans `gradle/`, relancer l'écriture localement, commiter.
**Raison** : la défense contre une dépendance altérée en route reposait sur l'épinglage des versions et des dépôts filtrés ; la somme vérifiée ferme le cas du dépôt ou du miroir compromis. Une génération sur une seule plateforme aurait laissé le job iOS en échec ou en mode `lenient` (ce que la question ouverte du lot 1 proposait) ; la génération croisée rend le mode strict possible.
**Conséquences** : tout ajout ou montée de version de dépendance régénère le fichier (sinon la CI échoue avec la liste des artefacts manquants — jamais ajouter une somme à la main sans l'avoir obtenue par Gradle) ; les `trusted-artifacts` restent vides ; la vérification des signatures PGP est une évolution possible.

## Questions ouvertes
- **Licence du dépôt public** : aucun fichier `LICENSE` pour l'instant (tous droits réservés par défaut). À trancher avant la publication (lot 12) avec le nom définitif.
- **Ancrage de `trialStart` sur Android (lot 10)** : `docs/01-architecture.md` §6 et le skill `android-ui` prévoient de sauvegarder le fichier de préférences de `trialStart`/`deviceId` par Auto Backup clé-valeur, mais le manifeste fixe `allowBackup="false"` et des règles d'extraction qui excluent tout (lot 0, défense en profondeur), et la clé maître d'`EncryptedSharedPreferences` ne survit de toute façon pas à une réinstallation. À trancher avant le lot 10 : (A) accepter la remise à zéro à la réinstallation (déjà notée « acceptée » dans l'ADR-007) et retirer la mention Auto Backup des docs ; (B) inclure un seul fichier de préférences en clair (`trialStart` n'est pas une donnée sensible) dans les règles de sauvegarde. Recommandation : A.
- **Règle de découpe objet/lieu et étiquettes du corpus (lot 3)** : le skill `capture-parser` dit « la préposition de lieu la plus à droite sépare objet et lieu », mais plusieurs phrases du seed attendent la découpe la plus à gauche avec le chemin complet comme lieu (p0011 « carton 7 de l'étagère du fond de la cave », p0025, p0028) ; et la préposition est tantôt retirée du lieu (p0012 « bureau »), tantôt conservée (p0014 « sous le canapé », p0016 « derrière les livres… »). À trancher au début du lot 3 : (a) la première préposition de lieu après l'objet ouvre le lieu, qui garde tout le reste de la phrase ; (b) la préposition de position (`sous`, `derrière`, `au fond de`) fait partie du lieu, celle de simple localisation (`dans`, `sur`, `à`, `@`) n'en fait pas partie. Corriger le corpus en conséquence, en une seule passe documentée.
- **Vérification des sommes des dépendances Gradle** : tranchée au lot 2 — voir ADR-017.
