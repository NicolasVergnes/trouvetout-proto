# Modèles embarqués

Les poids ne sont **jamais** versionnés (CLAUDE.md, règle 8 ; `.gitignore`, `tools/check-deps.sh`). Ils sont
téléchargés au build par `./gradlew :models:fetchModels` depuis une révision épinglée, et refusés si leur
SHA-256 diffère de celui de `manifest.json` (ADR-021, ADR-022).

| Modèle | Variante | Fichier | Taille | Licence |
| --- | --- | --- | --- | --- |
| EmbeddingGemma 2 Text 270M (int4, LiteRT-LM) | `TEXT_ONLY` | `embeddinggemma-2-text-270m.litertlm` | 164 626 432 octets | Apache 2.0 |
| EmbeddingGemma 2 Text-Vision 440M (int4, LiteRT-LM) | `OMNIMODAL` | `embeddinggemma-2-text-vision-440m.litertlm` | 387 710 976 octets | Apache 2.0 |

Sources :
- [litert-community/embeddinggemma-2-text-270m-litert-lm](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm),
  révision `9be6e8b90982095dc05c2bd162e4b954ee4dbac7` ;
- [litert-community/embeddinggemma-2-text-vision-440m-litert-lm](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm),
  révision `e301f74d5551b0c2641bd5cb4652a76239d5c5f8` ;
- modèle d'origine `google/embeddinggemma-2`.

Les sections texte des deux fichiers sont identiques : une seule identité texte, les vecteurs des fiches restent
valables d'un fichier à l'autre (`tools/eval-photos` le vérifie). Un téléphone < 6 Go charge le 270M, un téléphone
≥ 6 Go le 440M seul (texte et images) ; voir ADR-022.

## Catalogue des libellés (B8)

`labels_fr_en.tsv` (~2 000 libellés d'objets domestiques FR/EN, versionné) → `labels_fr_en.catalog` (vecteurs
calculés au build, ≈ 1 Mo, versionné : ce sont des données dérivées, pas des poids) par
`./gradlew :tools:genLabels` (gabarit `doc` par défaut, `-PlabelTemplate=…` pour en essayer un autre). L'en-tête
porte l'identité du modèle texte, la dimension, le gabarit et le SHA-256 du TSV ; `LabelCatalogFreshnessTest`
échoue si le TSV ou le modèle change sans régénération.

## Usage

- **Android** : copiés dans les assets de l'APK (`models/…`, non compressés) avec le catalogue ; au premier
  chargement l'app copie le seul fichier utilisé dans `cacheDir/models/` car LiteRT-LM ouvre un fichier, pas une
  entrée d'APK. `-Ptrouvetout.bundleModel=false` produit une app sans modèle (recherche par mots-clés seulement).
- **iOS** : ressources du bundle de l'app, lues en place.
- **Évaluation** (`./gradlew :tools:evalSearch :tools:evalPhotos`) : mêmes fichiers, même runtime (LiteRT-LM JVM,
  Java 21).

## Changer de version

1. Mettre à jour `url` (révision épinglée), `sha256` (`X-Linked-ETag` du fichier sur Hugging Face, ou somme
   calculée après téléchargement) et `bytes` dans `manifest.json`.
2. L'identifiant du modèle change avec la somme : l'app recalcule les vecteurs (jamais de perte, ADR-003) ;
   régénérer le catalogue (`:tools:genLabels`).
3. Relancer `./gradlew :tools:evalSearch :tools:evalPhotos` et reporter les scores dans la PR.

## Licences à afficher dans l'app

EmbeddingGemma 2 (Apache 2.0) et LiteRT-LM (Apache 2.0, avec sa `THIRD_PARTY_NOTICE`) : mention dans les
réglages (« À propos ») et page J7.
