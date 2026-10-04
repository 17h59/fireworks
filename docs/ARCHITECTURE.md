# FW Chat — Architecture (v1)

App Android 100 % locale pour l'API Fireworks AI. Kotlin + Jetpack Compose, minSdk 34. Aucun cloud hormis les appels d'inférence Fireworks. Injection manuelle via `AppContainer` (pas de Hilt).

## Découpage par package (propriété des fichiers)

| Package (`app.fwchat.…`) | Contenu | Propriétaire |
|---|---|---|
| `domain` | Modèles + interfaces (contrat). **Ne pas modifier sans l'orchestrateur.** | orchestrateur |
| `data.db` + `data.repo` | Room, DAOs, `ChatRepositoryImpl`, `SystemPromptRepositoryImpl`, backup JSON | agent data |
| `data.net` | `FireworksApiImpl` (OkHttp + SSE), `ModelRepositoryImpl`, `ChatEngineImpl` | agent réseau |
| `data.prefs` | `SettingsRepositoryImpl` (DataStore + clé API chiffrée Keystore) | agent réseau |
| `ui.markdown` | parseur markdown incrémental + rendu Compose | agent markdown |
| `ui.prompts` (guide) | modèles de prompts par famille + `docs/GUIDE_PROMPTS_SYSTEME.md` | agent guide |
| `ui.chat`, `ui.shell`, `ui.settings`, `ui.theme` | écrans | agents UI (phase 3) |

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
- **Modèle/paramètres** : propres à chaque chat, modifiables à tout moment (`updateChatSettings`), initialisés depuis les défauts globaux.

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
