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
| Embeddings | EmbeddingGemma 2 via LiteRT / Google AI Edge (Kotlin sur Android, Swift via le package LiteRT sur iOS) | Un seul modèle, deux plateformes |
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
CREATE TABLE embedding (
  owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, model_id TEXT NOT NULL,
  dim INTEGER NOT NULL, vector BLOB NOT NULL, text_hash TEXT NOT NULL,
  PRIMARY KEY (owner_type, owner_id, model_id)
);
CREATE VIRTUAL TABLE item_fts USING fts5(name, description, tags, place_path, raw_input,
  content='', tokenize='unicode61 remove_diacritics 2');
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
1. **Normalisation** de la requête (accents repliés, pluriels simples, fautes via distance ≤ 2 sur les tokens rares).
2. **Mot-clé** : FTS5 sur `item_fts` (préfixes activés) → score BM25.
3. **Synonymes métier** : table `synonyms` embarquée (FR/EN : chargeur/câble/alim, carton/boîte/caisse, clé/clef…) étendant la requête.
4. **Sémantique** : embedding de la requête (EmbeddingGemma 2) → similarité cosinus contre `embedding` (force brute, float16, < 5 000 vecteurs : quelques ms).
5. **Fusion** : Reciprocal Rank Fusion des listes (mot-clé, sémantique) + bonus récence (dernier événement) et fréquence de consultation.
6. **Réponse** : premier résultat rendu en phrase (C4) via un gabarit, pas de génération : `{chemin complet}, {état} {date relative}` ; liste des suivants en dessous.

### 5.2 Modèle et variantes (décision du 24 oct. 2026)
- Au premier lancement, `shared/ml/ModelSelector` lit la RAM totale (`ActivityManager.MemoryInfo` / `ProcessInfo.physicalMemory`) : **≥ 6 Go → variante omnimodale** (texte + image), **sinon texte seul**. Le choix est stocké dans `meta.model_variant` et modifiable dans les réglages (forcer texte seul).
- Les poids ne sont **pas** dans le binaire : pack téléchargé à la demande (Play Asset Delivery `on-demand` / On-Demand Resources ou téléchargement HTTPS depuis un domaine contrôlé, avec somme SHA-256 vérifiée). Tant que le pack n'est pas là, la recherche fonctionne en mot-clé + synonymes et le dit à l'utilisateur.
- Les vecteurs sont indexés par `model_id` ; un changement de variante déclenche une **réindexation** depuis `raw_input` / `name` / `description` (quelques secondes pour un foyer), jamais une perte.
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
