# Modèles embarqués

Les poids ne sont **jamais** versionnés (CLAUDE.md, règle 8 ; `.gitignore`, `tools/check-deps.sh`). Ils sont
téléchargés au build par `./gradlew :models:fetchTextModel` depuis une révision épinglée, et refusés si leur
SHA-256 diffère de celui de `manifest.json` (ADR-021).

| Modèle | Variante | Fichier | Taille | Licence |
| --- | --- | --- | --- | --- |
| EmbeddingGemma 2 Text 270M (int4, LiteRT-LM) | `TEXT_ONLY` | `embeddinggemma-2-text-270m.litertlm` | 164 626 432 octets | Apache 2.0 |

Source : [litert-community/embeddinggemma-2-text-270m-litert-lm](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm),
révision `9be6e8b90982095dc05c2bd162e4b954ee4dbac7` ; modèle d'origine `google/embeddinggemma-2`.

## Usage

- **Android** : copié dans les assets de l'APK (`models/…`, non compressé) ; au premier chargement l'app le copie
  une fois dans `cacheDir/models/` car LiteRT-LM ouvre un fichier, pas une entrée d'APK. `-Ptrouvetout.bundleModel=false`
  produit une app sans modèle (recherche par mots-clés seulement).
- **iOS** : ressource du bundle de l'app, lue en place.
- **Évaluation** (`./gradlew :tools:evalSearch`) : même fichier, même runtime (LiteRT-LM JVM, Java 21).

## Changer de version

1. Mettre à jour `url` (révision épinglée), `sha256` (`X-Linked-ETag` du fichier sur Hugging Face, ou somme
   calculée après téléchargement) et `bytes` dans `manifest.json`.
2. L'identifiant du modèle change avec la somme : l'app recalcule les vecteurs (jamais de perte, ADR-003).
3. Relancer `./gradlew :tools:evalSearch` et reporter les scores dans la PR.

## Licences à afficher dans l'app

EmbeddingGemma 2 (Apache 2.0) et LiteRT-LM (Apache 2.0, avec sa `THIRD_PARTY_NOTICE`) : mention dans les
réglages (« À propos ») et page J7.
