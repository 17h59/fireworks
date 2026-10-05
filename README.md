# FW Chat

Une application Android de chat conçue **exclusivement pour [Fireworks AI](https://fireworks.ai)**.
Simple, rapide, et **100 % locale** : tes conversations, tes prompts et ta clé API restent sur ton
téléphone. Aucun serveur intermédiaire, aucun compte : seules les requêtes d'inférence partent vers Fireworks.

> Kotlin · Jetpack Compose · Material 3 · Room · Android 14+ (testé sur Android 15)

## Pourquoi

Le playground de Fireworks est limité et peu confortable. FW Chat donne accès à **tous les modèles
utilisables directement** (serverless), avec un vrai confort de chat : historique, branches, prompts système,
paramètres de génération, raisonnement visible, coûts.

## Fonctionnalités

**Conversations**
- Chats sauvegardés, **renommables**, **recherchables par nom**, repris là où tu les as laissés.
- **Branches** : modifie un message et renvoie-le, l'IA régénère et une branche est créée (`‹ 2/3 ›` pour naviguer).
  Même chose en régénérant une réponse. « Enregistrer » modifie sur place, sans régénérer ni brancher.
- **Fork** depuis n'importe quel message (« Nouveau chat à partir d'ici ») et duplication d'un chat.
- Actions sous chaque message : copier, modifier, régénérer, nouveau chat à partir d'ici, supprimer.
- Sauvegarde et restauration JSON de tout (chats + prompts).

**Modèles et prompts**
- Liste des modèles rechargée au démarrage et à chaque erreur liée aux modèles ; **modèle par défaut** pour chaque nouveau chat.
- **Prompts système illimités**, un par défaut ; choisis au début d'une discussion puis **figés** : un chat
  garde pour toujours le prompt avec lequel il a démarré (ou l'absence de prompt).
- Un [guide de rédaction](docs/GUIDE_PROMPTS_SYSTEME.md) par famille de modèles (GLM, DeepSeek, Qwen, Kimi,
  MiniMax, gpt-oss, Nemotron) et des modèles de départ.

**Lecture**
- Réponses **en streaming**, markdown rendu de façon transparente (aucune boîte « markdown »), très gros textes fluides.
- **Raisonnement** (thinking) streamé dans un bloc repliable : aperçu fixe des 5 premières lignes, « Tout afficher »
  pour tout lire, re-toucher le ☰ pour replier.
- Auto-scroll intelligent : suit le texte quand tu es en bas, te laisse libre dès que tu remontes.
- Interface edge-to-edge qui respecte la barre de navigation (3 boutons ou gestes) et le clavier.

**Réglages Fireworks**
- Température, tokens max, mots d'arrêt, top-p/top-k/min-p, pénalités, seed, effort de raisonnement,
  accessibles **directement depuis le chat**. Ils sont **globaux** : identiques dans toutes les conversations,
  quel que soit le modèle, et conservés quand tu fermes l'app.

**Coûts**
- Icônes **`$` à `$$$$`** à côté de chaque modèle (indicatif, table de prix datée).
- Carte « Dépenses & crédit » dans les Réglages : dépenses du mois (lues sur ton compte) et crédit estimé
  à partir du solde que tu saisis.

## Installer

1. Depuis ton téléphone, ouvre la [release `latest`](https://github.com/17h59/fireworks/releases/tag/latest)
   et télécharge `fw-chat.apk` (autorise l'installation depuis ce navigateur si Android le demande).
2. Au premier lancement, colle ta **clé API Fireworks** (créée sur [fireworks.ai](https://fireworks.ai)). Elle est
   stockée **chiffrée** via l'Android Keystore.
3. Choisis un modèle, écris, c'est parti.

L'APK est reconstruit automatiquement à chaque push sur `main` : la release `latest` est toujours à jour, et
chaque version s'installe par-dessus la précédente sans perdre tes données.

## Documentation

| Document | Contenu |
|---|---|
| [Guide d'utilisation](docs/UTILISATION.md) | Prise en main, branches, prompts, réglages, coûts, sauvegarde |
| [Guide des prompts système](docs/GUIDE_PROMPTS_SYSTEME.md) | Comment rédiger un prompt système selon la famille de modèle |
| [Développement](docs/DEVELOPPEMENT.md) | Builder, tester, CI, conventions |
| [Architecture](docs/ARCHITECTURE.md) | Couches, sémantique des branches, API Fireworks |
| [API markdown](docs/MARKDOWN_API.md) | Le parseur incrémental et le rendu Compose |

## Confidentialité et sécurité

- Aucune télémétrie, aucun serveur tiers. Permissions : `INTERNET`, plus un service de premier plan et les
  notifications pour que les réponses longues continuent quand tu changes d'application.
- Clé API chiffrée (AES-256/GCM, Android Keystore), exclue des sauvegardes Android (`allowBackup=false`).
- HTTPS uniquement. L'APK est signé avec une clé de debug **publique** versionnée dans le dépôt (pour que les mises à jour
  s'installent sans configuration) : ne distribue pas cet APK comme un binaire « de confiance ».

## Licence

Voir [LICENSE](LICENSE).
