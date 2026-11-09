# Journal des versions — Changelog

Format : une entrée par version publiée ; les lots de construction sont détaillés dans `docs/03-plan-de-lots.md`
et les décisions dans `docs/04-decisions.md`.

## 0.1.0 — à publier

Première version (v1 : P0 + P1, sauf la réponse générative C13 reportée en P2, ADR-026).

- **Capture** : une phrase dite ou tapée (« passeport dans le tiroir du haut de la commode ») range l'objet et crée
  le lieu au besoin ; prendre, remettre, déplacer, prêter, rendre ; plusieurs objets par phrase ; mode « je range » ;
  widget, tuile Android, bouton Action et Raccourcis sur iPhone ; reconnaissance vocale sur le téléphone seulement.
- **Recherche** : avec ses propres mots, fautes et synonymes compris, modèle EmbeddingGemma 2 sur le téléphone ;
  réponse en une ligne (lieu, état, date, photo), filtres, suggestions ; recherche par photo et noms proposés
  d'après une photo sur les téléphones de 6 Go et plus.
- **Lieux** : arborescence libre, contenants déplaçables avec leur contenu, modèles de lieux proposés, photos,
  renommage et fusion sans perte d'historique, impression d'un lieu ou d'un contenant.
- **Rappels** : à une date (« rappelle-moi… ») et vérification tous les six mois, notifications locales.
- **Vos données** : journal d'événements jamais écrasé ; export ZIP lisible sans l'application et import ; corbeille
  de 30 jours ; sauvegarde chiffrée optionnelle dans un fichier de votre choix, avec une clé de secours ; suppression
  totale en une action.
- **Vie privée et sécurité** : aucune collecte, aucun compte, aucun accès réseau propre sur Android ; base chiffrée
  (SQLCipher) ; verrouillage optionnel par l'authentification du téléphone ; objets sensibles masqués.
- **Accessibilité** : VoiceOver et TalkBack, grandes tailles de texte, mode simplifié à deux boutons, thème sombre.
- **Essai et achat** : six mois d'essai complet, puis seuls les ajouts demandent l'achat (10 € une fois ou 2 € par
  an) ; tout le reste continue de fonctionner.
- **Prise en main** : une carte « Pour commencer » sur l'écran d'accueil, jamais bloquante.
