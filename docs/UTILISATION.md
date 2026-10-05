# Guide d'utilisation

## Premier lancement

Colle ta clé API Fireworks et valide. L'app charge la liste des modèles et choisit un modèle par défaut
(modifiable dans **Réglages**). Tu arrives sur un **nouveau chat** vide.

## Écrire un message

- En haut du chat, deux pastilles : le **modèle** (avec son coût `$`) et le **prompt système**. Tu peux les
  changer **tant que tu n'as pas envoyé le premier message**.
- Une fois le premier message envoyé, le prompt système est **verrouillé** (🔒) : toucher la pastille affiche le
  texte figé. Le modèle reste modifiable à tout moment.
- Le bouton d'envoi devient **■** pendant la génération : il l'arrête en conservant le texte déjà reçu.
- La génération continue si tu quittes l'app (une notification discrète « Réponse en cours… » s'affiche).

## Le raisonnement

Les modèles à raisonnement affichent leur réflexion dans un bloc **☰ Réflexion** au-dessus de la réponse :
aperçu des 5 premières lignes, **Tout afficher** pour tout lire, toucher le ☰ pour replier. Le bloc se replie
tout seul quand la réponse commence.

Les modèles à raisonnement dépensent des tokens pour réfléchir : si une réponse est coupée (« Réponse tronquée »),
augmente **Tokens max** dans ⚙.

## Modifier, brancher, forker

| Action | Effet |
|---|---|
| ✏️ sur un de tes messages → **Envoyer et régénérer** | Crée une **branche** : l'IA répond à la nouvelle version. |
| ✏️ → **Enregistrer** | Modifie le texte sur place. Pas de branche, pas de régénération. |
| ✏️ sur une réponse de l'IA → **Enregistrer** | Corrige la réponse sur place. |
| ↻ sur une réponse | Génère une réponse alternative (nouvelle branche). |
| `‹ 2/3 ›` | Passe d'une branche à l'autre ; le reste de la conversation suit la branche choisie. |
| ⋮ → **Nouveau chat à partir d'ici** | Copie la conversation jusqu'à ce message dans un nouveau chat « Fork de … ». |
| ⋮ → **Supprimer le message** | Supprime le message **et tout ce qui le suit**. |

## Tiroir : chats, recherche, prompts

Ouvre le tiroir avec ☰ (ou en glissant depuis le bord) : **Nouveau chat**, recherche par nom (insensible à la
casse et aux accents), liste groupée par date. ⋮ sur un chat : renommer, dupliquer, supprimer. En bas :
**Prompts système** et **Réglages**.

## Prompts système

**Prompts système** : crée autant de prompts que tu veux (nom, famille facultative, texte). L'étoile ⭐ définit le
prompt **par défaut** (ou « Aucun prompt système »). L'éditeur propose des modèles de départ par famille et des
conseils ; `{{date}}`, `{{heure}}` et `{{date_iso}}` sont remplacés à la création d'un chat. Modifier un prompt
n'affecte jamais les chats déjà créés. Voir le [guide de rédaction](GUIDE_PROMPTS_SYSTEME.md).

## Paramètres de génération (⚙)

Un seul jeu de paramètres pour **toutes** les conversations : température, tokens max, raisonnement, mots d'arrêt,
top-p / top-k / min-p, pénalités, seed. Chaque paramètre peut rester sur « Défaut du modèle » (non envoyé). Les
changements sont enregistrés automatiquement. Le raisonnement « Aucun » est ignoré pour les modèles qui le refusent
(GLM, gpt-oss).

## Coûts

- `$` → `$$$$` à côté des modèles : coût relatif d'utilisation, d'après les prix publics de Fireworks
  (table datée, **indicative**). Pas d'icône = prix inconnu.
- **Réglages → Dépenses & crédit** : dépenses du mois lues sur ton compte, rafraîchies à l'ouverture des Réglages et à
  la fin de chaque réponse (Fireworks peut les facturer avec jusqu'à un jour de retard).
  Fireworks n'expose pas le solde par API : saisis **une fois** ton solde (visible sur app.fireworks.ai/billing) et
  l'app affiche un **crédit estimé** = solde saisi − dépenses depuis.

## Sauvegarde

**Réglages → Sauvegarde** : exporte un fichier JSON (chats, branches, prompts) ou importe-en un
(**Fusionner** ou **Remplacer tout**). La clé API n'est pas incluse. Tout est stocké uniquement sur ton téléphone :
exporte de temps en temps.

## Dépannage

| Symptôme | Piste |
|---|---|
| « Clé API invalide » | Réglages → Remplacer la clé. |
| Modèle « introuvable / non déployé » | Recharge la liste (sélecteur de modèle ↻) ou choisis un autre modèle : certains modèles listés ne sont pas servis. |
| Réponse vide ou coupée | Augmente **Tokens max** ; les modèles à raisonnement en consomment beaucoup. |
| Erreur 429 | Trop de requêtes rapprochées : patiente quelques secondes (l'app réessaie seule au début d'une requête). |
| Crédit estimé faux | Ressaisis ton solde actuel (Réglages → Dépenses & crédit). |
