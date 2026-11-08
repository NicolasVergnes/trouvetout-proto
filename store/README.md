# Fiches des magasins

Textes prêts à coller dans la Play Console et App Store Connect, en français et en anglais. Les comptes de
développeur et le nom définitif manquent encore (`docs/07-recette.md`) : rien n'est publié d'ici.

| Dossier | Contenu | Limites |
| --- | --- | --- |
| `play/<langue>/title.txt` | Titre | 30 caractères |
| `play/<langue>/short_description.txt` | Description courte | 80 caractères |
| `play/<langue>/full_description.txt` | Description complète | 4 000 caractères |
| `play/data-safety.md` | Réponses « Sécurité des données », permissions, autres déclarations | — |
| `appstore/<langue>/name.txt`, `subtitle.txt` | Nom, sous-titre | 30 caractères chacun |
| `appstore/<langue>/keywords.txt` | Mots-clés, séparés par des virgules | 100 caractères |
| `appstore/<langue>/promotional_text.txt` | Texte promotionnel | 170 caractères |
| `appstore/<langue>/description.txt` | Description | 4 000 caractères |
| `appstore/app-privacy.md` | « Données non collectées », manifeste, chiffrement, autres champs | — |
| `appstore/review-notes.md` | Notes pour l'examen d'Apple (anglais) | — |
| `screenshots/` | Captures sans donnée réelle (foyer fictif), Android et iPhone, FR et EN | — |

Avant de coller : remplacer `{{PRIVACY_URL}}` et `{{SITE_URL}}` par les adresses du site publié (`site/README.md`),
et le nom si le nom définitif est choisi (ADR-010). Toute évolution d'un flux de données met d'abord à jour
`docs/02-securite-rgpd.md`, le site et ces fiches (skill `privacy-rgpd`).
