@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mobilefork.hermesagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.api.HermesEndpointUrl
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import com.mobilefork.hermesagent.ui.shell.ShellActionItem
import java.util.Locale

enum class SettingsPage(val route: String, val label: String) {
    Overview("/settings", "General"),
    Models("/settings/models", "Models"),
    Theme("/settings/theme", "Theme"),
    Tools("/settings/tools", "Tools"),
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    extraBottomSpacing: Dp = 0.dp,
    onContextActionsChanged: (List<ShellActionItem>) -> Unit = {},
    onSettingsChanged: () -> Unit = {},
    initialPage: SettingsPage = SettingsPage.Overview,
) {
    val uiState by viewModel.uiState.collectAsState()
    // Settings content follows the selected language immediately (source of truth = uiState),
    // so the page retranslates even before shell CompositionLocal catches up.
    val strings = hermesStringsFor(AppLanguage.fromTag(uiState.languageTag))
    val selectedPreset = ProviderPresets.find(uiState.provider)
    val selectedProviderLabel = strings.providerDisplayLabel(
        uiState.provider,
        selectedPreset?.label ?: uiState.provider,
    )
    var selectedPageName by rememberSaveable { mutableStateOf(initialPage.name) }
    val selectedPage = SettingsPage.entries.firstOrNull { it.name == selectedPageName } ?: SettingsPage.Overview

    SideEffect {
        onContextActionsChanged(emptyList())
    }

    // Snapshot existing runtime state once; do not cold-start on every recomposition.
    LaunchedEffect(Unit) {
        viewModel.refreshAgentEndpoint(forceStart = false)
    }

    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 920.dp)
                        .imePadding()
                        .testTag("HermesSettingsContentList")
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentPadding = PaddingValues(bottom = extraBottomSpacing),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SettingsPageNavigation(
                            selectedPage = selectedPage,
                            onSelectPage = { selectedPageName = it.name },
                            strings = strings,
                        )
                    }
                    if (selectedPage == SettingsPage.Overview) {
                    item {
                        SettingsHelpCard(providerLabel = selectedProviderLabel, strings = strings)
                    }
                    item {
                        LanguagePickerCard(
                            currentLanguageTag = uiState.languageTag,
                            onSelectLanguage = { language ->
                                viewModel.selectLanguage(language)
                                onSettingsChanged()
                            },
                            strings = strings,
                        )
                    }
                    item {
                        AgentPersonaCard(
                            customSystemPrompt = uiState.customSystemPrompt,
                            onPromptChange = viewModel::updateCustomSystemPrompt,
                            onSave = viewModel::saveAgentPersona,
                            onClear = viewModel::clearAgentPersona,
                            strings = strings,
                        )
                    }
                    }
                    if (selectedPage == SettingsPage.Theme) {
                    item {
                        AppearanceCard(
                            chatDisplayMode = uiState.chatDisplayMode,
                            keywordHighlightingEnabled = uiState.keywordHighlightingEnabled,
                            themePrimaryHex = uiState.themePrimaryHex,
                            themeSecondaryHex = uiState.themeSecondaryHex,
                            themeBackgroundHex = uiState.themeBackgroundHex,
                            themeSurfaceHex = uiState.themeSurfaceHex,
                            themeSurfaceVariantHex = uiState.themeSurfaceVariantHex,
                            themeCardShape = uiState.themeCardShape,
                            uiFontScale = uiState.uiFontScale,
                            onChatDisplayModeChange = viewModel::updateChatDisplayMode,
                            onKeywordHighlightingChange = viewModel::updateKeywordHighlighting,
                            onPrimaryHexChange = viewModel::updateThemePrimaryHex,
                            onSecondaryHexChange = viewModel::updateThemeSecondaryHex,
                            onBackgroundHexChange = viewModel::updateThemeBackgroundHex,
                            onSurfaceHexChange = viewModel::updateThemeSurfaceHex,
                            onSurfaceVariantHexChange = viewModel::updateThemeSurfaceVariantHex,
                            onCardShapeChange = viewModel::updateThemeCardShape,
                            onUiFontScaleChange = viewModel::updateUiFontScale,
                            onApplyPreset = viewModel::applyThemePreset,
                            onSaveAppearance = {
                                viewModel.saveAppearance()
                                onSettingsChanged()
                            },
                            strings = strings,
                        )
                    }
                    }
                    if (selectedPage == SettingsPage.Models) {
                    item {
                        OnDeviceInferenceCard(
                            onDeviceBackend = uiState.onDeviceBackend,
                            speculativeDecodingMode = uiState.liteRtLmSpeculativeDecodingMode,
                            onSelectBackend = viewModel::updateOnDeviceBackend,
                            onSelectSpeculativeDecodingMode = viewModel::updateLiteRtLmSpeculativeDecodingMode,
                            onStartRuntime = { runtimeFlavor ->
                                viewModel.startLocalRuntimeForFlavor(runtimeFlavor)
                            },
                            summary = uiState.onDeviceSummary,
                            strings = strings,
                        )
                    }
                    item {
                        ModelGenerationConfigCard(
                            maxTokens = uiState.localModelMaxTokens,
                            topK = uiState.localModelTopK,
                            topP = uiState.localModelTopP,
                            temperature = uiState.localModelTemperature,
                            accelerator = uiState.localModelAccelerator,
                            toolMode = uiState.localModelToolMode,
                            apiGenerationKnobsEnabled = uiState.apiGenerationKnobsEnabled,
                            customSystemPrompt = uiState.customSystemPrompt,
                            onMaxTokensChange = viewModel::updateLocalModelMaxTokens,
                            onTopKChange = viewModel::updateLocalModelTopK,
                            onTopPChange = viewModel::updateLocalModelTopP,
                            onTemperatureChange = viewModel::updateLocalModelTemperature,
                            onAcceleratorChange = viewModel::updateLocalModelAccelerator,
                            onToolModeChange = viewModel::updateLocalModelToolMode,
                            onApiGenerationKnobsEnabledChange = viewModel::updateApiGenerationKnobsEnabled,
                            onPromptChange = viewModel::updateCustomSystemPrompt,
                            onSave = viewModel::saveModelGenerationConfig,
                            onClearPrompt = viewModel::clearAgentPersona,
                        )
                    }
                    item {
                        LocalModelDownloadsSection(
                            dataSaverMode = uiState.dataSaverMode,
                            offlineAirplaneMode = uiState.offlineAirplaneMode,
                            onDataSaverModeChange = viewModel::updateDataSaverMode,
                            selectedBackend = uiState.onDeviceBackend,
                            onRuntimeFlavorSelected = viewModel::syncOnDeviceBackendWithRuntimeFlavor,
                            onCompletedDownloadReady = viewModel::startLocalRuntimeForFlavor,
                        )
                    }
                    }
                    if (selectedPage == SettingsPage.Overview) {
                    item {
                        OfflineAirplaneCard(
                            enabled = uiState.offlineAirplaneMode,
                            onChange = viewModel::updateOfflineAirplaneMode,
                            strings = strings,
                        )
                    }
                    }
                    if (selectedPage == SettingsPage.Models) {
                    item {
                        RemoteFallbackCard(
                            providerId = uiState.provider,
                            providerLabel = selectedProviderLabel,
                            baseUrl = uiState.baseUrl,
                            model = uiState.model,
                            apiKey = uiState.apiKey,
                            status = uiState.status,
                            onSelectProvider = viewModel::updateProvider,
                            onBaseUrlChange = viewModel::updateBaseUrl,
                            onModelChange = viewModel::updateModel,
                            onApiKeyChange = viewModel::updateApiKey,
                            onOpenProviderKeyPage = viewModel::openProviderKeyPage,
                            onCopyProviderKeyPage = viewModel::copyProviderKeyPage,
                            onCheckProviderKeyPage = viewModel::checkProviderKeyPage,
                            onImportProviderCredential = viewModel::importSavedProviderCredential,
                            onSave = viewModel::save,
                            strings = strings,
                        )
                    }
                    item {
                        AgentEndpointCard(
                            loopbackUrl = uiState.agentLoopbackUrl,
                            lanUrl = uiState.agentLanUrl,
                            apiKey = uiState.agentApiKey,
                            modelName = uiState.agentModelName,
                            started = uiState.agentEndpointStarted,
                            onRefresh = { viewModel.refreshAgentEndpoint(forceStart = true) },
                            strings = strings,
                        )
                    }
                    }
                    if (selectedPage == SettingsPage.Tools) {
                    item {
                        McpSettingsSection(selectedProviderId = uiState.provider)
                    }
                    item {
                        SkillsSection()
                    }
                    item {
                        LocalMemorySection()
                    }
                    item {
                        ToolProfileCard()
                    }
                    }
                    if (uiState.status.isNotBlank()) {
                        item {
                            Text(uiState.status)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPageNavigation(
    selectedPage: SettingsPage,
    onSelectPage: (SettingsPage) -> Unit,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("HermesSettingsPageNavigation"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = strings.settingsBreadcrumb(selectedPage.name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingsPage.entries.forEach { page ->
                    Button(
                        onClick = { onSelectPage(page) },
                        modifier = Modifier.testTag("HermesSettingsPage_${page.name}"),
                        enabled = page != selectedPage,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(strings.settingsPageLabel(page.name), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(selectedPage.route, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private enum class ModelConfigTab {
    ModelConfigs,
    SystemPrompt,
    ToolGuidance,
}

@Composable
private fun ModelGenerationConfigCard(
    maxTokens: Int,
    topK: Int,
    topP: Float,
    temperature: Float,
    accelerator: String,
    toolMode: String,
    apiGenerationKnobsEnabled: Boolean,
    customSystemPrompt: String,
    onMaxTokensChange: (Int) -> Unit,
    onTopKChange: (Int) -> Unit,
    onTopPChange: (Float) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onAcceleratorChange: (String) -> Unit,
    onToolModeChange: (String) -> Unit,
    onApiGenerationKnobsEnabledChange: (Boolean) -> Unit,
    onPromptChange: (String) -> Unit,
    onSave: () -> Unit,
    onClearPrompt: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(ModelConfigTab.ModelConfigs) }
    val strings = LocalHermesStrings.current
    val language = strings.language
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(settingsGenerationText(language, "configurations"), style = MaterialTheme.typography.titleMedium)
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                ModelConfigTab.entries.forEach { tab ->
                    Tab(
                        modifier = Modifier.testTag("LocalModelConfigTab-${tab.name}"),
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(modelConfigTabLabel(language, tab)) },
                    )
                }
            }
            when (selectedTab) {
                ModelConfigTab.ModelConfigs -> {
                    GenerationSwitchRow(
                        title = settingsGenerationText(language, "api_knobs_title"),
                        description = settingsGenerationText(language, "api_knobs_description"),
                        checked = apiGenerationKnobsEnabled,
                        onCheckedChange = onApiGenerationKnobsEnabledChange,
                    )
                    GenerationIntegerRow(
                        title = settingsGenerationText(language, "max_tokens"),
                        valueLabel = maxTokensLabel(maxTokens, language),
                        value = maxTokens,
                        defaultValue = AppSettings.DEFAULT_LOCAL_MODEL_MAX_TOKENS,
                        minValue = AppSettings.DEFAULT_LOCAL_MODEL_MAX_TOKENS,
                        maxValue = AppSettings.MAX_LOCAL_MODEL_MAX_TOKENS,
                        step = 256,
                        onValueChange = onMaxTokensChange,
                        testTagPrefix = "LocalModelMaxTokens",
                    )
                    GenerationIntegerRow(
                        title = strings.topKLabel(),
                        valueLabel = topK.toString(),
                        value = topK,
                        defaultValue = AppSettings.DEFAULT_LOCAL_MODEL_TOP_K,
                        minValue = AppSettings.MIN_LOCAL_MODEL_TOP_K,
                        maxValue = AppSettings.MAX_LOCAL_MODEL_TOP_K,
                        step = 1,
                        onValueChange = onTopKChange,
                        testTagPrefix = "LocalModelTopK",
                    )
                    GenerationSliderRow(
                        title = strings.topPLabel(),
                        valueLabel = formatGenerationDecimal(topP),
                        value = topP,
                        valueRange = AppSettings.MIN_LOCAL_MODEL_TOP_P..AppSettings.MAX_LOCAL_MODEL_TOP_P,
                        onValueChange = onTopPChange,
                        testTag = "LocalModelTopP",
                    )
                    GenerationSliderRow(
                        title = strings.temperatureLabel(),
                        valueLabel = formatGenerationDecimal(temperature),
                        value = temperature,
                        valueRange = AppSettings.MIN_LOCAL_MODEL_TEMPERATURE..AppSettings.MAX_LOCAL_MODEL_TEMPERATURE,
                        onValueChange = onTemperatureChange,
                        testTag = "LocalModelTemperature",
                    )
                    Text(settingsGenerationText(language, "accelerator"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        acceleratorChoices(language).forEach { choice ->
                            Button(
                                modifier = Modifier.testTag("LocalModelAccelerator-${choice.value}"),
                                onClick = { onAcceleratorChange(choice.value) },
                                enabled = accelerator != choice.value,
                            ) {
                                Text(choice.label)
                            }
                        }
                    }
                    Text(
                        settingsGenerationText(language, "accelerator_description"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ModelConfigTab.SystemPrompt -> {
                    OutlinedTextField(
                        value = customSystemPrompt,
                        onValueChange = onPromptChange,
                        label = { Text(settingsGenerationText(language, "system_prompt")) },
                        placeholder = { Text(settingsGenerationText(language, "system_prompt_placeholder")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("LocalModelSystemPrompt"),
                        minLines = 3,
                        maxLines = 8,
                    )
                    Text(
                        "${customSystemPrompt.length} / ${AppSettings.MAX_CUSTOM_SYSTEM_PROMPT_CHARS}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        modifier = Modifier.testTag("ClearLocalModelSystemPromptButton"),
                        onClick = onClearPrompt,
                        enabled = customSystemPrompt.isNotBlank(),
                    ) {
                        Text(settingsGenerationText(language, "clear_prompt"))
                    }
                }
                ModelConfigTab.ToolGuidance -> {
                    Text(
                        settingsGenerationText(language, "tool_guidance_description"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        toolGuidanceChoices(language).forEach { choice ->
                            Button(
                                modifier = Modifier.testTag("LocalModelToolMode-${choice.value}"),
                                onClick = { onToolModeChange(choice.value) },
                                enabled = toolMode != choice.value,
                            ) {
                                Text(choice.label)
                            }
                        }
                    }
                    Text(
                        toolGuidanceChoices(language)
                            .firstOrNull { it.value == toolMode }
                            ?.description
                            .orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SaveLocalModelGenerationConfigButton"),
                onClick = onSave,
            ) {
                Text(settingsGenerationText(language, "save_model_configuration"))
            }
        }
    }
}

@Composable
private fun GenerationSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GenerationIntegerRow(
    title: String,
    valueLabel: String,
    value: Int,
    defaultValue: Int,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
    testTagPrefix: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title $valueLabel" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueLabel, style = MaterialTheme.typography.titleSmall)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.testTag("${testTagPrefix}Decrease"),
                onClick = { onValueChange(decrementGenerationValue(value, defaultValue, minValue, step)) },
                enabled = value != defaultValue && value > minValue,
            ) {
                Text("-")
            }
            Button(
                modifier = Modifier.testTag("${testTagPrefix}Default"),
                onClick = { onValueChange(defaultValue) },
                enabled = value != defaultValue,
            ) {
                Text(settingsGenerationText(LocalHermesStrings.current.language, "default"))
            }
            Button(
                modifier = Modifier.testTag("${testTagPrefix}Increase"),
                onClick = { onValueChange(incrementGenerationValue(value, defaultValue, maxValue, step)) },
                enabled = value < maxValue,
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun GenerationSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title $valueLabel" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueLabel, style = MaterialTheme.typography.titleSmall)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
        )
    }
}

private data class AcceleratorChoice(
    val value: String,
    val label: String,
)

private fun acceleratorChoices(language: AppLanguage): List<AcceleratorChoice> = listOf(
    AcceleratorChoice("auto", settingsGenerationText(language, "auto")),
    AcceleratorChoice("cpu", "CPU"),
    AcceleratorChoice("gpu", "GPU"),
)

private fun maxTokensLabel(value: Int, language: AppLanguage): String {
    return if (value <= 0) settingsGenerationText(language, "default") else value.toString()
}

private fun modelConfigTabLabel(language: AppLanguage, tab: ModelConfigTab): String {
    return when (tab) {
        ModelConfigTab.ModelConfigs -> settingsGenerationText(language, "model_configs")
        ModelConfigTab.SystemPrompt -> settingsGenerationText(language, "system_prompt")
        ModelConfigTab.ToolGuidance -> settingsGenerationText(language, "tool_guidance")
    }
}

private data class ToolGuidanceChoice(val value: String, val label: String, val description: String)

private fun toolGuidanceChoices(language: AppLanguage): List<ToolGuidanceChoice> = listOf(
    ToolGuidanceChoice(
        "small",
        settingsGenerationText(language, "tool_mode_small"),
        settingsGenerationText(language, "tool_mode_small_description"),
    ),
    ToolGuidanceChoice(
        "general",
        settingsGenerationText(language, "tool_mode_general"),
        settingsGenerationText(language, "tool_mode_general_description"),
    ),
    ToolGuidanceChoice(
        "large",
        settingsGenerationText(language, "tool_mode_large"),
        settingsGenerationText(language, "tool_mode_large_description"),
    ),
)

internal fun settingsGenerationText(language: AppLanguage, key: String): String {
    return when (key) {
        "configurations" -> when (language) {
            AppLanguage.CHINESE -> "配置"
            AppLanguage.SPANISH -> "Configuraciones"
            AppLanguage.GERMAN -> "Konfigurationen"
            AppLanguage.PORTUGUESE -> "Configurações"
            AppLanguage.FRENCH -> "Configurations"
            AppLanguage.ENGLISH -> "Configurations"
        }
        "model_configs" -> when (language) {
            AppLanguage.CHINESE -> "模型配置"
            AppLanguage.SPANISH -> "Config. modelo"
            AppLanguage.GERMAN -> "Modellkonfig."
            AppLanguage.PORTUGUESE -> "Config. modelo"
            AppLanguage.FRENCH -> "Config. modèle"
            AppLanguage.ENGLISH -> "Model configs"
        }
        "system_prompt" -> when (language) {
            AppLanguage.CHINESE -> "系统提示词"
            AppLanguage.SPANISH -> "Prompt del sistema"
            AppLanguage.GERMAN -> "Systemprompt"
            AppLanguage.PORTUGUESE -> "Prompt do sistema"
            AppLanguage.FRENCH -> "Prompt système"
            AppLanguage.ENGLISH -> "System prompt"
        }
        "tool_guidance" -> when (language) {
            AppLanguage.CHINESE -> "工具指导"
            AppLanguage.SPANISH -> "Guía de herramientas"
            AppLanguage.GERMAN -> "Werkzeugführung"
            AppLanguage.PORTUGUESE -> "Guia de ferramentas"
            AppLanguage.FRENCH -> "Guide des outils"
            AppLanguage.ENGLISH -> "Tool guidance"
        }
        "tool_guidance_description" -> when (language) {
            AppLanguage.CHINESE -> "选择本地模型在每次请求前接收多少经过整理的工具名称和参数结构。"
            AppLanguage.SPANISH -> "Elige cuántos nombres y esquemas de herramientas seleccionados recibe el modelo local antes de cada solicitud."
            AppLanguage.GERMAN -> "Wähle, wie viele kuratierte Werkzeugnamen und Schemas das lokale Modell vor jeder Anfrage erhält."
            AppLanguage.PORTUGUESE -> "Escolha quantos nomes e formatos de ferramentas selecionados o modelo local recebe antes de cada solicitação."
            AppLanguage.FRENCH -> "Choisissez combien de noms et schémas d’outils sélectionnés le modèle local reçoit avant chaque requête."
            AppLanguage.ENGLISH -> "Choose how many curated tool names and argument shapes the local model receives before every request."
        }
        "tool_mode_small" -> when (language) {
            AppLanguage.CHINESE -> "小型模型"
            AppLanguage.SPANISH -> "Modelo pequeño"
            AppLanguage.GERMAN -> "Kleines Modell"
            AppLanguage.PORTUGUESE -> "Modelo pequeno"
            AppLanguage.FRENCH -> "Petit modèle"
            AppLanguage.ENGLISH -> "Small model"
        }
        "tool_mode_general" -> when (language) {
            AppLanguage.CHINESE -> "通用模型"
            AppLanguage.SPANISH -> "Modelo general"
            AppLanguage.GERMAN -> "Allgemeines Modell"
            AppLanguage.PORTUGUESE -> "Modelo geral"
            AppLanguage.FRENCH -> "Modèle général"
            AppLanguage.ENGLISH -> "General model"
        }
        "tool_mode_large" -> when (language) {
            AppLanguage.CHINESE -> "大型模型"
            AppLanguage.SPANISH -> "Modelo grande"
            AppLanguage.GERMAN -> "Großes Modell"
            AppLanguage.PORTUGUESE -> "Modelo grande"
            AppLanguage.FRENCH -> "Grand modèle"
            AppLanguage.ENGLISH -> "Large model"
        }
        "tool_mode_small_description" -> when (language) {
            AppLanguage.CHINESE -> "发送四个核心工具结构，并加入请求明确需要的专用工具。上下文最小。"
            AppLanguage.SPANISH -> "Envía cuatro esquemas básicos y añade herramientas especializadas inferidas de la solicitud. Contexto mínimo."
            AppLanguage.GERMAN -> "Sendet vier Kernschemas plus aus der Anfrage abgeleitete Spezialwerkzeuge. Kleinster Kontext."
            AppLanguage.PORTUGUESE -> "Envia quatro formatos principais e adiciona ferramentas especializadas inferidas do pedido. Contexto mínimo."
            AppLanguage.FRENCH -> "Envoie quatre schémas essentiels et les outils spécialisés déduits de la demande. Contexte minimal."
            AppLanguage.ENGLISH -> "Sends four core schemas plus specialized tools inferred from the request. Smallest context."
        }
        "tool_mode_general_description" -> when (language) {
            AppLanguage.CHINESE -> "推荐。始终发送常用文件、终端、Linux、Android、自动化、诊断和记忆工具的精简结构。"
            AppLanguage.SPANISH -> "Recomendado. Siempre envía esquemas compactos para archivos, terminal, Linux, Android, automatización, diagnóstico y memoria."
            AppLanguage.GERMAN -> "Empfohlen. Sendet immer kompakte Schemas für Dateien, Terminal, Linux, Android, Automatisierung, Diagnose und Speicher."
            AppLanguage.PORTUGUESE -> "Recomendado. Sempre envia formatos compactos para arquivos, terminal, Linux, Android, automação, diagnóstico e memória."
            AppLanguage.FRENCH -> "Recommandé. Envoie toujours des schémas compacts pour fichiers, terminal, Linux, Android, automatisation, diagnostic et mémoire."
            AppLanguage.ENGLISH -> "Recommended. Always sends compact file, terminal, Linux, Android, automation, diagnostics, and memory schemas."
        }
        "tool_mode_large_description" -> when (language) {
            AppLanguage.CHINESE -> "发送完整的紧凑工具目录。适合上下文较大的模型，但占用更多 token。"
            AppLanguage.SPANISH -> "Envía el catálogo compacto completo. Adecuado para modelos con más contexto, pero usa más tokens."
            AppLanguage.GERMAN -> "Sendet den vollständigen kompakten Werkzeugkatalog. Für größere Kontexte, benötigt aber mehr Tokens."
            AppLanguage.PORTUGUESE -> "Envia o catálogo compacto completo. Para modelos com contexto maior, mas usa mais tokens."
            AppLanguage.FRENCH -> "Envoie le catalogue compact complet. Pour les modèles à grand contexte, avec davantage de jetons."
            AppLanguage.ENGLISH -> "Sends the complete compact catalog. Best for larger-context models, but uses more tokens."
        }
        "api_knobs_title" -> when (language) {
            AppLanguage.CHINESE -> "对 API 模型使用生成参数"
            AppLanguage.SPANISH -> "Usar controles de generación para API"
            AppLanguage.GERMAN -> "Generierungsregler für API-Modelle"
            AppLanguage.PORTUGUESE -> "Usar controles de geração para APIs"
            AppLanguage.FRENCH -> "Utiliser les réglages de génération API"
            AppLanguage.ENGLISH -> "Use generation knobs for API models"
        }
        "api_knobs_description" -> when (language) {
            AppLanguage.CHINESE -> "关闭时，提供商模型会保持现有默认值。"
            AppLanguage.SPANISH -> "Desactivado mantiene los modelos del proveedor con sus valores predeterminados."
            AppLanguage.GERMAN -> "Aus lässt Anbieter-Modelle bei ihren vorhandenen Standardwerten."
            AppLanguage.PORTUGUESE -> "Desativado mantém os modelos do provedor nos padrões atuais."
            AppLanguage.FRENCH -> "Désactivé garde les modèles fournisseur sur leurs valeurs par défaut."
            AppLanguage.ENGLISH -> "Off keeps provider models on their existing defaults."
        }
        "max_tokens" -> when (language) {
            AppLanguage.CHINESE -> "最大 token 数"
            AppLanguage.SPANISH -> "Tokens máximos"
            AppLanguage.GERMAN -> "Max. Tokens"
            AppLanguage.PORTUGUESE -> "Tokens máximos"
            AppLanguage.FRENCH -> "Tokens max."
            AppLanguage.ENGLISH -> "Max tokens"
        }
        "accelerator" -> when (language) {
            AppLanguage.CHINESE -> "加速器"
            AppLanguage.SPANISH -> "Acelerador"
            AppLanguage.GERMAN -> "Beschleuniger"
            AppLanguage.PORTUGUESE -> "Acelerador"
            AppLanguage.FRENCH -> "Accélérateur"
            AppLanguage.ENGLISH -> "Accelerator"
        }
        "accelerator_description" -> when (language) {
            AppLanguage.CHINESE -> "自动会使用 Hermes 运行时默认值。可选择经过实现的 CPU 或 GPU 路径；Hermes 尚未实现独立 NPU 后端。"
            AppLanguage.SPANISH -> "Auto mantiene el valor predeterminado. Elige las rutas CPU o GPU implementadas; Hermes aún no implementa un backend NPU independiente."
            AppLanguage.GERMAN -> "Auto nutzt den Runtime-Standard. Wähle die implementierten CPU- oder GPU-Pfade; Hermes hat noch kein separates NPU-Backend."
            AppLanguage.PORTUGUESE -> "Auto mantém o padrão do runtime. Escolha as rotas CPU ou GPU implementadas; o Hermes ainda não implementa um backend NPU separado."
            AppLanguage.FRENCH -> "Auto garde le réglage du runtime. Choisissez les chemins CPU ou GPU implémentés ; Hermes n’a pas encore de backend NPU distinct."
            AppLanguage.ENGLISH -> "Auto keeps the runtime default. Choose the implemented CPU or GPU paths; Hermes does not yet implement a separate NPU backend."
        }
        "system_prompt_placeholder" -> when (language) {
            AppLanguage.CHINESE -> "Hermes 回复的可选指令。"
            AppLanguage.SPANISH -> "Instrucciones opcionales para las respuestas de Hermes."
            AppLanguage.GERMAN -> "Optionale Anweisungen für Hermes-Antworten."
            AppLanguage.PORTUGUESE -> "Instruções opcionais para respostas do Hermes."
            AppLanguage.FRENCH -> "Instructions facultatives pour les réponses de Hermes."
            AppLanguage.ENGLISH -> "Optional instructions for Hermes replies."
        }
        "clear_prompt" -> when (language) {
            AppLanguage.CHINESE -> "清空提示词"
            AppLanguage.SPANISH -> "Borrar prompt"
            AppLanguage.GERMAN -> "Prompt löschen"
            AppLanguage.PORTUGUESE -> "Limpar prompt"
            AppLanguage.FRENCH -> "Effacer le prompt"
            AppLanguage.ENGLISH -> "Clear prompt"
        }
        "save_model_configuration" -> when (language) {
            AppLanguage.CHINESE -> "保存模型配置"
            AppLanguage.SPANISH -> "Guardar configuración del modelo"
            AppLanguage.GERMAN -> "Modellkonfiguration speichern"
            AppLanguage.PORTUGUESE -> "Salvar configuração do modelo"
            AppLanguage.FRENCH -> "Enregistrer la configuration modèle"
            AppLanguage.ENGLISH -> "Save model configuration"
        }
        "default" -> when (language) {
            AppLanguage.CHINESE -> "默认"
            AppLanguage.SPANISH -> "Predeterminado"
            AppLanguage.GERMAN -> "Standard"
            AppLanguage.PORTUGUESE -> "Padrão"
            AppLanguage.FRENCH -> "Par défaut"
            AppLanguage.ENGLISH -> "Default"
        }
        "auto" -> when (language) {
            AppLanguage.CHINESE -> "自动"
            AppLanguage.SPANISH -> "Auto"
            AppLanguage.GERMAN -> "Auto"
            AppLanguage.PORTUGUESE -> "Auto"
            AppLanguage.FRENCH -> "Auto"
            AppLanguage.ENGLISH -> "Auto"
        }
        else -> key
    }
}

private fun incrementGenerationValue(value: Int, defaultValue: Int, maxValue: Int, step: Int): Int {
    val base = if (value == defaultValue) 0 else value
    return (base + step).coerceAtMost(maxValue)
}

private fun decrementGenerationValue(value: Int, defaultValue: Int, minValue: Int, step: Int): Int {
    val next = (value - step).coerceAtLeast(minValue)
    return if (next <= 0) defaultValue else next
}

private fun formatGenerationDecimal(value: Float): String {
    return String.format(Locale.US, "%.2f", value)
}

@Composable
private fun AgentPersonaCard(
    customSystemPrompt: String,
    onPromptChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.agentPersonaTitle(), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = customSystemPrompt,
                onValueChange = onPromptChange,
                label = { Text(strings.customSystemPromptLabel()) },
                placeholder = { Text(strings.customSystemPromptPlaceholder()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("AgentPersonaPrompt"),
                minLines = 3,
                maxLines = 8,
            )
            Text(
                strings.characterCount(customSystemPrompt.length, AppSettings.MAX_CUSTOM_SYSTEM_PROMPT_CHARS),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.testTag("SaveAgentPersonaButton"),
                    onClick = onSave,
                ) {
                    Text(strings.savePersonaLabel())
                }
                Button(
                    modifier = Modifier.testTag("ClearAgentPersonaButton"),
                    onClick = onClear,
                    enabled = customSystemPrompt.isNotBlank(),
                ) {
                    Text(strings.clearLabel())
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    chatDisplayMode: String,
    keywordHighlightingEnabled: Boolean,
    themePrimaryHex: String,
    themeSecondaryHex: String,
    themeBackgroundHex: String,
    themeSurfaceHex: String,
    themeSurfaceVariantHex: String,
    themeCardShape: String,
    uiFontScale: Float,
    onChatDisplayModeChange: (String) -> Unit,
    onKeywordHighlightingChange: (Boolean) -> Unit,
    onPrimaryHexChange: (String) -> Unit,
    onSecondaryHexChange: (String) -> Unit,
    onBackgroundHexChange: (String) -> Unit,
    onSurfaceHexChange: (String) -> Unit,
    onSurfaceVariantHexChange: (String) -> Unit,
    onCardShapeChange: (String) -> Unit,
    onUiFontScaleChange: (Float) -> Unit,
    onApplyPreset: (AppearanceThemePreset) -> Unit,
    onSaveAppearance: () -> Unit,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("HermesAppearanceCard"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                strings.appearanceTitle(),
                modifier = Modifier.testTag("HermesAppearanceCardTop"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                strings.appearanceDescription(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(strings.chatDisplayLabel(), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.testTag("ChatDisplayCompact"),
                    onClick = { onChatDisplayModeChange("compact") },
                    enabled = chatDisplayMode != "compact",
                ) {
                    Text(strings.compactModeLabel())
                }
                Button(
                    modifier = Modifier.testTag("ChatDisplayExpanded"),
                    onClick = { onChatDisplayModeChange("expanded") },
                    enabled = chatDisplayMode != "expanded",
                ) {
                    Text(strings.expandedModeLabel())
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.keywordHighlightingTitle(), style = MaterialTheme.typography.titleSmall)
                    Text(strings.keywordHighlightingDescription(), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = keywordHighlightingEnabled, onCheckedChange = onKeywordHighlightingChange)
            }
            Text(strings.uiFontSizeLabel(uiFontScale), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = uiFontScale,
                onValueChange = onUiFontScaleChange,
                valueRange = AppSettings.MIN_UI_FONT_SCALE..AppSettings.MAX_UI_FONT_SCALE,
                modifier = Modifier.fillMaxWidth().testTag("UiFontScaleSlider"),
            )
            Text(strings.colourPresetsTitle(), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                appearanceThemePresets.forEach { preset ->
                    Button(onClick = { onApplyPreset(preset) }) {
                        Text(strings.appearancePresetLabel(preset.id, preset.label))
                    }
                }
            }
            OutlinedTextField(
                value = themePrimaryHex,
                onValueChange = onPrimaryHexChange,
                label = { Text(strings.accentHexLabel()) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = themeSecondaryHex,
                onValueChange = onSecondaryHexChange,
                label = { Text(strings.secondaryAccentHexLabel()) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = themeBackgroundHex,
                onValueChange = onBackgroundHexChange,
                label = { Text(strings.backgroundHexLabel()) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = themeSurfaceHex,
                onValueChange = onSurfaceHexChange,
                label = { Text(strings.composerSurfaceHexLabel()) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = themeSurfaceVariantHex,
                onValueChange = onSurfaceVariantHexChange,
                label = { Text(strings.assistantPanelHexLabel()) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(strings.cardsAndBoxesTitle(), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("rounded", "soft", "square").forEach { shape ->
                    Button(
                        modifier = Modifier.testTag("CardShape-$shape"),
                        onClick = { onCardShapeChange(shape) },
                        enabled = themeCardShape != shape,
                    ) {
                        Text(strings.cardShapeLabel(shape))
                    }
                }
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SaveAppearanceButton"),
                onClick = onSaveAppearance,
            ) {
                Text(strings.saveAppearanceLabel())
            }
        }
    }
}

@Composable
private fun OfflineAirplaneCard(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.offlineAirplaneModeTitle(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        strings.offlineAirplaneModeDescription(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onChange)
            }
            Button(onClick = { onChange(!enabled) }) {
                Text(strings.offlineAirplaneToggleLabel(enabled))
            }
        }
    }
}

@Composable
private fun SettingsHelpCard(
    providerLabel: String,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Text("New here?")
            Text(strings.settingsNewHereTitle.ifBlank { "New here?" }, style = MaterialTheme.typography.titleMedium)
            Text(
                strings.forkDisclosure(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(strings.settingsHelpStart)
            // Accounts keeps app sign-in separate from provider key setup.
            Text(strings.settingsHelpAccounts)
            Text(strings.currentProviderProfile(providerLabel))
        }
    }
}

@Composable
private fun RemoteFallbackCard(
    providerId: String,
    providerLabel: String,
    baseUrl: String,
    model: String,
    apiKey: String,
    status: String,
    onSelectProvider: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onOpenProviderKeyPage: (String, String) -> Unit,
    onCopyProviderKeyPage: (String, String) -> Unit,
    onCheckProviderKeyPage: (String, String) -> Unit,
    onImportProviderCredential: () -> Unit,
    onSave: () -> Unit,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
) {
    val providerPreset = ProviderPresets.find(providerId)
    val customEndpointPreview = if (providerId == "custom") {
        runCatching { HermesEndpointUrl.chatCompletionsUrl(baseUrl) }.getOrNull()
    } else {
        null
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.remoteFallbackTitle(), style = MaterialTheme.typography.titleMedium)
            Text(strings.remoteFallbackDescription(), style = MaterialTheme.typography.bodySmall)
            Text(strings.providerLabel(), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderPresets.androidSettingsDefaults.forEach { preset ->
                    Button(
                        onClick = { onSelectProvider(preset.id) },
                        enabled = preset.id != providerId,
                    ) {
                        Text(strings.providerDisplayLabel(preset.id, preset.label))
                    }
                }
            }
            Text(strings.currentProviderProfile(providerLabel), style = MaterialTheme.typography.bodySmall)
            providerPreset?.apiKeyUrl?.takeIf { it.isNotBlank() }?.let { apiKeyUrl ->
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onOpenProviderKeyPage(providerId, apiKeyUrl) }) {
                        Text(strings.openProviderKeyPage(providerLabel))
                    }
                    Button(onClick = { onCopyProviderKeyPage(providerId, apiKeyUrl) }) {
                        Text(strings.copyProviderSetupUrl())
                    }
                    Button(onClick = { onCheckProviderKeyPage(providerId, apiKeyUrl) }) {
                        Text(strings.checkProviderSetupUrl())
                    }
                }
            }
            Button(onClick = onImportProviderCredential) {
                Text(strings.importSavedProviderCredential())
            }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text(strings.baseUrlLabel()) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (providerId == "custom") {
                Text(
                    strings.customEndpointConnectionHint(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                customEndpointPreview?.let { preview ->
                    Text(
                        text = strings.customEndpointPreview(preview),
                        modifier = Modifier.testTag("HermesEndpointDebugPreview"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            providerPreset?.modelHint?.takeIf { it.isNotBlank() }?.let { modelHint ->
                Button(onClick = { onModelChange(modelHint) }) {
                    Text(strings.suggestedModelSummary(modelHint))
                }
            }
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text(strings.modelLabel()) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(strings.apiKeyLabel()) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(strings.apiKeyHelp(), style = MaterialTheme.typography.bodySmall)
            Text(
                strings.providerCredentialInputHelp(ProviderPresets.apiKeyEnvVars(providerId)),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSave) {
                Text(strings.saveLabel())
            }
        }
    }
}

@Composable
private fun OnDeviceInferenceCard(
    onDeviceBackend: String,
    speculativeDecodingMode: String,
    onSelectBackend: (String) -> Unit,
    onSelectSpeculativeDecodingMode: (String) -> Unit,
    onStartRuntime: (String) -> Unit,
    summary: String,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.onDeviceInferenceTitle.ifBlank { "On-device inference" }, style = MaterialTheme.typography.titleMedium)
            Text(strings.onDeviceInferenceDescription, style = MaterialTheme.typography.bodySmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onSelectBackend(BackendKind.LLAMA_CPP.persistedValue)
                        onStartRuntime("GGUF")
                    },
                    enabled = onDeviceBackend != BackendKind.LLAMA_CPP.persistedValue,
                ) {
                    Text(strings.llamaCppLabel.ifBlank { "llama.cpp (GGUF)" })
                }
                Button(
                    onClick = {
                        onSelectBackend(BackendKind.LITERT_LM.persistedValue)
                        onStartRuntime("LiteRT-LM")
                    },
                    enabled = onDeviceBackend != BackendKind.LITERT_LM.persistedValue,
                ) {
                    Text(strings.liteRtLmLabel.ifBlank { "LiteRT-LM" })
                }
                Button(
                    onClick = { onSelectBackend(BackendKind.NONE.persistedValue) },
                    enabled = onDeviceBackend != BackendKind.NONE.persistedValue,
                ) {
                    Text(strings.remoteOnly())
                }
            }
            Text(strings.llamaCppDescription, style = MaterialTheme.typography.bodySmall)
            Text(strings.liteRtLmDescription, style = MaterialTheme.typography.bodySmall)
            Text(strings.gemma4MtpTitle(), style = MaterialTheme.typography.titleSmall)
            Text(strings.gemma4MtpDescription(), style = MaterialTheme.typography.bodySmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                speculativeDecodingChoices().forEach { choice ->
                    Button(
                        modifier = Modifier.testTag("LiteRtLmMtpMode-${choice.value}"),
                        onClick = { onSelectSpeculativeDecodingMode(choice.value) },
                        enabled = speculativeDecodingMode != choice.value,
                    ) {
                        Text(choice.label(strings))
                    }
                }
            }
            Text(localizedOnDeviceSummary(summary, strings), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class SpeculativeDecodingChoice(
    val value: String,
    val label: (com.mobilefork.hermesagent.ui.i18n.HermesStrings) -> String,
)

private fun speculativeDecodingChoices(): List<SpeculativeDecodingChoice> = listOf(
    SpeculativeDecodingChoice("auto") { it.gemma4MtpAutoLabel() },
    SpeculativeDecodingChoice("enabled") { it.gemma4MtpEnabledLabel() },
    SpeculativeDecodingChoice("disabled") { it.gemma4MtpDisabledLabel() },
)

private fun localizedOnDeviceSummary(
    summary: String,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
): String {
    val trimmed = summary.trim()
    return when {
        trimmed.isBlank() -> strings.noCompatibleLocalModel
        trimmed == "Remote provider mode" -> strings.remoteProviderMode()
        trimmed == "Checking preferred local model…" -> strings.checkingPreferredLocalModel()
        trimmed.startsWith("No preferred local model") -> strings.noCompatibleLocalModel
        trimmed.startsWith("Preferred local model:") ->
            "${strings.preferredLocalModel}: ${trimmed.substringAfter(':').trim()}"
        else -> trimmed
    }
}

@Composable
private fun LanguagePickerCard(
    currentLanguageTag: String,
    onSelectLanguage: (AppLanguage) -> Unit,
    strings: com.mobilefork.hermesagent.ui.i18n.HermesStrings,
) {
    val selected = AppLanguage.fromTag(currentLanguageTag)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("SettingsLanguagePicker"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.appLanguageTitle.ifBlank { "App language" }, style = MaterialTheme.typography.titleMedium)
            Text(strings.appLanguageDescription, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "${selected.flag} ${selected.nativeLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("SettingsLanguageCurrent"),
            )
            // Supported flags: 🇬🇧 🇨🇳 🇪🇸 🇩🇪 🇵🇹 🇫🇷
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLanguage.entries.forEach { language ->
                    val isSelected = selected.tag == language.tag
                    if (isSelected) {
                        Button(
                            onClick = { /* already active */ },
                            enabled = true,
                            modifier = Modifier
                                .testTag("SettingsLanguage-${language.tag}")
                                .semantics {
                                    contentDescription = strings.selectedLanguageDescription(language.nativeLabel)
                                },
                        ) {
                            Text("✓ ${language.flag} ${language.nativeLabel}")
                        }
                    } else {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onSelectLanguage(language) },
                            modifier = Modifier
                                .testTag("SettingsLanguage-${language.tag}")
                                .semantics {
                                    contentDescription = strings.switchLanguageDescription(language.nativeLabel)
                                },
                        ) {
                            Text("${language.flag} ${language.nativeLabel}")
                        }
                    }
                }
            }
        }
    }
}
