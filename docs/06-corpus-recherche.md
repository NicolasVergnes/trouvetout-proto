# Corpus de test — capture et recherche

Deux corpus, construits par le développeur (pas d'enquête), versionnés dans `corpus/`. Ils servent de tests de non-régression et de critère d'acceptation (N3). Chaque ligne porte `origin: synthetic | own-usage | real` pour pouvoir remplacer les phrases synthétiques plus tard.

## 1. `corpus/parser.jsonl` — capture (lot 3)

Objectif : ≥ 150 phrases FR, ≥ 60 EN ; cibles ≥ 95 % d'intents corrects, ≥ 90 % de découpe exacte.

Format :
```json
{"id":"p0001","lang":"fr","origin":"synthetic","input":"passeport dans le tiroir du haut de la commode","expect":{"intent":"Store","items":["passeport"],"place":"tiroir du haut de la commode"}}
{"id":"p0002","lang":"fr","origin":"synthetic","input":"je prends la perceuse","expect":{"intent":"Take","items":["perceuse"]}}
{"id":"p0003","lang":"fr","origin":"synthetic","input":"j'ai prêté la tente à Marc","expect":{"intent":"Lend","items":["tente"],"person":"Marc"}}
{"id":"p0004","lang":"fr","origin":"synthetic","input":"où sont les clés de la voiture ?","expect":{"intent":"Query","query":"clés de la voiture"}}
{"id":"p0005","lang":"fr","origin":"synthetic","input":"12 vis M4 dans le bac bleu de l'atelier","expect":{"intent":"Store","items":["vis M4"],"quantity":12,"place":"bac bleu de l'atelier"}}
{"id":"p0006","lang":"fr","origin":"synthetic","input":"passeport et carnet de santé dans le tiroir","expect":{"intent":"Store","items":["passeport","carnet de santé"],"place":"tiroir"}}
{"id":"p0007","lang":"en","origin":"synthetic","input":"spare keys in the top drawer of the dresser","expect":{"intent":"Store","items":["spare keys"],"place":"top drawer of the dresser"}}
```

Couverture à assurer : les 8 intents ; prépositions FR (`dans, sur, sous, derrière, devant, à côté de, au fond de, en haut de, en bas de, @`) et EN ; lieux à plusieurs niveaux (« carton 7 de l'étagère du fond de la cave ») ; articles élidés (« l'armoire ») ; pluriels ; quantités avec unités (m, cm, kg, paires) ; noms de personnes ; formulations orales (« euh », « alors », « bon ») ; fautes de transcription courantes (« clef/clé », « ou/où »).

## 2. `corpus/recherche.jsonl` — recherche (lot 6)

Objectif : 300 questions FR (+ 60 EN) sur un **jeu de fiches de référence** `corpus/fiches-reference.json` (≈ 400 objets dans ≈ 60 lieux, représentatif d'un foyer : cuisine, chambre, bureau, cave, garage, voiture). Cibles : 80 % top-1, 95 % top-3, < 1 s.

Format :
```json
{"id":"q0001","lang":"fr","origin":"synthetic","query":"le chargeur du Kindle","expect":"item_cable_usbc_blanc","kind":"synonym"}
{"id":"q0002","lang":"fr","origin":"synthetic","query":"décos de Noël","expect":"item_guirlandes","kind":"category"}
{"id":"q0003","lang":"fr","origin":"synthetic","query":"passport","expect":"item_passeport","kind":"crosslang"}
{"id":"q0004","lang":"fr","origin":"synthetic","query":"clé a molette","expect":"item_cle_molette","kind":"typo"}
{"id":"q0005","lang":"fr","origin":"synthetic","query":"le truc pour ouvrir les bouteilles","expect":"item_tire_bouchon","kind":"paraphrase"}
```

Répartition visée par `kind` : exact 20 %, typo/accents 15 %, synonym 20 %, paraphrase/description 20 %, category 10 %, crosslang 5 %, place-based (« ce qu'il y a dans le carton 7 ») 10 %.

## 3. Outils
- `tools/eval-parser` et `tools/eval-search` (Kotlin/JVM, lancés par Gradle) : rejouent les corpus, affichent les scores, échouent sous les seuils ; sortie JSON dans `build/reports/` pour la CI.
- `tools/eval-search --model none` permet de mesurer le mot-clé seul (utile pour les appareils sans pack).

## 4. Procédure d'enrichissement
1. Toute phrase réelle saisie par le développeur pendant ses tests est ajoutée avec `origin: own-usage` (sans donnée personnelle sensible). Le dépôt est public (ADR-013) : seules des phrases synthétiques ou écrites par le développeur sont versionnées ; aucune phrase d'un utilisateur ne rejoint jamais le corpus — l'app n'a, et n'aura, aucun canal pour cela.
2. Chaque bug de capture ou de recherche corrigé ajoute au moins une ligne au corpus correspondant.
3. Les seuils ne sont jamais abaissés ; si un ajout fait échouer, on corrige le moteur ou on documente l'exception dans la ligne (`"xfail": "raison"`).
