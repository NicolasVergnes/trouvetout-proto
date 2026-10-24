---
name: on-device-ml
description: Modèles IA embarqués de TrouveTout — EmbeddingGemma 2 (texte / omnimodal) via LiteRT, choix de variante selon la RAM, packs de modèles à la demande avec vérification SHA-256, recherche sémantique (embeddings, similarité, fusion), nommage d'objet par photo, réindexation. Utiliser pour shared/ml, shared/search, les packs et l'évaluation du corpus de recherche.
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
- Budgets : texte seul pic < 350 Mo ; omnimodal < 900 Mo ; échec de chargement → repli `NoopEmbedder` + message.

## Packs de modèles
- Les poids ne sont jamais dans le dépôt ni dans le binaire. Canal préféré : Play Asset Delivery (`on-demand`) / On-Demand Resources ; sinon téléchargement HTTPS depuis un domaine unique contrôlé.
- Chaque pack est décrit dans `models/manifest.json` (versionné) : `{ id, variant, modelId, files: [{name, sha256, bytes}], license }`. Vérification SHA-256 **avant** usage ; fichier rejeté sinon.
- `models/README.md` documente la conversion (format `.litertlm`/`.tflite` fourni par Google AI Edge) et la licence (Gemma Terms : afficher la mention dans « À propos »).
- Le pack texte seul (~200 Mo) peut être proposé dès la prise en main ; l'omnimodal (~600 Mo) est présenté avec sa taille et un bouton explicite.

## Embeddings et index
- Texte embarqué par fiche : `name + " " + description + " " + tags + " | " + cheminDuLieu` (le lieu aide « les affaires de ski à la cave »). Un second vecteur `rawInput` si présent.
- Photos (omnimodal) : un vecteur par photo, à l'ajout.
- Table `embedding(owner_type, owner_id, model_id, dim, vector float16, text_hash)`. Si `text_hash` change → recalcul ; si `model_id` change → réindexation complète en tâche de premier plan avec barre de progression (quelques secondes pour < 5 000 fiches).
- Recherche : normaliser les vecteurs à l'écriture ; similarité = produit scalaire ; force brute sur tableau contigu en mémoire (chargé au démarrage de la recherche) ; pas de base vectorielle avant 50 000 vecteurs.
- Dimension : utiliser la troncature Matryoshka à 256 si la mémoire le demande (mesurer l'impact sur le corpus avant).

## Fusion (shared/search)
1. Candidats mot-clé : FTS5 (BM25) sur requête normalisée + expansion synonymes (`synonyms_fr.txt`, `synonyms_en.txt`, format `a, b, c` par ligne).
2. Candidats sémantiques : top-50 cosinus (si embedder disponible).
3. RRF : `score = Σ 1/(60 + rang_liste)` ; bonus récence `+0.1·exp(-jours/90)` sur le dernier événement ; bonus fréquence de consultation plafonné.
4. Seuil d'affichage : si le meilleur score sémantique < 0,25 et aucun hit mot-clé → « rien trouvé » avec suggestions (C12).
5. Réponse C4 par gabarit : `"{nom} — {chemin lieu}{, contenant}, {état verbe} {date relative}"` ; aucune génération.

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
