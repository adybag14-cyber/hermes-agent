package com.mobilefork.hermesagent.play

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.ui.auth.AuthViewModel
import com.mobilefork.hermesagent.ui.chat.ChatScreen
import com.mobilefork.hermesagent.ui.chat.ChatViewModel
import com.mobilefork.hermesagent.ui.i18n.*
import com.mobilefork.hermesagent.ui.settings.SettingsScreen
import com.mobilefork.hermesagent.ui.shell.AppSection
import com.mobilefork.hermesagent.ui.theme.HermesTheme
import com.mobilefork.hermesagent.ui.theme.HermesThemeConfig

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayShell(chat: ChatViewModel) {
    val context = LocalContext.current
    val store = remember { AppSettingsStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val strings = hermesStringsFor(AppLanguage.fromTag(settings.languageTag))
    val auth: AuthViewModel = viewModel()
    HermesTheme(HermesThemeConfig(
        primaryHex = settings.themePrimaryHex, secondaryHex = settings.themeSecondaryHex,
        backgroundHex = settings.themeBackgroundHex, surfaceHex = settings.themeSurfaceHex,
        surfaceVariantHex = settings.themeSurfaceVariantHex, cardShape = settings.themeCardShape,
        fontScale = settings.uiFontScale,
    )) {
        CompositionLocalProvider(LocalHermesStrings provides strings) {
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { settingsOpen = false }, Modifier.testTag("PlayChatTab")) {
                            Text(strings.sectionHermes)
                        }
                        TextButton(onClick = { settingsOpen = true }, Modifier.testTag("PlaySettingsTab")) {
                            Text(strings.sectionSettings)
                        }
                    }
                    Text(strings.playEditionSummary(), Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("PlayEditionIdentity"), style = MaterialTheme.typography.bodySmall)
                    if (settingsOpen) {
                        SettingsScreen(Modifier.weight(1f), onSettingsChanged = { settings = store.load() })
                    } else {
                        ChatScreen(
                            modifier = Modifier.weight(1f), viewModel = chat,
                            chatDisplayMode = settings.chatDisplayMode,
                            keywordHighlightingEnabled = settings.keywordHighlightingEnabled,
                            authViewModel = auth, showNavigationButton = false,
                            onNavigateToSection = { section -> if (section != AppSection.Hermes) settingsOpen = true },
                            onOpenNavigationMenu = { settingsOpen = true },
                            onToggleChatDisplayMode = {
                                store.update { it.copy(chatDisplayMode = if (it.chatDisplayMode == "compact") "expanded" else "compact") }
                                settings = store.load()
                            },
                            onApplyProvider = { provider ->
                                ProviderPresets.find(provider)?.let { preset ->
                                    store.update { it.copy(provider = preset.id, baseUrl = preset.baseUrl, model = preset.modelHint) }
                                    settings = store.load()
                                    true
                                } ?: false
                            },
                            onApplyModel = { model ->
                                store.update { it.copy(model = model) }; settings = store.load(); true
                            },
                        )
                    }
                }
            }
        }
    }
}
