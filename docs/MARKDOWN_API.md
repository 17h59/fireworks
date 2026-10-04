# API Markdown (`app.fwchat.ui.markdown`)

Lecteur markdown « transparent » : aucun cadre ni étiquette, le texte formaté s'intègre dans la page ; seuls les
blocs de code ont un fond discret (+ libellé du langage et bouton copier). Parseur écrit à la main (aucune
librairie), pur Kotlin, linéaire, sans exception. Les agents UI n'ont besoin que de ce document.

## Résumé (ce qu'il faut appeler)

| Besoin | API |
|---|---|
| Message d'un chat dans la `LazyColumn` (long texte, streaming) | `LazyListScope.markdownItems(...)` + `MarkdownBlocksCache.blocks(...)` |
| Petit texte (aperçu thinking, libellé, message court) | `@Composable MarkdownText(...)` |
| Style (thème clair/sombre) | `rememberMarkdownStyle()` |
| Parser sans Compose (tests, copie, recherche) | `parseMarkdown(text)`, `plainText(inlines)` |

Tout se trouve dans le package `app.fwchat.ui.markdown`. Aucune dépendance gradle ajoutée.

## Signatures exactes

```kotlin
// ---- Parsing (pur Kotlin, JVM)
fun parseMarkdown(text: String, openTail: Boolean = false): List<MdBlock>

class IncrementalMarkdown(defaultStreaming: Boolean = true) {
    val blocks: List<MdBlock>
    fun update(newText: String, streaming: Boolean = defaultStreaming): List<MdBlock>
    fun reset()
}

class MarkdownBlocksCache(maxEntries: Int = 512) {          // LRU, thread principal
    fun blocks(key: Any, text: String, streaming: Boolean = false): List<MdBlock>
    fun remove(key: Any)
    fun clear()
}

// ---- Compose
@Composable fun rememberMarkdownStyle(baseStyle: TextStyle = MaterialTheme.typography.bodyLarge): MarkdownStyle
@Composable fun rememberMarkdownBlocksCache(maxEntries: Int = 512): MarkdownBlocksCache
@Composable fun rememberMarkdownBlocks(text: String, streaming: Boolean = false, resetKey: Any? = null): List<MdBlock>

fun LazyListScope.markdownItems(
    key: String,
    blocks: List<MdBlock>,
    style: MarkdownStyle? = null,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
)

@Composable fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = rememberMarkdownStyle(),
    streaming: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    previewFromEnd: Boolean = false,
    selectable: Boolean = false,
    color: Color = Color.Unspecified,
    resetKey: Any? = null,
)

fun plainText(list: List<MdInline>): String   // texte aplati d'inlines
```

`MarkdownStyle` est une `@Immutable data class` (corps 16sp/24sp, titres hiérarchisés 22→14sp, code monospace 13sp,
liens `primary` soulignés, espacements `blockSpacing`/`tightSpacing`/`headingTopSpacing`, `softBreakAsNewline`…) ;
on peut la personnaliser avec `.copy(...)` après `rememberMarkdownStyle()`.

Modèle (immuable, `data class`, égalité structurelle) :
`MdBlock` = `MdParagraph | MdHeading | MdCodeBlock | MdMathBlock | MdQuote | MdList(MdListItem) | MdTable | MdRule` ;
`MdInline` = `MdText | MdStrong | MdEmphasis | MdStrike | MdCode | MdLink | MdImage | MdMath | MdLineBreak | MdSoftBreak`.

## Intégration dans la LazyColumn du chat (avec streaming)

`markdownItems` s'appelle dans le lambda de la `LazyColumn` (contexte non composable) : on ne peut donc pas y utiliser
`remember`. On utilise un `MarkdownBlocksCache` créé une fois à l'extérieur : un appel avec le même texte est
quasi gratuit, un texte qui grandit ne re-parse que le dernier bloc.

```kotlin
@Composable
fun MessageList(messages: List<MessageUi>, live: State<LiveMessageUi?>, listState: LazyListState) {
    val style = rememberMarkdownStyle()               // UNE fois, hors du lambda
    val cache = rememberMarkdownBlocksCache()

    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        for (m in messages) {
            item(key = "msg:${m.id}:top", contentType = "msg-top") { MessageHeader(m) }
            if (m.isAssistant) {
                markdownItems(
                    key = "msg:${m.id}",                       // préfixe de clés, unique par message
                    blocks = cache.blocks(m.id, m.text),       // rendu définitif (streaming = false)
                    style = style,
                )
            } else {
                item(key = "msg:${m.id}:text") { Text(m.text) }   // ou markdownItems si on veut le markdown
            }
            item(key = "msg:${m.id}:actions", contentType = "msg-actions") { MessageActions(m) }
        }
        live.value?.let { l ->                                  // message en cours de génération
            markdownItems(
                key = "msg:${l.id}",                            // MÊME clé/id que le message final
                blocks = cache.blocks(l.id, l.text, streaming = true),
                style = style,
            )
        }
    }
}
```

Points importants :

- **Même `key` et même id de cache avant/après la fin du flux** : les items déjà composés sont conservés (pas de
  clignotement) ; au dernier appel, passer `streaming = false` (le dernier bloc passe en rendu définitif).
- Lire l'état live (`live.value`) **dans** le lambda de la `LazyColumn` : seul ce lambda est ré-exécuté à chaque token,
  pas tout l'écran.
- Les blocs fermés gardent la même identité d'objet : Compose saute leur recomposition (strong skipping) ; seuls
  l'item du bloc ouvert (et éventuellement le précédent) se recomposent.
- **Pas de `Arrangement.spacedBy` sur la LazyColumn pour les items markdown** : chaque item porte son espacement bas
  (`style.blockSpacing`, 12dp). Mettre l'espacement entre messages dans vos propres items (ex. `Spacer`) ou `contentPadding`.
- Un très long bloc est découpé en plusieurs items (paragraphe géant > 6000 car., code > 80 lignes, tableau > 40
  lignes, un item par élément de liste de haut niveau, un item par bloc d'une citation) : seul le visible est composé.
  Conséquence : dans un bloc de code/tableau découpé, le défilement horizontal des morceaux est indépendant.
- Auto-scroll : tant que le message live grandit, le dernier item change de hauteur ; l'agent UI gère « suivre le
  bas » comme prévu par l'architecture (les clés étant stables, `animateScrollToItem(lastIndex)` reste valable).
- Message supprimé : `cache.remove(id)` (facultatif, le cache est un LRU borné).

### Sélection de texte

- `selectable = true` (défaut) : chaque item est enveloppé dans son `SelectionContainer` → appui long sélectionne et
  copie du texte **à l'intérieur d'un bloc** (paragraphe, item de liste, cellule…).
- Pour une sélection qui traverse plusieurs blocs/messages : passer `selectable = false` et entourer toute la
  `LazyColumn` d'un seul `SelectionContainer { LazyColumn { … } }` (contrainte Compose : la sélection ne s'étend
  qu'aux items actuellement composés). La copie du message complet reste à la charge de l'UI (bouton « copier » sur `m.text`).

### Parse hors thread principal

`parseMarkdown` / `IncrementalMarkdown` sont synchrones (1 Mo ≈ 30–120 ms sur JVM de bureau, donc ≲ 0,3 s sur mobile ;
un message courant < 1 ms). Pour un message statique géant on peut appeler `parseMarkdown(text)` dans une coroutine
`Dispatchers.Default` puis passer la liste à `markdownItems`. (`rememberMarkdownBlocks` le fait déjà tout seul au-delà de 60 000 caractères en mode non-streaming.)

## Très gros textes : message en cours et parse hors thread principal

Ajouts pour garder la liste fluide avec des réponses de 100–300 Ko (utilisés par `ui.chat.MessageList`) :

```kotlin
// IncrementalMarkdown : nombre de blocs de tête jamais re-parsés (fermés) ; test de prolongement sans parser
val stableCount: Int
fun canExtend(newText: String): Boolean

// Message en cours : n'émettre en items QUE les blocs fermés ; le dernier bloc (ouvert) dans UN item vivant
class StreamingMarkdown(parser: IncrementalMarkdown = IncrementalMarkdown(true)) {
    fun parts(text: String): StreamParts                       // mémoïsé ; parts.liveBlock / parts.liveFromSlice
    fun stableState(text: () -> String): State<StreamStable>   // ne change que quand un bloc (ou une tranche) se ferme
}
fun splitStreamingBlocks(blocks: List<MdBlock>): StreamParts   // pure, testable
fun LazyListScope.markdownItems(..., lastBlockSlices: Int = Int.MAX_VALUE)   // tranches max du dernier bloc
@Composable fun MarkdownTail(block: MdBlock, fromSlice: Int, style: MarkdownStyle, modifier: Modifier = Modifier)
fun lazySliceCount(block: MdBlock): Int

// Cache : partager le parseur live avec le rendu final (fin de flux = re-parse de la fin seulement)
MarkdownBlocksCache.incremental(key, streaming) / put(key, parser) / cachedBlocks(key, text): List<MdBlock>?

// Gros texte statique : parse sur Dispatchers.Default ; null (afficher du texte brut) tant que ce n'est pas prêt
class MarkdownBlocksLoader(cache: MarkdownBlocksCache, scope: CoroutineScope) {
    fun blocks(key: Any, text: String): List<MdBlock>?   // lu dans le lambda de la LazyColumn (relancé à la fin du parse)
    fun prefetch(key: Any, text: String)
}
```

Schéma dans la `LazyColumn` : `val stable = entry.stable.value` (état dérivé, lu dans le lambda) →
`markdownItems(key, stable.blocks, style, lastBlockSlices = stable.lastBlockSlices)` puis
`item(key = "$key:live") { /* lit le texte live */ MarkdownTail(parts.liveBlock, parts.liveFromSlice, style) }`.
Un gros bloc de code ouvert est découpé : ses tranches de 80 lignes déjà complètes deviennent des items stables,
seule la dernière tranche (≤ 80 lignes) est recomposée à chaque publication.

## Petits textes : `MarkdownText`

```kotlin
// message court
MarkdownText(text = m.text)

// aperçu du thinking : 5 lignes max, on voit la FIN pendant le stream, pas de composition du texte entier
MarkdownText(
    text = thinking,
    streaming = isThinking,
    maxLines = 5,
    previewFromEnd = true,
    style = rememberMarkdownStyle().let { it.copy(body = it.body.copy(fontSize = 14.sp, lineHeight = 20.sp)) },
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    resetKey = messageId,
)
```

`MarkdownText` compose tous ses blocs dans une `Column` : à réserver aux textes courts ; pour une longue réponse
utiliser `markdownItems`. Avec `maxLines` fini, seuls les blocs nécessaires à l'aperçu sont composés et la hauteur est
plafonnée/rognée ; « Tout afficher » = rappeler sans `maxLines` (ou avec `markdownItems`).

## Streaming : règles de `IncrementalMarkdown`

```kotlin
val inc = IncrementalMarkdown()                 // un par message (remember(messageId) { … } dans un @Composable)
val blocks = inc.update(fullTextSoFar)          // texte COMPLET à chaque fois, qui ne fait que grandir
// … à la fin du flux :
val finalBlocks = inc.update(finalText, streaming = false)
```

- Invariant (testé par propriété sur des découpages aléatoires) : `update(t, streaming)` ≡ `parseMarkdown(t, openTail = streaming)`.
- Seul le dernier bloc (et l'avant-dernier quand la ligne en cours est incomplète) est re-parsé ; les autres sont réutilisés
  (`===`). Coût par ajout ≈ O(taille du dernier bloc). Si le texte n'est pas un simple ajout (édition) : re-parse complet automatique.
- Rendu optimiste du dernier bloc (`streaming = true`) : `**gras` non fermé s'affiche déjà en gras, `` `code`` en code,
  `[texte](url` en lien stylé non cliquable, un bloc ``` non fermé est un `MdCodeBlock(closed = false)` affiché comme code,
  un tableau en cours s'affiche avec les lignes déjà reçues, une liste en cours montre ses items.
- Non thread-safe : appeler depuis un seul thread (le principal).

## Ce qui est supporté

Blocs : paragraphes, titres `#`..`######` et setext, listes à puces/numérotées imbriquées (tolère 2 espaces sous `1.`),
cases à cocher `[ ]`/`[x]`, citations imbriquées (continuation paresseuse), blocs ``` et ~~~ (avec langage) et indentés,
tableaux GFM avec alignements, `---`, blocs de formule `$$..$$` et `\[..\]` (monospace), HTML brut = texte.
Inlines : `**gras**`, `*it*`/`_it_`, `~~barré~~`, `` `code` ``, `[txt](url "titre")`, autoliens (`<url>`, `http(s)://…`, `www.…`),
images `![alt](url)` (rendues comme lien `[Image : alt]`, jamais chargées), sauts de ligne (2 espaces, `\`, `<br>`),
échappements, entités (`&amp;`, `&#65;`…), formules `$x$`, `$$x$$`, `\(x\)`, `\[x\]` affichées telles quelles
(règle de `$` : `$5 et $10` n'est pas une formule).
Liens : cliquables via `LocalUriHandler` pour `http`, `https`, `mailto`, `tel` uniquement.

## Limites connues

- Pas de définitions de liens par référence (`[a][ref]`), pas de notes de bas de page, pas de rendu HTML (traité comme texte),
  pas de rendu LaTeX (texte brut), pas de chargement d'images.
- Un retour à la ligne simple dans un paragraphe est rendu comme un retour à la ligne (`softBreakAsNewline = true`), pas comme un espace (choix « chat »).
- Un tableau se termine à la première ligne sans `|` (plus tolérant que GFM strict, qui l'avale comme ligne).
- Le défilement horizontal des morceaux d'un gros code/tableau découpé est indépendant d'un morceau à l'autre.
- Imbrication plafonnée : 16 niveaux de conteneurs (citations/listes), 32 niveaux d'emphase ; au-delà le texte est rendu littéral.
- Les largeurs de colonnes de tableau sont estimées (≈ 0,56 em par caractère, plafond 32 caractères, puis retour à la ligne dans la cellule).
- Rendu Compose validé par compilation uniquement (pas de test UI) ; le comportement visuel n'a pas été vu sur appareil.
