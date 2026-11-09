# Site J7 — présentation, politique de confidentialité, format des données

Site statique, autonome : HTML et une feuille de style, **aucun script, aucune police ni ressource externe, aucun
traceur**. Les seuls liens sortants mènent au dépôt GitHub public et aux textes de licence. Hébergement : au choix de
l'éditeur (décision du 8 nov. 2026, ADR-027) ; copier le dossier tel quel à la racine d'un domaine ou d'un sous-dossier,
les liens sont relatifs.

| Page | FR | EN |
| --- | --- | --- |
| Présentation | `index.html` | `en/index.html` |
| Politique de confidentialité | `confidentialite.html` | `en/privacy.html` |
| Format des données | `format.html` | `en/format.html` |
| Licences (bilingue) | `licences.html` | — |

## Une fois en ligne

1. Remplacer `{{PRIVACY_URL}}` (et `{{SITE_URL}}`) dans `store/` par les adresses réelles :
   `…/confidentialite.html` pour la fiche française, `…/en/privacy.html` pour l'anglaise.
2. Ouvrir les pages sur un téléphone (largeur 360 px), en thème clair et sombre.
3. Le nom « TrouveTout » est provisoire (ADR-010) : au changement de nom, mettre à jour ces pages et les fiches.

## Tenir à jour

La politique reprend `docs/02-securite-rgpd.md` §5, qui reste la source : toute modification d'un flux de données
(permission, réseau, stockage, notification) met à jour les deux **avant** la version qui l'introduit (skill
`privacy-rgpd`). Le format reprend `docs/format-export.md`.
