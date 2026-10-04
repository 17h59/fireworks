package app.fwchat.ui.shell

import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import app.fwchat.AppContainer
import app.fwchat.FwChatApp

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_NEW = "chat/new"
    const val CHAT_ARG = "chatId"
    const val CHAT = "chat/{$CHAT_ARG}"
    const val SETTINGS = "settings"
    const val PROMPTS = "prompts"
    const val PROMPT_ARG = "promptId"
    const val PROMPT_TEMPLATE_ARG = "template"
    const val PROMPT_NEW_ID = "new"
    const val PROMPT = "prompt/{$PROMPT_ARG}?$PROMPT_TEMPLATE_ARG={$PROMPT_TEMPLATE_ARG}"

    fun chat(chatId: String) = "chat/$chatId"
    fun prompt(promptId: String) = "prompt/$promptId"
    fun newPromptFromTemplate(templateId: String) = "prompt/$PROMPT_NEW_ID?$PROMPT_TEMPLATE_ARG=$templateId"
}

/** Récupère le conteneur depuis l'Application, pour les factories `viewModelFactory { initializer { … } }`. */
fun CreationExtras.appContainer(): AppContainer = (this[APPLICATION_KEY] as FwChatApp).container

/** Fabrique une factory de ViewModel depuis le conteneur. */
inline fun <reified VM : ViewModel> containerFactory(crossinline create: (AppContainer) -> VM) =
    viewModelFactory { initializer { create(appContainer()) } }
