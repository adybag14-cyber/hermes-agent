@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mobilefork.hermesagent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.modelScopeMirrorButton
import com.mobilefork.hermesagent.ui.i18n.modelScopeMirrorNote
import com.mobilefork.hermesagent.ui.i18n.modelScopeResearchNotice
import com.mobilefork.hermesagent.ui.i18n.modelScopeLicencesButton
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import com.mobilefork.hermesagent.models.VerifiedLocalModelMirrors

internal fun recommendedLocalModelCardTestTag(presetId: String): String =
    "RecommendedLocalModelCard-$presetId"

internal fun dispatchAcceptedLocalModelRuntimeHandoff(
    result: LocalModelRuntimeHandoffResult,
    onAccepted: (
        requiredLlamaCppRuntimeLane: String?,
        selectionGeneration: Long,
    ) -> Unit,
): Boolean {
    val accepted = result as? LocalModelRuntimeHandoffResult.Accepted ?: return false
    onAccepted(accepted.requiredLlamaCppRuntimeLane, accepted.selectionGeneration)
    return true
}

@Composable
fun LocalModelDownloadsSection(
    dataSaverMode: Boolean,
    offlineAirplaneMode: Boolean,
    onDataSaverModeChange: (Boolean) -> Unit,
    selectedBackend: String,
    onRuntimeFlavorSelected: (String) -> Unit,
    onRequiredLlamaCppRuntimeLane: (String?) -> Unit,
    onCompletedDownloadReady: (runtimeFlavor: String, selectionGeneration: Long) -> Boolean,
    importModelClickOverride: (() -> Unit)? = null,
    viewModel: LocalModelDownloadsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val strings = LocalHermesStrings.current
    val uriHandler = LocalUriHandler.current
    var detectedModelMenuExpanded by remember { mutableStateOf(false) }
    val selectedDetectedModel = uiState.detectedModels.firstOrNull { model -> model.id == uiState.selectedDetectedModelId }
    val importModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importLocalModelFile(uri)
        }
    }

    LaunchedEffect(selectedBackend) {
        viewModel.syncSelectedBackend(selectedBackend)
    }

    LaunchedEffect(uiState.pendingAutoStartRecordId, uiState.downloads) {
        val pendingId = uiState.pendingAutoStartRecordId
        if (pendingId.isNotBlank()) {
            val completed = uiState.downloads.firstOrNull { item ->
                item.id == pendingId && item.statusLabel == "completed"
            }
            if (completed != null) {
                var runtimeHandoffAccepted = false
                val promotionAccepted = dispatchAcceptedLocalModelRuntimeHandoff(
                    result = viewModel.promoteDownloadedModelForAutoStart(completed.id),
                ) { _, selectionGeneration ->
                    runtimeHandoffAccepted = runCatching {
                        onCompletedDownloadReady(completed.runtimeFlavor, selectionGeneration)
                    }.getOrDefault(false)
                }
                if (promotionAccepted) {
                    viewModel.completePendingAutoStartHandoff(completed.id, runtimeHandoffAccepted)
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.localDownloadsTitle.ifBlank { "Hugging Face local model downloads" }, style = MaterialTheme.typography.titleMedium)
            Text(
                strings.localDownloadsDescription.ifBlank {
                    "Download full model files directly to the phone, keep progress in Android's system download manager, and resume safely after network loss or a phone restart. PocketPal AI is a good reference for the kind of mobile-local model hub Hermes is moving toward."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.dataSaverModeTitle.ifBlank { "Data saver mode" }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        strings.dataSaverModeDescription.ifBlank {
                            "When enabled, large model downloads wait for Wi‑Fi / unmetered connectivity so Hermes uses only minimal mobile data."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = dataSaverMode,
                    onCheckedChange = onDataSaverModeChange,
                )
            }
            OutlinedTextField(
                value = uiState.huggingFaceToken,
                onValueChange = viewModel::updateHuggingFaceToken,
                label = { Text(strings.huggingFaceTokenOptional.ifBlank { "Hugging Face token (optional)" }) },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::saveHuggingFaceToken) {
                    Text(strings.saveToken.ifBlank { "Save token" })
                }
                Button(onClick = viewModel::refreshDownloads) {
                    Text(strings.refreshDownloads.ifBlank { "Refresh downloads" })
                }
                Button(
                    modifier = Modifier.testTag("HermesImportModelButton"),
                    onClick = {
                        val override = importModelClickOverride
                        if (override != null) {
                            override()
                        } else {
                            importModelLauncher.launch(
                                arrayOf(
                                    "application/octet-stream",
                                    "application/x-gguf",
                                    "application/zip",
                                    "*/*",
                                )
                            )
                        }
                    },
                ) {
                    Text(strings.importModelFromPhoneFiles())
                }
            }
            if (offlineAirplaneMode) {
                Text(
                    strings.offlineAirplaneLocalModelsOnly(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
            Text(strings.quickLocalModelsTitle(), style = MaterialTheme.typography.titleSmall)
            Text(strings.quickLocalModelsDescription(), style = MaterialTheme.typography.bodySmall)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(strings.detectedModelCatalogTitle(), style = MaterialTheme.typography.titleSmall)
                    Text(strings.detectedModelCatalogDescription(), style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { detectedModelMenuExpanded = true },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.detectedModels.isNotEmpty(),
                        ) {
                            Text(selectedDetectedModel?.title ?: strings.detectedModelDropdownPlaceholder())
                        }
                        DropdownMenu(
                            expanded = detectedModelMenuExpanded,
                            onDismissRequest = { detectedModelMenuExpanded = false },
                        ) {
                            uiState.detectedModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(model.title, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${strings.localModelUiText("Release-certified")} · " +
                                                    "${model.expectedBytes?.div(1024L * 1024L)} MiB · ${model.runtimeFlavor}",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    },
                                    onClick = {
                                        detectedModelMenuExpanded = false
                                        onRuntimeFlavorSelected(model.runtimeFlavor)
                                        onRequiredLlamaCppRuntimeLane(
                                            viewModel.selectDetectedModel(model.id),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (selectedDetectedModel != null) {
                        Text(strings.localModelUiText(selectedDetectedModel.summary), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${strings.localModelUiText("Release-certified")} · " +
                                "${selectedDetectedModel.expectedBytes?.div(1024L * 1024L)} MiB · " +
                                "${selectedDetectedModel.runtimeFlavor} · ${selectedDetectedModel.repoOrUrl}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = viewModel::refreshDetectedModels,
                            enabled = !offlineAirplaneMode,
                        ) {
                            Text(strings.refreshCatalog())
                        }
                        Button(
                            onClick = {
                                selectedDetectedModel?.let { model ->
                                    onRuntimeFlavorSelected(model.runtimeFlavor)
                                    onRequiredLlamaCppRuntimeLane(
                                        viewModel.startDetectedModelDownload(dataSaverMode),
                                    )
                                }
                            },
                            enabled = selectedDetectedModel?.quickStartEligible == true && !offlineAirplaneMode,
                        ) {
                            Text(strings.downloadAndStart())
                        }
                    }
                    if (uiState.workerCatalogStatus.isNotBlank()) {
                        Text(strings.localModelUiText(uiState.workerCatalogStatus), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            LocalModelDownloadsViewModel.recommendedModelPresets.forEach { preset ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(recommendedLocalModelCardTestTag(preset.id)),
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(preset.title, style = MaterialTheme.typography.titleSmall)
                        Text(strings.recommendedLocalModelDescription(preset.id, preset.description), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${preset.repoOrUrl} · ${preset.filePath}\n" +
                                "Revision ${preset.revision}\n" +
                                "${preset.expectedBytes} bytes · SHA-256 ${preset.sha256}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "${preset.runtimeFlavor} · ${strings.recommendedLocalModelTestedLabel(preset.id, preset.testedLabel)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Button(
                            onClick = {
                                onRuntimeFlavorSelected(preset.runtimeFlavor)
                                onRequiredLlamaCppRuntimeLane(
                                    viewModel.startRecommendedModelDownload(preset.id, dataSaverMode),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !offlineAirplaneMode,
                        ) {
                            Text(strings.downloadAndStart())
                        }
                        val mirror = VerifiedLocalModelArtifacts.find(preset.repoOrUrl, preset.filePath)
                            ?.let(VerifiedLocalModelMirrors::forArtifact)
                        if (mirror != null) {
                            OutlinedButton(
                                onClick = {
                                    onRuntimeFlavorSelected(preset.runtimeFlavor)
                                    onRequiredLlamaCppRuntimeLane(
                                        viewModel.startRecommendedModelDownload(preset.id, dataSaverMode, useModelScope = true),
                                    )
                                },
                                enabled = !offlineAirplaneMode,
                                modifier = Modifier.fillMaxWidth().testTag("ModelScopeMirror-${preset.id}"),
                            ) { Text(strings.modelScopeMirrorButton()) }
                            Text(strings.modelScopeMirrorNote(), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("ModelScopeNote-${preset.id}"))
                            if (mirror.researchOnly) {
                                Text(strings.modelScopeResearchNotice(), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("ModelScopeResearch-${preset.id}"))
                            }
                            TextButton(onClick = { uriHandler.openUri("https://modelscope.cn/models/${mirror.repoId}") },
                                modifier = Modifier.testTag("ModelScopeLicences-${preset.id}")) {
                                Text(strings.modelScopeLicencesButton())
                            }
                        }
                    }
                }
            }
            Text(
                strings.localDownloadsExampleGuidance(),
                style = MaterialTheme.typography.bodySmall,
            )
            if (uiState.inspectionStatus.isNotBlank()) {
                Text(strings.localModelUiText(uiState.inspectionStatus), style = MaterialTheme.typography.bodySmall)
            }
            if (uiState.candidateSummary.isNotBlank()) {
                Text(strings.localModelUiText(uiState.candidateSummary), style = MaterialTheme.typography.bodySmall)
            }
            if (uiState.candidateRamWarning.isNotBlank()) {
                Text(strings.localModelUiText(uiState.candidateRamWarning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text(strings.downloadManagerTitle.ifBlank { "Download manager" }, style = MaterialTheme.typography.titleSmall)
            Text(
                strings.downloadManagerReliabilityDescription(),
                style = MaterialTheme.typography.bodySmall,
            )
            if (uiState.downloads.isEmpty()) {
                Text(strings.noLocalModelDownloadsYet.ifBlank { "No local model downloads yet." }, style = MaterialTheme.typography.bodySmall)
            } else {
                uiState.downloads.forEach { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                                    Text(strings.localDownloadStatusLine(item.runtimeFlavor, item.statusLabel), style = MaterialTheme.typography.labelMedium)
                                }
                                if (item.isPreferred) {
                                    Text(strings.preferredLocalModel.ifBlank { "Preferred local model" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            LinearProgressIndicator(
                                progress = { item.progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(item.progressLabel, style = MaterialTheme.typography.bodySmall)
                            Text(strings.localModelUiText(item.statusMessage), style = MaterialTheme.typography.bodySmall)
                            if (item.ramWarning.isNotBlank()) {
                                Text(strings.localModelUiText(item.ramWarning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(item.localPath, style = MaterialTheme.typography.bodySmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (item.statusLabel == "completed") {
                                    Button(
                                        onClick = {
                                            dispatchAcceptedLocalModelRuntimeHandoff(
                                                result = viewModel.setPreferredDownload(item.id),
                                            ) { _, selectionGeneration ->
                                                onCompletedDownloadReady(item.runtimeFlavor, selectionGeneration)
                                            }
                                        },
                                    ) {
                                        Text(if (item.isPreferred) strings.startRuntime() else strings.useAndStart())
                                    }
                                }
                                if (item.canRestartOnMobileData) {
                                    Button(onClick = { viewModel.restartDownloadOnMobileData(item.id) }) {
                                        Text(strings.restartOnMobileData())
                                    }
                                }
                                if (item.canOpenSystemDownloads) {
                                    Button(onClick = viewModel::openSystemDownloads) {
                                        Text(strings.openSystemDownloads())
                                    }
                                }
                                Button(onClick = { viewModel.removeDownload(item.id) }) {
                                    Text(strings.remove.ifBlank { "Remove" })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
