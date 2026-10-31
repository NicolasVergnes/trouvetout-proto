---
name: on-device-ml
description: Modèles IA embarqués de TrouveTout — EmbeddingGemma 2 (texte / omnimodal) via LiteRT-LM, choix de variante selon la RAM, modèle inclus au build avec vérification SHA-256, recherche sémantique (embeddings, similarité, fusion), nommage d'objet par photo, réindexation. Utiliser pour shared/ml, shared/search, models/ et l'évaluation du corpus de recherche.
---

# IA sur l'appareil

Décision de référence : ADR-003 (`docs/04-decisions.md`) et `docs/01-architecture.md` §5.

## Interfaces (shared/ml)
```kotlin
public interface TextEmbedder  { val modelId: String; val dim: Int; suspend fun embed(texts: List<String>): List<FloatArray> }
public interface ImageEmbedder { val modelId: String; val dim: Int; suspend fun embed(image: ImageRef): FloatArray }
public interface Rephraser     { suspend fun rephrase(answer: Answer, locale: Locale): String }   // C13, optionnel
public sealed interface ModelVariant { object TextOnly; object Omnimodal }
public interface ModelPackManager { fun state(): Flow<PackState>; suspend fun download(variant); suspend fun remove(variant) }
```
Implémentations : `LiteRtTextEmbedder`, `LiteRtImageEmbedder` (Android : LiteRT Kotlin ; iOS : LiteRT Swift/C via pont), `NoopEmbedder` (recherche mot-clé seule), `FakeEmbedder` (tests, déterministe : hachage → vecteur).

## Choix de variante (ModelSelector)
- Au premier lancement : `totalRam >= 6 GiB → Omnimodal` sinon `TextOnly`. Stocké dans `meta.model_variant` ; réglage « Forcer texte seul » ; le passage à l'omnimodal depuis les réglages n'est proposé que si la RAM le permet.
- Jamais de chargement du modèle en arrière-plan ; chargement paresseux au premier besoin, résident tant que l'app est au premier plan, libéré sur avertissement mémoire (`onTrimMemory` / `didReceiveMemoryWarning`).
- Budgets : texte seul < 350 Mo de mémoire sale (mesuré : 212 Mo sur API 29, PSS 434 Mo dont le fichier projeté) ; omnimodal < 900 Mo ; échec de chargement → `SearchMode.KeywordOnly(raison)` et bandeau explicatif.

## Modèle embarqué (ADR-021)
- Les poids ne sont jamais dans le dépôt. La variante texte est **incluse dans l'app** : `models/manifest.json` (versionné) donne `{ id, variant, file, url, sha256, bytes, license, source }` par modèle ; `./gradlew :models:fetchTextModel` télécharge la révision épinglée et refuse un fichier dont le SHA-256 diffère ; Android le copie dans des assets générés (non compressés), iOS le déclare comme ressource dans `iosApp/project.yml`, `tools` le reçoit pour l'évaluation. `-Ptrouvetout.bundleModel=false` produit une app sans modèle.
- Runtime : LiteRT-LM (`EmbeddingEngine`, tokenizer intégré au `.litertlm`) — Android `AssetTextModelSource` (`shared/androidMain`, copie unique vers `cacheDir/models/`, ABI arm64-v8a/x86_64 seulement), iOS `LiteRtTextModel.swift` via `CallbackTextModelSource` (Swift ne peut pas implémenter de `suspend`), JVM dans `tools/EvalSearch.kt` (Java 21).
- LiteRT-LM **refuse** une entrée plus longue que `maxInputLength` (256) : le noyau coupe les textes à 400 caractères (`PromptStyle.clip`).
- Pas de `ModelPackDownloader` ni de permission réseau en v1. Livraison Play : pack d'assets *fast-follow* au lot 12 (limite de 200 Mo du module de base). Le lot 7 tranche pour l'omnimodal (485 Mo).
- Licence Apache 2.0 (EmbeddingGemma 2 et LiteRT-LM) : mention dans les réglages, `THIRD_PARTY_NOTICE` pour J7.

## Embeddings et index
- Texte vectorisé : `nom. description. étiquettes. Rangé : racine, pièce` avec le préfixe document de la fiche du modèle (`PromptStyle.MODEL_CARD`) : la racine et la pièce donnent du contexte aux fiches nues ; les niveaux plus fins restent au mot-clé. Changer la forme du texte = incrémenter `DOCUMENT_TEXT_VERSION` (`SearchTuning`). Nom en `title:` et phrase dictée ajoutée ont été mesurés sans gain.
- Photos (omnimodal, lot 7) : un vecteur par photo, à l'ajout.
- Table cache `embedding(owner_type, owner_id, model_key, dim, vector float16, text_hash)` : `model_key` = modèle + dimension + préfixes ; `text_hash` change → recalcul ; autre modèle → réindexation complète au premier plan avec progression, anciens vecteurs effacés une fois les nouveaux complets. Jamais exportée.
- Recherche : vecteurs normalisés ; produit scalaire en force brute sur un tableau contigu (`SemanticIndex`) ; 256 dimensions (768 n'apporte rien en FR).
- Calcul des vecteurs seulement quand l'hôte appelle `SearchService.prepare()` (écran ou app au premier plan) ; `release()` quand l'app passe en arrière-plan ou sur avertissement mémoire (N6).

## Fusion (shared/search, `SearchTuning`)
1. Mot-clé : `KeywordIndex` en mémoire (pas de FTS5 : absent du SQLite d'Android), BM25 sur champs pondérés, complétion du dernier mot, fautes par distance d'édition et par clé phonétique française (`SearchText.phonetic`), synonymes FR/EN de `Synonyms.kt` (un saut, poids 0,7).
2. Sémantique : top-50 produits scalaires, filtrés au-dessus de 0,55 et à moins de 0,2 du meilleur.
3. Fusion **linéaire** : `0,3 × (score mot-clé / meilleur) + 0,7 × force` (0,6 / 0,4 quand la meilleure fiche mot-clé contient tous les mots de la question), force = `(cos − moyenne des 50 candidats) / 0,1` bornée à [0, 1] ; puis `× (1 + 0,1·exp(−jours/90))` et jusqu'à +10 % pour la fréquence d'ouverture (`search_usage`). La RRF a été écartée : sans écart de score, le bonus de récence inversait les rangs.
4. « Rien trouvé » : aucun mot commun et meilleur cosinus < 0,7.
5. Réponse C4 en données (`AnswerLine`) ; la phrase et la date relative sont écrites par chaque UI.
6. Tout réglage se mesure avec `./gradlew :tools:evalSearch -PevalArgs="…"` (options `--mode`, `--prompt`, `--dim`, `--share`, `--floor`, `--span`, `--margin`, `--gap`, `--answer-floor`, `--no-latency`) **sur le split `dev`** ; le `holdout` vérifie la généralisation.

## Nommage par photo (B8) et recherche par photo (C10)
- `labels_fr_en.txt` : ~2 000 libellés d'objets domestiques (FR puis EN par ligne) embarqués et pré-vectorisés au premier chargement de l'omnimodal (cache dans `embedding` avec `owner_type='label'`).
- Photo → vecteur → top-3 libellés (seuil 0,2) proposés comme nom ; l'utilisateur confirme ou tape. Jamais d'auto-nommage silencieux.
- Recherche par photo : vecteur de la photo contre les vecteurs des photos et des fiches ; option utilisateur (réglage), désactivée par défaut sur texte seul.

## Réponse générative (C13, lot 11, optionnel)
- Pack « LLM » séparé (Qwen3-0.6B ou Gemma 3 1B, format LiteRT-LM) ; `LiteRtRephraser` ; prompt contraint : contexte = top-5 fiches (nom, lieu, date), consigne de ne citer que ces fiches, 60 jetons max, température 0.
- Jamais sur le chemin critique : la réponse gabarit est affichée d'abord, la reformulation remplace si elle arrive en < 2 s.

## APIs système en bonus (jamais requises)
- iOS 26+ Foundation Models, Android ML Kit GenAI : derrière `Rephraser` / `ImageEmbedder` uniquement si le pack correspondant est absent et que l'utilisateur l'autorise dans les réglages (« Utiliser l'IA du système »). Désactivé par défaut pour garder une promesse simple.

## Évaluation (N3)
- `tools/eval-search` : charge `corpus/fiches-reference.json`, indexe avec l'embedder réel (JVM) ou `--model none`, rejoue `corpus/recherche.jsonl`, sort top-1/top-3/latence p50/p95, échoue sous 80 % / 95 % / 1 s.
- Comparer systématiquement : mot-clé seul, sémantique seule, fusion. Garder le rapport dans la PR.

## Pièges
- Ne pas tokeniser le français avec un tokenizer anglais : utiliser le tokenizer livré avec le modèle.
- Préfixes de tâche : EmbeddingGemma attend des préfixes (`task: search result | query:` / `title: none | text:`) — les appliquer de façon cohérente requête/document et le documenter dans `LiteRtTextEmbedder`.
- Float16 pour le stockage, float32 pour le calcul ; vérifier l'absence de NaN après normalisation.
- Licence Gemma Terms : usage commercial autorisé avec restrictions ; inclure la notice dans l'app.
