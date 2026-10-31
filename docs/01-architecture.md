# Architecture technique — v1

## 1. Vue d'ensemble

```
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ androidApp (Jetpack Compose) │   │ iosApp (SwiftUI)             │
│ widgets, tuile, speech, IAP  │   │ widgets, bouton Action,      │
│ Play Billing, Keystore       │   │ SFSpeech, StoreKit 2, Keychain│
└──────────────┬───────────────┘   └──────────────┬───────────────┘
               │ API Kotlin (KMP)                 │ API Swift (framework KMP)
┌──────────────▼──────────────────────────────────▼───────────────┐
│ shared (Kotlin Multiplatform)                                     │
│  domain/     modèle (Item, Place, Container, Event), use cases    │
│  data/       SQLDelight (SQLite), repositories, export/import     │
│  capture/    parseur de phrases (règles + similarité lieux)       │
│  search/     mot-clé tolérant + embeddings + fusion               │
│  ml/         chargement EmbeddingGemma 2 (LiteRT), choix variante │
│  licensing/  compteur d'essai, état d'achat (interfaces)          │
│  platform/   expect/actual : fichiers, horloge, RAM, crypto       │
└───────────────────────────────────────────────────────────────────┘
```

Règle d'or : **toute logique métier est dans `shared`**. Les apps natives ne font que l'interface, les permissions, l'accès aux APIs système (parole, widgets, achats, trousseau) et l'injection des implémentations `actual`.

## 2. Modules et répertoires

```
TrouveTout/
├── shared/                 # KMP : commonMain, androidMain, iosMain, commonTest
├── androidApp/             # Application Android (Compose, minSdk 29)
├── iosApp/                 # Projet Xcode (SwiftUI, iOS 16)
├── models/                 # Scripts de conversion/empaquetage des modèles (pas les poids)
├── corpus/                 # Corpus de test de la recherche (JSONL)
├── docs/                   # Cette documentation
└── tools/                  # Scripts de build, d'évaluation, de vérification réseau
```

Choix de bibliothèques (à confirmer au premier lot si une alternative s'impose) :

| Besoin | Choix | Raison |
| --- | --- | --- |
| Base de données | SQLDelight (driver Android + native iOS) | SQL explicite, schéma versionné, même fichier sur les deux plateformes |
| Chiffrement du fichier SQLite (H2, P1) | SQLCipher via SQLDelight | Clé dans Keystore/Keychain |
| Sérialisation | kotlinx.serialization (JSON) | Export lisible, stable |
| Concurrence | kotlinx.coroutines | Standard KMP |
| Injection | Koin ou injection manuelle | Léger ; pas de framework lourd |
| Embeddings | EmbeddingGemma 2 via LiteRT-LM (`litertlm-android`, Swift Package `LiteRTLM`, `litertlm-jvm` pour l'évaluation) — ADR-021 | Un seul modèle, deux plateformes, tokenizer inclus dans le fichier |
| Images | Coil (Android), SwiftUI AsyncImage/UIImage (iOS) | Natif |
| Tests | kotlin.test, Turbine ; JUnit/Espresso ; XCTest | Natif |

## 3. Modèle de données

### 3.1 Entités

- **Place** (lieu) : `id`, `name`, `parentId?`, `kind` (`root | room | furniture | spot`), `aliases[]`, `photoIds[]`, `createdAt`, `deletedAt?`.
- **Container** (contenant déplaçable) : `id`, `label` (ex. « Carton 7 »), `number?`, `placeId` (lieu courant), `photoIds[]`, `createdAt`, `deletedAt?`. Un contenant est un nœud de la hiérarchie déplaçable avec son contenu.
- **Item** (objet) : `id`, `name`, `description?`, `tags[]`, `quantity?`, `unit?`, `photoIds[]`, `sensitive` (bool), `pinned` (bool), `archived` (bool), `createdAt`, `deletedAt?`.
- **Location state** (dérivé) : pour chaque Item, `placeId?` ou `containerId?` courant, `state` (`stored | taken | lent | archived`), `lentTo?`, `since`.
- **Photo** : `id`, `fileName`, `width`, `height`, `thumbName`, `sha256`, `createdAt`.
- **Reminder** : `id`, `itemId`, `fireAt`, `kind` (`date | check`), `done`.
- **Embedding** : `ownerType` (`item | place | container | photo`), `ownerId`, `modelId`, `dim`, `vector` (BLOB float16), `textHash`.

### 3.2 Journal d'événements (A4) — cœur du modèle

Chaque modification est un événement immuable :

```
Event {
  id: ULID
  at: Instant (UTC)
  deviceId: UUID       # identité locale de l'appareil (générée au premier lancement)
  actor: "me"          # réservé pour le foyer (v2)
  type: ItemCreated | ItemUpdated | ItemStored | ItemTaken | ItemReturned |
        ItemLent | ItemMovedTo | ItemArchived | ItemDeleted | ItemRestored |
        PlaceCreated | PlaceRenamed | PlaceMerged | PlaceDeleted |
        ContainerCreated | ContainerMoved | ContainerDeleted |
        PhotoAttached | PhotoDetached | ReminderSet | ReminderDone
  payload: JSON (champs propres au type)
  source: voice | text | photo | widget | import
  rawInput?: String    # phrase dictée/tapée telle quelle (pour réindexer et pour le corpus)
}
```

- L'**état courant** (tables `item`, `place`, `container`, `location_state`) est une **projection** du journal, reconstruite à l'import et vérifiable par un test (`replay(events) == state`).
- Le journal n'est jamais réécrit. Une suppression est un événement ; la corbeille (A13) est la fenêtre de 30 jours avant purge des photos.
- Le `deviceId` et `actor` rendent la fusion multi-appareils (F5, v2) possible sans migration : fusion = union des journaux triée par `(at, deviceId, id)`, dernier déplacement gagnant.

### 3.3 Schéma SQLite (SQLDelight, extrait)

```sql
CREATE TABLE event (
  id TEXT PRIMARY KEY, at INTEGER NOT NULL, device_id TEXT NOT NULL,
  actor TEXT NOT NULL DEFAULT 'me', type TEXT NOT NULL, payload TEXT NOT NULL,
  source TEXT NOT NULL, raw_input TEXT
);
CREATE INDEX event_at ON event(at);

CREATE TABLE place (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, parent_id TEXT, kind TEXT NOT NULL,
  aliases TEXT NOT NULL DEFAULT '[]', created_at INTEGER NOT NULL, deleted_at INTEGER
);
CREATE TABLE container (
  id TEXT PRIMARY KEY, label TEXT NOT NULL, number INTEGER, place_id TEXT NOT NULL,
  created_at INTEGER NOT NULL, deleted_at INTEGER
);
CREATE TABLE item (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT, tags TEXT NOT NULL DEFAULT '[]',
  quantity REAL, unit TEXT, sensitive INTEGER NOT NULL DEFAULT 0,
  pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, deleted_at INTEGER
);
CREATE TABLE location_state (
  item_id TEXT PRIMARY KEY, place_id TEXT, container_id TEXT,
  state TEXT NOT NULL, lent_to TEXT, since INTEGER NOT NULL
);
CREATE TABLE photo (
  id TEXT PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL,
  file_name TEXT NOT NULL, thumb_name TEXT NOT NULL, width INTEGER, height INTEGER,
  sha256 TEXT NOT NULL, created_at INTEGER NOT NULL
);
-- Caches de la recherche (lot 6, ADR-021) : recalculables, jamais exportés.
CREATE TABLE embedding (
  owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, model_key TEXT NOT NULL,
  dim INTEGER NOT NULL, vector BLOB NOT NULL, text_hash TEXT NOT NULL,
  PRIMARY KEY (owner_type, owner_id, model_key)
);
CREATE TABLE search_usage (
  item_id TEXT PRIMARY KEY, opened_count INTEGER NOT NULL, last_opened_at INTEGER NOT NULL
);
CREATE TABLE reminder (
  id TEXT PRIMARY KEY, item_id TEXT NOT NULL, fire_at INTEGER NOT NULL,
  kind TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL); -- schema_version, device_id, model_variant, trial_started_at…
```

Migrations SQLDelight versionnées ; **jamais** de migration destructive. Le schéma est publié dans `docs/format-export.md` (à écrire au lot 1).

### 3.4 Format d'export (A7/A8, N9)

ZIP `trouvetout-export-YYYYMMDD.zip` :
```
manifest.json     { formatVersion: 1, appVersion, exportedAt, deviceId, counts }
events.jsonl      # journal complet, un événement par ligne (source de vérité)
snapshot.json     # état courant dérivé (confort de lecture humaine)
photos/<id>.jpg   # originaux compressés
```
L'import rejoue `events.jsonl` (les événements déjà connus par `id` sont ignorés → fusion naturelle). `snapshot.json` n'est jamais importé, il sert à la lecture sans l'app. Le `formatVersion` ne change qu'en ajoutant des champs ; un lecteur v1 doit tolérer les champs inconnus.

## 4. Capture (B1, B2, B4, B7, B10–B14)

Pipeline : `entrée (voix|texte) → texte → Parser → Intent → confirmation → événements`.

### 4.1 Transcription (B2)
- iOS : `SFSpeechRecognizer` avec `requiresOnDeviceRecognition = true` (fr-FR, en-US) ; si indisponible pour la langue/appareil, repli Whisper embarqué (`whisper.cpp`, modèle `base` multilingue, pack optionnel) ; sinon saisie texte.
- Android : `SpeechRecognizer.createOnDeviceSpeechRecognizer` seulement, après `isOnDeviceRecognitionAvailable` (API 31+) ; Android 10–11 : saisie texte, car `EXTRA_PREFER_OFFLINE` n'est qu'un indice (ADR-020) ; même repli Whisper si la mesure le justifie.
- Jamais de reconnaissance réseau : si seul le mode réseau est disponible, l'app propose la saisie texte et explique pourquoi.

### 4.2 Parseur (`shared/capture`) — règles, pas de LLM en v1
Intentions reconnues (FR et EN) :

| Intent | Exemples | Événements |
| --- | --- | --- |
| Store | « passeport dans le tiroir du haut de la commode », « clés @ entrée » | ItemCreated? + ItemStored |
| Take | « je prends la perceuse » | ItemTaken |
| Return | « je remets la perceuse », « perceuse remise au garage » | ItemReturned / ItemStored |
| MoveTo | « la perceuse est maintenant dans le garage » | ItemMovedTo |
| Lend | « j'ai prêté la tente à Marc » | ItemLent |
| ReturnFromLend | « Marc a rendu la tente » | ItemReturned |
| Query | « où est le passeport ? », « où sont les décos de Noël » | → recherche (C5) |
| Multi | « passeport et carnet de santé dans le tiroir » | plusieurs Store |

Méthode : normalisation (minuscules, accents conservés pour l'affichage, repliés pour la comparaison) → détection de l'intent par motifs (verbes, interrogatifs, prépositions `dans | sur | sous | derrière | à côté de | in | on | under`) → découpe objet / lieu → **résolution du lieu par similarité** avec les lieux et alias existants (distance d'édition + embeddings quand le modèle est chargé) → si aucun lieu ne dépasse le seuil, proposition de création (B7). Les quantités (« 12 vis M4 ») sont extraites par expression régulière. Chaque phrase traitée alimente le corpus de non-régression (`corpus/parser.jsonl`).

### 4.3 Points d'entrée (B5)
- Android : `AppWidgetProvider`/Glance (bouton micro + champ texte), `TileService` (tuile), activité transparente `CaptureActivity` lancée depuis les deux.
- iOS : WidgetKit (écran d'accueil + verrouillé) avec `AppIntent` de capture, bouton Action via le même App Intent (ouvre l'app en mode capture : la reconnaissance vocale nécessite le premier plan).

## 5. Recherche (C1–C7, C10–C13)

### 5.1 Pipeline hybride
1. **Normalisation** de la requête et des fiches, mêmes règles des deux côtés (`search/SearchText`) : accents et casse repliés, mots composés aussi joints (« tire-bouchon » → `tirebouchon`), pluriels simples, mots grammaticaux retirés (« où sont mes », « le truc pour »).
2. **Mot-clé** : index inversé **en mémoire** dans `shared` (`KeywordIndex`), BM25 sur champs pondérés (nom, étiquettes, chemin du lieu et contenant, description, dernières phrases dictées), complétion du mot en cours, fautes par distance d'édition (1 dès 4 lettres, 2 dès 8). Pas de FTS5 : absent du SQLite système d'Android et incapable de tolérer les fautes (ADR-021).
3. **Synonymes métier** : groupes FR/EN dans `search/Synonyms.kt` (chargeur/câble/alim, carton/boîte/caisse, clé/clef/key…), un saut d'expansion, poids réduit.
4. **Sémantique** : embedding de la requête (EmbeddingGemma 2, préfixes de tâche de la fiche du modèle) → produit scalaire contre les vecteurs normalisés du cache `embedding` (force brute en mémoire, float16 sur disque ; quelques ms pour 5 000).
5. **Fusion** : somme pondérée du score mot-clé (relatif au meilleur) et de la force sémantique (cosinus au-dessus d'un plancher), puis bonus de récence et de fréquence d'ouverture. La RRF a été mesurée puis écartée : sans écart de score, le bonus de récence suffisait à inverser les rangs (ADR-021).
6. **Réponse** : premier résultat en données structurées (C4, `AnswerLine`) — chemin cliquable (C6), état, date, photo — que chaque UI met en phrase avec ses formatteurs de date ; liste des suivants en dessous ; « rien trouvé » quand aucun mot ne correspond et que le meilleur cosinus reste sous le seuil de réponse.

### 5.2 Modèle et variantes (décision du 24 oct. 2026)
- Au premier lancement, `shared/ml/ModelSelector` lit la RAM totale (`ActivityManager.MemoryInfo` / `ProcessInfo.physicalMemory`) : **≥ 6 Go → variante omnimodale** (texte + image), **sinon texte seul**. Le choix est stocké dans `meta.model_variant` et modifiable dans les réglages (forcer texte seul).
- **Variante texte : incluse dans l'app** (ADR-021, révise ADR-008 pour ce fichier) : `models/manifest.json` épingle la révision et le SHA-256, `./gradlew :models:fetchTextModel` télécharge et vérifie au build, les poids ne sont jamais versionnés. Android : asset non compressé, copié une fois dans `cacheDir/models/` (LiteRT-LM ouvre un fichier) ; iOS : ressource du bundle lue en place. Aucune permission réseau, pas de `ModelPackDownloader` en v1. Sans modèle (build `-Ptrouvetout.bundleModel=false`, processeur 32 bits, échec de chargement), la recherche fonctionne en mot-clé + synonymes et le dit à l'utilisateur.
- Chargement paresseux à la première recherche, une seule utilisation à la fois, libéré quand l'app passe en arrière-plan ou sur avertissement mémoire ; les vecteurs manquants se calculent au premier plan seulement (N6), par lots, avec une progression visible.
- Les vecteurs sont indexés par `model_key` (modèle + dimension + préfixes) et `text_hash` (nom, description, étiquettes — pas le lieu, que le mot-clé couvre : renommer un lieu ne recalcule rien) ; un changement de modèle déclenche une **réindexation** au premier plan, les anciens vecteurs n'étant effacés qu'une fois les nouveaux complets, jamais une perte.
- Photos (variante omnimodale) : embedding de chaque photo à l'ajout ; recherche par photo (C10) = même espace ; nommage (B8) = similarité entre la photo et une liste de ~2 000 libellés d'objets domestiques FR/EN pré-embarqués (`shared/ml/labels_fr_en.txt`) → top 3 proposés.
- C13 (réponse générative, P1, dernier lot) : pack optionnel « LLM » (Qwen3-0.6B ou Gemma 3 1B via LiteRT-LM), utilisé uniquement pour reformuler une réponse déjà trouvée ou répondre à une question composée sur un sous-ensemble de fiches passé en contexte. Jamais sur le chemin critique.
- Les APIs système (Foundation Models, Gemini Nano, ML Kit) peuvent remplacer un pack quand elles sont présentes, derrière la même interface `TextEmbedder` / `ImageEmbedder` / `Rephraser`, mais ne sont jamais une condition.

### 5.3 Évaluation (N3)
`tools/eval-search` rejoue `corpus/recherche.jsonl` (question → id de la fiche attendue sur un jeu de fiches de référence) et sort top-1 / top-3 / latence. Seuils : 80 % / 95 % / < 1 s. Tourne en CI (JVM) avec le modèle texte seul.

## 6. Licence et essai (J1, J4, J5)

- `shared/licensing/TrialPolicy` : `trialStart`, `trialEnd = trialStart + 6 mois`, `canAddItems = purchased || now < trialEnd`.
- Ancrage de `trialStart` :
  - iOS : `AppTransaction.originalPurchaseDate` (StoreKit 2, signé par Apple, hors ligne) ; repli : date du premier lancement stockée dans le Keychain (survit à la réinstallation).
  - Android : date du premier lancement stockée dans `EncryptedSharedPreferences` + sauvegardée par Auto Backup (clé/valeur) ; la réinstallation peut remettre à zéro — accepté.
- Après l'essai : **seules les créations de fiches sont bloquées** (`ItemCreated`). Recherche, consultation, modification, déplacement, export, import, suppression restent libres. L'écran d'échéance explique, propose l'achat et la restauration, et n'apparaît qu'au moment d'un ajout.
- Achat : produit non consommable « déblocage » (10 € fixe) **ou** abonnement annuel (2 €/an) — un seul des deux sera retenu avant le lot J ; la couche `PurchaseGateway` (expect/actual : StoreKit 2 / Play Billing) expose `purchased: Flow<Boolean>` et `restore()`. Aucune validation serveur : vérification locale des transactions signées (StoreKit 2 JWS ; Play Billing : signature publique de l'app), acceptable pour ce niveau de prix.

## 7. Sécurité et vie privée (voir `02-securite-rgpd.md`)

- Pas de réseau en usage normal. Aucune dépendance à un SDK analytique, publicitaire ou de crash reporting tiers. Les crashs sont ceux des magasins (opt-in utilisateur côté OS).
- Fichiers : conteneur privé de l'app, exclus de la photothèque ; sauvegarde système (iCloud/Google) **désactivée** pour la base et les photos sauf si l'utilisateur active A9 (sauvegarde chiffrée par l'app, clé locale).
- Chiffrement au repos (P1) : SQLCipher, clé 256 bits générée dans Keychain (accessible `afterFirstUnlockThisDeviceOnly`) / Keystore (StrongBox si disponible).
- Captures d'écran et sélecteur d'apps : `FLAG_SECURE` optionnel (H3) ; iOS : vue floutée en arrière-plan.

## 8. Performance et budgets

| Budget | Cible | Mesure |
| --- | --- | --- |
| Capture voix → fiche | < 3 s (appareils 2023+), < 5 s ailleurs | test instrumenté avec audio préenregistré |
| Recherche | < 1 s / 5 000 objets | `tools/eval-search` |
| Chargement du modèle texte | < 1,5 s à froid, puis résident tant que l'app est au premier plan | trace |
| Mémoire | pic < 350 Mo (texte seul), < 900 Mo (omnimodal) | profil Android Studio / Instruments |
| Démarrage à froid | < 1,5 s jusqu'à l'écran de capture | trace |
| Batterie | aucun travail en arrière-plan hors notifications locales programmées | audit des WorkManager / BGTask : aucun |

## 9. Internationalisation

FR et EN dès la v1 : chaînes dans `shared/resources` (moko-resources ou équivalent) pour les textes métier partagés, `strings.xml` / `Localizable.xcstrings` pour l'UI native. Le parseur et les synonymes ont un fichier par langue. Les dates relatives (« il y a 3 jours ») utilisent les formatters système.
