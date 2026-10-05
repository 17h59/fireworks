package app.fwchat.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.fwchat.AppContainer
import app.fwchat.ui.chat.ChatScreen
import app.fwchat.ui.prompts.screens.PromptEditorScreen
import app.fwchat.ui.prompts.screens.PromptListScreen
import app.fwchat.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Racine de l'UI: un seul NavHost + un tiroir global. La route de départ dépend de la présence
 * d'une clé API; on n'affiche rien (fond uni) le temps de la lire.
 */
@Composable
fun AppRoot(container: AppContainer) {
    var startRoute by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (startRoute == null) {
            startRoute = if (container.settings.apiKey().isNullOrBlank()) Routes.ONBOARDING else Routes.CHAT_NEW
        }
    }
    val start = startRoute
    if (start == null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
    } else {
        AppShell(container, start)
    }
}

@Composable
private fun AppShell(container: AppContainer, startRoute: String) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val drawerViewModel: DrawerViewModel = viewModel(factory = DrawerViewModel.Factory)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val isChatRoute = route == Routes.CHAT_NEW || route == Routes.CHAT
    val currentChatId = if (route == Routes.CHAT) backStackEntry?.arguments?.getString(Routes.CHAT_ARG) else null

    // Clé perdue en cours d'usage (ex. clé Keystore illisible): retour à l'onboarding avec une pile propre.
    val hasApiKey by remember(container) {
        container.settings.settings.map { it.hasApiKey }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(hasApiKey, route) {
        if (shouldReturnToOnboarding(hasApiKey, route)) navController.navigateToOnboarding()
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    // La recherche du tiroir ne doit pas survivre à l'ouverture d'un chat.
    fun startNewChat() {
        closeDrawer()
        drawerViewModel.setQuery("")
        if (route != Routes.CHAT_NEW) navController.navigateToChat(Routes.CHAT_NEW)
    }

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    // Le tiroir se ferme d'abord au retour système.
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    // Le tiroir se ferme dès que l'on quitte un écran de chat (navigation, retour).
    LaunchedEffect(route) {
        if (drawerState.isOpen) drawerState.close()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isChatRoute || drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    viewModel = drawerViewModel,
                    currentChatId = currentChatId,
                    onNewChat = ::startNewChat,
                    onOpenChat = { id ->
                        closeDrawer()
                        drawerViewModel.setQuery("")
                        if (id != currentChatId) navController.navigateToChat(Routes.chat(id))
                    },
                    onCurrentChatDeleted = {
                        closeDrawer()
                        drawerViewModel.setQuery("")
                        navController.navigateToChat(Routes.CHAT_NEW)
                    },
                    onOpenPrompts = {
                        closeDrawer()
                        navController.navigate(Routes.PROMPTS) { launchSingleTop = true }
                    },
                    onOpenSettings = {
                        closeDrawer()
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                    },
                )
            }
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.fillMaxSize(),
            // Fondu court par défaut (le défaut de ~700 ms se voyait au 1er message); aucun sur les chats.
            enterTransition = { fadeIn(tween(ScreenFadeMillis)) },
            exitTransition = { fadeOut(tween(ScreenFadeMillis)) },
            popEnterTransition = { fadeIn(tween(ScreenFadeMillis)) },
            popExitTransition = { fadeOut(tween(ScreenFadeMillis)) },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = { navController.navigateToChat(Routes.CHAT_NEW) })
            }
            composable(
                route = Routes.CHAT_NEW,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                ChatScreen(
                    container = container,
                    chatId = null,
                    onOpenDrawer = ::openDrawer,
                    onChatCreated = { id -> navController.openCreatedChat(id) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onNewChat = ::startNewChat,
                    onOpenPrompts = { navController.navigate(Routes.PROMPTS) { launchSingleTop = true } },
                )
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument(Routes.CHAT_ARG) { type = NavType.StringType }),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) { entry: NavBackStackEntry ->
                ChatScreen(
                    container = container,
                    chatId = entry.arguments?.getString(Routes.CHAT_ARG),
                    onOpenDrawer = ::openDrawer,
                    onChatCreated = { id -> navController.openCreatedChat(id) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onNewChat = ::startNewChat,
                    onOpenPrompts = { navController.navigate(Routes.PROMPTS) { launchSingleTop = true } },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onBack = { navController.popBackStackSafely() },
                    onOpenPrompts = { navController.navigate(Routes.PROMPTS) { launchSingleTop = true } },
                    onApiKeyRemoved = { navController.navigateToOnboarding() },
                )
            }
            composable(Routes.PROMPTS) {
                PromptListScreen(
                    onBack = { navController.popBackStackSafely() },
                    onOpenPrompt = { id -> navController.navigate(Routes.prompt(id)) },
                    onNewPrompt = { navController.navigate(Routes.prompt(Routes.PROMPT_NEW_ID)) },
                    onNewPromptFromTemplate = { templateId ->
                        navController.navigate(Routes.newPromptFromTemplate(templateId))
                    },
                )
            }
            composable(
                route = Routes.PROMPT,
                arguments = listOf(
                    navArgument(Routes.PROMPT_ARG) { type = NavType.StringType },
                    navArgument(Routes.PROMPT_TEMPLATE_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                PromptEditorScreen(onClose = { navController.popBackStackSafely() })
            }
        }
    }
}

/** La pile ne garde qu'un chat à la fois: ouvrir un chat depuis n'importe où repart d'une pile propre. */
private fun NavHostController.navigateToChat(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** Clé supprimée: retour à l'onboarding avec une pile propre (le retour système ne revient pas aux réglages). */
private fun NavHostController.navigateToOnboarding() {
    navigate(Routes.ONBOARDING) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Appelé quand un chat vient d'être créé: au 1er envoi d'un brouillon, `chat/new` devient `chat/{id}` sans
 * empiler (le retour système ne revient pas au brouillon vide). Après un fork depuis un chat existant, le
 * nouveau chat est EMPILÉ au-dessus du chat d'origine (le retour y ramène).
 */
private fun NavHostController.openCreatedChat(chatId: String) {
    if (currentBackStackEntry?.destination?.route == Routes.CHAT_NEW) {
        navigate(Routes.chat(chatId)) {
            popUpTo(Routes.CHAT_NEW) { inclusive = true }
            launchSingleTop = true
        }
    } else {
        navigate(Routes.chat(chatId))
    }
}

private fun NavHostController.popBackStackSafely() {
    if (previousBackStackEntry != null) popBackStack()
}

/** Durée du fondu entre écrans hors chat. */
private const val ScreenFadeMillis = 120

/**
 * Une clé API qui disparaît en cours d'usage (route connue, hors onboarding) renvoie à l'onboarding.
 * Sur l'onboarding lui-même, la clé est absente par définition: rien à faire.
 */
internal fun shouldReturnToOnboarding(hasApiKey: Boolean, route: String?): Boolean =
    !hasApiKey && route != null && route != Routes.ONBOARDING
