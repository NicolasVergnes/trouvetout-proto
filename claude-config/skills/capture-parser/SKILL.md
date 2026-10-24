---
name: capture-parser
description: Parseur de phrases de TrouveTout (shared/capture) — intents FR/EN (ranger, prendre, remettre, déplacer, prêter, rendre, question), extraction objet/lieu/quantité/personne par règles, résolution de lieu par similarité, désambiguïsation, corpus parser.jsonl. Utiliser pour toute modification de la capture texte ou vocale.
---

# Parseur de capture (règles, sans LLM — ADR-004)

## Contrat
```kotlin
public data class ParseResult(val intent: Intent, val items: List<ItemMention>, val place: PlaceMention?,
  val person: String?, val confidence: Float, val alternatives: List<ParseResult> = emptyList())
public sealed interface Intent { Store; Take; Return; MoveTo; Lend; ReturnFromLend; Query; Unknown }
public data class ItemMention(val name: String, val quantity: Double?, val unit: String?)
public data class PlaceMention(val rawText: String, val resolved: PlaceMatch?)   // PlaceMatch = id + score
```
Le parseur est **pur** (pas d'E/S) ; la résolution de lieu reçoit la liste des lieux/alias connus (et un `TextEmbedder?` optionnel).

## Pipeline
1. **Normalisation** : trim, minuscules pour l'analyse (conserver l'original pour l'affichage), apostrophes typographiques → `'`, nombres en lettres → chiffres (« douze » → 12, FR/EN), suppression des hésitations (« euh », « alors », « bon », « um », « uh »), unification `clef/clé`.
2. **Intent** par motifs ordonnés (fichiers `rules_fr.kt`, `rules_en.kt`) :
   - Query : commence par `où (est|sont|se trouve)`, `c'est où`, `where (is|are)`, ou se termine par `?` avec un interrogatif.
   - Take : `je prends`, `j'emporte`, `je sors`, `I'm taking`, `taking`.
   - Return : `je remets`, `je range`, `remis(e)? (dans|au|à)`, `put back`, `back in`.
   - MoveTo : `(est|sont) maintenant (dans|sur|…)`, `is now in`, `moved to`.
   - Lend : `(j'ai )?prêté … à <Personne>`, `lent … to <Name>`.
   - ReturnFromLend : `<Personne> (m')?a rendu …`, `<Name> returned …`.
   - Store (défaut) : présence d'une préposition de lieu ou de `@`.
3. **Découpe objet / lieu** : la préposition de lieu la plus à droite non incluse dans un nom composé connu (« tiroir du haut » contient « du » mais pas de préposition de lieu) sépare objet et lieu. Prépositions FR : `dans, sur, sous, derrière, devant, à côté de, au fond de, en haut de, en bas de, au-dessus de, en dessous de, à, au, aux, chez, @` ; EN : `in, on, under, behind, inside, next to, at the back of, on top of, at, @`. Les articles de tête sont retirés (`le, la, les, l', un, une, des, mon, ma, mes, the, a, my`).
4. **Quantité** : `^(\d+([.,]\d+)?)\s*(m|cm|mm|kg|g|l|cl|paires?|pairs?|boîtes?|boxes?)?\s+(.+)` ; unité normalisée.
5. **Multi-objets** : séparateurs `et`, `,`, `and`, `+` dans la partie objet, sauf à l'intérieur d'un nom connu (vérifier contre les objets existants : « pain et chocolat » reste un objet si une fiche existe).
6. **Résolution du lieu** (`PlaceResolver`) : score = max(similarité normalisée de Levenshtein sur le texte complet et sur chaque segment du chemin, Jaccard des tokens, cosinus des embeddings si disponible) sur les lieux **et** leurs alias ; seuils : ≥ 0,85 → résolu ; 0,6–0,85 → proposition à confirmer (B7) ; < 0,6 → création proposée. Un lieu dicté à plusieurs niveaux (« carton 7 de l'étagère du fond de la cave ») est résolu de droite à gauche (cave → étagère du fond → carton 7), en créant les niveaux manquants après confirmation.
7. **Confiance** : produit des confiances d'intent, de découpe et de résolution ; < 0,5 → la carte de confirmation reste affichée jusqu'à action (pas de disparition automatique).

## Alias (B7)
Quand l'utilisateur rattache « la commode » à « commode de la chambre », ajouter `commode` aux `aliases` du lieu (événement `PlaceRenamed`/`PlaceAliasAdded`). Les alias participent à la résolution et à la recherche.

## Doublons (A14)
Avant `ItemCreated`, chercher un objet actif de nom similaire (Levenshtein normalisé ≥ 0,85 ou même embedding > 0,9) : proposer « Déplacer l'existant » ou « Créer un nouveau ».

## Corpus et tests
- `corpus/parser.jsonl` (format dans `docs/06-corpus-recherche.md`) ; test `ParserCorpusTest` : ≥ 95 % d'intents exacts, ≥ 90 % de découpe exacte (objets et lieu), FR et EN séparément.
- Chaque bug corrigé ajoute une ligne. Les phrases avec `xfail` sont tolérées mais comptées.
- Tests unitaires par règle (une classe par intent), tests de propriétés (une phrase sans préposition n'est jamais `Store` avec lieu).

## Ce qu'il ne faut pas faire
- Pas de modèle de langage pour « deviner » : si c'est ambigu, demander (B7) ; la carte de confirmation est là pour ça.
- Pas de dépendance à la casse ou à la ponctuation de la transcription.
- Pas de logs de la phrase (donnée métier) ; en test seulement.
