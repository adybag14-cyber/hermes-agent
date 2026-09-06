package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import org.json.JSONObject

/** Presentation shared by native tool-result handling, independent of the model loop. */
internal fun nativeToolActivityType(toolName: String): AgentEventType = when (toolName) {
    "file_write_tool", "write_file", "file_tool" -> AgentEventType.FileAccess
    "terminal_tool", "terminal", "shell", "mcp_send_terminal_input",
    "mcp_run_in_proot", "linux_sandbox_tool", "linux_sandbox",
    "proot_distro_tool", "proot-distro", "proot_distro" -> AgentEventType.ProcessLog
    else -> AgentEventType.ToolResult
}

/** Only a native, structured policy denial may terminate the model follow-up. */
internal fun nativeSandboxPolicyDenial(toolResult: String, languageTag: String): String? {
    val result = runCatching { JSONObject(toolResult) }.getOrNull() ?: return null
    if (result.optString("sandbox_execution_mode") != "request_owned_proot_blocked" ||
        result.opt("request_owned_operation_blocked") != true || result.optInt("exit_code") != 126
    ) return null
    return sandboxStopPolicyMessage(AppLanguage.fromTag(languageTag))
}

internal fun sandboxStopPolicyMessage(language: AppLanguage): String = when (language) {
    AppLanguage.ENGLISH -> "Hermes blocked this Linux sandbox command before it ran: this chat cannot guarantee that Stop prevents guest filesystem or package changes. This is an app safety restriction, not a model-loading or file-permission error. Use the manual Linux sandbox controls on the Device page."
    AppLanguage.CHINESE -> "Hermes 在执行前阻止了此 Linux 沙盒命令：当前聊天无法保证按下“停止”后不再修改沙盒文件或软件包。这是应用的安全限制，并非模型加载或文件权限错误。请使用“设备”页面的 Linux 沙盒手动控制。"
    AppLanguage.SPANISH -> "Hermes bloqueó este comando del entorno Linux antes de ejecutarlo: este chat no puede garantizar que Detener evite cambios en archivos o paquetes del entorno. Es una restricción de seguridad de la aplicación, no un error del modelo ni de permisos. Usa los controles manuales de Linux en Dispositivo."
    AppLanguage.GERMAN -> "Hermes hat diesen Linux-Sandbox-Befehl vor der Ausführung blockiert: Dieser Chat kann nicht garantieren, dass Stopp Änderungen an Dateien oder Paketen im Gast verhindert. Das ist eine Sicherheitsgrenze der App, kein Modell- oder Dateiberechtigungsfehler. Nutze die manuellen Linux-Sandbox-Steuerungen unter Gerät."
    AppLanguage.PORTUGUESE -> "O Hermes bloqueou este comando do ambiente Linux antes da execução: este chat não pode garantir que Parar impeça alterações nos arquivos ou pacotes do ambiente. É uma restrição de segurança do aplicativo, não um erro do modelo ou de permissões. Use os controles manuais de Linux em Dispositivo."
    AppLanguage.FRENCH -> "Hermes a bloqué cette commande Linux avant son exécution : ce chat ne peut pas garantir qu’Arrêter empêche les modifications des fichiers ou paquets de l’environnement. Il s’agit d’une restriction de sécurité de l’application, pas d’une erreur de modèle ou de permissions. Utilisez les commandes manuelles Linux dans Appareil."
}
