# Sécurité — Security

## Signaler une faille

Écrivez à **glopglopdev@gmail.com**, ou ouvrez un avis privé sur GitHub (onglet *Security* → *Report a
vulnerability*). Merci de ne pas ouvrir d'issue publique pour une faille.

Indiquez la version de l'application, le téléphone et son système, et les étapes pour reproduire. Vous recevrez une
réponse sous 7 jours. Une correction est publiée dès que possible, puis la faille est décrite dans `CHANGELOG.md`.

## Ce qui compte comme une faille

L'application ne collecte rien et n'a pas de serveur (voir `docs/02-securite-rgpd.md`). Nous voulons savoir en
particulier :

- toute donnée qui quitte le téléphone sans action de l'utilisateur (requête réseau, journal, sauvegarde du système) ;
- la base chiffrée ou la sauvegarde chiffrée lisibles sans leur clé ;
- un objet sensible visible dans un widget, une notification, une miniature ou sous le verrouillage ;
- un fichier d'import qui fait planter l'application, écrit hors de son dossier ou contourne les limites de taille ;
- l'achat débloqué sans transaction signée par le magasin.

## Reporting a vulnerability

Email **glopglopdev@gmail.com** or open a private GitHub security advisory (*Security* → *Report a
vulnerability*); please do not open a public issue. Include the app version, the phone and its OS, and the steps to
reproduce. You will get an answer within 7 days.
