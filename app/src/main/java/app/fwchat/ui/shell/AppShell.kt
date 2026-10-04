package app.fwchat.ui.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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

    fun closeDrawer() {
        scope.launch { drawerState.close() }
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
                    onNewChat = {
                        closeDrawer()
                        if (route != Routes.CHAT_NEW) navController.navigateToChat(Routes.CHAT_NEW)
                    },
                    onOpenChat = { id ->
                        closeDrawer()
                        if (id != currentChatId) navController.navigateToChat(Routes.chat(id))
                    },
                    onCurrentChatDeleted = {
                        closeDrawer()
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
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = { navController.navigateToChat(Routes.CHAT_NEW) })
            }
            composable(Routes.CHAT_NEW) {
                ChatScreen(
                    container = container,
                    chatId = null,
                    onOpenDrawer = ::openDrawer,
                    onChatCreated = { id -> navController.replaceWithChat(id) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                )
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument(Routes.CHAT_ARG) { type = NavType.StringType }),
            ) { entry: NavBackStackEntry ->
                ChatScreen(
                    container = container,
                    chatId = entry.arguments?.getString(Routes.CHAT_ARG),
                    onOpenDrawer = ::openDrawer,
                    onChatCreated = { id -> navController.replaceWithChat(id) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onBack = { navController.popBackStackSafely() },
                    onOpenPrompts = { navController.navigate(Routes.PROMPTS) { launchSingleTop = true } },
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

/** Le brouillon `chat/new` devient `chat/{id}` au premier envoi, sans empiler. */
private fun NavHostController.replaceWithChat(chatId: String) {
    navigate(Routes.chat(chatId)) {
        popUpTo(Routes.CHAT_NEW) { inclusive = true }
        launchSingleTop = true
    }
}

private fun NavHostController.popBackStackSafely() {
    if (previousBackStackEntry != null) popBackStack()
}
