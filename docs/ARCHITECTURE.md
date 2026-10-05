# FW Chat — Architecture

App Android 100 % locale pour l'API Fireworks AI. Kotlin + Jetpack Compose, minSdk 34. Aucun cloud hormis les appels d'inférence Fireworks. Injection manuelle via `AppContainer` (pas de Hilt).

## Couches et packages (`app.fwchat.…`)

| Package | Contenu |
|---|---|
| `domain` | Modèles et interfaces (contrat sans dépendance Android) : `Chat`, `Message`, `GenParams`, `ChatRepository`, `ChatEngine`, `FireworksApi`, `BillingRepository`, `ModelPricing`… |
| `data.db`, `data.repo` | Room (chats, messages en arbre, prompts), `ChatRepositoryImpl`, `ThreadLogic` (logique d'arbre pure), sauvegarde JSON |
| `data.net` | `FireworksApiImpl` (OkHttp + SSE, relances 429/5xx avant le flux), `ChatEngineImpl`, `ModelRepositoryImpl`, `BillingRepositoryImpl`, mapping d'erreurs |
| `data.prefs` | `SettingsRepositoryImpl` (DataStore) et clé API chiffrée par Android Keystore (`SecretStore`) |
| `service` | `GenerationService` : service de premier plan tant qu'une génération est en cours |
| `ui.chat` | Écran de chat : liste de messages paresseuse, bloc de réflexion, édition, composer, auto-scroll |
| `ui.common` | Feuilles de choix (modèle, prompt), éditeur de paramètres, badge de coût, dialogue de renommage |
| `ui.markdown` | Parseur markdown incrémental + rendu Compose paresseux (voir [MARKDOWN_API.md](MARKDOWN_API.md)) |
| `ui.prompts` | Modèles de prompts par famille, conseils, écrans de la bibliothèque |
| `ui.settings`, `ui.shell`, `ui.theme` | Réglages (dont dépenses et sauvegarde), navigation/tiroir/onboarding, thème et marque |

Injection manuelle via `AppContainer` (pas de Hilt). Les flux de données vont de Room / DataStore vers des `Flow`
observés par les ViewModels ; la génération vit dans un scope applicatif (`ChatEngine`) et survit à la navigation.

## Sémantique des conversations (branches)

- Un chat est un **arbre** de messages. `parentId = null` ⇒ racine (il peut y en avoir plusieurs si le 1er message est édité avec renvoi). Chemin actif : `Chat.selectedRootId`, puis `Message.selectedChildId` de nœud en nœud. Si un `selectedChildId` est null, on prend l'enfant le plus récent.
- Le prompt système n'est pas un message : c'est un **snapshot** (`systemPromptName/Text`) copié dans le chat à sa création. Irréversible : aucune API pour le modifier. Il est envoyé en premier message `system` à chaque requête ; s'il est null, aucun message `system` n'est envoyé.
- **Modifier sur place** (`editInPlace`) : change le texte, `edited = true`, aucune branche, aucune régénération (message utilisateur ou assistant).
- **Modifier et envoyer** (utilisateur, action principale par défaut) : `editUserAsBranch` crée un frère avec le même parent, le sélectionne, puis l'IA régénère sous ce nouveau message.
- **Régénérer** (assistant) : `addAssistantSibling` : nouvelle réponse sœur, sélectionnée.
- **Flèches `<  2/3  >`** : affichées sous tout message qui a ≥ 2 frères ; `selectSibling` sélectionne le frère ; le contenu en dessous suit les `selectedChildId` mémorisés dans cette branche.
- **Fork** : `fork(chatId, uptoMessageId)` clone le chemin actif racine → message inclus dans un nouveau chat, titre `Fork de <titre>`, mêmes modèle/prompt système/paramètres ; les autres branches et ce qui suit sont écartés.
- **Suppression d'un message** : supprime son sous-arbre ; la sélection du parent retombe sur un frère restant (sinon null).
- **Titre** : vide à la création ; au 1er message utilisateur, déduit de la 1re ligne (≈ 40 caractères). Renommage manuel libre.
- **Streaming** : le texte live vit en mémoire (`ChatEngine.streaming`), la DB est mise à jour par checkpoints (~1/s) et à la fin. Au démarrage, `recoverInterrupted()` marque INTERRUPTED les messages restés STREAMING.
- **Modèle** : propre à chaque chat, modifiable à tout moment. **Paramètres de génération** : un seul jeu **global** persistant (`SettingsRepository.defaultParams`), lu par le moteur à chaque requête ; `Chat.params` n'est conservé qu'à titre de trace (pas de migration) et n'est plus la source de vérité.

## API Fireworks (vérifié avec une vraie clé)

- Liste : `GET https://api.fireworks.ai/v1/accounts/fireworks/models?pageSize=200&pageToken=…` (Bearer). Champs utiles : `name`, `displayName`, `contextLength`, `supportsServerless`, `supportsImageInput`, `supportsTools`, `kind`, `state`. Garder : `supportsServerless == true`, `state == READY`, `kind` ne contient pas `EMBEDDING`, nom ne contient pas `reranker`. (309 modèles listés, ~15 serverless.)
- Chat : `POST https://api.fireworks.ai/inference/v1/chat/completions`, `stream: true`, `stream_options: {include_usage: true}`. Deltas : `content`, `reasoning_content`. Dernier chunk : `usage` (avec `completion_tokens_details.reasoning_tokens`). Fin : `data: [DONE]`.
- Paramètres : `temperature` 0–2, `top_p`, `top_k` 0–100, `min_p`, `max_tokens`, `stop` (4 max), `frequency_penalty`, `presence_penalty`, `repetition_penalty`, `seed`, `reasoning_effort` (`none|low|medium|high|max`).
- Les modèles à raisonnement consomment `max_tokens` (contenu vide + `finish_reason: length` si trop bas) → défaut 16384 et message explicite « coupé pendant la réflexion » quand `content` est vide et `finish_reason == length`.
- Erreurs : 401 → Unauthorized ; 402 → InsufficientCredit ; 429 → RateLimited ; 404 / `NOT_FOUND` sur le modèle → ModelNotFound (⇒ `ModelRepository.refresh()`).
- Modèles serverless connus à ce jour : deepseek-v4p1-flash, ember-1, glm-5p2, glm-5p3, glm-5p3-flash, gpt-oss-120b, inkling, kimi-k3, minimax-m3, nemotron-3-ultra-nvfp4, nemotron-lightning-3p5-30b-a3b, qwen3p8-2p4t-a95b, qwen3p8-max. Un prompt système est respecté par tous (testé).
- **Jamais de clé API dans le dépôt, les tests ou les logs.** Pour les tests live, lire `FW_API_KEY` dans l'environnement.

## UX (cibles)

- Écran principal = le chat. Tiroir latéral : « Nouveau chat », recherche par nom, liste des chats (appui long ou menu ⋮ : renommer, dupliquer-fork, supprimer), accès Réglages. Pas de sous-menus imbriqués.
- Nouveau chat : bandeau avec deux pastilles, **modèle** (défaut préchargé) et **prompt système** (défaut préchargé, ou « Aucun »), modifiables **jusqu'à l'envoi du premier message** ; ensuite le prompt est verrouillé (affiché en lecture seule).
- Barre du chat : pastille du modèle (changeable), icône réglages de génération (feuille du bas : température, tokens max, stop, top_p/top_k/min_p, pénalités, seed, raisonnement), menu ⋮.
- Sous chaque message : copier, modifier, (utilisateur) modifier+envoyer, (assistant) régénérer, forker ici, supprimer ; tokens utilisés sur les réponses.
- Thinking : bloc repliable (icône hamburger) au-dessus de la réponse, streamé, 5 lignes max en aperçu + « Tout afficher » ; re-cliquer le hamburger replie complètement.
- Auto-scroll : suit le texte tant que l'utilisateur est tout en bas ; dès qu'il remonte, il est libre ; bouton « ↓ » pour revenir en bas.
- Insets : edge-to-edge, `WindowInsets.systemBars` + `ime` gérés (le champ de saisie ne passe jamais sous la barre de navigation à 3 boutons ni sous le clavier).
- Markdown « transparent » : rendu direct, aucune boîte indiquant du markdown ; seuls les blocs de code ont un fond discret. Très long texte fluide (rendu paresseux par blocs, re-parse incrémental du dernier bloc pendant le streaming).
- Français uniquement en v1.

## Génération, reprise et arrière-plan

- `ChatEngineImpl` : une génération à la fois par chat ; texte live en mémoire (`streaming`, publié ~25 fois/s),
  checkpoints DB ~1/s, finalisation sous `NonCancellable` (`updateStreaming` final puis `finishMessage`).
  `recoverInterrupted()` s'exécute avant toute génération au démarrage.
- Le raisonnement « Aucun » est omis pour les modèles GLM et gpt-oss (`GenParamsCompat`), qui le refusent.
- `GenerationService` (foreground, type `dataSync`) est démarré par l'`AppContainer` dès qu'un chat génère, pour que
  le processus ne soit pas gelé quand l'app passe en arrière-plan ; il s'arrête seul quand plus rien ne génère.
- `EngineEvent.Error` porte le `chatId` : l'UI n'affiche en snackbar que les erreurs du chat visible, et pas celles
  déjà visibles dans le fil.

## Coûts (`BillingRepository`, `ModelPricing`)

- Aucune API de prix : table codée en dur et datée (`domain/ModelPricing.kt`), paliers `$`–`$$$$`.
- Dépenses du mois : `GET /v1/accounts` (id du compte, l'e-mail n'est jamais conservé) puis
  `GET /v1/{compte}/quotas/monthly-spend-usd` (`usage`). Le crédit n'est pas exposé par l'API : estimation =
  solde saisi par l'utilisateur − dépenses depuis la saisie (`billing/summary` si le mois a changé).
- Rafraîchi à l'ouverture des Réglages et à la fin de chaque génération (≥ 5 s entre deux requêtes).

## Choix de conception notables

- **Prompt système figé** : copié dans le chat à sa création, aucune API ne permet de le modifier.
- **Streaming performant** : seuls les blocs markdown *fermés* sont des items de `LazyColumn` ; le dernier bloc ouvert
  est rendu dans un item « live » ; le fil observé ignore les checkpoints de contenu des messages en cours.
- **Aperçu de la réflexion** : un `Text(maxLines = 5)` sur le début du texte (pas de layout personnalisé : un rognage
  mal placé avait fait déborder le texte sur les éléments voisins).
- **Sécurité** : clé API chiffrée (AES-GCM/Keystore), `allowBackup=false`, HTTPS seulement, aucune clé en dur
  (les tests live lisent `FW_API_KEY`).
