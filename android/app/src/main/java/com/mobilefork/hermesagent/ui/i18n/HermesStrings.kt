package com.mobilefork.hermesagent.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.mobilefork.hermesagent.device.HermesGlobalAction
import com.mobilefork.hermesagent.ui.device.DevicePermission
import com.mobilefork.hermesagent.ui.device.DeviceOperationStatus
import com.mobilefork.hermesagent.ui.device.LinuxSuiteFailureStage

data class HermesStrings(
    val language: AppLanguage,
    val alphaBadge: String,
    val sectionHermes: String,
    val sectionAccounts: String,
    val sectionPortal: String,
    val sectionDevice: String,
    val sectionSettings: String,
    val subtitleHermes: String,
    val subtitleAccounts: String,
    val subtitlePortal: String,
    val subtitleDevice: String,
    val subtitleSettings: String,
    val runtimeSetupAndOnboarding: String,
    val openPageActions: String,
    val hermesLogoDescription: String,
    val settingsNewHereTitle: String,
    val settingsHelpStart: String,
    val settingsHelpAccounts: String,
    val appLanguageTitle: String,
    val appLanguageDescription: String,
    val onDeviceInferenceTitle: String,
    val onDeviceInferenceDescription: String,
    val llamaCppLabel: String,
    val llamaCppDescription: String,
    val liteRtLmLabel: String,
    val liteRtLmDescription: String,
    val noCompatibleLocalModel: String,
    val chatTitle: String,
    val openHistory: String,
    val history: String,
    val newChat: String,
    val backToChat: String,
    val clearConversation: String,
    val speakLastReply: String,
    val welcomeToHermes: String,
    val welcomeDescription: String,
    val accounts: String,
    val settings: String,
    val messageHermes: String,
    val send: String,
    val authIntro: String,
    val corr3xtAuthBaseUrl: String,
    val saveAuthUrl: String,
    val refresh: String,
    val pendingCorr3xtSignIn: String,
    val signIn: String,
    val signOut: String,
    val reconnect: String,
    val hermesProviderPrefix: String,
    val portalTitle: String,
    val portalEmbeddedDescription: String,
    val fullScreenPortal: String,
    val minimizePortal: String,
    val openExternally: String,
    val refreshPortal: String,
    val localDownloadsTitle: String,
    val localDownloadsDescription: String,
    val dataSaverModeTitle: String,
    val dataSaverModeDescription: String,
    val huggingFaceTokenOptional: String,
    val saveToken: String,
    val refreshDownloads: String,
    val repoIdOrDirectUrl: String,
    val filePathInsideRepo: String,
    val revision: String,
    val runtimeTarget: String,
    val inspect: String,
    val download: String,
    val downloadManagerTitle: String,
    val noLocalModelDownloadsYet: String,
    val preferredLocalModel: String,
    val setPreferred: String,
    val remove: String,
) {
    fun runningReadOnlyNativeCommand(): String = when (language) {
        AppLanguage.CHINESE -> "正在运行只读本机命令…"
        AppLanguage.SPANISH -> "Ejecutando un comando nativo de solo lectura…"
        AppLanguage.GERMAN -> "Schreibgeschützter nativer Befehl wird ausgeführt…"
        AppLanguage.PORTUGUESE -> "Executando um comando nativo somente leitura…"
        AppLanguage.FRENCH -> "Exécution d’une commande native en lecture seule…"
        AppLanguage.ENGLISH -> "Running read-only native command…"
    }

    fun readOnlyNativeCommandUnavailable(): String = when (language) {
        AppLanguage.CHINESE -> "只读本机命令路由意外不可用"
        AppLanguage.SPANISH -> "La ruta del comando nativo de solo lectura no está disponible"
        AppLanguage.GERMAN -> "Die schreibgeschützte native Befehlsroute ist nicht verfügbar"
        AppLanguage.PORTUGUESE -> "A rota do comando nativo somente leitura não está disponível"
        AppLanguage.FRENCH -> "La route de commande native en lecture seule est indisponible"
        AppLanguage.ENGLISH -> "The read-only native command route was unexpectedly unavailable"
    }

    fun nativeTerminalCommandFailed(detail: String): String = when (language) {
        AppLanguage.CHINESE -> "本机终端命令失败：$detail"
        AppLanguage.SPANISH -> "El comando de terminal nativo falló: $detail"
        AppLanguage.GERMAN -> "Der native Terminalbefehl ist fehlgeschlagen: $detail"
        AppLanguage.PORTUGUESE -> "O comando de terminal nativo falhou: $detail"
        AppLanguage.FRENCH -> "La commande de terminal native a échoué : $detail"
        AppLanguage.ENGLISH -> "Native terminal command failed: $detail"
    }

    fun nativeReadOnlyCommandCompleted(content: String, modelRequests: Int): String = when (language) {
        AppLanguage.CHINESE -> "本机只读命令已完成\n$content\n模型请求数=$modelRequests"
        AppLanguage.SPANISH -> "Comando nativo de solo lectura completado\n$content\nsolicitudes_al_modelo=$modelRequests"
        AppLanguage.GERMAN -> "Schreibgeschützter nativer Befehl abgeschlossen\n$content\nModellanfragen=$modelRequests"
        AppLanguage.PORTUGUESE -> "Comando nativo somente leitura concluído\n$content\nsolicitações_ao_modelo=$modelRequests"
        AppLanguage.FRENCH -> "Commande native en lecture seule terminée\n$content\nrequêtes_modèle=$modelRequests"
        AppLanguage.ENGLISH -> "Native read-only command completed\n$content\nmodel_requests=$modelRequests"
    }

    fun runningNativeAndroidDiagnostics(): String = chatStatusText("Running native Android diagnostics…")

    fun nativeAndroidDiagnosticsFailed(detail: String): String = when (language) {
        AppLanguage.CHINESE -> "原生 Android 诊断失败：$detail"
        AppLanguage.SPANISH -> "Los diagnósticos nativos de Android fallaron: $detail"
        AppLanguage.GERMAN -> "Die native Android-Diagnose ist fehlgeschlagen: $detail"
        AppLanguage.PORTUGUESE -> "Os diagnósticos nativos do Android falharam: $detail"
        AppLanguage.FRENCH -> "Les diagnostics Android natifs ont échoué : $detail"
        AppLanguage.ENGLISH -> "Native Android diagnostics failed: $detail"
    }

    fun nativeAndroidDiagnosticsCompleted(content: String, modelRequests: Int): String = when (language) {
        AppLanguage.CHINESE -> "原生 Android 诊断已完成\n$content\n模型请求数=$modelRequests"
        AppLanguage.SPANISH -> "Diagnósticos nativos de Android completados\n$content\nsolicitudes_al_modelo=$modelRequests"
        AppLanguage.GERMAN -> "Native Android-Diagnose abgeschlossen\n$content\nModellanfragen=$modelRequests"
        AppLanguage.PORTUGUESE -> "Diagnósticos nativos do Android concluídos\n$content\nsolicitações_ao_modelo=$modelRequests"
        AppLanguage.FRENCH -> "Diagnostics Android natifs terminés\n$content\nrequêtes_modèle=$modelRequests"
        AppLanguage.ENGLISH -> "Native Android diagnostics completed\n$content\nmodel_requests=$modelRequests"
    }

    fun nativeAndroidDiagnosticsFailureResult(detail: String, modelRequests: Int): String = when (language) {
        AppLanguage.CHINESE -> "原生 Android 诊断失败\n$detail\n模型请求数=$modelRequests"
        AppLanguage.SPANISH -> "Los diagnósticos nativos de Android fallaron\n$detail\nsolicitudes_al_modelo=$modelRequests"
        AppLanguage.GERMAN -> "Native Android-Diagnose fehlgeschlagen\n$detail\nModellanfragen=$modelRequests"
        AppLanguage.PORTUGUESE -> "Os diagnósticos nativos do Android falharam\n$detail\nsolicitações_ao_modelo=$modelRequests"
        AppLanguage.FRENCH -> "Échec des diagnostics Android natifs\n$detail\nrequêtes_modèle=$modelRequests"
        AppLanguage.ENGLISH -> "Native Android diagnostics failed\n$detail\nmodel_requests=$modelRequests"
    }

    fun sectionKanban(): String = kanbanTitle()

    fun sectionTerminal(): String = when (language) {
        AppLanguage.CHINESE -> "终端"
        AppLanguage.SPANISH -> "Terminal"
        AppLanguage.GERMAN -> "Terminal"
        AppLanguage.PORTUGUESE -> "Terminal"
        AppLanguage.FRENCH -> "Terminal"
        AppLanguage.ENGLISH -> "Terminal"
    }

    fun subtitleKanban(): String = when (language) {
        AppLanguage.CHINESE -> "共享任务板与人工处置"
        AppLanguage.SPANISH -> "Tablero compartido y control humano de tareas"
        AppLanguage.GERMAN -> "Gemeinsames Aufgabenboard und manuelle Steuerung"
        AppLanguage.PORTUGUESE -> "Quadro compartilhado e controle humano de tarefas"
        AppLanguage.FRENCH -> "Tableau partagé et contrôle humain des tâches"
        AppLanguage.ENGLISH -> "Shared task board and human task control"
    }

    fun subtitleTerminal(): String = when (language) {
        AppLanguage.CHINESE -> "手动运行 PRoot Linux 命令"
        AppLanguage.SPANISH -> "Ejecuta comandos PRoot Linux manualmente"
        AppLanguage.GERMAN -> "PRoot-Linux-Befehle manuell ausführen"
        AppLanguage.PORTUGUESE -> "Execute comandos PRoot Linux manualmente"
        AppLanguage.FRENCH -> "Exécuter manuellement des commandes PRoot Linux"
        AppLanguage.ENGLISH -> "Run PRoot Linux commands manually"
    }

    fun attachmentFallback(): String = when (language) {
        AppLanguage.CHINESE -> "附件"
        AppLanguage.SPANISH -> "archivo adjunto"
        AppLanguage.GERMAN -> "Anhang"
        AppLanguage.PORTUGUESE -> "anexo"
        AppLanguage.FRENCH -> "pièce jointe"
        AppLanguage.ENGLISH -> "attachment"
    }

    fun messageClipboardLabel(): String = when (language) {
        AppLanguage.CHINESE -> "Hermes 消息"
        AppLanguage.SPANISH -> "Mensaje de Hermes"
        AppLanguage.GERMAN -> "Hermes-Nachricht"
        AppLanguage.PORTUGUESE -> "Mensagem do Hermes"
        AppLanguage.FRENCH -> "Message Hermes"
        AppLanguage.ENGLISH -> "Hermes message"
    }

    fun toolCallLabel(): String = when (language) {
        AppLanguage.CHINESE -> "工具调用"
        AppLanguage.SPANISH -> "Llamada de herramienta"
        AppLanguage.GERMAN -> "Werkzeugaufruf"
        AppLanguage.PORTUGUESE -> "Chamada de ferramenta"
        AppLanguage.FRENCH -> "Appel d’outil"
        AppLanguage.ENGLISH -> "Tool call"
    }

    fun argumentsLabel(): String = when (language) {
        AppLanguage.CHINESE -> "参数"
        AppLanguage.SPANISH -> "Argumentos"
        AppLanguage.GERMAN -> "Argumente"
        AppLanguage.PORTUGUESE -> "Argumentos"
        AppLanguage.FRENCH -> "Arguments"
        AppLanguage.ENGLISH -> "Arguments"
    }

    fun forkBadge(): String = when (language) {
        AppLanguage.CHINESE -> "分支"
        AppLanguage.SPANISH -> "Fork"
        AppLanguage.GERMAN -> "Fork"
        AppLanguage.PORTUGUESE -> "Fork"
        AppLanguage.FRENCH -> "Fork"
        AppLanguage.ENGLISH -> "FORK"
    }

    fun openNavigationMenu(): String = when (language) {
        AppLanguage.CHINESE -> "打开导航菜单"
        AppLanguage.SPANISH -> "Abrir menú de navegación"
        AppLanguage.GERMAN -> "Navigationsmenü öffnen"
        AppLanguage.PORTUGUESE -> "Abrir menu de navegação"
        AppLanguage.FRENCH -> "Ouvrir le menu de navigation"
        AppLanguage.ENGLISH -> "Open navigation menu"
    }

    fun forkDisclosure(): String = when (language) {
        AppLanguage.CHINESE -> "分支状态：Hermes Agent Fork 是独立社区分支，并非 Nous Research 或 Teknium 官方软件。"
        AppLanguage.SPANISH -> "Estado del fork: Hermes Agent Fork es un fork comunitario independiente. No es software oficial de Nous Research ni de Teknium."
        AppLanguage.GERMAN -> "Fork-Status: Hermes Agent Fork ist ein unabhängiger Community-Fork. Es ist keine offizielle Software von Nous Research oder Teknium."
        AppLanguage.PORTUGUESE -> "Status do fork: Hermes Agent Fork é um fork comunitário independente. Não é software oficial da Nous Research nem da Teknium."
        AppLanguage.FRENCH -> "Statut du fork : Hermes Agent Fork est un fork communautaire indépendant. Ce n’est pas un logiciel officiel de Nous Research ni de Teknium."
        AppLanguage.ENGLISH -> "Fork status: Hermes Agent Fork is an independent community fork. It is not official Nous Research or Teknium software."
    }

    fun currentProviderProfile(providerLabel: String): String {
        return when (language) {
            AppLanguage.CHINESE -> "当前提供商配置：$providerLabel"
            AppLanguage.SPANISH -> "Perfil actual del proveedor: $providerLabel"
            AppLanguage.GERMAN -> "Aktuelles Anbieterprofil: $providerLabel"
            AppLanguage.PORTUGUESE -> "Perfil atual do provedor: $providerLabel"
            AppLanguage.FRENCH -> "Profil fournisseur actuel : $providerLabel"
            AppLanguage.ENGLISH -> "Current provider profile: $providerLabel"
        }
    }

    fun chatCommandsTip(isListening: Boolean): String {
        if (isListening) {
            return when (language) {
                AppLanguage.CHINESE -> "正在聆听…"
                AppLanguage.SPANISH -> "Escuchando…"
                AppLanguage.GERMAN -> "Hört zu…"
                AppLanguage.PORTUGUESE -> "Ouvindo…"
                AppLanguage.FRENCH -> "Écoute…"
                AppLanguage.ENGLISH -> "Listening…"
            }
        }
        return when (language) {
            AppLanguage.CHINESE -> "提示：/help 会显示原生命令"
            AppLanguage.SPANISH -> "Consejo: /help muestra los comandos nativos"
            AppLanguage.GERMAN -> "Tipp: /help zeigt die nativen Befehle"
            AppLanguage.PORTUGUESE -> "Dica: /help mostra os comandos nativos"
            AppLanguage.FRENCH -> "Astuce : /help affiche les commandes natives"
            AppLanguage.ENGLISH -> "Tip: /help shows native chat commands"
        }
    }

    fun providerLabel(): String = when (language) {
        AppLanguage.CHINESE -> "提供商"
        AppLanguage.SPANISH -> "Proveedor"
        AppLanguage.GERMAN -> "Anbieter"
        AppLanguage.PORTUGUESE -> "Provedor"
        AppLanguage.FRENCH -> "Fournisseur"
        AppLanguage.ENGLISH -> "Provider"
    }

    fun baseUrlLabel(): String = when (language) {
        AppLanguage.CHINESE -> "基础 URL"
        AppLanguage.SPANISH -> "URL base"
        AppLanguage.GERMAN -> "Basis-URL"
        AppLanguage.PORTUGUESE -> "URL base"
        AppLanguage.FRENCH -> "URL de base"
        AppLanguage.ENGLISH -> "Base URL"
    }

    fun customEndpointConnectionHint(): String = when (language) {
        AppLanguage.CHINESE -> "自定义 OpenAI 兼容端点可以是裸主机、/v1 URL 或完整 /v1/chat/completions URL；Hermes 会规范化它，并使用服务器上完全匹配的模型名称。如果流提前关闭，Hermes 会在聊天中显示连接诊断。"
        AppLanguage.SPANISH -> "Los endpoints personalizados compatibles con OpenAI pueden ser un host sin esquema, una URL /v1 o una URL completa /v1/chat/completions; Hermes los normaliza y usa el nombre exacto del modelo del servidor. Si el flujo se cierra antes de tiempo, Hermes muestra diagnósticos de conexión en el chat."
        AppLanguage.GERMAN -> "Benutzerdefinierte OpenAI-kompatible Endpunkte können ein Roh-Host, eine /v1-URL oder eine vollständige /v1/chat/completions-URL sein; Hermes normalisiert sie und nutzt den exakten Modellnamen des Servers. Wenn der Stream vorzeitig endet, zeigt Hermes im Chat Verbindungsdiagnosen an."
        AppLanguage.PORTUGUESE -> "Endpoints personalizados compatíveis com OpenAI podem ser um host sem esquema, uma URL /v1 ou uma URL completa /v1/chat/completions; o Hermes normaliza e usa o nome exato do modelo do servidor. Se o stream fechar antes do esperado, o Hermes mostra diagnósticos de conexão no chat."
        AppLanguage.FRENCH -> "Les endpoints personnalisés compatibles OpenAI peuvent être un hôte brut, une URL /v1 ou une URL complète /v1/chat/completions; Hermes les normalise et utilise le nom exact du modèle côté serveur. Si le flux se ferme prématurément, Hermes affiche des diagnostics de connexion dans le chat."
        AppLanguage.ENGLISH -> "Custom OpenAI-compatible endpoints can be a raw host, a /v1 URL, or a full /v1/chat/completions URL; Hermes normalizes them and uses the exact model name from the server. If the stream closes early, Hermes shows connection diagnostics in chat."
    }

    fun customEndpointPreview(url: String): String = when (language) {
        AppLanguage.CHINESE -> "Hermes 将尝试：$url"
        AppLanguage.SPANISH -> "Hermes intentara: $url"
        AppLanguage.GERMAN -> "Hermes versucht: $url"
        AppLanguage.PORTUGUESE -> "Hermes tentara: $url"
        AppLanguage.FRENCH -> "Hermes essaiera: $url"
        AppLanguage.ENGLISH -> "Hermes will try: $url"
    }

    fun endpointStatusIndicatorLabel(): String = when (language) {
        AppLanguage.CHINESE -> "端点流状态"
        AppLanguage.SPANISH -> "Estado del stream del endpoint"
        AppLanguage.GERMAN -> "Endpunkt-Streamstatus"
        AppLanguage.PORTUGUESE -> "Status do stream do endpoint"
        AppLanguage.FRENCH -> "État du flux endpoint"
        AppLanguage.ENGLISH -> "Endpoint stream status"
    }

    fun endpointStatusTroubleshootingHint(): String = when (language) {
        AppLanguage.CHINESE -> "检查 /v1、模型名称、移动网络和服务器 SSE 保活设置。"
        AppLanguage.SPANISH -> "Revisa /v1, el nombre del modelo, la red móvil y el keep-alive SSE del servidor."
        AppLanguage.GERMAN -> "Prüfe /v1, Modellnamen, Mobilnetz und SSE-Keepalive des Servers."
        AppLanguage.PORTUGUESE -> "Verifique /v1, nome do modelo, rede móvel e keep-alive SSE do servidor."
        AppLanguage.FRENCH -> "Vérifiez /v1, le nom du modèle, le réseau mobile et le keep-alive SSE du serveur."
        AppLanguage.ENGLISH -> "Check /v1, the exact model name, mobile network, and server SSE keep-alive."
    }

    fun modelLabel(): String = when (language) {
        AppLanguage.CHINESE -> "模型"
        AppLanguage.SPANISH -> "Modelo"
        AppLanguage.GERMAN -> "Modell"
        AppLanguage.PORTUGUESE -> "Modelo"
        AppLanguage.FRENCH -> "Modèle"
        AppLanguage.ENGLISH -> "Model"
    }

    fun apiKeyLabel(): String = when (language) {
        AppLanguage.CHINESE -> "API 密钥 / 令牌"
        AppLanguage.SPANISH -> "Clave API / token"
        AppLanguage.GERMAN -> "API-Schlüssel / Token"
        AppLanguage.PORTUGUESE -> "Chave API / token"
        AppLanguage.FRENCH -> "Clé API / jeton"
        AppLanguage.ENGLISH -> "API key / token"
    }

    fun saveLabel(): String = when (language) {
        AppLanguage.CHINESE -> "保存"
        AppLanguage.SPANISH -> "Guardar"
        AppLanguage.GERMAN -> "Speichern"
        AppLanguage.PORTUGUESE -> "Salvar"
        AppLanguage.FRENCH -> "Enregistrer"
        AppLanguage.ENGLISH -> "Save"
    }

    fun providerDirectCallHelp(): String = when (language) {
        AppLanguage.CHINESE -> "选择 Hermes 要直接调用的提供商。提供商密钥或令牌在这里保存；应用账户登录请使用账户页面。"
        AppLanguage.SPANISH -> "Elige el proveedor al que Hermes llamará directamente. Guarda aquí claves o tokens de proveedor; usa Cuentas para iniciar sesión en la app."
        AppLanguage.GERMAN -> "Wähle den Anbieter, den Hermes direkt aufrufen soll. Speichere Anbieter-Schlüssel oder Tokens hier; nutze Konten für die App-Anmeldung."
        AppLanguage.PORTUGUESE -> "Escolha o provedor que o Hermes vai chamar diretamente. Salve chaves ou tokens de provedor aqui; use Contas para login no app."
        AppLanguage.FRENCH -> "Choisissez le fournisseur que Hermes doit appeler directement. Enregistrez ici les clés ou jetons fournisseur ; utilisez Comptes pour la connexion à l’application."
        AppLanguage.ENGLISH -> "Choose the provider you want Hermes to call directly. Save provider keys or tokens here; use Accounts for app sign-in."
    }

    fun providerDisplayLabel(providerId: String, fallbackLabel: String): String {
        return when (providerId.trim().lowercase()) {
            "custom" -> when (language) {
                AppLanguage.CHINESE -> "自定义 OpenAI 兼容端点"
                AppLanguage.SPANISH -> "Endpoint personalizado compatible con OpenAI"
                AppLanguage.GERMAN -> "Eigener OpenAI-kompatibler Endpunkt"
                AppLanguage.PORTUGUESE -> "Endpoint personalizado compatível com OpenAI"
                AppLanguage.FRENCH -> "Point de terminaison personnalisé compatible OpenAI"
                AppLanguage.ENGLISH -> "Custom OpenAI-compatible"
            }
            else -> fallbackLabel
        }
    }

    fun remoteProviderMode(): String = when (language) {
        AppLanguage.CHINESE -> "远程提供商模式"
        AppLanguage.SPANISH -> "Modo de proveedor remoto"
        AppLanguage.GERMAN -> "Remote-Anbietermodus"
        AppLanguage.PORTUGUESE -> "Modo de provedor remoto"
        AppLanguage.FRENCH -> "Mode fournisseur distant"
        AppLanguage.ENGLISH -> "Remote provider mode"
    }

    fun checkingPreferredLocalModel(): String = when (language) {
        AppLanguage.CHINESE -> "正在检查首选本地模型…"
        AppLanguage.SPANISH -> "Comprobando el modelo local preferido…"
        AppLanguage.GERMAN -> "Bevorzugtes lokales Modell wird geprüft…"
        AppLanguage.PORTUGUESE -> "Verificando modelo local preferido…"
        AppLanguage.FRENCH -> "Vérification du modèle local préféré…"
        AppLanguage.ENGLISH -> "Checking preferred local model…"
    }

    fun providerCredentialInputHelp(envVars: List<String>): String {
        val primary = envVars.firstOrNull().orEmpty()
        val aliases = envVars.drop(1).joinToString(separator = ", ")
        return if (aliases.isBlank()) {
            when (language) {
                AppLanguage.CHINESE -> "可粘贴原始密钥，或形如 $primary=... 的 CLI 环境变量行。"
                AppLanguage.SPANISH -> "Pega una clave sin formato o una línea de entorno CLI como $primary=..."
                AppLanguage.GERMAN -> "Füge einen Rohschlüssel oder eine CLI-Umgebungszeile wie $primary=... ein."
                AppLanguage.PORTUGUESE -> "Cole uma chave bruta ou uma linha de ambiente CLI como $primary=..."
                AppLanguage.FRENCH -> "Collez une clé brute ou une ligne d’environnement CLI comme $primary=..."
                AppLanguage.ENGLISH -> "Paste a raw key or a CLI env line such as $primary=..."
            }
        } else {
            when (language) {
                AppLanguage.CHINESE -> "可粘贴原始密钥，或形如 $primary=... 的 CLI 环境变量行；也接受 $aliases。"
                AppLanguage.SPANISH -> "Pega una clave sin formato o una línea de entorno CLI como $primary=...; también acepta $aliases."
                AppLanguage.GERMAN -> "Füge einen Rohschlüssel oder eine CLI-Umgebungszeile wie $primary=... ein; akzeptiert auch $aliases."
                AppLanguage.PORTUGUESE -> "Cole uma chave bruta ou uma linha de ambiente CLI como $primary=...; também aceita $aliases."
                AppLanguage.FRENCH -> "Collez une clé brute ou une ligne d’environnement CLI comme $primary=... ; accepte aussi $aliases."
                AppLanguage.ENGLISH -> "Paste a raw key or a CLI env line such as $primary=...; also accepts $aliases."
            }
        }
    }

    fun appearanceTitle(): String = when (language) {
        AppLanguage.CHINESE -> "主题与聊天布局"
        AppLanguage.SPANISH -> "Tema y diseño del chat"
        AppLanguage.GERMAN -> "Theme und Chat-Layout"
        AppLanguage.PORTUGUESE -> "Tema e layout do chat"
        AppLanguage.FRENCH -> "Thème et disposition du chat"
        AppLanguage.ENGLISH -> "Theme and chat layout"
    }

    fun appearanceDescription(): String = when (language) {
        AppLanguage.CHINESE -> "调整紧凑或展开聊天、关键词高亮、应用配色以及卡片圆角或方角。"
        AppLanguage.SPANISH -> "Ajusta chat compacto o expandido, resaltado de palabras clave, colores de la app y tarjetas redondeadas o cuadradas."
        AppLanguage.GERMAN -> "Passe kompakten oder erweiterten Chat, Hervorhebung, App-Farben und runde oder eckige Karten an."
        AppLanguage.PORTUGUESE -> "Ajuste chat compacto ou expandido, destaque de palavras-chave, cores do app e cartões arredondados ou quadrados."
        AppLanguage.FRENCH -> "Réglez le chat compact ou étendu, la mise en évidence, les couleurs de l’app et les cartes arrondies ou carrées."
        AppLanguage.ENGLISH -> "Tune compact or expanded chat, keyword highlighting, app colours, and rounded or squared cards."
    }

    fun chatDisplayLabel(): String = when (language) {
        AppLanguage.CHINESE -> "聊天显示"
        AppLanguage.SPANISH -> "Vista del chat"
        AppLanguage.GERMAN -> "Chat-Anzeige"
        AppLanguage.PORTUGUESE -> "Exibição do chat"
        AppLanguage.FRENCH -> "Affichage du chat"
        AppLanguage.ENGLISH -> "Chat display"
    }

    fun compactModeLabel(): String = when (language) {
        AppLanguage.CHINESE -> "紧凑"
        AppLanguage.SPANISH -> "Compacto"
        AppLanguage.GERMAN -> "Kompakt"
        AppLanguage.PORTUGUESE -> "Compacto"
        AppLanguage.FRENCH -> "Compact"
        AppLanguage.ENGLISH -> "Compact"
    }

    fun expandedModeLabel(): String = when (language) {
        AppLanguage.CHINESE -> "展开"
        AppLanguage.SPANISH -> "Expandido"
        AppLanguage.GERMAN -> "Erweitert"
        AppLanguage.PORTUGUESE -> "Expandido"
        AppLanguage.FRENCH -> "Étendu"
        AppLanguage.ENGLISH -> "Expanded"
    }

    fun keywordHighlightingTitle(): String = when (language) {
        AppLanguage.CHINESE -> "关键词与技能高亮"
        AppLanguage.SPANISH -> "Resaltado de palabras clave y habilidades"
        AppLanguage.GERMAN -> "Keyword- und Skill-Hervorhebung"
        AppLanguage.PORTUGUESE -> "Destaque de palavras-chave e habilidades"
        AppLanguage.FRENCH -> "Mise en évidence des mots-clés et compétences"
        AppLanguage.ENGLISH -> "Keyword and skill highlighting"
    }

    fun keywordHighlightingDescription(): String = when (language) {
        AppLanguage.CHINESE -> "为命令、工具、技能、附件和代理操作显示轻量标签。"
        AppLanguage.SPANISH -> "Píldoras sutiles para comandos, herramientas, habilidades, adjuntos y acciones del agente."
        AppLanguage.GERMAN -> "Dezente Markierungen für Befehle, Tools, Skills, Anhänge und Agentenaktionen."
        AppLanguage.PORTUGUESE -> "Marcadores sutis para comandos, ferramentas, habilidades, anexos e ações do agente."
        AppLanguage.FRENCH -> "Pastilles discrètes pour commandes, outils, compétences, pièces jointes et actions d’agent."
        AppLanguage.ENGLISH -> "Subtle pills for commands, tools, skills, attachments, and agent actions."
    }

    fun colourPresetsTitle(): String = when (language) {
        AppLanguage.CHINESE -> "配色预设"
        AppLanguage.SPANISH -> "Preajustes de color"
        AppLanguage.GERMAN -> "Farbvorlagen"
        AppLanguage.PORTUGUESE -> "Predefinições de cor"
        AppLanguage.FRENCH -> "Préréglages de couleur"
        AppLanguage.ENGLISH -> "Colour presets"
    }

    fun accentHexLabel(): String = when (language) {
        AppLanguage.CHINESE -> "强调色 / 用户气泡十六进制"
        AppLanguage.SPANISH -> "Hex de acento / burbuja de usuario"
        AppLanguage.GERMAN -> "Akzent / Nutzerblase Hex"
        AppLanguage.PORTUGUESE -> "Hex de destaque / bolha do usuário"
        AppLanguage.FRENCH -> "Hex accent / bulle utilisateur"
        AppLanguage.ENGLISH -> "Accent / user bubble hex"
    }

    fun secondaryAccentHexLabel(): String = when (language) {
        AppLanguage.CHINESE -> "第二强调色十六进制"
        AppLanguage.SPANISH -> "Hex de acento secundario"
        AppLanguage.GERMAN -> "Sekundärer Akzent Hex"
        AppLanguage.PORTUGUESE -> "Hex de destaque secundário"
        AppLanguage.FRENCH -> "Hex accent secondaire"
        AppLanguage.ENGLISH -> "Secondary accent hex"
    }

    fun backgroundHexLabel(): String = when (language) {
        AppLanguage.CHINESE -> "背景色十六进制"
        AppLanguage.SPANISH -> "Hex de fondo"
        AppLanguage.GERMAN -> "Hintergrund Hex"
        AppLanguage.PORTUGUESE -> "Hex do fundo"
        AppLanguage.FRENCH -> "Hex arrière-plan"
        AppLanguage.ENGLISH -> "Background hex"
    }

    fun composerSurfaceHexLabel(): String = when (language) {
        AppLanguage.CHINESE -> "输入框 / 卡片表面十六进制"
        AppLanguage.SPANISH -> "Hex de compositor / tarjeta"
        AppLanguage.GERMAN -> "Composer-/Kartenfläche Hex"
        AppLanguage.PORTUGUESE -> "Hex do compositor / cartão"
        AppLanguage.FRENCH -> "Hex surface compositeur / carte"
        AppLanguage.ENGLISH -> "Composer/card surface hex"
    }

    fun assistantPanelHexLabel(): String = when (language) {
        AppLanguage.CHINESE -> "助手 / 卡片面板十六进制"
        AppLanguage.SPANISH -> "Hex de panel asistente / tarjeta"
        AppLanguage.GERMAN -> "Assistent-/Kartenpanel Hex"
        AppLanguage.PORTUGUESE -> "Hex do painel assistente / cartão"
        AppLanguage.FRENCH -> "Hex panneau assistant / carte"
        AppLanguage.ENGLISH -> "Assistant/card panel hex"
    }

    fun cardsAndBoxesTitle(): String = when (language) {
        AppLanguage.CHINESE -> "卡片与输入框"
        AppLanguage.SPANISH -> "Tarjetas y cajas"
        AppLanguage.GERMAN -> "Karten und Felder"
        AppLanguage.PORTUGUESE -> "Cartões e caixas"
        AppLanguage.FRENCH -> "Cartes et boîtes"
        AppLanguage.ENGLISH -> "Cards and boxes"
    }

    fun cardShapeLabel(shape: String): String = when (shape.trim().lowercase()) {
        "square" -> when (language) {
            AppLanguage.CHINESE -> "方角"
            AppLanguage.SPANISH -> "Cuadrado"
            AppLanguage.GERMAN -> "Eckig"
            AppLanguage.PORTUGUESE -> "Quadrado"
            AppLanguage.FRENCH -> "Carré"
            AppLanguage.ENGLISH -> "Square"
        }
        "soft" -> when (language) {
            AppLanguage.CHINESE -> "柔和"
            AppLanguage.SPANISH -> "Suave"
            AppLanguage.GERMAN -> "Weich"
            AppLanguage.PORTUGUESE -> "Suave"
            AppLanguage.FRENCH -> "Doux"
            AppLanguage.ENGLISH -> "Soft"
        }
        else -> when (language) {
            AppLanguage.CHINESE -> "圆角"
            AppLanguage.SPANISH -> "Redondeado"
            AppLanguage.GERMAN -> "Rund"
            AppLanguage.PORTUGUESE -> "Arredondado"
            AppLanguage.FRENCH -> "Arrondi"
            AppLanguage.ENGLISH -> "Rounded"
        }
    }

    fun saveAppearanceLabel(): String = when (language) {
        AppLanguage.CHINESE -> "保存外观"
        AppLanguage.SPANISH -> "Guardar apariencia"
        AppLanguage.GERMAN -> "Erscheinungsbild speichern"
        AppLanguage.PORTUGUESE -> "Salvar aparência"
        AppLanguage.FRENCH -> "Enregistrer l’apparence"
        AppLanguage.ENGLISH -> "Save appearance"
    }

    fun offlineAirplaneModeTitle(): String = when (language) {
        AppLanguage.CHINESE -> "离线飞行模式"
        AppLanguage.SPANISH -> "Modo avión sin conexión"
        AppLanguage.GERMAN -> "Offline-Flugmodus"
        AppLanguage.PORTUGUESE -> "Modo avião offline"
        AppLanguage.FRENCH -> "Mode avion hors ligne"
        AppLanguage.ENGLISH -> "Offline airplane mode"
    }

    fun offlineAirplaneModeDescription(): String = when (language) {
        AppLanguage.CHINESE -> "阻止 Hermes 联网功能，同时保留本地文件、本机模型运行时和设备端自动化。"
        AppLanguage.SPANISH -> "Bloquea las funciones de internet de Hermes y mantiene archivos locales, runtimes localhost y automatización en el dispositivo."
        AppLanguage.GERMAN -> "Blockiert Hermes-Internetfunktionen, während lokale Dateien, localhost-Modellruntimes und Geräteautomation verfügbar bleiben."
        AppLanguage.PORTUGUESE -> "Bloqueia recursos de internet do Hermes mantendo arquivos locais, runtimes localhost e automação no dispositivo."
        AppLanguage.FRENCH -> "Bloque les fonctions Internet de Hermes tout en gardant fichiers locaux, runtimes localhost et automatisation sur l’appareil."
        AppLanguage.ENGLISH -> "Blocks Hermes internet features while keeping local files, localhost model runtimes, and on-device automation available."
    }

    fun offlineAirplaneToggleLabel(enabled: Boolean): String = if (enabled) {
        when (language) {
            AppLanguage.CHINESE -> "恢复应用联网"
            AppLanguage.SPANISH -> "Reactivar internet de la app"
            AppLanguage.GERMAN -> "App-Internet wieder aktivieren"
            AppLanguage.PORTUGUESE -> "Reativar internet do app"
            AppLanguage.FRENCH -> "Rétablir Internet pour l’app"
            AppLanguage.ENGLISH -> "Turn app internet back on"
        }
    } else {
        when (language) {
            AppLanguage.CHINESE -> "断开应用联网"
            AppLanguage.SPANISH -> "Cortar internet de la app"
            AppLanguage.GERMAN -> "App-Internet trennen"
            AppLanguage.PORTUGUESE -> "Cortar internet do app"
            AppLanguage.FRENCH -> "Couper Internet pour l’app"
            AppLanguage.ENGLISH -> "Cut app internet"
        }
    }

    fun offlineAirplaneStatus(enabled: Boolean): String = if (enabled) {
        when (language) {
            AppLanguage.CHINESE -> "离线飞行模式已开启。Hermes 会阻止门户、提供商设置、模型下载和 HTTP 自动化；本地后端与 localhost 仍可用。"
            AppLanguage.SPANISH -> "El modo avión offline está activado. Hermes bloqueará portal, configuración de proveedores, descargas de modelos y automatizaciones HTTP; los backends locales y localhost siguen disponibles."
            AppLanguage.GERMAN -> "Offline-Flugmodus ist aktiv. Hermes blockiert Portal, Anbieter-Setup, Modell-Downloads und HTTP-Automationen; lokale Backends und localhost bleiben verfügbar."
            AppLanguage.PORTUGUESE -> "O modo avião offline está ativado. O Hermes bloqueará portal, configuração de provedores, downloads de modelos e automações HTTP; backends locais e localhost seguem disponíveis."
            AppLanguage.FRENCH -> "Le mode avion hors ligne est activé. Hermes bloque le portail, la configuration fournisseur, les téléchargements de modèles et les automatisations HTTP ; les backends locaux et localhost restent disponibles."
            AppLanguage.ENGLISH -> "Offline airplane mode is on. Hermes will block portal, provider setup, model downloads, and HTTP automations while local backends and localhost stay available."
        }
    } else {
        when (language) {
            AppLanguage.CHINESE -> "离线飞行模式已关闭。Hermes 联网功能已恢复。"
            AppLanguage.SPANISH -> "El modo avión offline está desactivado. Las funciones de internet de Hermes vuelven a estar disponibles."
            AppLanguage.GERMAN -> "Offline-Flugmodus ist aus. Hermes-Internetfunktionen sind wieder verfügbar."
            AppLanguage.PORTUGUESE -> "O modo avião offline está desativado. Os recursos de internet do Hermes estão disponíveis novamente."
            AppLanguage.FRENCH -> "Le mode avion hors ligne est désactivé. Les fonctions Internet de Hermes sont de nouveau disponibles."
            AppLanguage.ENGLISH -> "Offline airplane mode is off. Hermes internet features are available again."
        }
    }

    fun agentPersonaLimited(limit: Int): String = when (language) {
        AppLanguage.CHINESE -> "代理人格最多 $limit 个字符。"
        AppLanguage.SPANISH -> "La personalidad del agente está limitada a $limit caracteres."
        AppLanguage.GERMAN -> "Die Agenten-Persona ist auf $limit Zeichen begrenzt."
        AppLanguage.PORTUGUESE -> "A persona do agente é limitada a $limit caracteres."
        AppLanguage.FRENCH -> "La personnalité de l’agent est limitée à $limit caractères."
        AppLanguage.ENGLISH -> "Agent persona is limited to $limit characters."
    }

    fun agentPersonaSaved(): String = when (language) {
        AppLanguage.CHINESE -> "代理人格已保存。新聊天会包含这个自定义系统提示词。"
        AppLanguage.SPANISH -> "Personalidad del agente guardada. Los chats nuevos incluirán este prompt de sistema personalizado."
        AppLanguage.GERMAN -> "Agenten-Persona gespeichert. Neue Chats enthalten diesen eigenen Systemprompt."
        AppLanguage.PORTUGUESE -> "Persona do agente salva. Novos chats incluirão este prompt de sistema personalizado."
        AppLanguage.FRENCH -> "Personnalité de l’agent enregistrée. Les nouveaux chats incluront cette invite système personnalisée."
        AppLanguage.ENGLISH -> "Agent persona saved. New chats will include this custom system prompt."
    }

    fun agentPersonaCleared(): String = when (language) {
        AppLanguage.CHINESE -> "代理人格已清除。"
        AppLanguage.SPANISH -> "Personalidad del agente borrada."
        AppLanguage.GERMAN -> "Agenten-Persona gelöscht."
        AppLanguage.PORTUGUESE -> "Persona do agente limpa."
        AppLanguage.FRENCH -> "Personnalité de l’agent effacée."
        AppLanguage.ENGLISH -> "Agent persona cleared."
    }

    fun agentPersonaTitle(): String = when (language) {
        AppLanguage.CHINESE -> "代理人格"
        AppLanguage.SPANISH -> "Personalidad del agente"
        AppLanguage.GERMAN -> "Agenten-Persona"
        AppLanguage.PORTUGUESE -> "Persona do agente"
        AppLanguage.FRENCH -> "Personnalité de l’agent"
        AppLanguage.ENGLISH -> "Agent persona"
    }

    fun customSystemPromptLabel(): String = when (language) {
        AppLanguage.CHINESE -> "自定义系统提示词"
        AppLanguage.SPANISH -> "Prompt de sistema personalizado"
        AppLanguage.GERMAN -> "Eigener Systemprompt"
        AppLanguage.PORTUGUESE -> "Prompt de sistema personalizado"
        AppLanguage.FRENCH -> "Invite système personnalisée"
        AppLanguage.ENGLISH -> "Custom system prompt"
    }

    fun customSystemPromptPlaceholder(): String = when (language) {
        AppLanguage.CHINESE -> "示例：保持简洁，外部发送前先询问，优先使用本地工具。"
        AppLanguage.SPANISH -> "Ejemplo: sé conciso, pregunta antes de envíos externos y prefiere herramientas locales."
        AppLanguage.GERMAN -> "Beispiel: knapp bleiben, vor externem Senden fragen, lokale Tools bevorzugen."
        AppLanguage.PORTUGUESE -> "Exemplo: seja conciso, pergunte antes de envios externos e prefira ferramentas locais."
        AppLanguage.FRENCH -> "Exemple : rester concis, demander avant les envois externes, préférer les outils locaux."
        AppLanguage.ENGLISH -> "Example: stay concise, ask before external sends, prefer local tools first."
    }

    fun characterCount(current: Int, limit: Int): String = when (language) {
        AppLanguage.CHINESE -> "$current/$limit 个字符"
        AppLanguage.SPANISH -> "$current/$limit caracteres"
        AppLanguage.GERMAN -> "$current/$limit Zeichen"
        AppLanguage.PORTUGUESE -> "$current/$limit caracteres"
        AppLanguage.FRENCH -> "$current/$limit caractères"
        AppLanguage.ENGLISH -> "$current/$limit characters"
    }

    fun savePersonaLabel(): String = when (language) {
        AppLanguage.CHINESE -> "保存人格"
        AppLanguage.SPANISH -> "Guardar personalidad"
        AppLanguage.GERMAN -> "Persona speichern"
        AppLanguage.PORTUGUESE -> "Salvar persona"
        AppLanguage.FRENCH -> "Enregistrer la personnalité"
        AppLanguage.ENGLISH -> "Save persona"
    }

    fun clearLabel(): String = when (language) {
        AppLanguage.CHINESE -> "清除"
        AppLanguage.SPANISH -> "Borrar"
        AppLanguage.GERMAN -> "Leeren"
        AppLanguage.PORTUGUESE -> "Limpar"
        AppLanguage.FRENCH -> "Effacer"
        AppLanguage.ENGLISH -> "Clear"
    }

    fun chatDisplayModeSet(mode: String): String {
        val label = if (mode.trim().equals("expanded", ignoreCase = true)) expandedModeLabel() else compactModeLabel()
        return when (language) {
            AppLanguage.CHINESE -> "聊天显示模式已设为 $label。"
            AppLanguage.SPANISH -> "Vista del chat configurada en $label."
            AppLanguage.GERMAN -> "Chat-Anzeige auf $label gesetzt."
            AppLanguage.PORTUGUESE -> "Exibição do chat definida como $label."
            AppLanguage.FRENCH -> "Affichage du chat défini sur $label."
            AppLanguage.ENGLISH -> "Chat display mode set to $label."
        }
    }

    fun keywordHighlightingStatus(enabled: Boolean): String = if (enabled) {
        when (language) {
            AppLanguage.CHINESE -> "关键词高亮已开启。"
            AppLanguage.SPANISH -> "Resaltado de palabras clave activado."
            AppLanguage.GERMAN -> "Keyword-Hervorhebung ist aktiv."
            AppLanguage.PORTUGUESE -> "Destaque de palavras-chave ativado."
            AppLanguage.FRENCH -> "Mise en évidence activée."
            AppLanguage.ENGLISH -> "Keyword highlighting is on."
        }
    } else {
        when (language) {
            AppLanguage.CHINESE -> "关键词高亮已关闭。"
            AppLanguage.SPANISH -> "Resaltado de palabras clave desactivado."
            AppLanguage.GERMAN -> "Keyword-Hervorhebung ist aus."
            AppLanguage.PORTUGUESE -> "Destaque de palavras-chave desativado."
            AppLanguage.FRENCH -> "Mise en évidence désactivée."
            AppLanguage.ENGLISH -> "Keyword highlighting is off."
        }
    }

    fun cardShapeSet(shape: String): String = when (language) {
        AppLanguage.CHINESE -> "卡片形状已设为 ${cardShapeLabel(shape)}。"
        AppLanguage.SPANISH -> "Forma de tarjeta configurada en ${cardShapeLabel(shape)}."
        AppLanguage.GERMAN -> "Kartenform auf ${cardShapeLabel(shape)} gesetzt."
        AppLanguage.PORTUGUESE -> "Formato do cartão definido como ${cardShapeLabel(shape)}."
        AppLanguage.FRENCH -> "Forme des cartes définie sur ${cardShapeLabel(shape)}."
        AppLanguage.ENGLISH -> "Card shape set to ${cardShapeLabel(shape)}."
    }

    fun appearancePresetLabel(presetId: String, fallbackLabel: String): String = when (presetId) {
        "hermes" -> when (language) {
            AppLanguage.CHINESE -> "Hermes 翡翠绿"
            AppLanguage.SPANISH -> "Esmeralda Hermes"
            AppLanguage.GERMAN -> "Hermes-Smaragd"
            AppLanguage.PORTUGUESE -> "Esmeralda Hermes"
            AppLanguage.FRENCH -> "Émeraude Hermes"
            AppLanguage.ENGLISH -> fallbackLabel
        }
        "legacy" -> when (language) {
            AppLanguage.CHINESE -> "经典紫色"
            AppLanguage.SPANISH -> "Morado clásico"
            AppLanguage.GERMAN -> "Klassisches Violett"
            AppLanguage.PORTUGUESE -> "Roxo clássico"
            AppLanguage.FRENCH -> "Violet classique"
            AppLanguage.ENGLISH -> fallbackLabel
        }
        "gold" -> when (language) {
            AppLanguage.CHINESE -> "金色夜幕"
            AppLanguage.SPANISH -> "Oro nocturno"
            AppLanguage.GERMAN -> "Gold noir"
            AppLanguage.PORTUGUESE -> "Ouro noir"
            AppLanguage.FRENCH -> "Or noir"
            AppLanguage.ENGLISH -> fallbackLabel
        }
        "graphite" -> when (language) {
            AppLanguage.CHINESE -> "石墨"
            AppLanguage.SPANISH -> "Grafito"
            AppLanguage.GERMAN -> "Graphit"
            AppLanguage.PORTUGUESE -> "Grafite"
            AppLanguage.FRENCH -> "Graphite"
            AppLanguage.ENGLISH -> fallbackLabel
        }
        "contrast" -> when (language) {
            AppLanguage.CHINESE -> "高对比度"
            AppLanguage.SPANISH -> "Alto contraste"
            AppLanguage.GERMAN -> "Hoher Kontrast"
            AppLanguage.PORTUGUESE -> "Alto contraste"
            AppLanguage.FRENCH -> "Contraste élevé"
            AppLanguage.ENGLISH -> fallbackLabel
        }
        else -> fallbackLabel
    }

    fun themePresetLoaded(presetId: String, fallbackLabel: String): String {
        val label = appearancePresetLabel(presetId, fallbackLabel)
        return when (language) {
            AppLanguage.CHINESE -> "已加载 $label 配色。点保存外观以持久保存。"
            AppLanguage.SPANISH -> "Colores de $label cargados. Guarda la apariencia para conservarlos."
            AppLanguage.GERMAN -> "$label-Farben geladen. Speichere das Erscheinungsbild, um sie zu behalten."
            AppLanguage.PORTUGUESE -> "Cores $label carregadas. Salve a aparência para persistir."
            AppLanguage.FRENCH -> "Couleurs $label chargées. Enregistrez l’apparence pour les conserver."
            AppLanguage.ENGLISH -> "Loaded $label colours. Save appearance to persist them."
        }
    }

    fun appearanceSaved(): String = when (language) {
        AppLanguage.CHINESE -> "外观已保存。"
        AppLanguage.SPANISH -> "Apariencia guardada."
        AppLanguage.GERMAN -> "Erscheinungsbild gespeichert."
        AppLanguage.PORTUGUESE -> "Aparência salva."
        AppLanguage.FRENCH -> "Apparence enregistrée."
        AppLanguage.ENGLISH -> "Appearance saved."
    }

    fun settingsSaveStarted(): String = when (language) {
        AppLanguage.CHINESE -> "正在保存设置并重启 Hermes 运行时…"
        AppLanguage.SPANISH -> "Guardando ajustes y reiniciando el runtime de Hermes…"
        AppLanguage.GERMAN -> "Einstellungen werden gespeichert und Hermes-Runtime wird neu gestartet…"
        AppLanguage.PORTUGUESE -> "Salvando configurações e reiniciando o runtime do Hermes…"
        AppLanguage.FRENCH -> "Enregistrement des réglages et redémarrage du runtime Hermes…"
        AppLanguage.ENGLISH -> "Saving settings and restarting Hermes runtime..."
    }

    fun settingsSavedBackendRestarted(): String = when (language) {
        AppLanguage.CHINESE -> "设置已保存，后端已重启。"
        AppLanguage.SPANISH -> "Ajustes guardados y backend reiniciado."
        AppLanguage.GERMAN -> "Einstellungen gespeichert und Backend neu gestartet."
        AppLanguage.PORTUGUESE -> "Configurações salvas e backend reiniciado."
        AppLanguage.FRENCH -> "Réglages enregistrés et backend redémarré."
        AppLanguage.ENGLISH -> "Settings saved and backend restarted"
    }

    fun settingsSavedImportedCredential(sourceLabel: String): String = when (language) {
        AppLanguage.CHINESE -> "设置已保存，已将 $sourceLabel 导入安全存储并重启后端。"
        AppLanguage.SPANISH -> "Ajustes guardados, $sourceLabel importado al almacenamiento seguro y backend reiniciado."
        AppLanguage.GERMAN -> "Einstellungen gespeichert, $sourceLabel in sicheren Speicher importiert und Backend neu gestartet."
        AppLanguage.PORTUGUESE -> "Configurações salvas, $sourceLabel importado para o armazenamento seguro e backend reiniciado."
        AppLanguage.FRENCH -> "Réglages enregistrés, $sourceLabel importé dans le stockage sécurisé et backend redémarré."
        AppLanguage.ENGLISH -> "Settings saved, imported $sourceLabel into secure storage, and backend restarted"
    }

    fun settingsSavedDataSaver(): String = when (language) {
        AppLanguage.CHINESE -> "设置已保存。省流模式会让大型下载等待 Wi-Fi / 非计费网络。"
        AppLanguage.SPANISH -> "Ajustes guardados. El modo ahorro de datos mantiene descargas grandes en Wi-Fi o redes no medidas."
        AppLanguage.GERMAN -> "Einstellungen gespeichert. Datensparmodus hält große Downloads auf WLAN oder ungetakteten Netzen."
        AppLanguage.PORTUGUESE -> "Configurações salvas. O modo economia de dados mantém downloads grandes no Wi-Fi ou redes não tarifadas."
        AppLanguage.FRENCH -> "Réglages enregistrés. Le mode économie de données garde les gros téléchargements sur Wi-Fi ou réseau non limité."
        AppLanguage.ENGLISH -> "Settings saved. Data saver mode now keeps heavy downloads on Wi-Fi / unmetered networks."
    }

    fun settingsSavedPreservedCredential(): String = when (language) {
        AppLanguage.CHINESE -> "设置已保存，后端已重启。空白 API 密钥栏保留了已有 Hermes 凭据。"
        AppLanguage.SPANISH -> "Ajustes guardados y backend reiniciado. El campo de clave API vacío conservó las credenciales Hermes existentes."
        AppLanguage.GERMAN -> "Einstellungen gespeichert und Backend neu gestartet. Das leere API-Schlüsselfeld hat vorhandene Hermes-Zugangsdaten beibehalten."
        AppLanguage.PORTUGUESE -> "Configurações salvas e backend reiniciado. O campo de chave API vazio manteve as credenciais Hermes existentes."
        AppLanguage.FRENCH -> "Réglages enregistrés et backend redémarré. Le champ de clé API vide a conservé les identifiants Hermes existants."
        AppLanguage.ENGLISH -> "Settings saved and backend restarted. Blank API key field left existing Hermes credentials untouched."
    }

    fun settingsSaveFailed(errorName: String): String = when (language) {
        AppLanguage.CHINESE -> "设置保存失败（$errorName）。"
        AppLanguage.SPANISH -> "Error al guardar ajustes ($errorName)."
        AppLanguage.GERMAN -> "Speichern der Einstellungen fehlgeschlagen ($errorName)."
        AppLanguage.PORTUGUESE -> "Falha ao salvar configurações ($errorName)."
        AppLanguage.FRENCH -> "Échec de l’enregistrement des réglages ($errorName)."
        AppLanguage.ENGLISH -> "Settings save failed ($errorName)."
    }

    fun onDeviceBackendReady(): String = when (language) {
        AppLanguage.CHINESE -> "设备端后端已就绪，Hermes 运行时已重启"
        AppLanguage.SPANISH -> "Backend en el dispositivo listo y runtime de Hermes reiniciado"
        AppLanguage.GERMAN -> "On-Device-Backend bereit und Hermes-Runtime neu gestartet"
        AppLanguage.PORTUGUESE -> "Backend no dispositivo pronto e runtime do Hermes reiniciado"
        AppLanguage.FRENCH -> "Backend sur l’appareil prêt et runtime Hermes redémarré"
        AppLanguage.ENGLISH -> "On-device backend ready and Hermes runtime restarted"
    }

    fun offlineAirplaneKeptRemoteFallbackDisabled(statusMessage: String): String = when (language) {
        AppLanguage.CHINESE -> "$statusMessage。离线飞行模式保持远程回退关闭。"
        AppLanguage.SPANISH -> "$statusMessage. El modo avión offline mantuvo desactivado el respaldo remoto."
        AppLanguage.GERMAN -> "$statusMessage. Offline-Flugmodus hat den Remote-Fallback deaktiviert gehalten."
        AppLanguage.PORTUGUESE -> "$statusMessage. O modo avião offline manteve o fallback remoto desativado."
        AppLanguage.FRENCH -> "$statusMessage. Le mode avion hors ligne a gardé le secours distant désactivé."
        AppLanguage.ENGLISH -> "$statusMessage. Offline airplane mode kept remote fallback disabled."
    }

    fun stayedOnSavedRemoteProvider(statusMessage: String): String = when (language) {
        AppLanguage.CHINESE -> "$statusMessage。Hermes 保持使用已保存的远程提供商。"
        AppLanguage.SPANISH -> "$statusMessage. Hermes permaneció en tu proveedor remoto guardado."
        AppLanguage.GERMAN -> "$statusMessage. Hermes blieb beim gespeicherten Remote-Anbieter."
        AppLanguage.PORTUGUESE -> "$statusMessage. O Hermes permaneceu no provedor remoto salvo."
        AppLanguage.FRENCH -> "$statusMessage. Hermes est resté sur le fournisseur distant enregistré."
        AppLanguage.ENGLISH -> "$statusMessage. Hermes stayed on your saved remote provider."
    }

    fun compactPromptLabel(expanded: Boolean): String = if (expanded) {
        when (language) {
            AppLanguage.CHINESE -> "完整提示词"
            AppLanguage.SPANISH -> "Prompt completo"
            AppLanguage.GERMAN -> "Vollständiger Prompt"
            AppLanguage.PORTUGUESE -> "Prompt completo"
            AppLanguage.FRENCH -> "Invite complète"
            AppLanguage.ENGLISH -> "Your full prompt"
        }
    } else {
        when (language) {
            AppLanguage.CHINESE -> "你的提示词"
            AppLanguage.SPANISH -> "Tu prompt"
            AppLanguage.GERMAN -> "Dein Prompt"
            AppLanguage.PORTUGUESE -> "Seu prompt"
            AppLanguage.FRENCH -> "Votre invite"
            AppLanguage.ENGLISH -> "Your prompt"
        }
    }

    fun chatDisplayModeLabel(mode: String): String {
        return if (mode.trim().equals("expanded", ignoreCase = true)) {
            expandedModeLabel()
        } else {
            compactModeLabel()
        }
    }

    fun userRoleLabel(): String = when (language) {
        AppLanguage.CHINESE -> "你"
        AppLanguage.SPANISH -> "Tú"
        AppLanguage.GERMAN -> "Du"
        AppLanguage.PORTUGUESE -> "Você"
        AppLanguage.FRENCH -> "Vous"
        AppLanguage.ENGLISH -> "You"
    }

    fun hermesPreparingReply(): String = when (language) {
        AppLanguage.CHINESE -> "Hermes 正在准备回复"
        AppLanguage.SPANISH -> "Hermes está preparando una respuesta"
        AppLanguage.GERMAN -> "Hermes bereitet eine Antwort vor"
        AppLanguage.PORTUGUESE -> "Hermes está preparando uma resposta"
        AppLanguage.FRENCH -> "Hermes prépare une réponse"
        AppLanguage.ENGLISH -> "Hermes is preparing a reply"
    }

    fun attachmentCount(count: Int): String = when (language) {
        AppLanguage.CHINESE -> "$count 个附件"
        AppLanguage.SPANISH -> if (count == 1) "$count adjunto" else "$count adjuntos"
        AppLanguage.GERMAN -> if (count == 1) "$count Anhang" else "$count Anhänge"
        AppLanguage.PORTUGUESE -> if (count == 1) "$count anexo" else "$count anexos"
        AppLanguage.FRENCH -> if (count == 1) "$count pièce jointe" else "$count pièces jointes"
        AppLanguage.ENGLISH -> "$count attachment${if (count == 1) "" else "s"}"
    }

    fun genericAttachmentLabel(): String = when (language) {
        AppLanguage.CHINESE -> "附件"
        AppLanguage.SPANISH -> "adjunto"
        AppLanguage.GERMAN -> "Anhang"
        AppLanguage.PORTUGUESE -> "anexo"
        AppLanguage.FRENCH -> "pièce jointe"
        AppLanguage.ENGLISH -> "attachment"
    }

    fun attachmentOnlyPrompt(): String = when (language) {
        AppLanguage.CHINESE -> "仅附件提示词"
        AppLanguage.SPANISH -> "Prompt solo con adjunto"
        AppLanguage.GERMAN -> "Nur-Anhang-Prompt"
        AppLanguage.PORTUGUESE -> "Prompt apenas com anexo"
        AppLanguage.FRENCH -> "Invite avec pièce jointe seulement"
        AppLanguage.ENGLISH -> "Attachment-only prompt"
    }

    fun attachmentPreviewUnavailable(): String = when (language) {
        AppLanguage.CHINESE -> "此附件没有可用预览。"
        AppLanguage.SPANISH -> "La vista previa no está disponible para este adjunto."
        AppLanguage.GERMAN -> "Für diesen Anhang ist keine Vorschau verfügbar."
        AppLanguage.PORTUGUESE -> "A prévia não está disponível para este anexo."
        AppLanguage.FRENCH -> "L’aperçu n’est pas disponible pour cette pièce jointe."
        AppLanguage.ENGLISH -> "Preview is not available for this attachment."
    }

    fun activityToolContext(): String = when (language) {
        AppLanguage.CHINESE -> "活动：工具上下文"
        AppLanguage.SPANISH -> "Actividad: contexto de herramienta"
        AppLanguage.GERMAN -> "Aktivität: Tool-Kontext"
        AppLanguage.PORTUGUESE -> "Atividade: contexto de ferramenta"
        AppLanguage.FRENCH -> "Activité : contexte d’outil"
        AppLanguage.ENGLISH -> "Activity: tool context"
    }

    fun hideLabel(): String = when (language) {
        AppLanguage.CHINESE -> "隐藏"
        AppLanguage.SPANISH -> "Ocultar"
        AppLanguage.GERMAN -> "Ausblenden"
        AppLanguage.PORTUGUESE -> "Ocultar"
        AppLanguage.FRENCH -> "Masquer"
        AppLanguage.ENGLISH -> "Hide"
    }

    fun detailsLabel(): String = when (language) {
        AppLanguage.CHINESE -> "详情"
        AppLanguage.SPANISH -> "Detalles"
        AppLanguage.GERMAN -> "Details"
        AppLanguage.PORTUGUESE -> "Detalhes"
        AppLanguage.FRENCH -> "Détails"
        AppLanguage.ENGLISH -> "Details"
    }

    fun moreCards(count: Int): String = when (language) {
        AppLanguage.CHINESE -> "还有 $count 张卡片"
        AppLanguage.SPANISH -> "$count tarjetas más"
        AppLanguage.GERMAN -> "$count weitere Karten"
        AppLanguage.PORTUGUESE -> "mais $count cartões"
        AppLanguage.FRENCH -> "$count cartes de plus"
        AppLanguage.ENGLISH -> "+$count more cards"
    }

    fun conversationHistoryTitle(): String = when (language) {
        AppLanguage.CHINESE -> "会话历史"
        AppLanguage.SPANISH -> "Historial de conversaciones"
        AppLanguage.GERMAN -> "Gesprächsverlauf"
        AppLanguage.PORTUGUESE -> "Histórico de conversas"
        AppLanguage.FRENCH -> "Historique des conversations"
        AppLanguage.ENGLISH -> "Conversation history"
    }

    fun noConversationHistory(): String = when (language) {
        AppLanguage.CHINESE -> "还没有会话历史。开始新的 Hermes 聊天即可创建。"
        AppLanguage.SPANISH -> "Aún no hay historial. Inicia un nuevo chat de Hermes para crearlo."
        AppLanguage.GERMAN -> "Noch kein Gesprächsverlauf. Starte einen neuen Hermes-Chat, um einen anzulegen."
        AppLanguage.PORTUGUESE -> "Ainda não há histórico. Inicie um novo chat do Hermes para criar um."
        AppLanguage.FRENCH -> "Aucun historique pour l’instant. Lancez un nouveau chat Hermes pour en créer un."
        AppLanguage.ENGLISH -> "No conversation history yet. Start a new Hermes chat to create one."
    }

    fun messageCount(count: Int): String = when (language) {
        AppLanguage.CHINESE -> "$count 条消息"
        AppLanguage.SPANISH -> if (count == 1) "$count mensaje" else "$count mensajes"
        AppLanguage.GERMAN -> if (count == 1) "$count Nachricht" else "$count Nachrichten"
        AppLanguage.PORTUGUESE -> if (count == 1) "$count mensagem" else "$count mensagens"
        AppLanguage.FRENCH -> if (count == 1) "$count message" else "$count messages"
        AppLanguage.ENGLISH -> "$count message${if (count == 1) "" else "s"}"
    }

    fun voiceInputLabel(): String = when (language) {
        AppLanguage.CHINESE -> "语音输入"
        AppLanguage.SPANISH -> "Entrada de voz"
        AppLanguage.GERMAN -> "Spracheingabe"
        AppLanguage.PORTUGUESE -> "Entrada de voz"
        AppLanguage.FRENCH -> "Saisie vocale"
        AppLanguage.ENGLISH -> "Voice input"
    }

    fun voiceInputCanceled(): String = when (language) {
        AppLanguage.CHINESE -> "语音输入已取消"
        AppLanguage.SPANISH -> "Entrada de voz cancelada"
        AppLanguage.GERMAN -> "Spracheingabe abgebrochen"
        AppLanguage.PORTUGUESE -> "Entrada de voz cancelada"
        AppLanguage.FRENCH -> "Saisie vocale annulée"
        AppLanguage.ENGLISH -> "Voice input canceled"
    }

    fun noSpeechCaptured(): String = when (language) {
        AppLanguage.CHINESE -> "未捕获到语音"
        AppLanguage.SPANISH -> "No se capturó voz"
        AppLanguage.GERMAN -> "Keine Sprache erkannt"
        AppLanguage.PORTUGUESE -> "Nenhuma fala capturada"
        AppLanguage.FRENCH -> "Aucune parole capturée"
        AppLanguage.ENGLISH -> "No speech was captured"
    }

    fun voiceRecognitionUnavailable(): String = when (language) {
        AppLanguage.CHINESE -> "此设备没有可用的语音识别"
        AppLanguage.SPANISH -> "El reconocimiento de voz no está disponible en este dispositivo"
        AppLanguage.GERMAN -> "Spracherkennung ist auf diesem Gerät nicht verfügbar"
        AppLanguage.PORTUGUESE -> "O reconhecimento de voz não está disponível neste dispositivo"
        AppLanguage.FRENCH -> "La reconnaissance vocale n’est pas disponible sur cet appareil"
        AppLanguage.ENGLISH -> "Voice recognition is not available on this device"
    }

    fun microphonePermissionRequired(): String = when (language) {
        AppLanguage.CHINESE -> "语音输入需要麦克风权限"
        AppLanguage.SPANISH -> "Se requiere permiso de micrófono para la voz"
        AppLanguage.GERMAN -> "Für Spracheingabe ist Mikrofonzugriff erforderlich"
        AppLanguage.PORTUGUESE -> "A permissão do microfone é necessária para entrada de voz"
        AppLanguage.FRENCH -> "L’autorisation du micro est requise pour la saisie vocale"
        AppLanguage.ENGLISH -> "Microphone permission is required for voice input"
    }

    fun cameraCaptureCanceled(): String = when (language) {
        AppLanguage.CHINESE -> "相机拍摄已取消"
        AppLanguage.SPANISH -> "Captura de cámara cancelada"
        AppLanguage.GERMAN -> "Kameraaufnahme abgebrochen"
        AppLanguage.PORTUGUESE -> "Captura da câmera cancelada"
        AppLanguage.FRENCH -> "Capture caméra annulée"
        AppLanguage.ENGLISH -> "Camera capture canceled"
    }

    fun cameraAttachFailed(errorMessage: String): String = when (language) {
        AppLanguage.CHINESE -> "无法附加相机图片：$errorMessage"
        AppLanguage.SPANISH -> "No se pudo adjuntar la imagen de cámara: $errorMessage"
        AppLanguage.GERMAN -> "Kamerabild konnte nicht angehängt werden: $errorMessage"
        AppLanguage.PORTUGUESE -> "Não foi possível anexar a imagem da câmera: $errorMessage"
        AppLanguage.FRENCH -> "Impossible de joindre l’image caméra : $errorMessage"
        AppLanguage.ENGLISH -> "Unable to attach camera image: $errorMessage"
    }

    fun speechPlaybackNotReady(): String = when (language) {
        AppLanguage.CHINESE -> "语音播放尚未就绪"
        AppLanguage.SPANISH -> "La reproducción de voz aún no está lista"
        AppLanguage.GERMAN -> "Sprachausgabe ist noch nicht bereit"
        AppLanguage.PORTUGUESE -> "A reprodução de voz ainda não está pronta"
        AppLanguage.FRENCH -> "La lecture vocale n’est pas encore prête"
        AppLanguage.ENGLISH -> "Speech playback is not ready yet"
    }

    fun chatStatusText(text: String): String = when (text) {
        "Message copied" -> when (language) {
            AppLanguage.CHINESE -> "消息已复制"
            AppLanguage.SPANISH -> "Mensaje copiado"
            AppLanguage.GERMAN -> "Nachricht kopiert"
            AppLanguage.PORTUGUESE -> "Mensagem copiada"
            AppLanguage.FRENCH -> "Message copié"
            AppLanguage.ENGLISH -> text
        }
        "Image attached for multimodal Gemma requests" -> when (language) {
            AppLanguage.CHINESE -> "已为多模态 Gemma 请求附加图片"
            AppLanguage.SPANISH -> "Imagen adjunta para solicitudes multimodales de Gemma"
            AppLanguage.GERMAN -> "Bild für multimodale Gemma-Anfragen angehängt"
            AppLanguage.PORTUGUESE -> "Imagem anexada para solicitações multimodais do Gemma"
            AppLanguage.FRENCH -> "Image jointe pour les requêtes Gemma multimodales"
            AppLanguage.ENGLISH -> text
        }
        "Voice input captured" -> when (language) {
            AppLanguage.CHINESE -> "语音输入已捕获"
            AppLanguage.SPANISH -> "Entrada de voz capturada"
            AppLanguage.GERMAN -> "Spracheingabe erfasst"
            AppLanguage.PORTUGUESE -> "Entrada de voz capturada"
            AppLanguage.FRENCH -> "Saisie vocale capturée"
            AppLanguage.ENGLISH -> text
        }
        "Listening…" -> when (language) {
            AppLanguage.CHINESE -> "正在聆听…"
            AppLanguage.SPANISH -> "Escuchando…"
            AppLanguage.GERMAN -> "Hört zu…"
            AppLanguage.PORTUGUESE -> "Ouvindo…"
            AppLanguage.FRENCH -> "Écoute…"
            AppLanguage.ENGLISH -> text
        }
        "Started a new chat" -> when (language) {
            AppLanguage.CHINESE -> "已开始新聊天"
            AppLanguage.SPANISH -> "Nuevo chat iniciado"
            AppLanguage.GERMAN -> "Neuer Chat gestartet"
            AppLanguage.PORTUGUESE -> "Novo chat iniciado"
            AppLanguage.FRENCH -> "Nouveau chat lancé"
            AppLanguage.ENGLISH -> text
        }
        "Cleared the previous conversation" -> when (language) {
            AppLanguage.CHINESE -> "已清除上一个会话"
            AppLanguage.SPANISH -> "Conversación anterior borrada"
            AppLanguage.GERMAN -> "Vorherige Unterhaltung gelöscht"
            AppLanguage.PORTUGUESE -> "Conversa anterior limpa"
            AppLanguage.FRENCH -> "Conversation précédente effacée"
            AppLanguage.ENGLISH -> text
        }
        "Wait for Hermes to finish before editing a sent message." -> when (language) {
            AppLanguage.CHINESE -> "请等待 Hermes 完成后再编辑已发送的消息。"
            AppLanguage.SPANISH -> "Espera a que Hermes termine antes de editar un mensaje enviado."
            AppLanguage.GERMAN -> "Warte, bis Hermes fertig ist, bevor du eine gesendete Nachricht bearbeitest."
            AppLanguage.PORTUGUESE -> "Aguarde o Hermes terminar antes de editar uma mensagem enviada."
            AppLanguage.FRENCH -> "Attendez la fin de Hermes avant de modifier un message envoyé."
            AppLanguage.ENGLISH -> text
        }
        "Editing sent message; send to resubmit." -> when (language) {
            AppLanguage.CHINESE -> "正在编辑已发送消息；发送以重新提交。"
            AppLanguage.SPANISH -> "Editando mensaje enviado; envía para reenviar."
            AppLanguage.GERMAN -> "Gesendete Nachricht wird bearbeitet; senden zum erneuten Absenden."
            AppLanguage.PORTUGUESE -> "Editando mensagem enviada; envie para reenviar."
            AppLanguage.FRENCH -> "Modification du message envoyé ; envoyez pour resoumettre."
            AppLanguage.ENGLISH -> text
        }
        "Running native Android diagnostics…" -> when (language) {
            AppLanguage.CHINESE -> "正在运行原生 Android 诊断…"
            AppLanguage.SPANISH -> "Ejecutando diagnósticos nativos de Android…"
            AppLanguage.GERMAN -> "Native Android-Diagnose wird ausgeführt…"
            AppLanguage.PORTUGUESE -> "Executando diagnósticos nativos do Android…"
            AppLanguage.FRENCH -> "Exécution des diagnostics Android natifs…"
            AppLanguage.ENGLISH -> text
        }
        "Send or clear the current draft before running a signal quick action." -> when (language) {
            AppLanguage.CHINESE -> "运行信号快捷操作前，请先发送或清除当前草稿。"
            AppLanguage.SPANISH -> "Envía o borra el borrador actual antes de ejecutar una acción rápida de señal."
            AppLanguage.GERMAN -> "Sende oder lösche den aktuellen Entwurf, bevor du eine Signal-Schnellaktion ausführst."
            AppLanguage.PORTUGUESE -> "Envie ou limpe o rascunho atual antes de executar uma ação rápida de sinal."
            AppLanguage.FRENCH -> "Envoyez ou effacez le brouillon avant d’exécuter une action rapide de signal."
            AppLanguage.ENGLISH -> text
        }
        "Starting Hermes runtime…" -> when (language) {
            AppLanguage.CHINESE -> "正在启动 Hermes 运行时…"
            AppLanguage.SPANISH -> "Iniciando el runtime de Hermes…"
            AppLanguage.GERMAN -> "Hermes-Laufzeit wird gestartet…"
            AppLanguage.PORTUGUESE -> "Iniciando o runtime do Hermes…"
            AppLanguage.FRENCH -> "Démarrage du runtime Hermes…"
            AppLanguage.ENGLISH -> text
        }
        "Stopped by user" -> tr(
            "Stopped by user", "已由用户停止", "Detenido por el usuario", "Vom Benutzer gestoppt", "Parado pelo usuário", "Arrêté par l’utilisateur",
        )
        "Hermes is replying…" -> when (language) {
            AppLanguage.CHINESE -> "Hermes 正在回复…"
            AppLanguage.SPANISH -> "Hermes está respondiendo…"
            AppLanguage.GERMAN -> "Hermes antwortet…"
            AppLanguage.PORTUGUESE -> "Hermes está respondendo…"
            AppLanguage.FRENCH -> "Hermes répond…"
            AppLanguage.ENGLISH -> text
        }
        "Hermes is reading the image…" -> when (language) {
            AppLanguage.CHINESE -> "Hermes 正在读取图片…"
            AppLanguage.SPANISH -> "Hermes está leyendo la imagen…"
            AppLanguage.GERMAN -> "Hermes liest das Bild…"
            AppLanguage.PORTUGUESE -> "Hermes está lendo a imagem…"
            AppLanguage.FRENCH -> "Hermes lit l’image…"
            AppLanguage.ENGLISH -> text
        }
        else -> if (text.startsWith("Opened ")) {
            when (language) {
                AppLanguage.CHINESE -> "已打开 ${text.removePrefix("Opened ")}"
                AppLanguage.SPANISH -> "Abierto ${text.removePrefix("Opened ")}"
                AppLanguage.GERMAN -> "${text.removePrefix("Opened ")} geöffnet"
                AppLanguage.PORTUGUESE -> "${text.removePrefix("Opened ")} aberto"
                AppLanguage.FRENCH -> "${text.removePrefix("Opened ")} ouvert"
                AppLanguage.ENGLISH -> text
            }
        } else {
            text
        }
    }

    fun deviceStatusText(status: DeviceOperationStatus): String = when (status) {
        DeviceOperationStatus.LinuxSuiteProvisioning -> tr(
            "Preparing the Linux command suite…", "正在准备 Linux 命令套件…", "Preparando la suite de comandos Linux…",
            "Linux-Befehlssuite wird vorbereitet…", "Preparando a suíte de comandos Linux…", "Préparation de la suite de commandes Linux…",
        )
        DeviceOperationStatus.LinuxSuiteReady -> tr(
            "Linux command suite ready for on-device commands", "Linux 命令套件已就绪，可运行设备端命令",
            "La suite de comandos Linux está lista para comandos en el dispositivo",
            "Die Linux-Befehlssuite ist für On-Device-Befehle bereit", "A suíte de comandos Linux está pronta para comandos no dispositivo",
            "La suite de commandes Linux est prête pour les commandes sur l’appareil",
        )
        DeviceOperationStatus.LinuxSuiteInstalling -> tr(
            "Installing the Linux command suite…", "正在安装 Linux 命令套件…", "Instalando la suite de comandos Linux…",
            "Linux-Befehlssuite wird installiert…", "Instalando a suíte de comandos Linux…", "Installation de la suite de commandes Linux…",
        )
        is DeviceOperationStatus.LinuxSuiteInstalled -> tr(
            "Linux command suite ready (${status.architecture}, ${status.packageCount} packages)",
            "Linux 命令套件已就绪（${status.architecture}，${status.packageCount} 个软件包）",
            "Suite de comandos Linux lista (${status.architecture}, ${status.packageCount} paquetes)",
            "Linux-Befehlssuite bereit (${status.architecture}, ${status.packageCount} Pakete)",
            "Suíte de comandos Linux pronta (${status.architecture}, ${status.packageCount} pacotes)",
            "Suite de commandes Linux prête (${status.architecture}, ${status.packageCount} paquets)",
        )
        is DeviceOperationStatus.LinuxSuiteFailed -> when (status.stage) {
            LinuxSuiteFailureStage.Provisioning -> tr(
                "Linux command suite preparation failed", "Linux 命令套件准备失败", "Falló la preparación de la suite de comandos Linux",
                "Vorbereitung der Linux-Befehlssuite fehlgeschlagen", "Falha ao preparar a suíte de comandos Linux",
                "Échec de la préparation de la suite de commandes Linux",
            )
            LinuxSuiteFailureStage.Installation -> tr(
                "Linux command suite installation failed", "Linux 命令套件安装失败", "Falló la instalación de la suite de comandos Linux",
                "Installation der Linux-Befehlssuite fehlgeschlagen", "Falha ao instalar a suíte de comandos Linux",
                "Échec de l’installation de la suite de commandes Linux",
            )
        }
        is DeviceOperationStatus.SandboxRunning -> {
            val action = deviceSandboxActionLabel(status.action)
            tr(
                "Running Linux sandbox action: $action (${status.distroId})…", "正在运行 Linux 沙箱操作：$action（${status.distroId}）…",
                "Ejecutando acción del entorno Linux: $action (${status.distroId})…",
                "Linux-Sandbox-Aktion wird ausgeführt: $action (${status.distroId})…",
                "Executando ação do sandbox Linux: $action (${status.distroId})…",
                "Exécution de l’action du bac à sable Linux : $action (${status.distroId})…",
            )
        }
        is DeviceOperationStatus.SandboxCompleted -> {
            val action = deviceSandboxActionLabel(status.action)
            val target = status.sandboxName.ifBlank { status.distroId }
            tr(
                "Linux sandbox $action completed for $target (exit code ${status.exitCode})",
                "Linux 沙箱已为 $target 完成$action（退出代码 ${status.exitCode}）",
                "La acción $action del entorno Linux terminó para $target (código de salida ${status.exitCode})",
                "Linux-Sandbox-Aktion $action für $target abgeschlossen (Exit-Code ${status.exitCode})",
                "A ação $action do sandbox Linux foi concluída para $target (código de saída ${status.exitCode})",
                "L’action $action du bac à sable Linux est terminée pour $target (code de sortie ${status.exitCode})",
            )
        }
        is DeviceOperationStatus.SandboxFailed -> {
            val action = deviceSandboxActionLabel(status.action)
            val exit = status.exitCode?.let { deviceExitCodeSuffix(it) }.orEmpty()
            tr(
                "Linux sandbox $action failed for ${status.distroId}$exit", "Linux 沙箱为 ${status.distroId} 执行${action}失败$exit",
                "Falló la acción $action del entorno Linux para ${status.distroId}$exit",
                "Linux-Sandbox-Aktion $action für ${status.distroId} fehlgeschlagen$exit",
                "Falha na ação $action do sandbox Linux para ${status.distroId}$exit",
                "Échec de l’action $action du bac à sable Linux pour ${status.distroId}$exit",
            )
        }
        is DeviceOperationStatus.HostPackageRunning -> {
            val action = deviceHostPackageActionLabel(status.action)
            tr(
                "Running host package action: $action…", "正在运行主机软件包操作：$action…", "Ejecutando acción de paquetes del host: $action…",
                "Host-Paketaktion wird ausgeführt: $action…", "Executando ação de pacotes do host: $action…",
                "Exécution de l’action sur les paquets hôte : $action…",
            )
        }
        is DeviceOperationStatus.HostPackageCompleted -> {
            val action = deviceHostPackageActionLabel(status.action)
            val versions = listOfNotNull(
                status.prootVersion.takeIf { it.isNotBlank() }?.let { "proot $it" },
                status.prootDistroVersion.takeIf { it.isNotBlank() }?.let { "proot-distro $it" },
            ).joinToString(" · ").let { if (it.isBlank()) "" else " · $it" }
            tr(
                "Host package action completed: $action$versions", "主机软件包操作已完成：$action$versions",
                "Acción de paquetes del host completada: $action$versions", "Host-Paketaktion abgeschlossen: $action$versions",
                "Ação de pacotes do host concluída: $action$versions", "Action sur les paquets hôte terminée : $action$versions",
            )
        }
        is DeviceOperationStatus.HostPackageFailed -> {
            val action = deviceHostPackageActionLabel(status.action)
            tr(
                "Host package action failed: $action", "主机软件包操作失败：$action", "Falló la acción de paquetes del host: $action",
                "Host-Paketaktion fehlgeschlagen: $action", "Falha na ação de pacotes do host: $action",
                "Échec de l’action sur les paquets hôte : $action",
            )
        }
        is DeviceOperationStatus.DocumentImported -> tr(
            "Imported ${status.fileName} into the Hermes workspace", "已将 ${status.fileName} 导入 Hermes 工作区",
            "Se importó ${status.fileName} al espacio de trabajo de Hermes", "${status.fileName} wurde in den Hermes-Arbeitsbereich importiert",
            "${status.fileName} foi importado para o espaço de trabalho do Hermes", "${status.fileName} a été importé dans l’espace de travail Hermes",
        )
        is DeviceOperationStatus.ImportFailed -> tr(
            "Document import failed", "文档导入失败", "Falló la importación del documento", "Dokumentimport fehlgeschlagen",
            "Falha ao importar o documento", "Échec de l’importation du document",
        )
        is DeviceOperationStatus.SharedFolderSaved -> tr(
            "Saved shared folder access for ${status.label}", "已保存 ${status.label} 的共享文件夹访问权限",
            "Se guardó el acceso a la carpeta compartida ${status.label}", "Freigegebener Ordnerzugriff für ${status.label} gespeichert",
            "Acesso à pasta compartilhada ${status.label} salvo", "Accès au dossier partagé ${status.label} enregistré",
        )
        DeviceOperationStatus.SharedFolderCleared -> tr(
            "Cleared shared folder permission", "已清除共享文件夹权限", "Se borró el permiso de carpeta compartida",
            "Freigegebene Ordnerberechtigung gelöscht", "Permissão da pasta compartilhada removida",
            "Autorisation du dossier partagé supprimée",
        )
        is DeviceOperationStatus.WorkspaceFileExported -> tr(
            "Exported ${status.fileName}", "已导出 ${status.fileName}", "Se exportó ${status.fileName}",
            "${status.fileName} exportiert", "${status.fileName} exportado", "${status.fileName} exporté",
        )
        is DeviceOperationStatus.WorkspaceExportFailed -> tr(
            "Workspace export failed", "工作区导出失败", "Falló la exportación del espacio de trabajo", "Arbeitsbereichsexport fehlgeschlagen",
            "Falha ao exportar o espaço de trabalho", "Échec de l’exportation de l’espace de travail",
        )
        DeviceOperationStatus.DiagnosticsExported -> tr(
            "Exported diagnostics logs", "已导出诊断日志", "Se exportaron los registros de diagnóstico", "Diagnoseprotokolle exportiert",
            "Logs de diagnóstico exportados", "Journaux de diagnostic exportés",
        )
        is DeviceOperationStatus.DiagnosticsExportFailed -> tr(
            "Diagnostics log export failed", "诊断日志导出失败", "Falló la exportación de los registros de diagnóstico",
            "Export der Diagnoseprotokolle fehlgeschlagen", "Falha ao exportar os logs de diagnóstico",
            "Échec de l’exportation des journaux de diagnostic",
        )
        DeviceOperationStatus.DiagnosticsCleared -> tr(
            "Cleared last crash diagnostics", "已清除上次崩溃诊断", "Se borró el último diagnóstico de fallo",
            "Letzte Absturzdiagnose gelöscht", "Último diagnóstico de falha removido", "Dernier diagnostic de plantage supprimé",
        )
        is DeviceOperationStatus.AccessibilityActionCompleted -> {
            val action = deviceAccessibilityActionLabel(status.action)
            tr(
                "Accessibility action completed: $action", "无障碍操作已完成：$action", "Acción de accesibilidad completada: $action",
                "Bedienungshilfe-Aktion abgeschlossen: $action", "Ação de acessibilidade concluída: $action",
                "Action d’accessibilité terminée : $action",
            )
        }
        DeviceOperationStatus.AccessibilityEnableRequired -> tr(
            "Enable Hermes accessibility in Android settings first", "请先在 Android 设置中启用 Hermes 无障碍服务",
            "Activa primero la accesibilidad de Hermes en los ajustes de Android",
            "Aktiviere zuerst Hermes-Barrierefreiheit in den Android-Einstellungen",
            "Ative primeiro a acessibilidade do Hermes nas configurações do Android",
            "Activez d’abord l’accessibilité Hermes dans les paramètres Android",
        )
        DeviceOperationStatus.AccessibilityNotConnected -> tr(
            "Hermes accessibility is enabled but not connected yet", "Hermes 无障碍服务已启用，但尚未连接",
            "La accesibilidad de Hermes está activada, pero aún no está conectada",
            "Hermes-Barrierefreiheit ist aktiviert, aber noch nicht verbunden",
            "A acessibilidade do Hermes está ativada, mas ainda não conectada",
            "L’accessibilité Hermes est activée, mais pas encore connectée",
        )
        is DeviceOperationStatus.PermissionResult -> when (status.permission) {
            DevicePermission.Notifications -> tr(
                if (status.granted) "Notifications enabled for Hermes runtime alerts" else "Notification permission was denied",
                if (status.granted) "已为 Hermes 运行时提醒启用通知" else "通知权限被拒绝",
                if (status.granted) "Notificaciones activadas para las alertas del runtime de Hermes" else "Se denegó el permiso de notificaciones",
                if (status.granted) "Benachrichtigungen für Hermes-Laufzeitwarnungen aktiviert" else "Benachrichtigungsberechtigung wurde verweigert",
                if (status.granted) "Notificações ativadas para alertas do runtime Hermes" else "A permissão de notificação foi negada",
                if (status.granted) "Notifications activées pour les alertes du runtime Hermes" else "L’autorisation de notification a été refusée",
            )
            DevicePermission.Bluetooth -> tr(
                if (status.granted) "Bluetooth access granted" else "Bluetooth access was denied",
                if (status.granted) "已授予蓝牙访问权限" else "蓝牙访问被拒绝",
                if (status.granted) "Acceso Bluetooth concedido" else "Se denegó el acceso Bluetooth",
                if (status.granted) "Bluetooth-Zugriff gewährt" else "Bluetooth-Zugriff wurde verweigert",
                if (status.granted) "Acesso Bluetooth concedido" else "O acesso Bluetooth foi negado",
                if (status.granted) "Accès Bluetooth accordé" else "L’accès Bluetooth a été refusé",
            )
        }
        is DeviceOperationStatus.SystemControlResult -> deviceSystemControlHeadline(status.action, status.succeeded)
    }

    fun deviceStatusDiagnosticDetail(detail: String): String = tr(
        "Diagnostic details: $detail", "诊断详情：$detail", "Detalles de diagnóstico: $detail", "Diagnosedetails: $detail",
        "Detalhes do diagnóstico: $detail", "Détails du diagnostic : $detail",
    )

    private fun deviceSandboxActionLabel(action: String): String = when (action.trim().lowercase()) {
        "deploy" -> tr("deploy", "部署", "desplegar", "bereitstellen", "implantar", "déployer")
        "update" -> tr("update", "更新", "actualizar", "aktualisieren", "atualizar", "mettre à jour")
        "start" -> tr("start", "启动", "iniciar", "starten", "iniciar", "démarrer")
        "stop" -> tr("stop", "停止", "detener", "stoppen", "parar", "arrêter")
        "set_mirror" -> tr("change mirror", "更改镜像", "cambiar espejo", "Mirror wechseln", "alterar espelho", "changer de miroir")
        "uninstall", "remove" -> tr("uninstall", "卸载", "desinstalar", "deinstallieren", "desinstalar", "désinstaller")
        "status" -> tr("check status", "检查状态", "comprobar estado", "Status prüfen", "verificar status", "vérifier l’état")
        else -> tr("requested operation", "请求的操作", "operación solicitada", "angeforderte Aktion", "operação solicitada", "opération demandée")
    }

    private fun deviceHostPackageActionLabel(action: String): String = when (action.trim().lowercase()) {
        "update", "refresh", "update_index" -> tr("refresh index", "刷新索引", "actualizar índice", "Index aktualisieren", "atualizar índice", "actualiser l’index")
        "upgrade", "full-upgrade", "dist-upgrade" -> tr("upgrade suite", "升级套件", "actualizar suite", "Suite aktualisieren", "atualizar suíte", "mettre à niveau la suite")
        "install", "add" -> tr("install packages", "安装软件包", "instalar paquetes", "Pakete installieren", "instalar pacotes", "installer des paquets")
        "remove", "uninstall", "purge" -> tr("remove packages", "移除软件包", "eliminar paquetes", "Pakete entfernen", "remover pacotes", "supprimer des paquets")
        "set_mirror", "mirror" -> tr("change mirror", "更改镜像", "cambiar espejo", "Mirror wechseln", "alterar espelho", "changer de miroir")
        "status", "show" -> tr("check status", "检查状态", "comprobar estado", "Status prüfen", "verificar status", "vérifier l’état")
        "list", "list-installed" -> tr("list packages", "列出软件包", "listar paquetes", "Pakete auflisten", "listar pacotes", "lister les paquets")
        "search", "find" -> tr("search packages", "搜索软件包", "buscar paquetes", "Pakete suchen", "buscar pacotes", "rechercher des paquets")
        else -> tr("requested operation", "请求的操作", "operación solicitada", "angeforderte Aktion", "operação solicitada", "opération demandée")
    }

    private fun deviceAccessibilityActionLabel(action: HermesGlobalAction): String = when (action) {
        HermesGlobalAction.Home -> tr("Home", "主页", "Inicio", "Startbildschirm", "Início", "Accueil")
        HermesGlobalAction.Back -> tr("Back", "返回", "Atrás", "Zurück", "Voltar", "Retour")
        HermesGlobalAction.Recents -> tr("Recent apps", "最近使用的应用", "Aplicaciones recientes", "Letzte Apps", "Apps recentes", "Applications récentes")
        HermesGlobalAction.Notifications -> tr("Notifications", "通知", "Notificaciones", "Benachrichtigungen", "Notificações", "Notifications")
        HermesGlobalAction.QuickSettings -> tr("Quick settings", "快捷设置", "Ajustes rápidos", "Schnelleinstellungen", "Configurações rápidas", "Réglages rapides")
    }

    private fun deviceExitCodeSuffix(exitCode: Int): String = tr(
        " (exit code $exitCode)", "（退出代码 $exitCode）", " (código de salida $exitCode)", " (Exit-Code $exitCode)",
        " (código de saída $exitCode)", " (code de sortie $exitCode)",
    )

    private fun deviceSystemControlHeadline(action: String, succeeded: Boolean): String {
        val target = deviceSystemControlTarget(action)
        return when (action) {
            "start_background_runtime" -> if (succeeded) tr(
                "Hermes background runtime started", "Hermes 后台运行时已启动", "Runtime de Hermes en segundo plano iniciado",
                "Hermes-Hintergrundlaufzeit gestartet", "Runtime do Hermes em segundo plano iniciado", "Runtime Hermes en arrière-plan démarré",
            ) else tr(
                "Could not start the Hermes background runtime", "无法启动 Hermes 后台运行时", "No se pudo iniciar el runtime de Hermes en segundo plano",
                "Hermes-Hintergrundlaufzeit konnte nicht gestartet werden", "Não foi possível iniciar o runtime do Hermes em segundo plano",
                "Impossible de démarrer le runtime Hermes en arrière-plan",
            )
            "stop_background_runtime" -> if (succeeded) tr(
                "Hermes background runtime stopped", "Hermes 后台运行时已停止", "Runtime de Hermes en segundo plano detenido",
                "Hermes-Hintergrundlaufzeit gestoppt", "Runtime do Hermes em segundo plano parado", "Runtime Hermes en arrière-plan arrêté",
            ) else tr(
                "Could not stop the Hermes background runtime", "无法停止 Hermes 后台运行时", "No se pudo detener el runtime de Hermes en segundo plano",
                "Hermes-Hintergrundlaufzeit konnte nicht gestoppt werden", "Não foi possível parar o runtime do Hermes em segundo plano",
                "Impossible d’arrêter le runtime Hermes en arrière-plan",
            )
            "start_floating_button" -> if (succeeded) tr(
                "Hermes floating button started", "Hermes 浮动按钮已启动", "Botón flotante de Hermes iniciado",
                "Schwebende Hermes-Schaltfläche gestartet", "Botão flutuante do Hermes iniciado", "Bouton flottant Hermes démarré",
            ) else tr(
                "Could not start the Hermes floating button", "无法启动 Hermes 浮动按钮", "No se pudo iniciar el botón flotante de Hermes",
                "Schwebende Hermes-Schaltfläche konnte nicht gestartet werden", "Não foi possível iniciar o botão flutuante do Hermes",
                "Impossible de démarrer le bouton flottant Hermes",
            )
            "stop_floating_button" -> if (succeeded) tr(
                "Hermes floating button stopped", "Hermes 浮动按钮已停止", "Botón flotante de Hermes detenido",
                "Schwebende Hermes-Schaltfläche gestoppt", "Botão flutuante do Hermes parado", "Bouton flottant Hermes arrêté",
            ) else tr(
                "Could not stop the Hermes floating button", "无法停止 Hermes 浮动按钮", "No se pudo detener el botón flotante de Hermes",
                "Schwebende Hermes-Schaltfläche konnte nicht gestoppt werden", "Não foi possível parar o botão flutuante do Hermes",
                "Impossible d’arrêter le bouton flottant Hermes",
            )
            else -> if (succeeded) tr(
                "Opened $target", "已打开$target", "Se abrió $target", "$target geöffnet", "$target aberto", "$target ouvert",
            ) else tr(
                "Could not open $target", "无法打开$target", "No se pudo abrir $target", "$target konnte nicht geöffnet werden",
                "Não foi possível abrir $target", "Impossible d’ouvrir $target",
            )
        }
    }

    private fun deviceSystemControlTarget(action: String): String = when (action) {
        "open_wifi_panel" -> tr("Wi-Fi and internet controls", "Wi-Fi 和互联网控制", "los controles de Wi-Fi e internet", "WLAN- und Internetsteuerung", "os controles de Wi-Fi e internet", "les contrôles Wi-Fi et Internet")
        "open_notification_settings" -> tr("Hermes notification settings", "Hermes 通知设置", "los ajustes de notificaciones de Hermes", "Hermes-Benachrichtigungseinstellungen", "as configurações de notificação do Hermes", "les réglages de notification Hermes")
        "open_bluetooth_settings" -> tr("Bluetooth settings", "蓝牙设置", "los ajustes de Bluetooth", "Bluetooth-Einstellungen", "as configurações de Bluetooth", "les réglages Bluetooth")
        "open_connected_devices_settings" -> tr("connected-device settings", "已连接设备设置", "los ajustes de dispositivos conectados", "Einstellungen für verbundene Geräte", "as configurações de dispositivos conectados", "les réglages des appareils connectés")
        "open_mobile_network_settings" -> tr("mobile network settings", "移动网络设置", "los ajustes de red móvil", "Mobilfunkeinstellungen", "as configurações de rede móvel", "les réglages du réseau mobile")
        "open_data_usage_settings" -> tr("data usage settings", "数据使用设置", "los ajustes de uso de datos", "Datennutzungseinstellungen", "as configurações de uso de dados", "les réglages d’utilisation des données")
        "open_hotspot_settings" -> tr("hotspot and tethering settings", "热点和网络共享设置", "los ajustes de zona Wi-Fi y conexión compartida", "Hotspot- und Tethering-Einstellungen", "as configurações de hotspot e tethering", "les réglages de point d’accès et de partage de connexion")
        "open_airplane_mode_settings" -> tr("airplane mode settings", "飞行模式设置", "los ajustes del modo avión", "Flugmoduseinstellungen", "as configurações do modo avião", "les réglages du mode avion")
        "open_nfc_settings" -> tr("NFC settings", "NFC 设置", "los ajustes de NFC", "NFC-Einstellungen", "as configurações de NFC", "les réglages NFC")
        "open_overlay_settings" -> tr("draw-over-other-apps settings", "在其他应用上层显示设置", "los ajustes para mostrar sobre otras aplicaciones", "Einstellungen zum Einblenden über anderen Apps", "as configurações de sobreposição em outros apps", "les réglages d’affichage au-dessus des autres applications")
        "open_accessibility_settings" -> tr("accessibility settings", "无障碍设置", "los ajustes de accesibilidad", "Bedienungshilfe-Einstellungen", "as configurações de acessibilidade", "les réglages d’accessibilité")
        else -> tr("Android system controls", "Android 系统控制", "los controles del sistema Android", "Android-Systemsteuerung", "os controles do sistema Android", "les contrôles système Android")
    }

    fun newChatActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "开始新的 Hermes 会话。"
        AppLanguage.SPANISH -> "Inicia una nueva conversación de Hermes."
        AppLanguage.GERMAN -> "Startet eine neue Hermes-Unterhaltung."
        AppLanguage.PORTUGUESE -> "Inicia uma nova conversa do Hermes."
        AppLanguage.FRENCH -> "Lance une nouvelle conversation Hermes."
        AppLanguage.ENGLISH -> "Start a fresh Hermes conversation."
    }

    fun backToChatActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "返回当前会话。"
        AppLanguage.SPANISH -> "Vuelve a la conversación activa."
        AppLanguage.GERMAN -> "Kehrt zur aktiven Unterhaltung zurück."
        AppLanguage.PORTUGUESE -> "Volta para a conversa ativa."
        AppLanguage.FRENCH -> "Revient à la conversation active."
        AppLanguage.ENGLISH -> "Return to the active conversation."
    }

    fun historyActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "浏览之前的 Hermes 会话。"
        AppLanguage.SPANISH -> "Explora conversaciones anteriores de Hermes."
        AppLanguage.GERMAN -> "Durchsucht frühere Hermes-Unterhaltungen."
        AppLanguage.PORTUGUESE -> "Navega por conversas anteriores do Hermes."
        AppLanguage.FRENCH -> "Parcourt les conversations Hermes précédentes."
        AppLanguage.ENGLISH -> "Browse previous Hermes conversations."
    }

    fun newChatInlineActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "不离开 Hermes 即可开始新的会话。"
        AppLanguage.SPANISH -> "Inicia una nueva conversación sin salir de Hermes."
        AppLanguage.GERMAN -> "Startet eine neue Unterhaltung, ohne Hermes zu verlassen."
        AppLanguage.PORTUGUESE -> "Inicia uma nova conversa sem sair do Hermes."
        AppLanguage.FRENCH -> "Lance une nouvelle conversation sans quitter Hermes."
        AppLanguage.ENGLISH -> "Start a fresh conversation without leaving Hermes."
    }

    fun clearConversationActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "移除当前会话并重新开始。"
        AppLanguage.SPANISH -> "Elimina la conversación actual y empieza de cero."
        AppLanguage.GERMAN -> "Entfernt die aktuelle Unterhaltung und startet sauber neu."
        AppLanguage.PORTUGUESE -> "Remove a conversa atual e começa do zero."
        AppLanguage.FRENCH -> "Supprime la conversation actuelle et repart proprement."
        AppLanguage.ENGLISH -> "Remove the current conversation and start clean."
    }

    fun speakLastReplyActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "朗读最新的助手回复。"
        AppLanguage.SPANISH -> "Reproduce en voz alta la última respuesta del asistente."
        AppLanguage.GERMAN -> "Liest die letzte Assistentenantwort laut vor."
        AppLanguage.PORTUGUESE -> "Reproduz a última resposta do assistente em voz alta."
        AppLanguage.FRENCH -> "Lit à voix haute la dernière réponse de l’assistant."
        AppLanguage.ENGLISH -> "Play the latest assistant reply out loud."
    }

    fun speakReply(): String = when (language) {
        AppLanguage.CHINESE -> "朗读回复"
        AppLanguage.SPANISH -> "Leer respuesta"
        AppLanguage.GERMAN -> "Antwort vorlesen"
        AppLanguage.PORTUGUESE -> "Ler resposta"
        AppLanguage.FRENCH -> "Lire la réponse"
        AppLanguage.ENGLISH -> "Speak reply"
    }

    fun moreInputActions(): String = when (language) {
        AppLanguage.CHINESE -> "更多输入操作"
        AppLanguage.SPANISH -> "Más acciones de entrada"
        AppLanguage.GERMAN -> "Weitere Eingabeaktionen"
        AppLanguage.PORTUGUESE -> "Mais ações de entrada"
        AppLanguage.FRENCH -> "Plus d’actions de saisie"
        AppLanguage.ENGLISH -> "More input actions"
    }

    fun attachImage(): String = when (language) {
        AppLanguage.CHINESE -> "图片"
        AppLanguage.SPANISH -> "Imagen"
        AppLanguage.GERMAN -> "Bild"
        AppLanguage.PORTUGUESE -> "Imagem"
        AppLanguage.FRENCH -> "Image"
        AppLanguage.ENGLISH -> "Image"
    }

    fun camera(): String = when (language) {
        AppLanguage.CHINESE -> "相机"
        AppLanguage.SPANISH -> "Cámara"
        AppLanguage.GERMAN -> "Kamera"
        AppLanguage.PORTUGUESE -> "Câmera"
        AppLanguage.FRENCH -> "Caméra"
        AppLanguage.ENGLISH -> "Camera"
    }

    fun signalIntelligence(): String = when (language) {
        AppLanguage.CHINESE -> "信号智能"
        AppLanguage.SPANISH -> "Inteligencia de señal"
        AppLanguage.GERMAN -> "Signalintelligenz"
        AppLanguage.PORTUGUESE -> "Inteligência de sinais"
        AppLanguage.FRENCH -> "Intelligence des signaux"
        AppLanguage.ENGLISH -> "Signal intelligence"
    }

    fun signalQuickActionLabel(id: String, fallback: String): String {
        return SIGNAL_QUICK_ACTION_TRANSLATIONS[language]?.get(id) ?: fallback
    }

    fun copyMessageLabel(): String = when (language) {
        AppLanguage.CHINESE -> "复制"
        AppLanguage.SPANISH -> "Copiar"
        AppLanguage.GERMAN -> "Kopieren"
        AppLanguage.PORTUGUESE -> "Copiar"
        AppLanguage.FRENCH -> "Copier"
        AppLanguage.ENGLISH -> "Copy"
    }

    fun editMessageLabel(): String = when (language) {
        AppLanguage.CHINESE -> "编辑"
        AppLanguage.SPANISH -> "Editar"
        AppLanguage.GERMAN -> "Bearbeiten"
        AppLanguage.PORTUGUESE -> "Editar"
        AppLanguage.FRENCH -> "Modifier"
        AppLanguage.ENGLISH -> "Edit"
    }

    fun resendMessageLabel(): String = when (language) {
        AppLanguage.CHINESE -> "重发"
        AppLanguage.SPANISH -> "Reenviar"
        AppLanguage.GERMAN -> "Erneut senden"
        AppLanguage.PORTUGUESE -> "Reenviar"
        AppLanguage.FRENCH -> "Renvoyer"
        AppLanguage.ENGLISH -> "Resend"
    }

    fun messageActionsContentDescription(): String = when (language) {
        AppLanguage.CHINESE -> "消息操作"
        AppLanguage.SPANISH -> "Acciones del mensaje"
        AppLanguage.GERMAN -> "Nachrichtenaktionen"
        AppLanguage.PORTUGUESE -> "Ações da mensagem"
        AppLanguage.FRENCH -> "Actions du message"
        AppLanguage.ENGLISH -> "Message actions"
    }

    fun diagnosticsLogsTitle(): String = when (language) {
        AppLanguage.CHINESE -> "诊断日志"
        AppLanguage.SPANISH -> "Registros de diagnóstico"
        AppLanguage.GERMAN -> "Diagnoseprotokolle"
        AppLanguage.PORTUGUESE -> "Logs de diagnóstico"
        AppLanguage.FRENCH -> "Journaux de diagnostic"
        AppLanguage.ENGLISH -> "Diagnostics logs"
    }

    fun diagnosticsLogsRedactionNote(): String = when (language) {
        AppLanguage.CHINESE -> "崩溃预览和导出会脱敏密钥、令牌、邮箱、电话号码和用户路径。"
        AppLanguage.SPANISH -> "Las vistas previas y exportaciones de fallos ocultan claves, tokens, correos, teléfonos y rutas de usuario."
        AppLanguage.GERMAN -> "Absturzvorschauen und Exporte schwärzen Schlüssel, Tokens, E-Mails, Telefonnummern und Benutzerpfade."
        AppLanguage.PORTUGUESE -> "Pré-visualizações e exportações de falhas ocultam chaves, tokens, e-mails, telefones e caminhos do usuário."
        AppLanguage.FRENCH -> "Les aperçus et exports de crash masquent clés, jetons, e-mails, téléphones et chemins utilisateur."
        AppLanguage.ENGLISH -> "Crash previews and exports redact keys, tokens, emails, phone numbers, and user paths."
    }

    fun diagnosticsExportLogsLabel(): String = when (language) {
        AppLanguage.CHINESE -> "导出日志"
        AppLanguage.SPANISH -> "Exportar registros"
        AppLanguage.GERMAN -> "Protokolle exportieren"
        AppLanguage.PORTUGUESE -> "Exportar logs"
        AppLanguage.FRENCH -> "Exporter les journaux"
        AppLanguage.ENGLISH -> "Export logs"
    }

    fun diagnosticsClearLastCrashLabel(): String = when (language) {
        AppLanguage.CHINESE -> "清除上次崩溃"
        AppLanguage.SPANISH -> "Borrar último fallo"
        AppLanguage.GERMAN -> "Letzten Absturz löschen"
        AppLanguage.PORTUGUESE -> "Limpar última falha"
        AppLanguage.FRENCH -> "Effacer le dernier crash"
        AppLanguage.ENGLISH -> "Clear last crash"
    }

    fun diagnosticsNoCrashCaptured(): String = when (language) {
        AppLanguage.CHINESE -> "未捕获崩溃"
        AppLanguage.SPANISH -> "No se capturó ningún fallo"
        AppLanguage.GERMAN -> "Kein Absturz erfasst"
        AppLanguage.PORTUGUESE -> "Nenhuma falha capturada"
        AppLanguage.FRENCH -> "Aucun crash capturé"
        AppLanguage.ENGLISH -> "No crash captured"
    }

    fun diagnosticsLastCrashCaptured(capturedAt: String, exceptionType: String): String {
        val whenLabel = capturedAt.ifBlank {
            when (language) {
                AppLanguage.CHINESE -> "最近"
                AppLanguage.SPANISH -> "recientemente"
                AppLanguage.GERMAN -> "kürzlich"
                AppLanguage.PORTUGUESE -> "recentemente"
                AppLanguage.FRENCH -> "récemment"
                AppLanguage.ENGLISH -> "recently"
            }
        }
        val suffix = exceptionType.trim().takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when (language) {
            AppLanguage.CHINESE -> "上次崩溃捕获于 $whenLabel$suffix"
            AppLanguage.SPANISH -> "Último fallo capturado $whenLabel$suffix"
            AppLanguage.GERMAN -> "Letzter Absturz erfasst $whenLabel$suffix"
            AppLanguage.PORTUGUESE -> "Última falha capturada $whenLabel$suffix"
            AppLanguage.FRENCH -> "Dernier crash capturé $whenLabel$suffix"
            AppLanguage.ENGLISH -> "Last crash captured $whenLabel$suffix"
        }
    }

    fun bootStatusText(status: String): String = when (status) {
        "Opening Hermes…" -> bootOpeningStatus()
        "Hermes shell ready" -> bootShellReadyStatus()
        else -> status
    }

    fun bootOpeningStatus(): String = when (language) {
        AppLanguage.CHINESE -> "正在打开 Hermes…"
        AppLanguage.SPANISH -> "Abriendo Hermes…"
        AppLanguage.GERMAN -> "Hermes wird geöffnet…"
        AppLanguage.PORTUGUESE -> "Abrindo Hermes…"
        AppLanguage.FRENCH -> "Ouverture de Hermes…"
        AppLanguage.ENGLISH -> "Opening Hermes…"
    }

    fun bootShellReadyStatus(): String = when (language) {
        AppLanguage.CHINESE -> "Hermes 外壳已就绪"
        AppLanguage.SPANISH -> "Shell de Hermes lista"
        AppLanguage.GERMAN -> "Hermes-Shell bereit"
        AppLanguage.PORTUGUESE -> "Shell do Hermes pronta"
        AppLanguage.FRENCH -> "Shell Hermes prête"
        AppLanguage.ENGLISH -> "Hermes shell ready"
    }

    fun topKLabel(): String = when (language) {
        AppLanguage.CHINESE -> "Top K"
        AppLanguage.SPANISH -> "Top K"
        AppLanguage.GERMAN -> "Top K"
        AppLanguage.PORTUGUESE -> "Top K"
        AppLanguage.FRENCH -> "Top K"
        AppLanguage.ENGLISH -> "Top K"
    }

    fun topPLabel(): String = when (language) {
        AppLanguage.CHINESE -> "Top P"
        AppLanguage.SPANISH -> "Top P"
        AppLanguage.GERMAN -> "Top P"
        AppLanguage.PORTUGUESE -> "Top P"
        AppLanguage.FRENCH -> "Top P"
        AppLanguage.ENGLISH -> "Top P"
    }

    fun temperatureLabel(): String = when (language) {
        AppLanguage.CHINESE -> "温度"
        AppLanguage.SPANISH -> "Temperatura"
        AppLanguage.GERMAN -> "Temperatur"
        AppLanguage.PORTUGUESE -> "Temperatura"
        AppLanguage.FRENCH -> "Température"
        AppLanguage.ENGLISH -> "Temperature"
    }

    fun agentEndpointTitle(): String = when (language) {
        AppLanguage.CHINESE -> "本地代理端点"
        AppLanguage.SPANISH -> "Endpoint del agente local"
        AppLanguage.GERMAN -> "Lokaler Agenten-Endpunkt"
        AppLanguage.PORTUGUESE -> "Endpoint do agente local"
        AppLanguage.FRENCH -> "Point de terminaison agent local"
        AppLanguage.ENGLISH -> "Local agent endpoint"
    }

    fun agentEndpointDescription(): String = when (language) {
        AppLanguage.CHINESE -> "其他代理应用可通过此 OpenAI 兼容端点连接 Hermes。"
        AppLanguage.SPANISH -> "Otras apps agenticas pueden conectarse a Hermes mediante este endpoint compatible con OpenAI."
        AppLanguage.GERMAN -> "Andere Agenten-Apps können über diesen OpenAI-kompatiblen Endpunkt mit Hermes verbinden."
        AppLanguage.PORTUGUESE -> "Outros apps agenticos podem conectar ao Hermes por este endpoint compatível com OpenAI."
        AppLanguage.FRENCH -> "D’autres apps agentiques peuvent se connecter à Hermes via ce point de terminaison compatible OpenAI."
        AppLanguage.ENGLISH -> "Other agentic apps can connect to Hermes through this OpenAI-compatible endpoint."
    }

    fun agentEndpointNotReady(): String = when (language) {
        AppLanguage.CHINESE -> "启动 Hermes 运行时后显示端点。"
        AppLanguage.SPANISH -> "Inicia el runtime de Hermes para mostrar el endpoint."
        AppLanguage.GERMAN -> "Starte die Hermes-Laufzeit, um den Endpunkt anzuzeigen."
        AppLanguage.PORTUGUESE -> "Inicie o runtime do Hermes para mostrar o endpoint."
        AppLanguage.FRENCH -> "Démarrez le runtime Hermes pour afficher le point de terminaison."
        AppLanguage.ENGLISH -> "Start the Hermes runtime to reveal the endpoint."
    }

    fun agentEndpointLoopbackLabel(): String = when (language) {
        AppLanguage.CHINESE -> "本机回环"
        AppLanguage.SPANISH -> "Bucle local"
        AppLanguage.GERMAN -> "Loopback"
        AppLanguage.PORTUGUESE -> "Loopback local"
        AppLanguage.FRENCH -> "Boucle locale"
        AppLanguage.ENGLISH -> "Device loopback"
    }

    fun agentEndpointLanLabel(): String = when (language) {
        AppLanguage.CHINESE -> "局域网 IP"
        AppLanguage.SPANISH -> "IP de LAN"
        AppLanguage.GERMAN -> "LAN-IP"
        AppLanguage.PORTUGUESE -> "IP da LAN"
        AppLanguage.FRENCH -> "IP LAN"
        AppLanguage.ENGLISH -> "LAN IP"
    }

    fun agentEndpointApiKeyLabel(): String = when (language) {
        AppLanguage.CHINESE -> "API 密钥"
        AppLanguage.SPANISH -> "Clave API"
        AppLanguage.GERMAN -> "API-Schlüssel"
        AppLanguage.PORTUGUESE -> "Chave API"
        AppLanguage.FRENCH -> "Clé API"
        AppLanguage.ENGLISH -> "API key"
    }

    fun agentEndpointApiKeyMasked(): String = when (language) {
        AppLanguage.CHINESE -> "已配置（点击复制）"
        AppLanguage.SPANISH -> "Configurada (toca para copiar)"
        AppLanguage.GERMAN -> "Konfiguriert (zum Kopieren tippen)"
        AppLanguage.PORTUGUESE -> "Configurada (toque para copiar)"
        AppLanguage.FRENCH -> "Configurée (appuyer pour copier)"
        AppLanguage.ENGLISH -> "Configured (tap to copy)"
    }

    fun agentEndpointModelLabel(modelName: String): String = when (language) {
        AppLanguage.CHINESE -> "模型：$modelName"
        AppLanguage.SPANISH -> "Modelo: $modelName"
        AppLanguage.GERMAN -> "Modell: $modelName"
        AppLanguage.PORTUGUESE -> "Modelo: $modelName"
        AppLanguage.FRENCH -> "Modèle : $modelName"
        AppLanguage.ENGLISH -> "Model: $modelName"
    }

    fun agentEndpointAcpHint(): String = when (language) {
        AppLanguage.CHINESE -> "ACP 客户端可使用 LAN URL 和 API 密钥。外部 MCP 传输在此 Android 版本中已禁用。"
        AppLanguage.SPANISH -> "Los clientes ACP pueden usar la URL LAN y la clave API. Los transportes MCP externos están desactivados en esta versión de Android."
        AppLanguage.GERMAN -> "ACP-Clients können LAN-URL und API-Schlüssel nutzen. Externe MCP-Transporte sind in dieser Android-Version deaktiviert."
        AppLanguage.PORTUGUESE -> "Clientes ACP podem usar a URL LAN e a chave API. Transportes MCP externos estão desativados nesta versão Android."
        AppLanguage.FRENCH -> "Les clients ACP peuvent utiliser l’URL LAN et la clé API. Les transports MCP externes sont désactivés dans cette version Android."
        AppLanguage.ENGLISH -> "ACP clients can use the LAN URL and API key. External MCP transports are disabled in this Android release."
    }

    fun agentEndpointRefresh(): String = when (language) {
        AppLanguage.CHINESE -> "刷新端点"
        AppLanguage.SPANISH -> "Actualizar endpoint"
        AppLanguage.GERMAN -> "Endpunkt aktualisieren"
        AppLanguage.PORTUGUESE -> "Atualizar endpoint"
        AppLanguage.FRENCH -> "Actualiser le point de terminaison"
        AppLanguage.ENGLISH -> "Refresh endpoint"
    }

    fun mcpQuickAddNativeTools(): String = when (language) {
        AppLanguage.CHINESE -> "原生工具"
        AppLanguage.SPANISH -> "Herramientas nativas"
        AppLanguage.GERMAN -> "Native Tools"
        AppLanguage.PORTUGUESE -> "Ferramentas nativas"
        AppLanguage.FRENCH -> "Outils natifs"
        AppLanguage.ENGLISH -> "Native tools"
    }

    fun mcpQuickAddStdioServer(): String = when (language) {
        AppLanguage.CHINESE -> "添加 Stdio MCP"
        AppLanguage.SPANISH -> "Añadir MCP stdio"
        AppLanguage.GERMAN -> "Stdio-MCP hinzufügen"
        AppLanguage.PORTUGUESE -> "Adicionar MCP stdio"
        AppLanguage.FRENCH -> "Ajouter MCP stdio"
        AppLanguage.ENGLISH -> "Add stdio MCP"
    }

    fun mcpQuickAddSseServer(): String = when (language) {
        AppLanguage.CHINESE -> "添加 SSE MCP"
        AppLanguage.SPANISH -> "Añadir MCP SSE"
        AppLanguage.GERMAN -> "SSE-MCP hinzufügen"
        AppLanguage.PORTUGUESE -> "Adicionar MCP SSE"
        AppLanguage.FRENCH -> "Ajouter MCP SSE"
        AppLanguage.ENGLISH -> "Add SSE MCP"
    }

    fun mcpConfigurationTitle(): String = when (language) {
        AppLanguage.CHINESE -> "MCP 配置"
        AppLanguage.SPANISH -> "Configuración MCP"
        AppLanguage.GERMAN -> "MCP-Konfiguration"
        AppLanguage.PORTUGUESE -> "Configuração MCP"
        AppLanguage.FRENCH -> "Configuration MCP"
        AppLanguage.ENGLISH -> "MCP configuration"
    }

    fun mcpConfigurationDescription(): String = when (language) {
        AppLanguage.CHINESE -> "外部 MCP stdio、SSE 和 HTTP 传输在嵌入式 Android 运行时中不可用，因为其进程生命周期尚无法安全验证。"
        AppLanguage.SPANISH -> "Los transportes MCP externos stdio, SSE y HTTP no están disponibles en el runtime Android integrado porque su ciclo de vida aún no puede verificarse de forma segura."
        AppLanguage.GERMAN -> "Externe MCP-Transporte über stdio, SSE und HTTP sind in der eingebetteten Android-Laufzeit nicht verfügbar, da ihr Prozesslebenszyklus noch nicht sicher verifiziert werden kann."
        AppLanguage.PORTUGUESE -> "Transportes MCP externos stdio, SSE e HTTP não estão disponíveis no runtime Android integrado porque o ciclo de vida deles ainda não pode ser verificado com segurança."
        AppLanguage.FRENCH -> "Les transports MCP externes stdio, SSE et HTTP ne sont pas disponibles dans le runtime Android intégré, car leur cycle de vie ne peut pas encore être vérifié en toute sécurité."
        AppLanguage.ENGLISH -> "External MCP stdio, SSE, and HTTP transports are unavailable in the embedded Android runtime because their process lifetime cannot yet be verified safely."
    }

    fun mcpStoredConfigNotExecuted(): String = tr(
        "Any existing MCP JSON is retained at this path for export or migration only; Hermes does not load or execute it on Android.",
        "现有 MCP JSON 仅保留在此路径以供导出或迁移；Hermes 不会在 Android 上加载或执行它。",
        "El JSON MCP existente se conserva en esta ruta solo para exportarlo o migrarlo; Hermes no lo carga ni lo ejecuta en Android.",
        "Vorhandenes MCP-JSON bleibt an diesem Pfad nur für Export oder Migration erhalten; Hermes lädt oder führt es unter Android nicht aus.",
        "O JSON MCP existente é mantido neste caminho apenas para exportação ou migração; o Hermes não o carrega nem executa no Android.",
        "Le JSON MCP existant est conservé à cet emplacement uniquement pour l’export ou la migration ; Hermes ne le charge ni ne l’exécute sur Android.",
    )

    fun mcpSimpleMode(): String = when (language) {
        AppLanguage.CHINESE -> "简单"
        AppLanguage.SPANISH -> "Simple"
        AppLanguage.GERMAN -> "Einfach"
        AppLanguage.PORTUGUESE -> "Simples"
        AppLanguage.FRENCH -> "Simple"
        AppLanguage.ENGLISH -> "Simple"
    }

    fun mcpAdvancedMode(): String = when (language) {
        AppLanguage.CHINESE -> "高级"
        AppLanguage.SPANISH -> "Avanzado"
        AppLanguage.GERMAN -> "Erweitert"
        AppLanguage.PORTUGUESE -> "Avançado"
        AppLanguage.FRENCH -> "Avancé"
        AppLanguage.ENGLISH -> "Advanced"
    }

    fun mcpConfigFile(path: String): String = when (language) {
        AppLanguage.CHINESE -> "配置文件：$path"
        AppLanguage.SPANISH -> "Archivo de configuración: $path"
        AppLanguage.GERMAN -> "Konfigurationsdatei: $path"
        AppLanguage.PORTUGUESE -> "Arquivo de configuração: $path"
        AppLanguage.FRENCH -> "Fichier de configuration : $path"
        AppLanguage.ENGLISH -> "Config file: $path"
    }

    fun mcpAutoDetect(): String = when (language) {
        AppLanguage.CHINESE -> "检测"
        AppLanguage.SPANISH -> "Detectar"
        AppLanguage.GERMAN -> "Erkennen"
        AppLanguage.PORTUGUESE -> "Detectar"
        AppLanguage.FRENCH -> "Détecter"
        AppLanguage.ENGLISH -> "Detect"
    }

    fun mcpAutoFill(): String = when (language) {
        AppLanguage.CHINESE -> "自动填写"
        AppLanguage.SPANISH -> "Autorrellenar"
        AppLanguage.GERMAN -> "Automatisch füllen"
        AppLanguage.PORTUGUESE -> "Preencher"
        AppLanguage.FRENCH -> "Préremplir"
        AppLanguage.ENGLISH -> "Auto fill"
    }

    fun mcpAutoSetup(): String = when (language) {
        AppLanguage.CHINESE -> "自动设置"
        AppLanguage.SPANISH -> "Configurar"
        AppLanguage.GERMAN -> "Einrichten"
        AppLanguage.PORTUGUESE -> "Configurar"
        AppLanguage.FRENCH -> "Configurer"
        AppLanguage.ENGLISH -> "Auto setup"
    }

    fun mcpAddServer(): String = when (language) {
        AppLanguage.CHINESE -> "添加 MCP"
        AppLanguage.SPANISH -> "Añadir MCP"
        AppLanguage.GERMAN -> "MCP hinzufügen"
        AppLanguage.PORTUGUESE -> "Adicionar MCP"
        AppLanguage.FRENCH -> "Ajouter MCP"
        AppLanguage.ENGLISH -> "Add MCP"
    }

    fun mcpTestRefresh(): String = when (language) {
        AppLanguage.CHINESE -> "测试/刷新"
        AppLanguage.SPANISH -> "Probar/actualizar"
        AppLanguage.GERMAN -> "Testen/aktualisieren"
        AppLanguage.PORTUGUESE -> "Testar/atualizar"
        AppLanguage.FRENCH -> "Tester/actualiser"
        AppLanguage.ENGLISH -> "Test / refresh"
    }

    fun mcpPreview(): String = when (language) {
        AppLanguage.CHINESE -> "预览"
        AppLanguage.SPANISH -> "Vista previa"
        AppLanguage.GERMAN -> "Vorschau"
        AppLanguage.PORTUGUESE -> "Prévia"
        AppLanguage.FRENCH -> "Aperçu"
        AppLanguage.ENGLISH -> "Preview"
    }

    fun mcpSaveAndReload(): String = when (language) {
        AppLanguage.CHINESE -> "保存并重载"
        AppLanguage.SPANISH -> "Guardar y recargar"
        AppLanguage.GERMAN -> "Speichern und neu laden"
        AppLanguage.PORTUGUESE -> "Salvar e recarregar"
        AppLanguage.FRENCH -> "Enregistrer et recharger"
        AppLanguage.ENGLISH -> "Save and reload"
    }

    fun mcpReloadServers(): String = when (language) {
        AppLanguage.CHINESE -> "重载服务器"
        AppLanguage.SPANISH -> "Recargar servidores"
        AppLanguage.GERMAN -> "Server neu laden"
        AppLanguage.PORTUGUESE -> "Recarregar servidores"
        AppLanguage.FRENCH -> "Recharger les serveurs"
        AppLanguage.ENGLISH -> "Reload servers"
    }

    fun mcpConfigJsonLabel(): String = when (language) {
        AppLanguage.CHINESE -> "MCP 配置 JSON"
        AppLanguage.SPANISH -> "JSON de configuración MCP"
        AppLanguage.GERMAN -> "MCP-Konfigurations-JSON"
        AppLanguage.PORTUGUESE -> "JSON de configuração MCP"
        AppLanguage.FRENCH -> "JSON de configuration MCP"
        AppLanguage.ENGLISH -> "MCP config JSON"
    }

    fun mcpProviderCacheResendTitle(): String = when (language) {
        AppLanguage.CHINESE -> "提供商缓存重发"
        AppLanguage.SPANISH -> "Reenvío de caché del proveedor"
        AppLanguage.GERMAN -> "Anbieter-Cache erneut senden"
        AppLanguage.PORTUGUESE -> "Reenvio de cache do provedor"
        AppLanguage.FRENCH -> "Renvoi du cache fournisseur"
        AppLanguage.ENGLISH -> "Provider cache resend"
    }

    fun mcpProviderCacheResendDescription(): String = when (language) {
        AppLanguage.CHINESE -> "开启后，Hermes 可为支持输入令牌缓存的提供商重发稳定的历史/工具输出上下文。关闭后会阻止缓存上下文重发。"
        AppLanguage.SPANISH -> "Al activarlo, Hermes puede reenviar contexto estable previo o de herramientas para caché de tokens de entrada. Desactivado bloquea ese reenvío."
        AppLanguage.GERMAN -> "Aktiviert kann Hermes stabilen früheren oder Tool-Ausgabe-Kontext für Anbieter mit Eingabetoken-Cache erneut senden. Deaktiviert blockiert dieses erneute Senden."
        AppLanguage.PORTUGUESE -> "Quando ativado, o Hermes pode reenviar contexto estável anterior ou de ferramentas para cache de tokens de entrada. Desativado bloqueia esse reenvio."
        AppLanguage.FRENCH -> "Activé, Hermes peut renvoyer un contexte stable précédent ou d’outils pour le cache de jetons d’entrée. Désactivé, ce renvoi est bloqué."
        AppLanguage.ENGLISH -> "When enabled, Hermes may resend stable prior/tool-output context for provider input-token caching. When disabled, cached context resend is blocked."
    }

    fun mcpAddDialogTitle(): String = when (language) {
        AppLanguage.CHINESE -> "添加 MCP 服务器"
        AppLanguage.SPANISH -> "Añadir servidor MCP"
        AppLanguage.GERMAN -> "MCP-Server hinzufügen"
        AppLanguage.PORTUGUESE -> "Adicionar servidor MCP"
        AppLanguage.FRENCH -> "Ajouter un serveur MCP"
        AppLanguage.ENGLISH -> "Add MCP server"
    }

    fun mcpAddDialogDescription(): String = when (language) {
        AppLanguage.CHINESE -> "输入 MCP 命令或服务器名称，并添加备注。Hermes 会写入草稿配置；安装命令后再测试/刷新。"
        AppLanguage.SPANISH -> "Introduce el comando o nombre del servidor MCP y una nota. Hermes escribirá un borrador; pruébalo/actualízalo cuando el comando esté instalado."
        AppLanguage.GERMAN -> "Gib den MCP-Befehl oder Servernamen und eine Notiz ein. Hermes schreibt einen Entwurf; teste/aktualisiere ihn, sobald der Befehl installiert ist."
        AppLanguage.PORTUGUESE -> "Insira o comando ou nome do servidor MCP e uma nota. O Hermes grava um rascunho; teste/atualize quando o comando estiver instalado."
        AppLanguage.FRENCH -> "Saisissez la commande ou le nom du serveur MCP et une note. Hermes écrit un brouillon ; testez/actualisez quand la commande est installée."
        AppLanguage.ENGLISH -> "Enter an MCP command or server name and a note. Hermes writes a draft config; test / refresh once the command is installed."
    }

    fun mcpServerNameLabel(): String = when (language) {
        AppLanguage.CHINESE -> "MCP 名称或命令"
        AppLanguage.SPANISH -> "Nombre o comando MCP"
        AppLanguage.GERMAN -> "MCP-Name oder Befehl"
        AppLanguage.PORTUGUESE -> "Nome ou comando MCP"
        AppLanguage.FRENCH -> "Nom ou commande MCP"
        AppLanguage.ENGLISH -> "MCP name or command"
    }

    fun mcpServerNoteLabel(): String = when (language) {
        AppLanguage.CHINESE -> "备注"
        AppLanguage.SPANISH -> "Nota"
        AppLanguage.GERMAN -> "Notiz"
        AppLanguage.PORTUGUESE -> "Nota"
        AppLanguage.FRENCH -> "Note"
        AppLanguage.ENGLISH -> "Note"
    }

    fun mcpAddAndTest(): String = when (language) {
        AppLanguage.CHINESE -> "添加"
        AppLanguage.SPANISH -> "Añadir"
        AppLanguage.GERMAN -> "Hinzufügen"
        AppLanguage.PORTUGUESE -> "Adicionar"
        AppLanguage.FRENCH -> "Ajouter"
        AppLanguage.ENGLISH -> "Add"
    }

    fun mcpCancel(): String = when (language) {
        AppLanguage.CHINESE -> "取消"
        AppLanguage.SPANISH -> "Cancelar"
        AppLanguage.GERMAN -> "Abbrechen"
        AppLanguage.PORTUGUESE -> "Cancelar"
        AppLanguage.FRENCH -> "Annuler"
        AppLanguage.ENGLISH -> "Cancel"
    }

    fun mcpStatusText(text: String): String {
        if (language == AppLanguage.ENGLISH || text.isBlank()) {
            return text
        }
        val enabledServerCount = Regex("""with (\d+) enabled server""").find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val reloadedServerCount = Regex("""Reloaded (\d+) MCP server""").find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when {
            text == "MCP simple onboarding is ready. Auto setup writes a local native-tools config." -> when (language) {
                AppLanguage.CHINESE -> "MCP 简单引导已就绪。自动设置会写入本地原生工具配置。"
                AppLanguage.SPANISH -> "La guía simple de MCP está lista. La configuración automática escribe herramientas nativas locales."
                AppLanguage.GERMAN -> "Die einfache MCP-Einrichtung ist bereit. Auto-Setup schreibt lokale Native-Tools."
                AppLanguage.PORTUGUESE -> "A integração simples de MCP está pronta. A configuração automática grava ferramentas nativas locais."
                AppLanguage.FRENCH -> "L’intégration simple MCP est prête. La configuration automatique écrit les outils natifs locaux."
                AppLanguage.ENGLISH -> text
            }
            text == "Advanced MCP config editing is ready. Save validates JSON before reload." -> when (language) {
                AppLanguage.CHINESE -> "高级 MCP 配置编辑已就绪。保存会先验证 JSON 再重载。"
                AppLanguage.SPANISH -> "La edición avanzada de MCP está lista. Guardar valida el JSON antes de recargar."
                AppLanguage.GERMAN -> "Die erweiterte MCP-Bearbeitung ist bereit. Speichern validiert JSON vor dem Neuladen."
                AppLanguage.PORTUGUESE -> "A edição avançada de MCP está pronta. Salvar valida o JSON antes de recarregar."
                AppLanguage.FRENCH -> "L’édition avancée MCP est prête. L’enregistrement valide le JSON avant rechargement."
                AppLanguage.ENGLISH -> text
            }
            text.startsWith("No MCP config file found.") -> when (language) {
                AppLanguage.CHINESE -> "未找到 MCP 配置文件。自动填写可创建 hermes-home/mcp/mcp_config.json。"
                AppLanguage.SPANISH -> "No se encontró archivo MCP. Autorrellenar puede crear hermes-home/mcp/mcp_config.json."
                AppLanguage.GERMAN -> "Keine MCP-Konfigurationsdatei gefunden. Automatisch füllen kann hermes-home/mcp/mcp_config.json erstellen."
                AppLanguage.PORTUGUESE -> "Nenhum arquivo MCP encontrado. Preencher pode criar hermes-home/mcp/mcp_config.json."
                AppLanguage.FRENCH -> "Aucun fichier MCP trouvé. Préremplir peut créer hermes-home/mcp/mcp_config.json."
                AppLanguage.ENGLISH -> text
            }
            text.startsWith("Added MCP server draft") -> when (language) {
                AppLanguage.CHINESE -> "已添加 MCP 服务器草稿。命令安装到设备后，请使用测试/刷新。"
                AppLanguage.SPANISH -> "Borrador de servidor MCP añadido. Usa Probar/actualizar cuando el comando esté instalado."
                AppLanguage.GERMAN -> "MCP-Serverentwurf hinzugefügt. Nutze Testen/aktualisieren, sobald der Befehl installiert ist."
                AppLanguage.PORTUGUESE -> "Rascunho de servidor MCP adicionado. Use Testar/atualizar quando o comando estiver instalado."
                AppLanguage.FRENCH -> "Brouillon de serveur MCP ajouté. Utilisez Tester/actualiser quand la commande est installée."
                AppLanguage.ENGLISH -> text
            }
            text == "MCP server name is empty. Enter a command or server name before adding." -> when (language) {
                AppLanguage.CHINESE -> "MCP 服务器名称为空。请先输入命令或服务器名称再添加。"
                AppLanguage.SPANISH -> "El nombre del servidor MCP está vacío. Escribe un comando o nombre antes de añadirlo."
                AppLanguage.GERMAN -> "Der MCP-Servername ist leer. Gib vor dem Hinzufügen einen Befehl oder Servernamen ein."
                AppLanguage.PORTUGUESE -> "O nome do servidor MCP está vazio. Informe um comando ou nome antes de adicionar."
                AppLanguage.FRENCH -> "Le nom du serveur MCP est vide. Saisissez une commande ou un nom avant l’ajout."
                AppLanguage.ENGLISH -> text
            }
            text == "MCP config is empty. Add a JSON object before reloading." -> when (language) {
                AppLanguage.CHINESE -> "MCP 配置为空。请先添加 JSON 对象再重载。"
                AppLanguage.SPANISH -> "La configuración MCP está vacía. Añade un objeto JSON antes de recargar."
                AppLanguage.GERMAN -> "Die MCP-Konfiguration ist leer. Füge vor dem Neuladen ein JSON-Objekt hinzu."
                AppLanguage.PORTUGUESE -> "A configuração MCP está vazia. Adicione um objeto JSON antes de recarregar."
                AppLanguage.FRENCH -> "La configuration MCP est vide. Ajoutez un objet JSON avant de recharger."
                AppLanguage.ENGLISH -> text
            }
            text.startsWith("Provider cache resend is enabled globally") -> when (language) {
                AppLanguage.CHINESE -> "已全局启用提供商缓存重发，但当前提供商不允许重发缓存上下文。"
                AppLanguage.SPANISH -> "El reenvío de caché está activado globalmente, pero este proveedor no permite reenviar contexto en caché."
                AppLanguage.GERMAN -> "Das erneute Senden des Anbieter-Cache ist global aktiv, aber dieser Anbieter erlaubt keinen gecachten Kontext."
                AppLanguage.PORTUGUESE -> "O reenvio de cache está ativado globalmente, mas este provedor não permite reenviar contexto em cache."
                AppLanguage.FRENCH -> "Le renvoi du cache est activé globalement, mais ce fournisseur n’autorise pas le contexte en cache."
                AppLanguage.ENGLISH -> text
            }
            text.contains("Review it, then use Auto setup") -> localizedMcpServerCount(
                "review",
                enabledServerCount ?: 0,
                text,
            )
            text.startsWith("Auto setup prepared MCP config") && enabledServerCount != null -> localizedMcpServerCount(
                "prepared",
                enabledServerCount,
                text,
            )
            reloadedServerCount != null -> localizedMcpServerCount("reloaded", reloadedServerCount, text)
            enabledServerCount != null -> localizedMcpServerCount("validated", enabledServerCount, text)
            text.contains("No enabled server definitions") -> localizedMcpServerCount("none", 0, text)
            text.contains("invalid", ignoreCase = true) -> when (language) {
                AppLanguage.CHINESE -> "MCP 配置 JSON 无效。请检查语法后重试。"
                AppLanguage.SPANISH -> "El JSON MCP no es válido. Revisa la sintaxis e inténtalo de nuevo."
                AppLanguage.GERMAN -> "Das MCP-JSON ist ungültig. Prüfe die Syntax und versuche es erneut."
                AppLanguage.PORTUGUESE -> "O JSON MCP é inválido. Verifique a sintaxe e tente novamente."
                AppLanguage.FRENCH -> "Le JSON MCP est invalide. Vérifiez la syntaxe puis réessayez."
                AppLanguage.ENGLISH -> text
            }
            text.startsWith("Provider cache resend enabled") -> when (language) {
                AppLanguage.CHINESE -> "已启用提供商缓存重发。Hermes 仅会对允许的提供商重发稳定上下文。"
                AppLanguage.SPANISH -> "Reenvío de caché activado. Hermes solo reenvía contexto estable a proveedores que lo permitan."
                AppLanguage.GERMAN -> "Anbieter-Cache erneut senden ist aktiv. Hermes sendet stabilen Kontext nur an erlaubende Anbieter."
                AppLanguage.PORTUGUESE -> "Reenvio de cache ativado. O Hermes só reenvia contexto estável a provedores que permitem."
                AppLanguage.FRENCH -> "Renvoi du cache activé. Hermes ne renvoie le contexte stable qu’aux fournisseurs qui l’autorisent."
                AppLanguage.ENGLISH -> text
            }
            text.startsWith("Provider cache resend disabled") -> when (language) {
                AppLanguage.CHINESE -> "已关闭提供商缓存重发。Hermes 不会重发缓存的历史/工具输出上下文。"
                AppLanguage.SPANISH -> "Reenvío de caché desactivado. Hermes no reenviará contexto previo o de herramientas."
                AppLanguage.GERMAN -> "Anbieter-Cache erneut senden ist deaktiviert. Hermes sendet keinen gecachten Verlauf oder Tool-Kontext erneut."
                AppLanguage.PORTUGUESE -> "Reenvio de cache desativado. O Hermes não reenviará histórico ou contexto de ferramentas."
                AppLanguage.FRENCH -> "Renvoi du cache désactivé. Hermes ne renverra pas l’historique ou le contexte d’outils."
                AppLanguage.ENGLISH -> text
            }
            else -> text
        }
    }

    fun mcpConfigPreviewText(text: String): String {
        if (language == AppLanguage.ENGLISH || text.isBlank()) {
            return text
        }
        val replacements = when (language) {
            AppLanguage.CHINESE -> listOf(
                "Hermes Android local tools exposed to the agent runtime" to "Hermes Android 本地工具已暴露给代理运行时",
                "User-added MCP server draft" to "用户添加的 MCP 服务器草稿",
                "Use Test / refresh after the command is installed on this device." to "命令安装到此设备后，请使用测试/刷新。",
                "Use Test \\/ refresh after the command is installed on this device." to "命令安装到此设备后，请使用测试/刷新。",
            )
            AppLanguage.SPANISH -> listOf(
                "Hermes Android local tools exposed to the agent runtime" to "Herramientas locales de Hermes Android expuestas al runtime del agente",
                "User-added MCP server draft" to "Borrador de servidor MCP añadido por el usuario",
                "Use Test / refresh after the command is installed on this device." to "Usa Probar/actualizar cuando el comando esté instalado en este dispositivo.",
                "Use Test \\/ refresh after the command is installed on this device." to "Usa Probar/actualizar cuando el comando esté instalado en este dispositivo.",
            )
            AppLanguage.GERMAN -> listOf(
                "Hermes Android local tools exposed to the agent runtime" to "Lokale Hermes-Android-Tools für die Agentenlaufzeit",
                "User-added MCP server draft" to "Vom Nutzer hinzugefügter MCP-Serverentwurf",
                "Use Test / refresh after the command is installed on this device." to "Nach Installation des Befehls auf diesem Gerät Testen/aktualisieren verwenden.",
                "Use Test \\/ refresh after the command is installed on this device." to "Nach Installation des Befehls auf diesem Gerät Testen/aktualisieren verwenden.",
            )
            AppLanguage.PORTUGUESE -> listOf(
                "Hermes Android local tools exposed to the agent runtime" to "Ferramentas locais do Hermes Android expostas ao runtime do agente",
                "User-added MCP server draft" to "Rascunho de servidor MCP adicionado pelo usuário",
                "Use Test / refresh after the command is installed on this device." to "Use Testar/atualizar depois que o comando estiver instalado neste dispositivo.",
                "Use Test \\/ refresh after the command is installed on this device." to "Use Testar/atualizar depois que o comando estiver instalado neste dispositivo.",
            )
            AppLanguage.FRENCH -> listOf(
                "Hermes Android local tools exposed to the agent runtime" to "Outils locaux Hermes Android exposés au runtime de l’agent",
                "User-added MCP server draft" to "Brouillon de serveur MCP ajouté par l’utilisateur",
                "Use Test / refresh after the command is installed on this device." to "Utilisez Tester/actualiser une fois la commande installée sur cet appareil.",
                "Use Test \\/ refresh after the command is installed on this device." to "Utilisez Tester/actualiser une fois la commande installée sur cet appareil.",
            )
            AppLanguage.ENGLISH -> emptyList()
        }
        var translated = text
        replacements.forEach { (source, target) ->
            translated = translated.replace(source, target)
        }
        return translated
    }

    private fun localizedMcpServerCount(kind: String, count: Int, fallback: String): String {
        return when (kind) {
            "reloaded" -> when (language) {
                AppLanguage.CHINESE -> if (count == 0) "已重载 MCP 配置。未找到已启用的服务器定义。" else "已从本地配置重载 $count 个 MCP 服务器定义。"
                AppLanguage.SPANISH -> if (count == 0) "Configuración MCP recargada. No hay servidores habilitados." else "Recargadas $count definiciones de servidor MCP desde la configuración local."
                AppLanguage.GERMAN -> if (count == 0) "MCP-Konfiguration neu geladen. Keine aktivierten Serverdefinitionen gefunden." else "$count MCP-Serverdefinitionen aus lokaler Konfiguration neu geladen."
                AppLanguage.PORTUGUESE -> if (count == 0) "Configuração MCP recarregada. Nenhum servidor habilitado encontrado." else "$count definições de servidor MCP recarregadas da configuração local."
                AppLanguage.FRENCH -> if (count == 0) "Configuration MCP rechargée. Aucun serveur activé trouvé." else "$count définitions de serveur MCP rechargées depuis la configuration locale."
                AppLanguage.ENGLISH -> fallback
            }
            "validated" -> when (language) {
                AppLanguage.CHINESE -> "MCP 配置已验证，包含 $count 个已启用的服务器定义。"
                AppLanguage.SPANISH -> "Configuración MCP validada con $count servidores habilitados."
                AppLanguage.GERMAN -> "MCP-Konfiguration mit $count aktivierten Serverdefinitionen validiert."
                AppLanguage.PORTUGUESE -> "Configuração MCP validada com $count servidores habilitados."
                AppLanguage.FRENCH -> "Configuration MCP validée avec $count serveurs activés."
                AppLanguage.ENGLISH -> fallback
            }
            "review" -> when (language) {
                AppLanguage.CHINESE -> if (count == 0) "已自动填充 MCP 配置。未找到已启用的服务器定义。请检查后使用自动设置保存并重载。" else "已自动填充 MCP 配置，包含 $count 个已启用的服务器定义。请检查后使用自动设置保存并重载。"
                AppLanguage.SPANISH -> if (count == 0) "Configuración MCP autorrellenada. No hay servidores habilitados. Revísala y usa Configuración automática para guardar y recargar." else "Configuración MCP autorrellenada con $count servidores habilitados. Revísala y usa Configuración automática para guardar y recargar."
                AppLanguage.GERMAN -> if (count == 0) "MCP-Konfiguration automatisch ausgefüllt. Keine aktivierten Serverdefinitionen gefunden. Prüfe sie und nutze Auto-Setup zum Speichern und Neuladen." else "MCP-Konfiguration mit $count aktivierten Serverdefinitionen automatisch ausgefüllt. Prüfe sie und nutze Auto-Setup zum Speichern und Neuladen."
                AppLanguage.PORTUGUESE -> if (count == 0) "Configuração MCP preenchida automaticamente. Nenhum servidor habilitado encontrado. Revise e use Configuração automática para salvar e recarregar." else "Configuração MCP preenchida automaticamente com $count servidores habilitados. Revise e use Configuração automática para salvar e recarregar."
                AppLanguage.FRENCH -> if (count == 0) "Configuration MCP préremplie. Aucun serveur activé trouvé. Vérifiez-la puis utilisez la configuration automatique pour enregistrer et recharger." else "Configuration MCP préremplie avec $count serveurs activés. Vérifiez-la puis utilisez la configuration automatique pour enregistrer et recharger."
                AppLanguage.ENGLISH -> fallback
            }
            "prepared" -> when (language) {
                AppLanguage.CHINESE -> "自动设置已准备 MCP 配置，包含 $count 个已启用的服务器定义。"
                AppLanguage.SPANISH -> "La configuración automática preparó MCP con $count servidores habilitados."
                AppLanguage.GERMAN -> "Auto-Setup hat die MCP-Konfiguration mit $count aktivierten Serverdefinitionen vorbereitet."
                AppLanguage.PORTUGUESE -> "A configuração automática preparou o MCP com $count servidores habilitados."
                AppLanguage.FRENCH -> "La configuration automatique a préparé MCP avec $count serveurs activés."
                AppLanguage.ENGLISH -> fallback
            }
            else -> when (language) {
                AppLanguage.CHINESE -> "MCP 配置已验证。未找到已启用的服务器定义。"
                AppLanguage.SPANISH -> "Configuración MCP validada. No hay servidores habilitados."
                AppLanguage.GERMAN -> "MCP-Konfiguration validiert. Keine aktivierten Serverdefinitionen gefunden."
                AppLanguage.PORTUGUESE -> "Configuração MCP validada. Nenhum servidor habilitado encontrado."
                AppLanguage.FRENCH -> "Configuration MCP validée. Aucun serveur activé trouvé."
                AppLanguage.ENGLISH -> fallback
            }
        }
    }

    fun chatCommandHelp(): String = when (language) {
        AppLanguage.CHINESE -> "可用应用命令：/new、/history、/clear、/accounts、/settings、/device、/portal、/auth、/signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>、/provider <id>、/model <name>、/speak last。"
        AppLanguage.SPANISH -> "Comandos disponibles: /new, /history, /clear, /accounts, /settings, /device, /portal, /auth, /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>, /provider <id>, /model <name>, /speak last."
        AppLanguage.GERMAN -> "Verfügbare Befehle: /new, /history, /clear, /accounts, /settings, /device, /portal, /auth, /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>, /provider <id>, /model <name>, /speak last."
        AppLanguage.PORTUGUESE -> "Comandos disponíveis: /new, /history, /clear, /accounts, /settings, /device, /portal, /auth, /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>, /provider <id>, /model <name>, /speak last."
        AppLanguage.FRENCH -> "Commandes disponibles : /new, /history, /clear, /accounts, /settings, /device, /portal, /auth, /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>, /provider <id>, /model <name>, /speak last."
        AppLanguage.ENGLISH -> "Available app commands: /new, /history, /clear, /accounts, /settings, /device, /portal, /auth, /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>, /provider <id>, /model <name>, /speak last."
    }

    fun chatCommandOpenedAccounts(): String = when (language) {
        AppLanguage.CHINESE -> "已打开账户页面，可管理登录和提供商认证。"
        AppLanguage.SPANISH -> "Cuentas abierto para gestionar inicios de sesión y autenticación de proveedores."
        AppLanguage.GERMAN -> "Konten geöffnet, damit du Anmeldungen und Anbieter-Auth verwalten kannst."
        AppLanguage.PORTUGUESE -> "Contas aberto para gerenciar logins e autenticação de provedores."
        AppLanguage.FRENCH -> "Comptes ouvert pour gérer connexions et authentification fournisseur."
        AppLanguage.ENGLISH -> "Opened Accounts so you can manage sign-ins and provider auth."
    }

    fun chatCommandOpenedSettings(): String = when (language) {
        AppLanguage.CHINESE -> "已打开设置，可配置提供商、基础 URL、模型和 API 密钥。"
        AppLanguage.SPANISH -> "Ajustes abierto para proveedor, URL base, modelo y clave API."
        AppLanguage.GERMAN -> "Einstellungen für Anbieter, Basis-URL, Modell und API-Schlüssel geöffnet."
        AppLanguage.PORTUGUESE -> "Configurações abertas para provedor, URL base, modelo e chave API."
        AppLanguage.FRENCH -> "Réglages ouverts pour fournisseur, URL de base, modèle et clé API."
        AppLanguage.ENGLISH -> "Opened Settings for provider, base URL, model, and API key controls."
    }

    fun chatCommandOpenedDevice(): String = when (language) {
        AppLanguage.CHINESE -> "已打开设备页面，可使用 Linux 命令、共享文件夹和无障碍控制。"
        AppLanguage.SPANISH -> "Dispositivo abierto para comandos Linux, carpetas compartidas y controles de accesibilidad."
        AppLanguage.GERMAN -> "Gerät für Linux-Befehle, freigegebene Ordner und Bedienhilfen geöffnet."
        AppLanguage.PORTUGUESE -> "Dispositivo aberto para comandos Linux, pastas compartilhadas e controles de acessibilidade."
        AppLanguage.FRENCH -> "Appareil ouvert pour commandes Linux, dossiers partagés et contrôles d’accessibilité."
        AppLanguage.ENGLISH -> "Opened Device for Linux commands, shared folders, and accessibility controls."
    }

    fun chatCommandOpenedPortal(): String = when (language) {
        AppLanguage.CHINESE -> "已打开提供商门户页面。"
        AppLanguage.SPANISH -> "Página del portal de proveedores abierta."
        AppLanguage.GERMAN -> "Anbieterportal geöffnet."
        AppLanguage.PORTUGUESE -> "Página do portal de provedores aberta."
        AppLanguage.FRENCH -> "Page du portail fournisseur ouverte."
        AppLanguage.ENGLISH -> "Opened the Provider Portal page."
    }

    fun chatCommandProviderUsage(): String = when (language) {
        AppLanguage.CHINESE -> "用法：/provider <provider-id>"
        AppLanguage.SPANISH -> "Uso: /provider <provider-id>"
        AppLanguage.GERMAN -> "Nutzung: /provider <provider-id>"
        AppLanguage.PORTUGUESE -> "Uso: /provider <provider-id>"
        AppLanguage.FRENCH -> "Utilisation : /provider <provider-id>"
        AppLanguage.ENGLISH -> "Usage: /provider <provider-id>"
    }

    fun chatCommandProviderApplied(providerId: String): String = when (language) {
        AppLanguage.CHINESE -> "已应用提供商 $providerId，并重启 Hermes 后端。"
        AppLanguage.SPANISH -> "Proveedor $providerId aplicado y backend de Hermes reiniciado."
        AppLanguage.GERMAN -> "Anbieter $providerId angewendet und Hermes-Backend neu gestartet."
        AppLanguage.PORTUGUESE -> "Provedor $providerId aplicado e backend do Hermes reiniciado."
        AppLanguage.FRENCH -> "Fournisseur $providerId appliqué et backend Hermes redémarré."
        AppLanguage.ENGLISH -> "Applied provider $providerId and restarted the Hermes backend."
    }

    fun chatCommandUnknownProvider(providerId: String): String = when (language) {
        AppLanguage.CHINESE -> "未知提供商“$providerId”。请打开设置查看可用提供商配置。"
        AppLanguage.SPANISH -> "Proveedor desconocido '$providerId'. Abre Ajustes para ver perfiles disponibles."
        AppLanguage.GERMAN -> "Unbekannter Anbieter '$providerId'. Öffne Einstellungen für verfügbare Profile."
        AppLanguage.PORTUGUESE -> "Provedor desconhecido '$providerId'. Abra Configurações para ver os perfis disponíveis."
        AppLanguage.FRENCH -> "Fournisseur inconnu '$providerId'. Ouvrez Réglages pour voir les profils disponibles."
        AppLanguage.ENGLISH -> "Unknown provider '$providerId'. Open Settings for the available provider profiles."
    }

    fun chatCommandModelUsage(): String = when (language) {
        AppLanguage.CHINESE -> "用法：/model <model-name>"
        AppLanguage.SPANISH -> "Uso: /model <model-name>"
        AppLanguage.GERMAN -> "Nutzung: /model <model-name>"
        AppLanguage.PORTUGUESE -> "Uso: /model <model-name>"
        AppLanguage.FRENCH -> "Utilisation : /model <model-name>"
        AppLanguage.ENGLISH -> "Usage: /model <model-name>"
    }

    fun chatCommandModelUpdated(modelName: String): String = when (language) {
        AppLanguage.CHINESE -> "已将当前 Hermes 模型更新为“$modelName”，并重启后端。"
        AppLanguage.SPANISH -> "Modelo activo de Hermes actualizado a '$modelName' y backend reiniciado."
        AppLanguage.GERMAN -> "Aktives Hermes-Modell auf '$modelName' aktualisiert und Backend neu gestartet."
        AppLanguage.PORTUGUESE -> "Modelo Hermes ativo atualizado para '$modelName' e backend reiniciado."
        AppLanguage.FRENCH -> "Modèle Hermes actif mis à jour vers '$modelName' et backend redémarré."
        AppLanguage.ENGLISH -> "Updated the active Hermes model to '$modelName' and restarted the backend."
    }

    fun chatCommandModelFailed(modelName: String): String = when (language) {
        AppLanguage.CHINESE -> "无法应用模型“$modelName”。请打开设置直接编辑模型。"
        AppLanguage.SPANISH -> "No se pudo aplicar el modelo '$modelName'. Abre Ajustes para editarlo directamente."
        AppLanguage.GERMAN -> "Modell '$modelName' konnte nicht angewendet werden. Bearbeite es direkt in den Einstellungen."
        AppLanguage.PORTUGUESE -> "Não foi possível aplicar o modelo '$modelName'. Abra Configurações para editar diretamente."
        AppLanguage.FRENCH -> "Impossible d’appliquer le modèle '$modelName'. Ouvrez Réglages pour le modifier directement."
        AppLanguage.ENGLISH -> "Could not apply model '$modelName'. Open Settings to edit the model directly."
    }

    fun chatCommandSignInUsage(): String = when (language) {
        AppLanguage.CHINESE -> "用法：/signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>"
        AppLanguage.SPANISH -> "Uso: /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>"
        AppLanguage.GERMAN -> "Nutzung: /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>"
        AppLanguage.PORTUGUESE -> "Uso: /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>"
        AppLanguage.FRENCH -> "Utilisation : /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>"
        AppLanguage.ENGLISH -> "Usage: /signin <openrouter|openai|codex|chatgpt|claude|gemini|qwen|qwen-coding-plan|qwen-oauth|zai|google|email|phone>"
    }

    fun chatCommandOpenRouterOAuth(): String = when (language) {
        AppLanguage.CHINESE -> "已在浏览器中打开 OpenRouter OAuth。请批准 Hermes 保存用户可控 API 密钥，或在设置中粘贴 OpenRouter API 密钥。"
        AppLanguage.SPANISH -> "OAuth de OpenRouter abierto en el navegador. Autoriza a Hermes a guardar una clave API controlada por ti, o pega una clave API de OpenRouter en Ajustes."
        AppLanguage.GERMAN -> "OpenRouter OAuth im Browser geöffnet. Erlaube Hermes, einen nutzergesteuerten API-Schlüssel zu speichern, oder füge ihn in Einstellungen ein."
        AppLanguage.PORTUGUESE -> "OAuth do OpenRouter aberto no navegador. Autorize o Hermes a salvar uma chave API controlada por você, ou cole uma chave OpenRouter em Configurações."
        AppLanguage.FRENCH -> "OAuth OpenRouter ouvert dans le navigateur. Autorisez Hermes à enregistrer une clé API contrôlée par vous, ou collez une clé OpenRouter dans Réglages."
        AppLanguage.ENGLISH -> "Opened OpenRouter OAuth in your browser. Approve Hermes to save a user-controlled API key, or paste an OpenRouter API key in Settings."
    }

    fun chatCommandLegacyQwenOAuth(): String = when (language) {
        AppLanguage.CHINESE -> "已在设置中准备旧版 qwen-oauth 令牌配置，并在浏览器中打开提供商设置页。Qwen OAuth 登录已于 2026-04-15 停用；新 Qwen Cloud API 密钥配置请使用 /signin qwen。"
        AppLanguage.SPANISH -> "Configuración legacy qwen-oauth preparada en Ajustes y página de proveedor abierta en el navegador. Los inicios Qwen OAuth terminaron el 2026-04-15; usa /signin qwen para nuevas claves API de Qwen Cloud."
        AppLanguage.GERMAN -> "Legacy qwen-oauth-Token-Setup in Einstellungen vorbereitet und Anbieter-Setup im Browser geöffnet. Qwen OAuth-Anmeldungen wurden am 2026-04-15 eingestellt; nutze /signin qwen für neue Qwen-Cloud-API-Schlüssel."
        AppLanguage.PORTUGUESE -> "Configuração legacy qwen-oauth preparada em Configurações e página do provedor aberta no navegador. Logins Qwen OAuth foram encerrados em 2026-04-15; use /signin qwen para novas chaves API Qwen Cloud."
        AppLanguage.FRENCH -> "Configuration legacy qwen-oauth préparée dans Réglages et page fournisseur ouverte dans le navigateur. Les connexions Qwen OAuth ont été arrêtées le 2026-04-15 ; utilisez /signin qwen pour les nouvelles clés API Qwen Cloud."
        AppLanguage.ENGLISH -> "Prepared legacy qwen-oauth token setup in Settings and opened the provider setup page in your browser. Qwen OAuth sign-ins were discontinued on 2026-04-15; use /signin qwen for new Qwen Cloud API-key setup."
    }

    fun chatCommandProviderTokenSetup(method: String): String = when (language) {
        AppLanguage.CHINESE -> "已在设置中准备 $method API 密钥/令牌配置，并在浏览器中打开提供商设置页。请在该处粘贴提供商凭据以驱动 Hermes。"
        AppLanguage.SPANISH -> "Configuración de clave API/token de $method preparada en Ajustes y página de proveedor abierta en el navegador. Pega allí la credencial para alimentar Hermes."
        AppLanguage.GERMAN -> "$method API-Schlüssel/Token-Setup in Einstellungen vorbereitet und Anbieter-Setup im Browser geöffnet. Füge dort die Zugangsdaten ein, um Hermes zu betreiben."
        AppLanguage.PORTUGUESE -> "Configuração de chave API/token de $method preparada em Configurações e página do provedor aberta no navegador. Cole a credencial ali para alimentar o Hermes."
        AppLanguage.FRENCH -> "Configuration clé API/jeton $method préparée dans Réglages et page fournisseur ouverte dans le navigateur. Collez l’identifiant fournisseur pour alimenter Hermes."
        AppLanguage.ENGLISH -> "Prepared $method API-key/token setup in Settings and opened the provider setup page in your browser. Paste the provider credential there to power Hermes."
    }

    fun chatCommandCorr3xtSignIn(method: String): String = when (language) {
        AppLanguage.CHINESE -> "已为 $method 打开 Corr3xt 应用登录。请在浏览器中完成，然后返回 Hermes。"
        AppLanguage.SPANISH -> "Inicio de sesión Corr3xt abierto para $method. Complétalo en el navegador y vuelve a Hermes."
        AppLanguage.GERMAN -> "Corr3xt-App-Anmeldung für $method geöffnet. Schließe sie im Browser ab und kehre zu Hermes zurück."
        AppLanguage.PORTUGUESE -> "Login Corr3xt aberto para $method. Complete no navegador e volte ao Hermes."
        AppLanguage.FRENCH -> "Connexion Corr3xt ouverte pour $method. Terminez dans le navigateur puis revenez à Hermes."
        AppLanguage.ENGLISH -> "Opened Corr3xt app sign-in for $method. Complete it in your browser, then come back to Hermes."
    }

    fun chatCommandSignInFailed(method: String): String = when (language) {
        AppLanguage.CHINESE -> "无法启动“$method”登录。请在账户中配置可访问的 Corr3xt URL，或在设置中使用提供商 API 密钥。"
        AppLanguage.SPANISH -> "No se pudo iniciar sesión para '$method'. Configura una URL Corr3xt alcanzable en Cuentas o usa claves API de proveedor en Ajustes."
        AppLanguage.GERMAN -> "Anmeldung für '$method' konnte nicht gestartet werden. Konfiguriere eine erreichbare Corr3xt-URL in Konten oder nutze Anbieter-API-Schlüssel in Einstellungen."
        AppLanguage.PORTUGUESE -> "Não foi possível iniciar login para '$method'. Configure uma URL Corr3xt acessível em Contas ou use chaves API de provedor em Configurações."
        AppLanguage.FRENCH -> "Impossible de démarrer la connexion pour '$method'. Configurez une URL Corr3xt joignable dans Comptes ou utilisez des clés API fournisseur dans Réglages."
        AppLanguage.ENGLISH -> "Could not start sign-in for '$method'. Configure a reachable Corr3xt URL in Accounts, or use provider API keys in Settings."
    }

    fun chatCommandSpeakingLatest(): String = when (language) {
        AppLanguage.CHINESE -> "正在朗读最新 Hermes 回复。"
        AppLanguage.SPANISH -> "Leyendo la última respuesta de Hermes."
        AppLanguage.GERMAN -> "Neueste Hermes-Antwort wird vorgelesen."
        AppLanguage.PORTUGUESE -> "Lendo a resposta Hermes mais recente."
        AppLanguage.FRENCH -> "Lecture de la dernière réponse Hermes."
        AppLanguage.ENGLISH -> "Speaking the latest Hermes reply."
    }

    fun chatCommandNoReplyToSpeak(): String = when (language) {
        AppLanguage.CHINESE -> "还没有可朗读的助手回复。"
        AppLanguage.SPANISH -> "Aún no hay respuesta del asistente para leer."
        AppLanguage.GERMAN -> "Es gibt noch keine Assistentenantwort zum Vorlesen."
        AppLanguage.PORTUGUESE -> "Ainda não há resposta do assistente para ler."
        AppLanguage.FRENCH -> "Aucune réponse de l’assistant à lire pour l’instant."
        AppLanguage.ENGLISH -> "There is no assistant reply available to speak yet."
    }

    fun chatCommandSpeakUsage(): String = when (language) {
        AppLanguage.CHINESE -> "用法：/speak last"
        AppLanguage.SPANISH -> "Uso: /speak last"
        AppLanguage.GERMAN -> "Nutzung: /speak last"
        AppLanguage.PORTUGUESE -> "Uso: /speak last"
        AppLanguage.FRENCH -> "Utilisation : /speak last"
        AppLanguage.ENGLISH -> "Usage: /speak last"
    }

    fun defaultBaseUrlSummary(providerLabel: String, defaultBaseUrl: String): String = when (language) {
        AppLanguage.CHINESE -> "$providerLabel 的默认地址：$defaultBaseUrl"
        AppLanguage.SPANISH -> "URL predeterminada para $providerLabel: $defaultBaseUrl"
        AppLanguage.GERMAN -> "Standard-URL für $providerLabel: $defaultBaseUrl"
        AppLanguage.PORTUGUESE -> "URL padrão para $providerLabel: $defaultBaseUrl"
        AppLanguage.FRENCH -> "URL par défaut pour $providerLabel : $defaultBaseUrl"
        AppLanguage.ENGLISH -> "Default for $providerLabel: $defaultBaseUrl"
    }

    fun suggestedModelSummary(modelHint: String): String = when (language) {
        AppLanguage.CHINESE -> "建议模型：$modelHint"
        AppLanguage.SPANISH -> "Modelo sugerido: $modelHint"
        AppLanguage.GERMAN -> "Vorgeschlagenes Modell: $modelHint"
        AppLanguage.PORTUGUESE -> "Modelo sugerido: $modelHint"
        AppLanguage.FRENCH -> "Modèle suggéré : $modelHint"
        AppLanguage.ENGLISH -> "Suggested model: $modelHint"
    }

    fun modelSelectionTitle(): String = when (language) {
        AppLanguage.CHINESE -> "模型选择"
        AppLanguage.SPANISH -> "Selección de modelo"
        AppLanguage.GERMAN -> "Modellauswahl"
        AppLanguage.PORTUGUESE -> "Seleção de modelo"
        AppLanguage.FRENCH -> "Sélection du modèle"
        AppLanguage.ENGLISH -> "Model selection"
    }

    fun modelSelectionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "从提供商建议、Gemma 4、Gemma 3 和 Gemma 3n 本地模型中选择，或直接输入自定义模型 ID。"
        AppLanguage.SPANISH -> "Elige entre la sugerencia del proveedor, modelos locales Gemma 4, Gemma 3 y Gemma 3n, o escribe un ID de modelo personalizado."
        AppLanguage.GERMAN -> "Wähle den Anbietervorschlag, lokale Gemma-4-, Gemma-3- und Gemma-3n-Modelle oder gib eine eigene Modell-ID ein."
        AppLanguage.PORTUGUESE -> "Escolha entre a sugestão do provedor, modelos locais Gemma 4, Gemma 3 e Gemma 3n, ou digite um ID de modelo personalizado."
        AppLanguage.FRENCH -> "Choisissez la suggestion du fournisseur, des modèles locaux Gemma 4, Gemma 3 et Gemma 3n, ou saisissez un ID de modèle personnalisé."
        AppLanguage.ENGLISH -> "Choose a provider suggestion, first-class local Gemma 4, Gemma 3, and Gemma 3n models, or type a custom model ID."
    }

    fun addImage(): String = when (language) {
        AppLanguage.CHINESE -> "添加图片"
        AppLanguage.SPANISH -> "Añadir imagen"
        AppLanguage.GERMAN -> "Bild hinzufügen"
        AppLanguage.PORTUGUESE -> "Adicionar imagem"
        AppLanguage.FRENCH -> "Ajouter une image"
        AppLanguage.ENGLISH -> "Add image"
    }

    fun removeAttachment(): String = when (language) {
        AppLanguage.CHINESE -> "移除附件"
        AppLanguage.SPANISH -> "Quitar adjunto"
        AppLanguage.GERMAN -> "Anhang entfernen"
        AppLanguage.PORTUGUESE -> "Remover anexo"
        AppLanguage.FRENCH -> "Retirer la pièce jointe"
        AppLanguage.ENGLISH -> "Remove attachment"
    }

    fun attachedImages(count: Int): String = when (language) {
        AppLanguage.CHINESE -> "已附加 $count 张图片"
        AppLanguage.SPANISH -> "$count imagen(es) adjunta(s)"
        AppLanguage.GERMAN -> "$count Bild(er) angehängt"
        AppLanguage.PORTUGUESE -> "$count imagem(ns) anexada(s)"
        AppLanguage.FRENCH -> "$count image(s) jointe(s)"
        AppLanguage.ENGLISH -> "$count image(s) attached"
    }

    fun retryHermes(): String = when (language) {
        AppLanguage.CHINESE -> "重试 Hermes"
        AppLanguage.SPANISH -> "Reintentar Hermes"
        AppLanguage.GERMAN -> "Hermes erneut versuchen"
        AppLanguage.PORTUGUESE -> "Tentar Hermes novamente"
        AppLanguage.FRENCH -> "Réessayer Hermes"
        AppLanguage.ENGLISH -> "Retry Hermes"
    }

    fun gettingStartedTitle(): String = when (language) {
        AppLanguage.CHINESE -> "开始使用"
        AppLanguage.SPANISH -> "Primeros pasos"
        AppLanguage.GERMAN -> "Erste Schritte"
        AppLanguage.PORTUGUESE -> "Primeiros passos"
        AppLanguage.FRENCH -> "Premiers pas"
        AppLanguage.ENGLISH -> "Getting started"
    }

    fun gettingStartedStep(index: Int): String = when (index) {
        1 -> when (language) {
            AppLanguage.CHINESE -> "1. 账户：使用 Corr3xt 登录邮箱、电话或 Google；提供商密钥在设置中配置。"
            AppLanguage.SPANISH -> "1. Cuentas: inicia sesión por Corr3xt con correo, teléfono o Google; configura claves de proveedores en Ajustes."
            AppLanguage.GERMAN -> "1. Konten: Melde dich per Corr3xt mit E-Mail, Telefon oder Google an; Anbieter-Schlüssel richtest du in Einstellungen ein."
            AppLanguage.PORTUGUESE -> "1. Contas: entre pelo Corr3xt com e-mail, telefone ou Google; configure chaves de provedores nas Configurações."
            AppLanguage.FRENCH -> "1. Comptes : connectez-vous via Corr3xt avec e-mail, téléphone ou Google ; configurez les clés fournisseur dans Paramètres."
            AppLanguage.ENGLISH -> "1. Accounts: sign in through Corr3xt with email, phone, or Google; configure provider keys in Settings."
        }
        2 -> when (language) {
            AppLanguage.CHINESE -> "2. 设置：选择提供商，确认基础 URL/模型，并保存 API 密钥或令牌。"
            AppLanguage.SPANISH -> "2. Ajustes: elige proveedor, confirma la URL base/modelo y guarda la clave API o token."
            AppLanguage.GERMAN -> "2. Einstellungen: Wähle Anbieter, prüfe Basis-URL/Modell und speichere den API-Schlüssel oder Token."
            AppLanguage.PORTUGUESE -> "2. Configurações: escolha provedor, confirme URL base/modelo e salve a chave API ou token."
            AppLanguage.FRENCH -> "2. Réglages : choisissez le fournisseur, vérifiez l’URL de base/le modèle et enregistrez la clé API ou le jeton."
            AppLanguage.ENGLISH -> "2. Settings: choose a provider, confirm the base URL/model, and save your API key or token."
        }
        3 -> when (language) {
            AppLanguage.CHINESE -> "3. 设备：如果希望 Hermes 直接编辑真实手机文件，请授予共享文件夹访问权限。"
            AppLanguage.SPANISH -> "3. Equipo: concede acceso a carpeta compartida si quieres que Hermes edite archivos móviles reales."
            AppLanguage.GERMAN -> "3. Gerät: Erteile Freigabeordner-Zugriff, wenn Hermes echte mobile Dateien direkt bearbeiten soll."
            AppLanguage.PORTUGUESE -> "3. Aparelho: conceda acesso à pasta compartilhada se quiser que Hermes edite arquivos móveis reais."
            AppLanguage.FRENCH -> "3. Appareil : accordez l’accès au dossier partagé pour que Hermes modifie de vrais fichiers mobiles."
            AppLanguage.ENGLISH -> "3. Device: grant shared-folder access if you want Hermes to edit real mobile files directly."
        }
        else -> when (language) {
            AppLanguage.CHINESE -> "4. Hermes 聊天：运行时就绪后，可使用语音输入、聊天命令或齿轮按钮执行页面操作。"
            AppLanguage.SPANISH -> "4. Chat Hermes: usa voz, comandos de chat o el botón de engranaje cuando el runtime esté listo."
            AppLanguage.GERMAN -> "4. Hermes-Chat: Nutze Spracheingabe, Chat-Befehle oder das Zahnrad, sobald die Runtime bereit ist."
            AppLanguage.PORTUGUESE -> "4. Chat Hermes: use voz, comandos de chat ou o botão de engrenagem quando o runtime estiver pronto."
            AppLanguage.FRENCH -> "4. Chat Hermes : utilisez la voix, les commandes ou le bouton engrenage quand le runtime est prêt."
            AppLanguage.ENGLISH -> "4. Hermes chat: use voice input, chat commands, or the cog button for page-specific actions once the runtime is ready."
        }
    }

    fun apiKeyHelp(): String = when (language) {
        AppLanguage.CHINESE -> "粘贴所选提供商的 API 密钥或访问令牌，然后点保存以重启本地 Hermes 后端并应用新配置。"
        AppLanguage.SPANISH -> "Pega la clave API o token de acceso del proveedor seleccionado y pulsa Guardar para reiniciar el backend local de Hermes con la nueva configuración."
        AppLanguage.GERMAN -> "Füge den API-Schlüssel oder Zugriffstoken für den gewählten Anbieter ein und tippe auf Speichern, um das lokale Hermes-Backend mit der neuen Konfiguration neu zu starten."
        AppLanguage.PORTUGUESE -> "Cole a chave API ou token de acesso do provedor selecionado e toque em Salvar para reiniciar o backend local do Hermes com a nova configuração."
        AppLanguage.FRENCH -> "Collez la clé API ou le jeton d’accès du fournisseur sélectionné puis appuyez sur Enregistrer pour redémarrer le backend local Hermes avec la nouvelle configuration."
        AppLanguage.ENGLISH -> "Paste the API key or access token for the selected provider, then tap Save to restart the local Hermes backend with the new config."
    }

    fun openProviderKeyPage(providerLabel: String): String = when (language) {
        AppLanguage.CHINESE -> "打开 $providerLabel 设置页面"
        AppLanguage.SPANISH -> "Abrir página de configuración de $providerLabel"
        AppLanguage.GERMAN -> "$providerLabel-Einrichtungsseite öffnen"
        AppLanguage.PORTUGUESE -> "Abrir página de configuração do $providerLabel"
        AppLanguage.FRENCH -> "Ouvrir la page de configuration $providerLabel"
        AppLanguage.ENGLISH -> "Open $providerLabel setup page"
    }

    fun copyProviderSetupUrl(): String = when (language) {
        AppLanguage.CHINESE -> "复制设置链接"
        AppLanguage.SPANISH -> "Copiar URL de configuración"
        AppLanguage.GERMAN -> "Setup-URL kopieren"
        AppLanguage.PORTUGUESE -> "Copiar URL de configuração"
        AppLanguage.FRENCH -> "Copier l’URL de configuration"
        AppLanguage.ENGLISH -> "Copy setup URL"
    }

    fun checkProviderSetupUrl(): String = when (language) {
        AppLanguage.CHINESE -> "检查设置页面"
        AppLanguage.SPANISH -> "Comprobar configuración"
        AppLanguage.GERMAN -> "Setup prüfen"
        AppLanguage.PORTUGUESE -> "Verificar configuração"
        AppLanguage.FRENCH -> "Vérifier la configuration"
        AppLanguage.ENGLISH -> "Check setup"
    }

    fun importSavedProviderCredential(): String = when (language) {
        AppLanguage.CHINESE -> "使用已保存的 Hermes 凭据"
        AppLanguage.SPANISH -> "Usar credencial Hermes guardada"
        AppLanguage.GERMAN -> "Gespeicherte Hermes-Zugangsdaten nutzen"
        AppLanguage.PORTUGUESE -> "Usar credencial Hermes salva"
        AppLanguage.FRENCH -> "Utiliser l’identifiant Hermes enregistré"
        AppLanguage.ENGLISH -> "Use saved Hermes credential"
    }

    fun copyAuthSignInUrl(): String = when (language) {
        AppLanguage.CHINESE -> "复制登录链接"
        AppLanguage.SPANISH -> "Copiar URL de inicio de sesión"
        AppLanguage.GERMAN -> "Anmelde-URL kopieren"
        AppLanguage.PORTUGUESE -> "Copiar URL de login"
        AppLanguage.FRENCH -> "Copier l’URL de connexion"
        AppLanguage.ENGLISH -> "Copy sign-in URL"
    }

    fun authCopiedSignInUrl(): String = when (language) {
        AppLanguage.CHINESE -> "已复制登录链接。"
        AppLanguage.SPANISH -> "URL de inicio de sesión copiada."
        AppLanguage.GERMAN -> "Anmelde-URL kopiert."
        AppLanguage.PORTUGUESE -> "URL de login copiada."
        AppLanguage.FRENCH -> "URL de connexion copiée."
        AppLanguage.ENGLISH -> "Copied sign-in URL."
    }

    fun toolProfileTitle(): String = when (language) {
        AppLanguage.CHINESE -> "设备端工具路由"
        AppLanguage.SPANISH -> "Enrutamiento de herramientas en el dispositivo"
        AppLanguage.GERMAN -> "On-Device-Werkzeugrouting"
        AppLanguage.PORTUGUESE -> "Roteamento de ferramentas no dispositivo"
        AppLanguage.FRENCH -> "Routage des outils sur l’appareil"
        AppLanguage.ENGLISH -> "On-device tool routing"
    }

    fun toolProfileEnabledSummary(tools: String): String = when (language) {
        AppLanguage.CHINESE -> "兼容模型可使用的原生工具架构：$tools"
        AppLanguage.SPANISH -> "Esquemas nativos disponibles para modelos compatibles: $tools"
        AppLanguage.GERMAN -> "Native Schemas für kompatible Modelle: $tools"
        AppLanguage.PORTUGUESE -> "Esquemas nativos disponíveis para modelos compatíveis: $tools"
        AppLanguage.FRENCH -> "Schémas natifs disponibles pour les modèles compatibles : $tools"
        AppLanguage.ENGLISH -> "Native schemas available to compatible models: $tools"
    }

    fun toolProfileLinuxSummary(): String = when (language) {
        AppLanguage.CHINESE -> "请直接描述任务，例如“运行 date 命令并告诉我时间”或“检查我的设备状态”。Hermes 会为命令选择 terminal_tool，为设备检查选择 android_device_diagnostics_tool。"
        AppLanguage.SPANISH -> "Describe la tarea directamente, por ejemplo, «Ejecuta date y dime la hora» o «Comprueba el estado de mi dispositivo». Hermes elige terminal_tool para comandos y android_device_diagnostics_tool para comprobaciones."
        AppLanguage.GERMAN -> "Beschreibe die Aufgabe direkt, etwa „Führe date aus und nenne mir die Uhrzeit“ oder „Prüfe meinen Gerätestatus“. Hermes wählt terminal_tool für Befehle und android_device_diagnostics_tool für Geräteprüfungen."
        AppLanguage.PORTUGUESE -> "Descreva a tarefa diretamente, por exemplo, “Execute date e diga a hora” ou “Verifique o status do meu dispositivo”. O Hermes escolhe terminal_tool para comandos e android_device_diagnostics_tool para verificações."
        AppLanguage.FRENCH -> "Décrivez directement la tâche, par exemple « Exécute date et donne-moi l’heure » ou « Vérifie l’état de mon appareil ». Hermes choisit terminal_tool pour les commandes et android_device_diagnostics_tool pour les vérifications."
        AppLanguage.ENGLISH -> "Describe the task directly, for example, “Run the date command and tell me the time” or “Check my device status.” Hermes selects terminal_tool for commands and android_device_diagnostics_tool for device checks."
    }

    fun toolProfileAccessibilitySummary(): String = when (language) {
        AppLanguage.CHINESE -> "启用 Hermes 无障碍服务后，android_ui_tool 可检查可见界面并执行获准的界面操作。"
        AppLanguage.SPANISH -> "Tras activar el servicio de accesibilidad de Hermes, android_ui_tool puede inspeccionar la interfaz visible y realizar acciones autorizadas."
        AppLanguage.GERMAN -> "Nach Aktivierung des Hermes-Barrierefreiheitsdienstes kann android_ui_tool die sichtbare Oberfläche prüfen und erlaubte UI-Aktionen ausführen."
        AppLanguage.PORTUGUESE -> "Depois de ativar o serviço de acessibilidade do Hermes, android_ui_tool pode inspecionar a interface visível e executar ações autorizadas."
        AppLanguage.FRENCH -> "Après activation du service d’accessibilité Hermes, android_ui_tool peut inspecter l’interface visible et effectuer les actions autorisées."
        AppLanguage.ENGLISH -> "After you enable the Hermes accessibility service, android_ui_tool can inspect the visible interface and perform approved UI actions."
    }

    fun toolProfileCommandSuiteSummary(): String = when (language) {
        AppLanguage.CHINESE -> "Hermes 会自动选择兼容且已启用的工具；无需输入工具名称。不支持结构化工具调用的模型可能只用文字回答，而不会实际运行工具。"
        AppLanguage.SPANISH -> "Hermes selecciona automáticamente una herramienta compatible y habilitada; no hace falta escribir su nombre. Los modelos sin llamadas estructuradas pueden responder en texto sin ejecutar nada."
        AppLanguage.GERMAN -> "Hermes wählt automatisch ein kompatibles, aktiviertes Werkzeug; der Werkzeugname muss nicht eingegeben werden. Modelle ohne strukturierte Werkzeugaufrufe antworten möglicherweise nur als Text."
        AppLanguage.PORTUGUESE -> "O Hermes seleciona automaticamente uma ferramenta compatível e ativada; não é preciso digitar o nome. Modelos sem chamadas estruturadas podem responder em texto sem executar a ferramenta."
        AppLanguage.FRENCH -> "Hermes sélectionne automatiquement un outil compatible et activé ; vous n’avez pas à saisir son nom. Les modèles sans appel d’outil structuré peuvent répondre en texte sans l’exécuter."
        AppLanguage.ENGLISH -> "Hermes automatically selects a compatible enabled tool; you do not need to type a tool name. Models without structured tool-calling may answer in prose without running it."
    }

    fun toolProfileExcludedSummary(blocked: String): String = when (language) {
        AppLanguage.CHINESE -> "移动运行时中仍排除：$blocked"
        AppLanguage.SPANISH -> "Aún excluido del runtime móvil: $blocked"
        AppLanguage.GERMAN -> "Im mobilen Runtime weiterhin ausgeschlossen: $blocked"
        AppLanguage.PORTUGUESE -> "Ainda excluído do runtime móvel: $blocked"
        AppLanguage.FRENCH -> "Toujours exclus du runtime mobile : $blocked"
        AppLanguage.ENGLISH -> "Still excluded from the mobile runtime: $blocked"
    }

    fun deviceGuideTitle(): String = when (language) {
        AppLanguage.CHINESE -> "如何使用此预览版"
        AppLanguage.SPANISH -> "Cómo usar esta versión alfa"
        AppLanguage.GERMAN -> "So verwendest du diese Alpha-Version"
        AppLanguage.PORTUGUESE -> "Como usar esta versão alfa"
        AppLanguage.FRENCH -> "Comment utiliser cette version alpha"
        AppLanguage.ENGLISH -> "How to use this alpha"
    }

    fun deviceGuideStep(index: Int): String = when (index) {
        1 -> when (language) {
            AppLanguage.CHINESE -> "1. 请用自然语言提问，例如“运行 date 命令并告诉我时间”或“检查我的设备状态”。这两个精确示例会直接运行只读原生路由，无需模型。其他工具请求仍可能需要支持结构化工具调用的模型。"
            AppLanguage.SPANISH -> "1. Pídelo en lenguaje natural, por ejemplo, «Ejecuta date y dime la hora» o «Comprueba el estado de mi dispositivo». Estos dos ejemplos exactos usan rutas nativas de solo lectura sin un modelo. Otras solicitudes pueden requerir un modelo con llamadas estructuradas."
            AppLanguage.GERMAN -> "1. Frage in natürlicher Sprache, etwa „Führe date aus und nenne mir die Uhrzeit“ oder „Prüfe meinen Gerätestatus“. Diese beiden exakten Beispiele nutzen schreibgeschützte native Routen ohne Modell. Andere Werkzeuganfragen können ein Modell mit strukturierten Aufrufen erfordern."
            AppLanguage.PORTUGUESE -> "1. Peça em linguagem natural, por exemplo, “Execute date e diga a hora” ou “Verifique o status do meu dispositivo”. Esses dois exemplos exatos usam rotas nativas somente leitura sem modelo. Outros pedidos podem exigir um modelo com chamadas estruturadas."
            AppLanguage.FRENCH -> "1. Demandez en langage naturel, par exemple « Exécute date et donne-moi l’heure » ou « Vérifie l’état de mon appareil ». Ces deux exemples exacts utilisent des routes natives en lecture seule sans modèle. Les autres demandes peuvent exiger un modèle avec appels structurés."
            AppLanguage.ENGLISH -> "1. Ask in plain language, for example, “Run the date command and tell me the time” or “Check my device status.” Those two exact examples use read-only native routes without a model. Other tool requests may require a model with structured tool-calling."
        }
        2 -> when (language) {
            AppLanguage.CHINESE -> "2. 如果你想让 Hermes 直接读取或编辑真实文件，请通过 Android 原生选择器授予共享文件夹访问权限，然后用自然语言说明要处理的文件。"
            AppLanguage.SPANISH -> "2. Concede una carpeta compartida desde el selector nativo de Android para que Hermes lea o edite los archivos reales y luego describe la tarea en lenguaje natural."
            AppLanguage.GERMAN -> "2. Gewähre über den nativen Android-Auswahldialog einen freigegebenen Ordner, damit Hermes echte Dateien lesen oder bearbeiten kann, und beschreibe die Aufgabe dann in natürlicher Sprache."
            AppLanguage.PORTUGUESE -> "2. Conceda uma pasta compartilhada no seletor nativo do Android para o Hermes ler ou editar os arquivos reais e depois descreva a tarefa em linguagem natural."
            AppLanguage.FRENCH -> "2. Accordez un dossier partagé via le sélecteur natif Android pour que Hermes lise ou modifie les vrais fichiers, puis décrivez la tâche en langage naturel."
            AppLanguage.ENGLISH -> "2. Grant a shared folder from Android's native picker so Hermes can read or edit the real files, then describe the file task in plain language."
        }
        3 -> when (language) {
            AppLanguage.CHINESE -> "3. 只有在需要草稿副本或暂存文件时，才把文件导入工作区。"
            AppLanguage.SPANISH -> "3. Importa archivos al espacio de trabajo solo cuando quieras copias temporales o archivos de preparación."
            AppLanguage.GERMAN -> "3. Importiere Dateien nur dann in den Arbeitsbereich, wenn du Entwurfs- oder Staging-Kopien brauchst."
            AppLanguage.PORTUGUESE -> "3. Importe arquivos para o espaço de trabalho apenas quando quiser cópias temporárias ou de preparação."
            AppLanguage.FRENCH -> "3. Importez des fichiers dans l’espace de travail uniquement si vous voulez des copies temporaires ou de préparation."
            AppLanguage.ENGLISH -> "3. Import files into the workspace only when you want scratch copies or staging files."
        }
        4 -> when (language) {
            AppLanguage.CHINESE -> "4. 如果你希望 Hermes 检查可见 UI 并触发更精确的操作，请启用 Hermes 无障碍服务。"
            AppLanguage.SPANISH -> "4. Activa la accesibilidad de Hermes si quieres que inspeccione la UI visible y lance acciones más precisas además de Inicio, Atrás, Recientes, Notificaciones y Ajustes rápidos."
            AppLanguage.GERMAN -> "4. Aktiviere die Hermes-Barrierefreiheit, wenn Hermes die sichtbare UI prüfen und gezielte Aktionen zusätzlich zu Start, Zurück, Letzte Apps, Benachrichtigungen und Schnelleinstellungen auslösen soll."
            AppLanguage.PORTUGUESE -> "4. Ative a acessibilidade do Hermes se quiser que ele inspecione a UI visível e acione ações mais precisas além de Início, Voltar, Recentes, Notificações e Ajustes rápidos."
            AppLanguage.FRENCH -> "4. Activez l’accessibilité Hermes si vous voulez qu’il inspecte l’interface visible et déclenche des actions ciblées en plus de Accueil, Retour, Récents, Notifications et Réglages rapides."
            AppLanguage.ENGLISH -> "4. Enable Hermes accessibility if you want Hermes to inspect the visible UI and trigger targeted actions in addition to Home / Back / Recents / Notifications / Quick settings."
        }
        else -> ""
    }

    fun deviceWorkspacePath(workspacePath: String): String = when (language) {
        AppLanguage.CHINESE -> "工作区路径：$workspacePath"
        AppLanguage.SPANISH -> "Ruta del espacio de trabajo: $workspacePath"
        AppLanguage.GERMAN -> "Arbeitsbereichspfad: $workspacePath"
        AppLanguage.PORTUGUESE -> "Caminho do espaço de trabalho: $workspacePath"
        AppLanguage.FRENCH -> "Chemin de l’espace de travail : $workspacePath"
        AppLanguage.ENGLISH -> "Workspace path: $workspacePath"
    }

    fun operatorStandbyTitle(): String = when (language) {
        AppLanguage.CHINESE -> "操作员待命"
        AppLanguage.SPANISH -> "Operador en espera"
        AppLanguage.GERMAN -> "Operator-Standby"
        AppLanguage.PORTUGUESE -> "Operador em espera"
        AppLanguage.FRENCH -> "Opérateur en veille"
        AppLanguage.ENGLISH -> "Operator standby"
    }

    fun operatorStandbyStatus(ready: Boolean, enabledCount: Int, externalCount: Int): String = when (language) {
        AppLanguage.CHINESE -> if (ready) "已启用 $enabledCount 个自动化，其中 $externalCount 个可由外部广播触发。" else "没有已启用的自动化待运行。"
        AppLanguage.SPANISH -> if (ready) "$enabledCount automatizaciones habilitadas; $externalCount aceptan disparo externo." else "No hay automatizaciones habilitadas esperando ejecución."
        AppLanguage.GERMAN -> if (ready) "$enabledCount Automationen aktiviert; $externalCount nehmen externe Broadcasts an." else "Keine aktivierten Automationen warten auf Ausführung."
        AppLanguage.PORTUGUESE -> if (ready) "$enabledCount automações ativadas; $externalCount aceitam acionamento externo." else "Nenhuma automação ativada aguardando execução."
        AppLanguage.FRENCH -> if (ready) "$enabledCount automatisations activées ; $externalCount acceptent un déclenchement externe." else "Aucune automatisation activée en attente d’exécution."
        AppLanguage.ENGLISH -> if (ready) "$enabledCount enabled automations; $externalCount accept external broadcast dispatch." else "No enabled automations are waiting for dispatch."
    }

    fun operatorStandbyRunHistory(count: Int): String = when (language) {
        AppLanguage.CHINESE -> "最近运行：$count"
        AppLanguage.SPANISH -> "Ejecuciones recientes: $count"
        AppLanguage.GERMAN -> "Letzte Ausführungen: $count"
        AppLanguage.PORTUGUESE -> "Execuções recentes: $count"
        AppLanguage.FRENCH -> "Exécutions récentes : $count"
        AppLanguage.ENGLISH -> "Recent runs: $count"
    }

    fun operatorStandbyRemoteDispatch(count: Int): String = when (language) {
        AppLanguage.CHINESE -> "远程待命自动化：$count"
        AppLanguage.SPANISH -> "Automatizaciones de espera remota: $count"
        AppLanguage.GERMAN -> "Remote-Standby-Automationen: $count"
        AppLanguage.PORTUGUESE -> "Automações de espera remota: $count"
        AppLanguage.FRENCH -> "Automatisations de veille distante : $count"
        AppLanguage.ENGLISH -> "Remote standby automations: $count"
    }

    fun operatorStandbyLastDispatch(taskName: String, source: String, channel: String): String {
        val cleanTask = taskName.ifBlank {
            when (language) {
                AppLanguage.CHINESE -> "远程任务"
                AppLanguage.SPANISH -> "tarea remota"
                AppLanguage.GERMAN -> "Remote-Aufgabe"
                AppLanguage.PORTUGUESE -> "tarefa remota"
                AppLanguage.FRENCH -> "tâche distante"
                AppLanguage.ENGLISH -> "remote task"
            }
        }
        val cleanSource = source.ifBlank {
            when (language) {
                AppLanguage.CHINESE -> "远程"
                AppLanguage.SPANISH -> "remoto"
                AppLanguage.GERMAN -> "remote"
                AppLanguage.PORTUGUESE -> "remoto"
                AppLanguage.FRENCH -> "distant"
                AppLanguage.ENGLISH -> "remote"
            }
        }
        val cleanChannel = channel.ifBlank {
            when (language) {
                AppLanguage.CHINESE -> "待命"
                AppLanguage.SPANISH -> "espera"
                AppLanguage.GERMAN -> "Standby"
                AppLanguage.PORTUGUESE -> "espera"
                AppLanguage.FRENCH -> "veille"
                AppLanguage.ENGLISH -> "standby"
            }
        }
        return when (language) {
            AppLanguage.CHINESE -> "上次远程调度：$cleanTask，经由 $cleanSource/$cleanChannel"
            AppLanguage.SPANISH -> "Último despacho remoto: $cleanTask por $cleanSource/$cleanChannel"
            AppLanguage.GERMAN -> "Letzte Remote-Dispatch: $cleanTask über $cleanSource/$cleanChannel"
            AppLanguage.PORTUGUESE -> "Último despacho remoto: $cleanTask por $cleanSource/$cleanChannel"
            AppLanguage.FRENCH -> "Dernier dispatch distant : $cleanTask via $cleanSource/$cleanChannel"
            AppLanguage.ENGLISH -> "Last remote dispatch: $cleanTask via $cleanSource/$cleanChannel"
        }
    }

    fun operatorStandbyLastRun(label: String, success: Boolean?, result: String): String {
        val status = when (success) {
            true -> when (language) {
                AppLanguage.CHINESE -> "成功"
                AppLanguage.SPANISH -> "correcto"
                AppLanguage.GERMAN -> "erfolgreich"
                AppLanguage.PORTUGUESE -> "sucesso"
                AppLanguage.FRENCH -> "réussi"
                AppLanguage.ENGLISH -> "success"
            }
            false -> when (language) {
                AppLanguage.CHINESE -> "失败"
                AppLanguage.SPANISH -> "fallo"
                AppLanguage.GERMAN -> "fehlgeschlagen"
                AppLanguage.PORTUGUESE -> "falha"
                AppLanguage.FRENCH -> "échec"
                AppLanguage.ENGLISH -> "failed"
            }
            null -> when (language) {
                AppLanguage.CHINESE -> "未运行"
                AppLanguage.SPANISH -> "sin ejecutar"
                AppLanguage.GERMAN -> "nicht ausgeführt"
                AppLanguage.PORTUGUESE -> "sem execução"
                AppLanguage.FRENCH -> "non exécuté"
                AppLanguage.ENGLISH -> "not run"
            }
        }
        val cleanLabel = label.ifBlank {
            when (language) {
                AppLanguage.CHINESE -> "自动化"
                AppLanguage.SPANISH -> "automatización"
                AppLanguage.GERMAN -> "Automation"
                AppLanguage.PORTUGUESE -> "automação"
                AppLanguage.FRENCH -> "automatisation"
                AppLanguage.ENGLISH -> "automation"
            }
        }
        val cleanResult = result.take(180)
        return when (language) {
            AppLanguage.CHINESE -> if (cleanResult.isBlank()) "上次运行：$cleanLabel ($status)" else "上次运行：$cleanLabel ($status) - $cleanResult"
            AppLanguage.SPANISH -> if (cleanResult.isBlank()) "Última ejecución: $cleanLabel ($status)" else "Última ejecución: $cleanLabel ($status) - $cleanResult"
            AppLanguage.GERMAN -> if (cleanResult.isBlank()) "Letzte Ausführung: $cleanLabel ($status)" else "Letzte Ausführung: $cleanLabel ($status) - $cleanResult"
            AppLanguage.PORTUGUESE -> if (cleanResult.isBlank()) "Última execução: $cleanLabel ($status)" else "Última execução: $cleanLabel ($status) - $cleanResult"
            AppLanguage.FRENCH -> if (cleanResult.isBlank()) "Dernière exécution : $cleanLabel ($status)" else "Dernière exécution : $cleanLabel ($status) - $cleanResult"
            AppLanguage.ENGLISH -> if (cleanResult.isBlank()) "Last run: $cleanLabel ($status)" else "Last run: $cleanLabel ($status) - $cleanResult"
        }
    }

    fun portalLoadingStatus(loggedIn: Boolean): String = when (language) {
        AppLanguage.CHINESE -> if (loggedIn) "已登录提供商门户" else "正在加载嵌入式门户预览"
        AppLanguage.SPANISH -> if (loggedIn) "Sesión iniciada en el portal del proveedor" else "Cargando la vista previa incrustada del portal"
        AppLanguage.GERMAN -> if (loggedIn) "Beim Anbieterportal angemeldet" else "Eingebettete Portal-Vorschau wird geladen"
        AppLanguage.PORTUGUESE -> if (loggedIn) "Sessão iniciada no portal do provedor" else "Carregando a prévia incorporada do portal"
        AppLanguage.FRENCH -> if (loggedIn) "Connecté au portail fournisseur" else "Chargement de l’aperçu intégré du portail"
        AppLanguage.ENGLISH -> if (loggedIn) "Signed in to Provider Portal" else "Loading the embedded portal preview"
    }

    fun portalFallbackStatus(error: String): String = when (language) {
        AppLanguage.CHINESE -> "使用默认提供商门户 URL（$error）"
        AppLanguage.SPANISH -> "Usando la URL predeterminada del portal del proveedor ($error)"
        AppLanguage.GERMAN -> "Standard-URL des Anbieterportals wird verwendet ($error)"
        AppLanguage.PORTUGUESE -> "Usando a URL padrão do portal do provedor ($error)"
        AppLanguage.FRENCH -> "URL par défaut du portail fournisseur utilisée ($error)"
        AppLanguage.ENGLISH -> "Using default Provider Portal URL ($error)"
    }

    fun portalInitialStatus(): String = when (language) {
        AppLanguage.CHINESE -> "正在加载提供商门户…"
        AppLanguage.SPANISH -> "Cargando el portal del proveedor…"
        AppLanguage.GERMAN -> "Anbieterportal wird geladen…"
        AppLanguage.PORTUGUESE -> "Carregando o portal do provedor…"
        AppLanguage.FRENCH -> "Chargement du portail fournisseur…"
        AppLanguage.ENGLISH -> "Loading Provider Portal…"
    }

    fun portalBlockedByOfflineAirplaneMode(): String = when (language) {
        AppLanguage.CHINESE -> "离线飞行模式已开启，提供商门户被阻止。"
        AppLanguage.SPANISH -> "El modo avión sin conexión está activo, por lo que el portal del proveedor está bloqueado."
        AppLanguage.GERMAN -> "Der Offline-Flugmodus ist aktiv, daher ist das Anbieterportal blockiert."
        AppLanguage.PORTUGUESE -> "O modo avião offline está ativo, então o portal do provedor está bloqueado."
        AppLanguage.FRENCH -> "Le mode avion hors ligne est activé, le portail fournisseur est donc bloqué."
        AppLanguage.ENGLISH -> "Offline airplane mode is on, so Provider Portal is blocked."
    }

    fun portalDisabledOnDevice(): String = when (language) {
        AppLanguage.CHINESE -> "此设备已禁用提供商门户。"
        AppLanguage.SPANISH -> "El portal del proveedor está desactivado en este dispositivo."
        AppLanguage.GERMAN -> "Das Anbieterportal ist auf diesem Gerät deaktiviert."
        AppLanguage.PORTUGUESE -> "O portal do provedor está desativado neste dispositivo."
        AppLanguage.FRENCH -> "Le portail fournisseur est désactivé sur cet appareil."
        AppLanguage.ENGLISH -> "Provider Portal is disabled on this device."
    }

    fun portalEnabledStatus(): String = when (language) {
        AppLanguage.CHINESE -> "提供商门户已启用。"
        AppLanguage.SPANISH -> "El portal del proveedor está activado."
        AppLanguage.GERMAN -> "Das Anbieterportal ist aktiviert."
        AppLanguage.PORTUGUESE -> "O portal do provedor está ativado."
        AppLanguage.FRENCH -> "Le portail fournisseur est activé."
        AppLanguage.ENGLISH -> "Provider Portal is enabled."
    }

    fun portalReloadDescription(): String = when (language) {
        AppLanguage.CHINESE -> "重新加载嵌入式提供商门户页面。"
        AppLanguage.SPANISH -> "Vuelve a cargar la página incrustada del portal del proveedor."
        AppLanguage.GERMAN -> "Lädt die eingebettete Anbieterportal-Seite neu."
        AppLanguage.PORTUGUESE -> "Recarrega a página incorporada do portal do provedor."
        AppLanguage.FRENCH -> "Recharge la page intégrée du portail fournisseur."
        AppLanguage.ENGLISH -> "Reload the embedded Provider Portal page."
    }

    fun portalResizeDescription(): String = when (language) {
        AppLanguage.CHINESE -> "无需离开应用即可调整嵌入式门户预览大小。"
        AppLanguage.SPANISH -> "Cambia el tamaño de la vista previa incrustada del portal sin salir de la app."
        AppLanguage.GERMAN -> "Passt die eingebettete Portal-Vorschau an, ohne die App zu verlassen."
        AppLanguage.PORTUGUESE -> "Redimensiona a prévia incorporada do portal sem sair do app."
        AppLanguage.FRENCH -> "Redimensionne l’aperçu intégré du portail sans quitter l’app."
        AppLanguage.ENGLISH -> "Resize the embedded portal preview without leaving the app."
    }

    fun portalExternalDescription(): String = when (language) {
        AppLanguage.CHINESE -> "如果嵌入式视图受限，请在浏览器中打开完整门户。"
        AppLanguage.SPANISH -> "Abre el portal completo en el navegador si la vista incrustada tiene límites."
        AppLanguage.GERMAN -> "Öffnet das vollständige Portal im Browser, falls die Einbettung eingeschränkt ist."
        AppLanguage.PORTUGUESE -> "Abre o portal completo no navegador se a incorporação estiver limitada."
        AppLanguage.FRENCH -> "Ouvre le portail complet dans le navigateur si l’intégration est limitée."
        AppLanguage.ENGLISH -> "Open the full portal in your browser if the embed is limited."
    }

    fun portalLoadFailed(): String = when (language) {
        AppLanguage.CHINESE -> "提供商门户加载失败"
        AppLanguage.SPANISH -> "No se pudo cargar el portal del proveedor"
        AppLanguage.GERMAN -> "Anbieterportal konnte nicht geladen werden"
        AppLanguage.PORTUGUESE -> "Falha ao carregar o portal do provedor"
        AppLanguage.FRENCH -> "Échec du chargement du portail fournisseur"
        AppLanguage.ENGLISH -> "Failed to load Provider Portal"
    }

    fun portalHttpError(status: String): String = when (language) {
        AppLanguage.CHINESE -> "提供商门户返回 HTTP $status"
        AppLanguage.SPANISH -> "El portal del proveedor devolvió HTTP $status"
        AppLanguage.GERMAN -> "Das Anbieterportal hat HTTP $status zurückgegeben"
        AppLanguage.PORTUGUESE -> "O portal do provedor retornou HTTP $status"
        AppLanguage.FRENCH -> "Le portail fournisseur a renvoyé HTTP $status"
        AppLanguage.ENGLISH -> "Provider Portal returned HTTP $status"
    }

    fun portalNetworkBlockedMessage(): String = when (language) {
        AppLanguage.CHINESE -> "离线飞行模式已阻止门户网络访问。"
        AppLanguage.SPANISH -> "El modo avión sin conexión bloquea el acceso de red del portal."
        AppLanguage.GERMAN -> "Der Offline-Flugmodus blockiert den Netzwerkzugriff des Portals."
        AppLanguage.PORTUGUESE -> "O modo avião offline bloqueia o acesso de rede do portal."
        AppLanguage.FRENCH -> "Le mode avion hors ligne bloque l’accès réseau du portail."
        AppLanguage.ENGLISH -> "Portal network access is blocked by offline airplane mode."
    }

    fun portalDisabledMessage(): String = when (language) {
        AppLanguage.CHINESE -> "门户已禁用。"
        AppLanguage.SPANISH -> "El portal está desactivado."
        AppLanguage.GERMAN -> "Das Portal ist deaktiviert."
        AppLanguage.PORTUGUESE -> "O portal está desativado."
        AppLanguage.FRENCH -> "Le portail est désactivé."
        AppLanguage.ENGLISH -> "Portal is disabled."
    }

    fun portalEnabledLabel(): String = when (language) {
        AppLanguage.CHINESE -> "启用门户"
        AppLanguage.SPANISH -> "Portal activado"
        AppLanguage.GERMAN -> "Portal aktiviert"
        AppLanguage.PORTUGUESE -> "Portal ativado"
        AppLanguage.FRENCH -> "Portail activé"
        AppLanguage.ENGLISH -> "Portal enabled"
    }

    fun inferenceLabel(inferenceUrl: String): String = when (language) {
        AppLanguage.CHINESE -> "推理：$inferenceUrl"
        AppLanguage.SPANISH -> "Inferencia: $inferenceUrl"
        AppLanguage.GERMAN -> "Inferenz: $inferenceUrl"
        AppLanguage.PORTUGUESE -> "Inferência: $inferenceUrl"
        AppLanguage.FRENCH -> "Inférence : $inferenceUrl"
        AppLanguage.ENGLISH -> "Inference: $inferenceUrl"
    }

    fun accountsActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "连接 Corr3xt 和提供商登录。"
        AppLanguage.SPANISH -> "Conecta Corr3xt y los inicios de sesión de proveedores."
        AppLanguage.GERMAN -> "Verbindet Corr3xt- und Anbieter-Anmeldungen."
        AppLanguage.PORTUGUESE -> "Conecta Corr3xt e logins de provedores."
        AppLanguage.FRENCH -> "Connecte Corr3xt et les connexions fournisseur."
        AppLanguage.ENGLISH -> "Connect Corr3xt and provider sign-ins."
    }

    fun settingsActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "配置提供商、模型和 API 密钥。"
        AppLanguage.SPANISH -> "Configura proveedor, modelo y clave API."
        AppLanguage.GERMAN -> "Konfiguriert Anbieter, Modell und API-Schlüssel."
        AppLanguage.PORTUGUESE -> "Configure provedor, modelo e chave API."
        AppLanguage.FRENCH -> "Configure le fournisseur, le modèle et la clé API."
        AppLanguage.ENGLISH -> "Configure provider, model, and API key."
    }

    fun portalActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "在 Hermes 启动时打开门户页面。"
        AppLanguage.SPANISH -> "Abre la página del portal mientras Hermes arranca."
        AppLanguage.GERMAN -> "Öffnet die Portal-Seite, während Hermes startet."
        AppLanguage.PORTUGUESE -> "Abre a página do portal enquanto o Hermes inicia."
        AppLanguage.FRENCH -> "Ouvre la page du portail pendant le démarrage de Hermes."
        AppLanguage.ENGLISH -> "Open the portal page while Hermes boots."
    }

    fun deviceActionDescription(): String = when (language) {
        AppLanguage.CHINESE -> "授予文件、Linux 工具和手机控制权限。"
        AppLanguage.SPANISH -> "Concede archivos, herramientas Linux y controles del teléfono."
        AppLanguage.GERMAN -> "Gewährt Zugriff auf Dateien, Linux-Tools und Telefonsteuerung."
        AppLanguage.PORTUGUESE -> "Concede arquivos, ferramentas Linux e controles do telefone."
        AppLanguage.FRENCH -> "Accorde les fichiers, outils Linux et contrôles du téléphone."
        AppLanguage.ENGLISH -> "Grant files, Linux tools, and phone controls."
    }

    fun authNotSignedIn(): String = when (language) {
        AppLanguage.CHINESE -> "未登录"
        AppLanguage.SPANISH -> "Sin iniciar sesión"
        AppLanguage.GERMAN -> "Nicht angemeldet"
        AppLanguage.PORTUGUESE -> "Sem sessão iniciada"
        AppLanguage.FRENCH -> "Non connecté"
        AppLanguage.ENGLISH -> "Not signed in"
    }

    fun cancelPendingSignIn(): String = when (language) {
        AppLanguage.CHINESE -> "取消等待中的登录"
        AppLanguage.SPANISH -> "Cancelar inicio de sesión pendiente"
        AppLanguage.GERMAN -> "Ausstehende Anmeldung abbrechen"
        AppLanguage.PORTUGUESE -> "Cancelar login pendente"
        AppLanguage.FRENCH -> "Annuler la connexion en attente"
        AppLanguage.ENGLISH -> "Cancel pending sign-in"
    }

    fun authGlobalStatusDefault(): String = when (language) {
        AppLanguage.CHINESE -> "已准备好使用已配置的 Corr3xt 应用登录 URL；提供商访问请在设置中使用安全 API 密钥或令牌。"
        AppLanguage.SPANISH -> "La URL Corr3xt configurada está lista para el inicio de sesión de la app; los proveedores usan claves API o tokens seguros en Ajustes."
        AppLanguage.GERMAN -> "Die konfigurierte Corr3xt-URL ist für die App-Anmeldung bereit; Anbieter nutzen sichere API-Schlüssel oder Tokens in Einstellungen."
        AppLanguage.PORTUGUESE -> "A URL Corr3xt configurada está pronta para login no app; provedores usam chaves API ou tokens seguros nas Configurações."
        AppLanguage.FRENCH -> "L’URL Corr3xt configurée est prête pour la connexion à l’application ; les fournisseurs utilisent des clés API ou jetons sécurisés dans Paramètres."
        AppLanguage.ENGLISH -> "Configured Corr3xt app sign-in URL is ready; providers use secure API keys or tokens in Settings."
    }

    fun authConfigureCorr3xtFirst(): String = when (language) {
        AppLanguage.CHINESE -> "请先配置可访问的 Corr3xt URL 以启用应用登录；提供商访问请在设置中使用安全 API 密钥或令牌。"
        AppLanguage.SPANISH -> "Configura una URL Corr3xt accesible para activar el inicio de sesión de la app; los proveedores usan claves API o tokens seguros en Ajustes."
        AppLanguage.GERMAN -> "Konfiguriere zuerst eine erreichbare Corr3xt-URL für die App-Anmeldung; Anbieter nutzen sichere API-Schlüssel oder Tokens in Einstellungen."
        AppLanguage.PORTUGUESE -> "Configure uma URL Corr3xt acessível para ativar o login no app; provedores usam chaves API ou tokens seguros nas Configurações."
        AppLanguage.FRENCH -> "Configurez d’abord une URL Corr3xt joignable pour activer la connexion à l’application ; les fournisseurs utilisent des clés API ou jetons sécurisés dans Paramètres."
        AppLanguage.ENGLISH -> "Configure a reachable Corr3xt URL to enable app sign-in; providers use secure API keys or tokens in Settings."
    }

    fun authWaitingCallback(label: String): String = when (language) {
        AppLanguage.CHINESE -> "正在等待 $label 的 Corr3xt 回调"
        AppLanguage.SPANISH -> "Esperando el callback de Corr3xt para $label"
        AppLanguage.GERMAN -> "Warte auf Corr3xt-Callback für $label"
        AppLanguage.PORTUGUESE -> "Aguardando o callback do Corr3xt para $label"
        AppLanguage.FRENCH -> "En attente du callback Corr3xt pour $label"
        AppLanguage.ENGLISH -> "Waiting for Corr3xt callback for $label"
    }

    fun authConnectedMethods(count: Int): String = when (language) {
        AppLanguage.CHINESE -> "已连接 $count 个登录方式"
        AppLanguage.SPANISH -> "$count métodos de inicio conectados"
        AppLanguage.GERMAN -> "$count Anmeldemethoden verbunden"
        AppLanguage.PORTUGUESE -> "$count métodos de login conectados"
        AppLanguage.FRENCH -> "$count méthodes de connexion connectées"
        AppLanguage.ENGLISH -> "$count sign-in methods connected"
    }

    fun authNoBrowser(): String = when (language) {
        AppLanguage.CHINESE -> "无法打开 Corr3xt：没有可用浏览器"
        AppLanguage.SPANISH -> "No se puede abrir Corr3xt: no hay navegador disponible"
        AppLanguage.GERMAN -> "Corr3xt konnte nicht geöffnet werden: kein Browser verfügbar"
        AppLanguage.PORTUGUESE -> "Não foi possível abrir o Corr3xt: nenhum navegador disponível"
        AppLanguage.FRENCH -> "Impossible d’ouvrir Corr3xt : aucun navigateur disponible"
        AppLanguage.ENGLISH -> "Unable to open Corr3xt: no browser is available"
    }

    fun authTryAgain(): String = when (language) {
        AppLanguage.CHINESE -> "无法打开 Corr3xt。请检查认证 URL 后重试。"
        AppLanguage.SPANISH -> "No se pudo abrir Corr3xt. Revisa la URL de autenticación e inténtalo de nuevo."
        AppLanguage.GERMAN -> "Corr3xt konnte nicht geöffnet werden. Prüfe die Auth-URL und versuche es erneut."
        AppLanguage.PORTUGUESE -> "Não foi possível abrir o Corr3xt. Verifique a URL de autenticação e tente novamente."
        AppLanguage.FRENCH -> "Impossible d’ouvrir Corr3xt. Vérifiez l’URL d’authentification puis réessayez."
        AppLanguage.ENGLISH -> "Unable to open Corr3xt. Check the auth URL and try again."
    }

    fun authCheckingCorr3xt(label: String): String = when (language) {
        AppLanguage.CHINESE -> "正在检查 $label 的 Corr3xt 登录页面…"
        AppLanguage.SPANISH -> "Comprobando la página de inicio Corr3xt para $label…"
        AppLanguage.GERMAN -> "Corr3xt-Anmeldeseite für $label wird geprüft…"
        AppLanguage.PORTUGUESE -> "Verificando a página de login Corr3xt para $label…"
        AppLanguage.FRENCH -> "Vérification de la page de connexion Corr3xt pour $label…"
        AppLanguage.ENGLISH -> "Checking Corr3xt sign-in page for $label…"
    }

    fun authHostCouldNotBeResolved(host: String): String = when (language) {
        AppLanguage.CHINESE -> "无法解析 Corr3xt 登录主机 $host。请使用可访问的登录 URL，或在设置中用 API 密钥配置此提供商。"
        AppLanguage.SPANISH -> "No se pudo resolver el host de inicio Corr3xt $host. Usa una URL de autenticación accesible o configura este proveedor con una clave API en Ajustes."
        AppLanguage.GERMAN -> "Der Corr3xt-Anmeldehost $host konnte nicht aufgelöst werden. Verwende eine erreichbare Auth-URL oder konfiguriere diesen Anbieter in den Einstellungen mit einem API-Schlüssel."
        AppLanguage.PORTUGUESE -> "Não foi possível resolver o host de login Corr3xt $host. Use uma URL de autenticação acessível ou configure este provedor com uma chave API nas Configurações."
        AppLanguage.FRENCH -> "Impossible de résoudre l’hôte de connexion Corr3xt $host. Utilisez une URL d’authentification accessible ou configurez ce fournisseur avec une clé API dans Paramètres."
        AppLanguage.ENGLISH -> "Corr3xt auth host $host could not be resolved. Use a reachable auth URL or configure this provider with an API key in Settings."
    }

    fun authPageCouldNotBeReached(errorName: String): String = when (language) {
        AppLanguage.CHINESE -> "无法访问 Corr3xt 登录页面：$errorName。请使用可访问的登录 URL，或在设置中用 API 密钥配置此提供商。"
        AppLanguage.SPANISH -> "No se pudo abrir la página de inicio Corr3xt: $errorName. Usa una URL de autenticación accesible o configura este proveedor con una clave API en Ajustes."
        AppLanguage.GERMAN -> "Die Corr3xt-Anmeldeseite konnte nicht erreicht werden: $errorName. Verwende eine erreichbare Auth-URL oder konfiguriere diesen Anbieter in den Einstellungen mit einem API-Schlüssel."
        AppLanguage.PORTUGUESE -> "Não foi possível acessar a página de login Corr3xt: $errorName. Use uma URL de autenticação acessível ou configure este provedor com uma chave API nas Configurações."
        AppLanguage.FRENCH -> "Impossible d’atteindre la page de connexion Corr3xt : $errorName. Utilisez une URL d’authentification accessible ou configurez ce fournisseur avec une clé API dans Paramètres."
        AppLanguage.ENGLISH -> "Corr3xt auth page could not be reached: $errorName. Use a reachable auth URL or configure this provider with an API key in Settings."
    }

    fun authAppSignInHostCouldNotBeResolved(host: String): String = when (language) {
        AppLanguage.CHINESE -> "无法解析 Corr3xt 应用登录主机 $host。在设置可访问的 Corr3xt URL 前，应用登录不可用；运行时提供商请在设置中使用安全 API 密钥或令牌。"
        AppLanguage.SPANISH -> "No se pudo resolver el host de inicio de sesión Corr3xt $host. El inicio de sesión de la app no está disponible hasta configurar una URL Corr3xt accesible; los proveedores de runtime usan claves API o tokens seguros en Ajustes."
        AppLanguage.GERMAN -> "Der Corr3xt-App-Anmeldehost $host konnte nicht aufgelöst werden. Die App-Anmeldung ist nicht verfügbar, bis eine erreichbare Corr3xt-URL gesetzt ist; Runtime-Anbieter nutzen sichere API-Schlüssel oder Tokens in Einstellungen."
        AppLanguage.PORTUGUESE -> "Não foi possível resolver o host de login Corr3xt $host. O login do app fica indisponível até configurar uma URL Corr3xt acessível; provedores de runtime usam chaves API ou tokens seguros nas Configurações."
        AppLanguage.FRENCH -> "Impossible de résoudre l’hôte de connexion Corr3xt $host. La connexion à l’application est indisponible tant qu’une URL Corr3xt joignable n’est pas définie ; les fournisseurs runtime utilisent des clés API ou jetons sécurisés dans Paramètres."
        AppLanguage.ENGLISH -> "Corr3xt app sign-in host $host could not be resolved. App sign-in is unavailable until a reachable Corr3xt URL is set; runtime providers use secure API keys or tokens in Settings."
    }

    fun authAppSignInPageCouldNotBeReached(errorName: String): String = when (language) {
        AppLanguage.CHINESE -> "无法访问 Corr3xt 应用登录页面：$errorName。在设置可访问的 Corr3xt URL 前，应用登录不可用；运行时提供商请在设置中使用安全 API 密钥或令牌。"
        AppLanguage.SPANISH -> "No se pudo abrir la página de inicio de sesión Corr3xt: $errorName. El inicio de sesión de la app no está disponible hasta configurar una URL Corr3xt accesible; los proveedores de runtime usan claves API o tokens seguros en Ajustes."
        AppLanguage.GERMAN -> "Die Corr3xt-App-Anmeldeseite konnte nicht erreicht werden: $errorName. Die App-Anmeldung ist nicht verfügbar, bis eine erreichbare Corr3xt-URL gesetzt ist; Runtime-Anbieter nutzen sichere API-Schlüssel oder Tokens in Einstellungen."
        AppLanguage.PORTUGUESE -> "Não foi possível acessar a página de login Corr3xt: $errorName. O login do app fica indisponível até configurar uma URL Corr3xt acessível; provedores de runtime usam chaves API ou tokens seguros nas Configurações."
        AppLanguage.FRENCH -> "Impossible d’atteindre la page de connexion Corr3xt : $errorName. La connexion à l’application est indisponible tant qu’une URL Corr3xt joignable n’est pas définie ; les fournisseurs runtime utilisent des clés API ou jetons sécurisés dans Paramètres."
        AppLanguage.ENGLISH -> "Corr3xt app sign-in page could not be reached: $errorName. App sign-in is unavailable until a reachable Corr3xt URL is set; runtime providers use secure API keys or tokens in Settings."
    }

    fun authApiKeyFallbackAvailable(label: String): String = when (language) {
        AppLanguage.CHINESE -> "可改用 $label 的安全 API 密钥设置继续。"
        AppLanguage.SPANISH -> "Puedes continuar con la configuración segura de clave API para $label."
        AppLanguage.GERMAN -> "Du kannst stattdessen mit der sicheren API-Schlüssel-Einrichtung für $label fortfahren."
        AppLanguage.PORTUGUESE -> "Você pode continuar com a configuração segura por chave API para $label."
        AppLanguage.FRENCH -> "Vous pouvez continuer avec la configuration sécurisée par clé API pour $label."
        AppLanguage.ENGLISH -> "You can continue with secure API-key setup for $label."
    }

    fun authApiKeyFallbackTitle(): String = when (language) {
        AppLanguage.CHINESE -> "改用 API 密钥"
        AppLanguage.SPANISH -> "Usar clave API"
        AppLanguage.GERMAN -> "API-Schlüssel verwenden"
        AppLanguage.PORTUGUESE -> "Usar chave API"
        AppLanguage.FRENCH -> "Utiliser une clé API"
        AppLanguage.ENGLISH -> "Use API key instead"
    }

    fun authApiKeyFallbackDescription(label: String): String = when (language) {
        AppLanguage.CHINESE -> "Hermes 会预选 $label，密钥会保存在 Android 加密存储中，并同步到本地 Python 运行时环境。"
        AppLanguage.SPANISH -> "Hermes preseleccionará $label, guardará la clave en el almacenamiento cifrado de Android y la sincronizará con el runtime local de Python."
        AppLanguage.GERMAN -> "Hermes wählt $label vor, speichert den Schlüssel verschlüsselt unter Android und synchronisiert ihn mit der lokalen Python-Runtime."
        AppLanguage.PORTUGUESE -> "O Hermes vai pré-selecionar $label, salvar a chave no armazenamento criptografado do Android e sincronizá-la com o runtime Python local."
        AppLanguage.FRENCH -> "Hermes présélectionnera $label, enregistrera la clé dans le stockage chiffré Android et la synchronisera avec le runtime Python local."
        AppLanguage.ENGLISH -> "Hermes will preselect $label, save the key in Android encrypted storage, and sync it into the local Python runtime."
    }

    fun useApiKeyInSettings(): String = when (language) {
        AppLanguage.CHINESE -> "在设置中使用 API 密钥"
        AppLanguage.SPANISH -> "Usar clave API en Ajustes"
        AppLanguage.GERMAN -> "API-Schlüssel in Einstellungen nutzen"
        AppLanguage.PORTUGUESE -> "Usar chave API nas Configurações"
        AppLanguage.FRENCH -> "Utiliser une clé API dans Paramètres"
        AppLanguage.ENGLISH -> "Use API key in Settings"
    }

    fun setUpApiKeyFor(label: String): String = when (language) {
        AppLanguage.CHINESE -> "设置 $label API 密钥"
        AppLanguage.SPANISH -> "Configurar clave API de $label"
        AppLanguage.GERMAN -> "$label-API-Schlüssel einrichten"
        AppLanguage.PORTUGUESE -> "Configurar chave API do $label"
        AppLanguage.FRENCH -> "Configurer la clé API $label"
        AppLanguage.ENGLISH -> "Set up $label API key"
    }

    fun authApiKeySetupReady(label: String): String = when (language) {
        AppLanguage.CHINESE -> "$label 已准备好使用安全 API 密钥设置。请在设置中粘贴密钥并保存。"
        AppLanguage.SPANISH -> "$label está listo para configuración segura con clave API. Pega la clave en Ajustes y guarda."
        AppLanguage.GERMAN -> "$label ist für die sichere API-Schlüssel-Einrichtung bereit. Füge den Schlüssel in den Einstellungen ein und speichere."
        AppLanguage.PORTUGUESE -> "$label está pronto para configuração segura por chave API. Cole a chave nas Configurações e salve."
        AppLanguage.FRENCH -> "$label est prêt pour une configuration sécurisée par clé API. Collez la clé dans Paramètres puis enregistrez."
        AppLanguage.ENGLISH -> "$label is ready for secure API-key setup. Paste the key in Settings and save."
    }

    fun authCanceled(): String = when (language) {
        AppLanguage.CHINESE -> "已取消等待中的 Corr3xt 登录"
        AppLanguage.SPANISH -> "Inicio de sesión Corr3xt pendiente cancelado"
        AppLanguage.GERMAN -> "Ausstehende Corr3xt-Anmeldung abgebrochen"
        AppLanguage.PORTUGUESE -> "Login Corr3xt pendente cancelado"
        AppLanguage.FRENCH -> "Connexion Corr3xt en attente annulée"
        AppLanguage.ENGLISH -> "Canceled pending Corr3xt sign-in"
    }

    fun authDescription(methodId: String, fallback: String): String {
        return when (methodId) {
            "email" -> when (language) {
                AppLanguage.CHINESE -> "通过 Corr3xt 使用邮箱链接或密码流程登录应用。"
                AppLanguage.SPANISH -> "Inicia sesión en la app mediante Corr3xt usando un enlace por correo o un flujo con contraseña."
                AppLanguage.GERMAN -> "Melde dich über Corr3xt mit einem E-Mail-Link oder Passwort-Flow in der App an."
                AppLanguage.PORTUGUESE -> "Entre no app pelo Corr3xt usando um link por e-mail ou fluxo com senha."
                AppLanguage.FRENCH -> "Connectez-vous à l’application via Corr3xt avec un lien e-mail ou un flux par mot de passe."
                AppLanguage.ENGLISH -> fallback
            }
            "google" -> when (language) {
                AppLanguage.CHINESE -> "通过 Corr3xt 使用 Google 账户登录应用。"
                AppLanguage.SPANISH -> "Inicia sesión en la app con una cuenta de Google mediante Corr3xt."
                AppLanguage.GERMAN -> "Melde dich über Corr3xt mit einem Google-Konto in der App an."
                AppLanguage.PORTUGUESE -> "Entre no app com uma conta Google pelo Corr3xt."
                AppLanguage.FRENCH -> "Connectez-vous à l’application avec un compte Google via Corr3xt."
                AppLanguage.ENGLISH -> fallback
            }
            "phone" -> when (language) {
                AppLanguage.CHINESE -> "通过 Corr3xt 使用短信或手机验证流程登录应用。"
                AppLanguage.SPANISH -> "Inicia sesión en la app con un flujo de SMS o verificación por teléfono mediante Corr3xt."
                AppLanguage.GERMAN -> "Melde dich über Corr3xt mit einem SMS- oder Telefonverifizierungsfluss in der App an."
                AppLanguage.PORTUGUESE -> "Entre no app com um fluxo de SMS ou verificação por telefone via Corr3xt."
                AppLanguage.FRENCH -> "Connectez-vous à l’application via Corr3xt avec un flux SMS ou de vérification téléphonique."
                AppLanguage.ENGLISH -> fallback
            }
            "chatgpt" -> when (language) {
                AppLanguage.CHINESE -> "粘贴 ChatGPT Web 访问令牌并同步到 Hermes Android。"
                AppLanguage.SPANISH -> "Pega un token de acceso de ChatGPT Web y sincronízalo con Hermes Android."
                AppLanguage.GERMAN -> "Füge ein ChatGPT-Web-Zugriffstoken ein und synchronisiere es mit Hermes Android."
                AppLanguage.PORTUGUESE -> "Cole um token de acesso do ChatGPT Web e sincronize-o com o Hermes Android."
                AppLanguage.FRENCH -> "Collez un jeton d’accès ChatGPT Web et synchronisez-le avec Hermes Android."
                AppLanguage.ENGLISH -> fallback
            }
            "claude" -> when (language) {
                AppLanguage.CHINESE -> "使用 Anthropic / Claude API 密钥进行 Hermes Android 远程模型调用。"
                AppLanguage.SPANISH -> "Usa una clave API de Anthropic / Claude para llamadas remotas de Hermes Android."
                AppLanguage.GERMAN -> "Nutze einen Anthropic-/Claude-API-Schlüssel für Hermes-Android-Remote-Modellaufrufe."
                AppLanguage.PORTUGUESE -> "Use uma chave API Anthropic / Claude para chamadas remotas do Hermes Android."
                AppLanguage.FRENCH -> "Utilisez une clé API Anthropic / Claude pour les appels de modèle distants Hermes Android."
                AppLanguage.ENGLISH -> fallback
            }
            "gemini" -> when (language) {
                AppLanguage.CHINESE -> "使用 Google AI Studio / Gemini API 密钥进行 Hermes Android 远程模型调用。"
                AppLanguage.SPANISH -> "Usa una clave API de Google AI Studio / Gemini para llamadas remotas de Hermes Android."
                AppLanguage.GERMAN -> "Nutze einen Google-AI-Studio-/Gemini-API-Schlüssel für Hermes-Android-Remote-Modellaufrufe."
                AppLanguage.PORTUGUESE -> "Use uma chave API Google AI Studio / Gemini para chamadas remotas do Hermes Android."
                AppLanguage.FRENCH -> "Utilisez une clé API Google AI Studio / Gemini pour les appels de modèle distants Hermes Android."
                AppLanguage.ENGLISH -> fallback
            }
            "qwen" -> when (language) {
                AppLanguage.CHINESE -> "使用 Qwen Cloud / DashScope API 密钥进行 Hermes Android 远程模型调用。"
                AppLanguage.SPANISH -> "Usa una clave API de Qwen Cloud / DashScope para llamadas remotas de Hermes Android."
                AppLanguage.GERMAN -> "Nutze einen Qwen-Cloud-/DashScope-API-Schlüssel für Hermes-Android-Remote-Modellaufrufe."
                AppLanguage.PORTUGUESE -> "Use uma chave API Qwen Cloud / DashScope para chamadas remotas do Hermes Android."
                AppLanguage.FRENCH -> "Utilisez une clé API Qwen Cloud / DashScope pour les appels de modèle distants Hermes Android."
                AppLanguage.ENGLISH -> fallback
            }
            "qwen-coding-plan" -> when (language) {
                AppLanguage.CHINESE -> "使用 Qwen Coding Plan API 密钥和专用 DashScope 编程端点。"
                AppLanguage.SPANISH -> "Usa una clave API de Qwen Coding Plan con el endpoint dedicado de DashScope para código."
                AppLanguage.GERMAN -> "Nutze einen Qwen-Coding-Plan-API-Schlüssel mit dem dedizierten DashScope-Coding-Endpunkt."
                AppLanguage.PORTUGUESE -> "Use uma chave API do Qwen Coding Plan com o endpoint dedicado de programação do DashScope."
                AppLanguage.FRENCH -> "Utilisez une clé API Qwen Coding Plan avec le point de terminaison DashScope dédié au code."
                AppLanguage.ENGLISH -> fallback
            }
            "qwen-oauth" -> when (language) {
                AppLanguage.CHINESE -> "复用已有的 Qwen OAuth / Qwen Chat 令牌；新的 Qwen OAuth 登录已于 2026-04-15 停用，新设置请使用 Qwen Cloud。"
                AppLanguage.SPANISH -> "Reutiliza un token existente de Qwen OAuth / Qwen Chat; los inicios de sesión nuevos con Qwen OAuth se discontinuaron el 2026-04-15, así que usa Qwen Cloud para una configuración nueva."
                AppLanguage.GERMAN -> "Verwende einen vorhandenen Qwen-OAuth-/Qwen-Chat-Token; neue Qwen-OAuth-Anmeldungen wurden am 2026-04-15 eingestellt, nutze für neue Einrichtung Qwen Cloud."
                AppLanguage.PORTUGUESE -> "Reutilize um token Qwen OAuth / Qwen Chat existente; novos logins Qwen OAuth foram descontinuados em 2026-04-15, então use Qwen Cloud para nova configuração."
                AppLanguage.FRENCH -> "Réutilisez un jeton Qwen OAuth / Qwen Chat existant ; les nouvelles connexions Qwen OAuth ont été arrêtées le 2026-04-15, utilisez Qwen Cloud pour une nouvelle configuration."
                AppLanguage.ENGLISH -> fallback
            }
            "zai" -> when (language) {
                AppLanguage.CHINESE -> "使用 Z.AI / GLM API 密钥进行 Hermes Android 远程模型调用。"
                AppLanguage.SPANISH -> "Usa una clave API de Z.AI / GLM para llamadas remotas de Hermes Android."
                AppLanguage.GERMAN -> "Nutze einen Z.AI-/GLM-API-Schlüssel für Hermes-Android-Remote-Modellaufrufe."
                AppLanguage.PORTUGUESE -> "Use uma chave API Z.AI / GLM para chamadas remotas do Hermes Android."
                AppLanguage.FRENCH -> "Utilisez une clé API Z.AI / GLM pour les appels de modèle distants Hermes Android."
                AppLanguage.ENGLISH -> fallback
            }
            else -> fallback
        }
    }

    fun authRefreshDescription(): String = when (language) {
        AppLanguage.CHINESE -> "重新加载本地 Corr3xt 与提供商登录状态。"
        AppLanguage.SPANISH -> "Vuelve a cargar el estado local de Corr3xt y de los proveedores."
        AppLanguage.GERMAN -> "Lädt den lokalen Corr3xt- und Anbieter-Anmeldestatus neu."
        AppLanguage.PORTUGUESE -> "Recarrega o estado local do Corr3xt e dos provedores."
        AppLanguage.FRENCH -> "Recharge l’état local de Corr3xt et des fournisseurs."
        AppLanguage.ENGLISH -> "Reload local Corr3xt and provider auth status."
    }

    fun authCancelPendingDescription(): String = when (language) {
        AppLanguage.CHINESE -> "停止等待当前的 Corr3xt 回调。"
        AppLanguage.SPANISH -> "Deja de esperar el callback actual de Corr3xt."
        AppLanguage.GERMAN -> "Beendet das Warten auf den aktuellen Corr3xt-Callback."
        AppLanguage.PORTUGUESE -> "Para de aguardar o callback atual do Corr3xt."
        AppLanguage.FRENCH -> "Arrête d’attendre le callback Corr3xt en cours."
        AppLanguage.ENGLISH -> "Stop waiting for the current Corr3xt callback."
    }

    fun authWaitingCallbackFor(label: String): String = when (language) {
        AppLanguage.CHINESE -> "正在等待 $label 的 Corr3xt 回调。"
        AppLanguage.SPANISH -> "Esperando el callback de Corr3xt para $label."
        AppLanguage.GERMAN -> "Warte auf den Corr3xt-Callback für $label."
        AppLanguage.PORTUGUESE -> "Aguardando o callback do Corr3xt para $label."
        AppLanguage.FRENCH -> "En attente du callback Corr3xt pour $label."
        AppLanguage.ENGLISH -> "Waiting for Corr3xt callback for $label."
    }

    fun importModelFromPhoneFiles(): String = when (language) {
        AppLanguage.CHINESE -> "从手机文件导入模型"
        AppLanguage.SPANISH -> "Importar modelo desde archivos del teléfono"
        AppLanguage.GERMAN -> "Modell aus Telefon-Dateien importieren"
        AppLanguage.PORTUGUESE -> "Importar modelo dos arquivos do telefone"
        AppLanguage.FRENCH -> "Importer un modèle depuis les fichiers du téléphone"
        AppLanguage.ENGLISH -> "Import model from phone files"
    }

    fun offlineAirplaneLocalModelsOnly(): String = when (language) {
        AppLanguage.CHINESE -> "离线飞行模式已开启，因此 Hermes 只会使用已导入或已下载的本地模型。"
        AppLanguage.SPANISH -> "El modo avión sin conexión está activo, por lo que Hermes solo usará modelos locales importados o ya descargados."
        AppLanguage.GERMAN -> "Der Offline-Flugmodus ist aktiv; Hermes nutzt daher nur importierte oder bereits heruntergeladene lokale Modelle."
        AppLanguage.PORTUGUESE -> "O modo avião offline está ativado, então o Hermes usará apenas modelos locais importados ou já baixados."
        AppLanguage.FRENCH -> "Le mode avion hors ligne est actif ; Hermes utilisera donc seulement les modèles locaux importés ou déjà téléchargés."
        AppLanguage.ENGLISH -> "Offline airplane mode is on, so Hermes will only use imported or already-downloaded local models."
    }

    fun recommendedLocalModelDescription(presetId: String, fallback: String): String = when (presetId) {
        "qwen35-08b-q4km-gguf" -> when (language) {
            AppLanguage.CHINESE -> "小型 Unsloth GGUF 模型，适合在手机上快速验证可见聊天回复、文件创建/删除以及原生工具调用。"
            AppLanguage.SPANISH -> "Modelo GGUF pequeño de Unsloth para respuestas visibles rápidas, creación y borrado de archivos, y validación de herramientas nativas en teléfonos."
            AppLanguage.GERMAN -> "Kleines Unsloth-GGUF-Modell für schnelle sichtbare Chat-Antworten, Datei-Erstellung und -Löschung sowie native Tool-Calling-Validierung auf Telefonen."
            AppLanguage.PORTUGUESE -> "Modelo GGUF pequeno da Unsloth para respostas visíveis rápidas, criação e exclusão de arquivos e validação de chamadas de ferramentas nativas em telefones."
            AppLanguage.FRENCH -> "Petit modèle GGUF Unsloth pour des réponses visibles rapides, la création/suppression de fichiers et la validation des appels d’outils natifs sur téléphone."
            AppLanguage.ENGLISH -> fallback
        }
        "minicpm5-1b-fable5-q4km-gguf" -> when (language) {
            AppLanguage.CHINESE -> "面向嵌入式 llama.cpp 运行时的紧凑型 MiniCPM5 思维模型；采用 Q4_K_M 量化，以适应手机的实际内存限制。"
            AppLanguage.SPANISH -> "Modelo de razonamiento MiniCPM5 compacto para el entorno llama.cpp integrado, elegido en Q4_K_M para un uso práctico de la memoria del teléfono."
            AppLanguage.GERMAN -> "Kompaktes MiniCPM5-Denkmodell für die eingebettete llama.cpp-Laufzeit, mit Q4_K_M für einen praxistauglichen Telefonspeicherbedarf."
            AppLanguage.PORTUGUESE -> "Modelo de raciocínio MiniCPM5 compacto para o runtime llama.cpp integrado, escolhido em Q4_K_M para uso prático da memória do telefone."
            AppLanguage.FRENCH -> "Modèle de raisonnement MiniCPM5 compact pour le moteur llama.cpp intégré, choisi en Q4_K_M pour une utilisation réaliste de la mémoire du téléphone."
            AppLanguage.ENGLISH -> fallback
        }
        "minicpm5-1b-web-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "面向移动设备的 MiniCPM5 LiteRT-LM 工件，采用较短的网页缓存和 Android 安全聊天模板。"
            AppLanguage.SPANISH -> "Artefacto MiniCPM5 LiteRT-LM orientado a móviles, con caché web más corta y una plantilla de chat segura para Android."
            AppLanguage.GERMAN -> "Mobil ausgerichtetes MiniCPM5-LiteRT-LM-Artefakt mit kürzerem Web-Cache und Android-sicherer Chatvorlage."
            AppLanguage.PORTUGUESE -> "Artefato MiniCPM5 LiteRT-LM voltado a dispositivos móveis, com cache web mais curto e modelo de chat seguro para Android."
            AppLanguage.FRENCH -> "Artefact MiniCPM5 LiteRT-LM adapté au mobile, avec un cache web plus court et un modèle de discussion sûr pour Android."
            AppLanguage.ENGLISH -> fallback
        }
        "vibethinker-3b-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "为原生 LiteRT-LM 运行时转换的 30 亿参数推理模型；适用于高内存手机和模拟器。"
            AppLanguage.SPANISH -> "Modelo de razonamiento de tres mil millones de parámetros convertido para el entorno LiteRT-LM nativo; destinado a teléfonos y emuladores con mucha RAM."
            AppLanguage.GERMAN -> "Drei-Milliarden-Parameter-Denkmodell für die native LiteRT-LM-Laufzeit; vorgesehen für Telefone und Emulatoren mit viel RAM."
            AppLanguage.PORTUGUESE -> "Modelo de raciocínio de três bilhões de parâmetros convertido para o runtime LiteRT-LM nativo; destinado a telefones e emuladores com muita RAM."
            AppLanguage.FRENCH -> "Modèle de raisonnement de trois milliards de paramètres converti pour le moteur LiteRT-LM natif, destiné aux téléphones et émulateurs disposant de beaucoup de RAM."
            AppLanguage.ENGLISH -> fallback
        }
        "gemma4-e2b-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "Hermes 移动聊天的一等 Gemma 4 本地运行时目标，覆盖图像能力运行时管线、MTP 加速和 Android 代理工具。"
            AppLanguage.SPANISH -> "Objetivo local Gemma 4 de primera clase para chat móvil de Hermes, con canalización de imagen, aceleración MTP y herramientas de agente Android."
            AppLanguage.GERMAN -> "Erstklassiges lokales Gemma-4-Laufzeitziel für Hermes Mobile Chat mit Bild-Pipeline, MTP-Beschleunigung und Android-Agentenwerkzeugen."
            AppLanguage.PORTUGUESE -> "Alvo local Gemma 4 de primeira classe para o chat móvel do Hermes, com suporte a imagem, aceleração MTP e ferramentas de agente Android."
            AppLanguage.FRENCH -> "Cible locale Gemma 4 de premier niveau pour le chat mobile Hermes, avec pipeline image, accélération MTP et outils d’agent Android."
            AppLanguage.ENGLISH -> fallback
        }
        "gemma4-e4b-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "更大的 Gemma 4 LiteRT-LM 模型，仍低于 5 GB 测试上限；使用 Google AI Edge Gallery 当前的 MTP 更新工件，在高内存手机上获得更高质量的本地代理回复。"
            AppLanguage.SPANISH -> "Modelo Gemma 4 LiteRT-LM más grande, por debajo del límite de prueba de 5 GB, con el artefacto MTP actual de Google AI Edge Gallery para respuestas locales de mayor calidad en teléfonos con mucha RAM."
            AppLanguage.GERMAN -> "Größeres Gemma-4-LiteRT-LM-Modell unter der 5-GB-Testgrenze mit aktuellem MTP-Artefakt aus Google AI Edge Gallery für bessere lokale Agentenantworten auf RAM-starken Telefonen."
            AppLanguage.PORTUGUESE -> "Modelo Gemma 4 LiteRT-LM maior, abaixo do limite de teste de 5 GB, usando o artefato MTP atual do Google AI Edge Gallery para respostas locais melhores em telefones com muita RAM."
            AppLanguage.FRENCH -> "Modèle Gemma 4 LiteRT-LM plus grand sous le plafond de test de 5 Go, utilisant l’artefact MTP actuel de Google AI Edge Gallery pour de meilleures réponses locales sur téléphones à forte RAM."
            AppLanguage.ENGLISH -> fallback
        }
        "gemma3-1b-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "小型 Gemma 3 兼容性目标，适合低内存设备和快速本地运行时启动。"
            AppLanguage.SPANISH -> "Objetivo de compatibilidad Gemma 3 pequeño para dispositivos con poca memoria y arranque local rápido."
            AppLanguage.GERMAN -> "Kleines Gemma-3-Kompatibilitätsziel für Geräte mit wenig Speicher und schnellen lokalen Laufzeitstart."
            AppLanguage.PORTUGUESE -> "Alvo pequeno de compatibilidade Gemma 3 para dispositivos com pouca memória e inicialização local rápida."
            AppLanguage.FRENCH -> "Petite cible de compatibilité Gemma 3 pour les appareils à faible mémoire et le démarrage local rapide."
            AppLanguage.ENGLISH -> fallback
        }
        else -> fallback
    }

    fun recommendedLocalModelTestedLabel(presetId: String, fallback: String): String = when (presetId) {
        "qwen35-08b-q4km-gguf" -> when (language) {
            AppLanguage.CHINESE -> "Unsloth Q4_K_M 手机工具调用"
            AppLanguage.SPANISH -> "Herramientas en teléfono con Unsloth Q4_K_M"
            AppLanguage.GERMAN -> "Unsloth Q4_K_M Tool-Calling auf Telefonen"
            AppLanguage.PORTUGUESE -> "Chamada de ferramentas no telefone com Unsloth Q4_K_M"
            AppLanguage.FRENCH -> "Appels d’outils sur téléphone avec Unsloth Q4_K_M"
            AppLanguage.ENGLISH -> fallback
        }
        "minicpm5-1b-fable5-q4km-gguf" -> when (language) {
            AppLanguage.CHINESE -> "MiniCPM5 llama.cpp 兼容性目标"
            AppLanguage.SPANISH -> "Objetivo de compatibilidad MiniCPM5 con llama.cpp"
            AppLanguage.GERMAN -> "MiniCPM5-Kompatibilitätsziel für llama.cpp"
            AppLanguage.PORTUGUESE -> "Alvo de compatibilidade MiniCPM5 com llama.cpp"
            AppLanguage.FRENCH -> "Cible de compatibilité MiniCPM5 avec llama.cpp"
            AppLanguage.ENGLISH -> fallback
        }
        "minicpm5-1b-web-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "MiniCPM5 移动版 LiteRT-LM 兼容性目标"
            AppLanguage.SPANISH -> "Objetivo de compatibilidad MiniCPM5 móvil con LiteRT-LM"
            AppLanguage.GERMAN -> "Mobiles MiniCPM5-Kompatibilitätsziel für LiteRT-LM"
            AppLanguage.PORTUGUESE -> "Alvo de compatibilidade MiniCPM5 móvel com LiteRT-LM"
            AppLanguage.FRENCH -> "Cible de compatibilité MiniCPM5 mobile avec LiteRT-LM"
            AppLanguage.ENGLISH -> fallback
        }
        "vibethinker-3b-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "VibeThinker LiteRT-LM 兼容性目标"
            AppLanguage.SPANISH -> "Objetivo de compatibilidad VibeThinker con LiteRT-LM"
            AppLanguage.GERMAN -> "VibeThinker-Kompatibilitätsziel für LiteRT-LM"
            AppLanguage.PORTUGUESE -> "Alvo de compatibilidade VibeThinker com LiteRT-LM"
            AppLanguage.FRENCH -> "Cible de compatibilité VibeThinker avec LiteRT-LM"
            AppLanguage.ENGLISH -> fallback
        }
        "gemma4-e2b-litert-lm", "gemma4-e4b-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "Edge Gallery 1.0.13 MTP 路径"
            AppLanguage.SPANISH -> "Ruta MTP de Edge Gallery 1.0.13"
            AppLanguage.GERMAN -> "Edge-Gallery-1.0.13-MTP-Pfad"
            AppLanguage.PORTUGUESE -> "Caminho MTP do Edge Gallery 1.0.13"
            AppLanguage.FRENCH -> "Chemin MTP Edge Gallery 1.0.13"
            AppLanguage.ENGLISH -> fallback
        }
        "gemma3-1b-litert-lm" -> when (language) {
            AppLanguage.CHINESE -> "小型兼容性路径"
            AppLanguage.SPANISH -> "Ruta pequeña de compatibilidad"
            AppLanguage.GERMAN -> "Kleiner Kompatibilitätspfad"
            AppLanguage.PORTUGUESE -> "Caminho pequeno de compatibilidade"
            AppLanguage.FRENCH -> "Petit chemin de compatibilité"
            AppLanguage.ENGLISH -> fallback
        }
        else -> fallback
    }

    fun localModelUiText(text: String): String {
        if (language == AppLanguage.ENGLISH || text.isBlank()) return text
        val replacements = when (language) {
            AppLanguage.CHINESE -> listOf(
                "Release-certified" to "已通过发布认证",
                "Experimental - not tested by Hermes" to "实验性 - 未经 Hermes 测试",
                "Cleared Hugging Face token" to "已清除 Hugging Face 令牌",
                "Saved Hugging Face token for private or gated model downloads" to "已保存用于私有或受限模型下载的 Hugging Face 令牌",
                "Tap Refresh catalog to load signed model choices when needed." to "需要时点按刷新目录以加载已签名的模型选项。",
                "Refreshing signed Hugging Face model catalog…" to "正在刷新已签名的 Hugging Face 模型目录…",
                "Signed catalog loaded, but no downloadable model files were detected yet" to "已加载签名目录，但还没有检测到可下载的模型文件",
                "Signed catalog loaded with " to "已加载签名目录，包含 ",
                " downloadable model choices" to " 个可下载模型选项",
                "Unable to load signed model catalog:" to "无法加载签名模型目录：",
                "Importing local model from phone files…" to "正在从手机文件导入本地模型…",
                " and marked it as the preferred local model." to "，并已标记为首选本地模型。",
                "Local file" to "本地文件",
                "Preparing download…" to "正在准备下载…",
                "Inspecting model candidate…" to "正在检查模型候选项…",
                "Model candidate inspected" to "模型候选项已检查",
                "Queued " to "已将 ",
                " in Android DownloadManager" to " 加入 Android 下载管理器",
                "; Hermes will start it when Android finishes the download." to " 加入队列；Android 完成下载后 Hermes 会启动它。",
                " is already downloaded. Starting runtime…" to " 已下载。正在启动运行时…",
                "Preparing " to "正在准备 ",
                " from signed catalog…" to "（来自签名目录）…",
                "Restarted " to "已重新开始 ",
                " with mobile data and roaming allowed" to "，允许使用移动数据和漫游",
                "Unable to restart this download on mobile data" to "无法通过移动数据重新开始此下载",
                "Opened Android Downloads" to "已打开 Android 下载",
                "Android Downloads is not available on this device" to "此设备没有 Android 下载界面",
                "Marked this model as the preferred local runtime candidate" to "已将此模型标记为首选本地运行时候选项",
                "Preferred model is ready. Starting Hermes runtime…" to "首选模型已准备好。正在启动 Hermes 运行时…",
                "Existing model file is present on disk" to "现有模型文件已存在于磁盘上",
                "Download file is present on disk" to "下载文件已存在于磁盘上",
                "Imported model file is missing on disk" to "导入的模型文件在磁盘上缺失",
                "Android no longer reports this download" to "Android 不再报告此下载",
                "Imported existing model file from disk" to "已从磁盘导入现有模型文件",
                "File: " to "文件：",
                "Size: " to "大小：",
                "Phone RAM: " to "手机内存：",
                "ABIs: " to "ABI：",
                "HTTP range resume is available" to "支持 HTTP 分段续传",
                "resume depends on server support" to "能否续传取决于服务器支持"
            )
            AppLanguage.SPANISH -> listOf(
                "Release-certified" to "Certificado para lanzamiento",
                "Experimental - not tested by Hermes" to "Experimental - no probado por Hermes",
                "Cleared Hugging Face token" to "Token de Hugging Face borrado",
                "Saved Hugging Face token for private or gated model downloads" to "Token de Hugging Face guardado para descargas privadas o restringidas",
                "Tap Refresh catalog to load signed model choices when needed." to "Toca Actualizar catálogo para cargar modelos firmados cuando sea necesario.",
                "Refreshing signed Hugging Face model catalog…" to "Actualizando el catálogo firmado de modelos de Hugging Face…",
                "Signed catalog loaded, but no downloadable model files were detected yet" to "Catálogo firmado cargado, pero aún no se detectaron archivos de modelo descargables",
                "Signed catalog loaded with " to "Catálogo firmado cargado con ",
                " downloadable model choices" to " opciones de modelo descargables",
                "Unable to load signed model catalog:" to "No se pudo cargar el catálogo firmado de modelos:",
                "Importing local model from phone files…" to "Importando modelo local desde archivos del teléfono…",
                " and marked it as the preferred local model." to " y marcado como modelo local preferido.",
                "Local file" to "Archivo local",
                "Preparing download…" to "Preparando descarga…",
                "Inspecting model candidate…" to "Inspeccionando candidato de modelo…",
                "Model candidate inspected" to "Candidato de modelo inspeccionado",
                "Queued " to "En cola ",
                " in Android DownloadManager" to " en Android DownloadManager",
                "; Hermes will start it when Android finishes the download." to "; Hermes lo iniciará cuando Android termine la descarga.",
                " is already downloaded. Starting runtime…" to " ya está descargado. Iniciando runtime…",
                "Preparing " to "Preparando ",
                " from signed catalog…" to " desde el catálogo firmado…",
                "Restarted " to "Reiniciado ",
                " with mobile data and roaming allowed" to " con datos móviles y roaming permitidos",
                "Unable to restart this download on mobile data" to "No se puede reiniciar esta descarga con datos móviles",
                "Opened Android Downloads" to "Descargas de Android abiertas",
                "Android Downloads is not available on this device" to "Descargas de Android no está disponible en este dispositivo",
                "Marked this model as the preferred local runtime candidate" to "Este modelo se marcó como candidato local preferido del runtime",
                "Preferred model is ready. Starting Hermes runtime…" to "El modelo preferido está listo. Iniciando el runtime de Hermes…",
                "Existing model file is present on disk" to "El archivo de modelo existente está en el disco",
                "Download file is present on disk" to "El archivo descargado está en el disco",
                "Imported model file is missing on disk" to "Falta el archivo de modelo importado en el disco",
                "Android no longer reports this download" to "Android ya no informa de esta descarga",
                "Imported existing model file from disk" to "Archivo de modelo existente importado desde el disco",
                "File: " to "Archivo: ",
                "Size: " to "Tamaño: ",
                "Phone RAM: " to "RAM del teléfono: ",
                "ABIs: " to "ABI: ",
                "HTTP range resume is available" to "La reanudación HTTP por rangos está disponible",
                "resume depends on server support" to "la reanudación depende del soporte del servidor"
            )
            AppLanguage.GERMAN -> listOf(
                "Release-certified" to "Für die Veröffentlichung zertifiziert",
                "Experimental - not tested by Hermes" to "Experimentell - nicht von Hermes getestet",
                "Cleared Hugging Face token" to "Hugging-Face-Token gelöscht",
                "Saved Hugging Face token for private or gated model downloads" to "Hugging-Face-Token für private oder beschränkte Modell-Downloads gespeichert",
                "Tap Refresh catalog to load signed model choices when needed." to "Tippe bei Bedarf auf Katalog aktualisieren, um signierte Modelloptionen zu laden.",
                "Refreshing signed Hugging Face model catalog…" to "Signierten Hugging-Face-Modellkatalog aktualisieren…",
                "Signed catalog loaded, but no downloadable model files were detected yet" to "Signierter Katalog geladen, aber noch keine herunterladbaren Modelldateien erkannt",
                "Signed catalog loaded with " to "Signierter Katalog geladen mit ",
                " downloadable model choices" to " herunterladbaren Modelloptionen",
                "Unable to load signed model catalog:" to "Signierter Modellkatalog konnte nicht geladen werden:",
                "Importing local model from phone files…" to "Lokales Modell aus Telefon-Dateien importieren…",
                " and marked it as the preferred local model." to " und als bevorzugtes lokales Modell markiert.",
                "Local file" to "Lokale Datei",
                "Preparing download…" to "Download vorbereiten…",
                "Inspecting model candidate…" to "Modellkandidat wird geprüft…",
                "Model candidate inspected" to "Modellkandidat geprüft",
                "Queued " to "In Warteschlange: ",
                " in Android DownloadManager" to " im Android-Downloadmanager",
                "; Hermes will start it when Android finishes the download." to "; Hermes startet es, wenn Android den Download beendet.",
                " is already downloaded. Starting runtime…" to " ist bereits heruntergeladen. Laufzeit wird gestartet…",
                "Preparing " to "Vorbereitung von ",
                " from signed catalog…" to " aus dem signierten Katalog…",
                "Restarted " to "Neu gestartet: ",
                " with mobile data and roaming allowed" to " mit erlaubten mobilen Daten und Roaming",
                "Unable to restart this download on mobile data" to "Dieser Download kann nicht über mobile Daten neu gestartet werden",
                "Opened Android Downloads" to "Android-Downloads geöffnet",
                "Android Downloads is not available on this device" to "Android-Downloads ist auf diesem Gerät nicht verfügbar",
                "Marked this model as the preferred local runtime candidate" to "Dieses Modell wurde als bevorzugter lokaler Laufzeitkandidat markiert",
                "Preferred model is ready. Starting Hermes runtime…" to "Bevorzugtes Modell ist bereit. Hermes-Laufzeit wird gestartet…",
                "Existing model file is present on disk" to "Vorhandene Modelldatei ist auf dem Datenträger",
                "Download file is present on disk" to "Download-Datei ist auf dem Datenträger",
                "Imported model file is missing on disk" to "Importierte Modelldatei fehlt auf dem Datenträger",
                "Android no longer reports this download" to "Android meldet diesen Download nicht mehr",
                "Imported existing model file from disk" to "Vorhandene Modelldatei vom Datenträger importiert",
                "File: " to "Datei: ",
                "Size: " to "Größe: ",
                "Phone RAM: " to "Telefon-RAM: ",
                "ABIs: " to "ABIs: ",
                "HTTP range resume is available" to "HTTP-Range-Fortsetzung ist verfügbar",
                "resume depends on server support" to "Fortsetzung hängt von Serverunterstützung ab"
            )
            AppLanguage.PORTUGUESE -> listOf(
                "Release-certified" to "Certificado para lançamento",
                "Experimental - not tested by Hermes" to "Experimental - não testado pelo Hermes",
                "Cleared Hugging Face token" to "Token do Hugging Face apagado",
                "Saved Hugging Face token for private or gated model downloads" to "Token do Hugging Face salvo para downloads privados ou restritos",
                "Tap Refresh catalog to load signed model choices when needed." to "Toque em Atualizar catálogo para carregar modelos assinados quando necessário.",
                "Refreshing signed Hugging Face model catalog…" to "Atualizando catálogo assinado de modelos do Hugging Face…",
                "Signed catalog loaded, but no downloadable model files were detected yet" to "Catálogo assinado carregado, mas nenhum arquivo de modelo baixável foi detectado ainda",
                "Signed catalog loaded with " to "Catálogo assinado carregado com ",
                " downloadable model choices" to " opções de modelo baixáveis",
                "Unable to load signed model catalog:" to "Não foi possível carregar o catálogo assinado de modelos:",
                "Importing local model from phone files…" to "Importando modelo local dos arquivos do telefone…",
                " and marked it as the preferred local model." to " e marcado como modelo local preferido.",
                "Local file" to "Arquivo local",
                "Preparing download…" to "Preparando download…",
                "Inspecting model candidate…" to "Inspecionando candidato de modelo…",
                "Model candidate inspected" to "Candidato de modelo inspecionado",
                "Queued " to "Na fila ",
                " in Android DownloadManager" to " no Android DownloadManager",
                "; Hermes will start it when Android finishes the download." to "; o Hermes vai iniciá-lo quando o Android terminar o download.",
                " is already downloaded. Starting runtime…" to " já está baixado. Iniciando runtime…",
                "Preparing " to "Preparando ",
                " from signed catalog…" to " do catálogo assinado…",
                "Restarted " to "Reiniciado ",
                " with mobile data and roaming allowed" to " com dados móveis e roaming permitidos",
                "Unable to restart this download on mobile data" to "Não foi possível reiniciar este download com dados móveis",
                "Opened Android Downloads" to "Downloads do Android abertos",
                "Android Downloads is not available on this device" to "Downloads do Android não está disponível neste dispositivo",
                "Marked this model as the preferred local runtime candidate" to "Este modelo foi marcado como candidato local preferido do runtime",
                "Preferred model is ready. Starting Hermes runtime…" to "O modelo preferido está pronto. Iniciando o runtime do Hermes…",
                "Existing model file is present on disk" to "O arquivo de modelo existente está no disco",
                "Download file is present on disk" to "O arquivo baixado está no disco",
                "Imported model file is missing on disk" to "O arquivo de modelo importado está ausente no disco",
                "Android no longer reports this download" to "O Android não informa mais este download",
                "Imported existing model file from disk" to "Arquivo de modelo existente importado do disco",
                "File: " to "Arquivo: ",
                "Size: " to "Tamanho: ",
                "Phone RAM: " to "RAM do telefone: ",
                "ABIs: " to "ABIs: ",
                "HTTP range resume is available" to "Retomada HTTP por intervalo disponível",
                "resume depends on server support" to "a retomada depende do suporte do servidor"
            )
            AppLanguage.FRENCH -> listOf(
                "Release-certified" to "Certifié pour la publication",
                "Experimental - not tested by Hermes" to "Expérimental - non testé par Hermes",
                "Cleared Hugging Face token" to "Jeton Hugging Face effacé",
                "Saved Hugging Face token for private or gated model downloads" to "Jeton Hugging Face enregistré pour les téléchargements privés ou restreints",
                "Tap Refresh catalog to load signed model choices when needed." to "Touchez Actualiser le catalogue pour charger les modèles signés au besoin.",
                "Refreshing signed Hugging Face model catalog…" to "Actualisation du catalogue signé de modèles Hugging Face…",
                "Signed catalog loaded, but no downloadable model files were detected yet" to "Catalogue signé chargé, mais aucun fichier de modèle téléchargeable n’a encore été détecté",
                "Signed catalog loaded with " to "Catalogue signé chargé avec ",
                " downloadable model choices" to " choix de modèles téléchargeables",
                "Unable to load signed model catalog:" to "Impossible de charger le catalogue signé de modèles :",
                "Importing local model from phone files…" to "Import du modèle local depuis les fichiers du téléphone…",
                " and marked it as the preferred local model." to " et défini comme modèle local préféré.",
                "Local file" to "Fichier local",
                "Preparing download…" to "Préparation du téléchargement…",
                "Inspecting model candidate…" to "Inspection du modèle candidat…",
                "Model candidate inspected" to "Modèle candidat inspecté",
                "Queued " to "Mis en file : ",
                " in Android DownloadManager" to " dans Android DownloadManager",
                "; Hermes will start it when Android finishes the download." to " ; Hermes le démarrera quand Android aura terminé le téléchargement.",
                " is already downloaded. Starting runtime…" to " est déjà téléchargé. Démarrage du runtime…",
                "Preparing " to "Préparation de ",
                " from signed catalog…" to " depuis le catalogue signé…",
                "Restarted " to "Relancé : ",
                " with mobile data and roaming allowed" to " avec données mobiles et itinérance autorisées",
                "Unable to restart this download on mobile data" to "Impossible de relancer ce téléchargement en données mobiles",
                "Opened Android Downloads" to "Téléchargements Android ouvert",
                "Android Downloads is not available on this device" to "Téléchargements Android n’est pas disponible sur cet appareil",
                "Marked this model as the preferred local runtime candidate" to "Ce modèle a été marqué comme candidat local préféré du runtime",
                "Preferred model is ready. Starting Hermes runtime…" to "Le modèle préféré est prêt. Démarrage du runtime Hermes…",
                "Existing model file is present on disk" to "Le fichier modèle existant est présent sur le disque",
                "Download file is present on disk" to "Le fichier téléchargé est présent sur le disque",
                "Imported model file is missing on disk" to "Le fichier modèle importé est absent du disque",
                "Android no longer reports this download" to "Android ne signale plus ce téléchargement",
                "Imported existing model file from disk" to "Fichier modèle existant importé depuis le disque",
                "File: " to "Fichier : ",
                "Size: " to "Taille : ",
                "Phone RAM: " to "RAM du téléphone : ",
                "ABIs: " to "ABI : ",
                "HTTP range resume is available" to "La reprise HTTP par plage est disponible",
                "resume depends on server support" to "la reprise dépend du serveur"
            )
            AppLanguage.ENGLISH -> emptyList()
        }
        var translated = text
        replacements.forEach { (source, target) ->
            translated = translated.replace(source, target)
        }
        return translated
    }

    fun localDownloadsExampleGuidance(): String = when (language) {
        AppLanguage.CHINESE -> "输入任意 Hugging Face 仓库、hf:// 仓库、仓库页面 URL、resolve URL 或直接文件 URL。Hermes 会优先尝试推断与当前运行时匹配的文件；如果仓库里没有明显的 GGUF / LiteRT-LM 文件，就会退回到另一个看起来像模型工件的文件，并把最终是否可运行交给所选后端决定。若想固定具体文件，可填写仓库内文件路径。示例：GGUF 可用 `Qwen/Qwen2.5-1.5B-Instruct-GGUF`；LiteRT-LM 可用 `litert-community/Phi-4-mini-instruct`。"
        AppLanguage.SPANISH -> "Introduce cualquier repo de Hugging Face, un repo hf://, la URL de la página del repo, una URL resolve o una URL directa al archivo. Hermes intentará priorizar un archivo nativo del runtime cuando pueda inferirlo; si el repo no expone un GGUF / LiteRT-LM claro, hará fallback a otro artefacto que parezca de modelo y dejará que el backend elegido decida si puede cargarlo. Si quieres fijar un archivo exacto, completa la ruta interna del repo. Ejemplos: GGUF `Qwen/Qwen2.5-1.5B-Instruct-GGUF`; LiteRT-LM `litert-community/Phi-4-mini-instruct`."
        AppLanguage.GERMAN -> "Gib ein beliebiges Hugging-Face-Repo, ein hf://-Repo, eine Repo-Seiten-URL, eine Resolve-URL oder eine direkte Datei-URL ein. Hermes bevorzugt nach Möglichkeit eine runtime-native Datei; wenn das Repo kein klares GGUF / LiteRT-LM-Artefakt enthält, fällt Hermes auf eine andere modellartige Datei zurück und überlässt dem gewählten Backend die endgültige Kompatibilitätsentscheidung. Wenn du eine bestimmte Datei erzwingen willst, trage den Pfad im Repo ein. Beispiele: GGUF `Qwen/Qwen2.5-1.5B-Instruct-GGUF`; LiteRT-LM `litert-community/Phi-4-mini-instruct`."
        AppLanguage.PORTUGUESE -> "Insira qualquer repositório do Hugging Face, um repositório hf://, a URL da página do repositório, uma URL resolve ou uma URL direta do arquivo. O Hermes tenta priorizar um arquivo nativo do runtime quando consegue inferi-lo; se o repositório não expuser um GGUF / LiteRT-LM claro, ele faz fallback para outro artefato com cara de modelo e deixa o backend escolhido decidir se consegue carregá-lo. Se quiser fixar um arquivo exato, preencha o caminho interno do repositório. Exemplos: GGUF `Qwen/Qwen2.5-1.5B-Instruct-GGUF`; LiteRT-LM `litert-community/Phi-4-mini-instruct`."
        AppLanguage.FRENCH -> "Saisissez n’importe quel dépôt Hugging Face, un dépôt hf://, l’URL de la page du dépôt, une URL resolve ou une URL directe de fichier. Hermes essaie de privilégier un fichier natif pour le runtime lorsqu’il peut l’inférer ; si le dépôt n’expose pas clairement un artefact GGUF / LiteRT-LM, Hermes se rabat sur un autre artefact ressemblant à un modèle et laisse le backend choisi décider s’il peut le charger. Si vous voulez forcer un fichier précis, renseignez le chemin du fichier dans le dépôt. Exemples : GGUF `Qwen/Qwen2.5-1.5B-Instruct-GGUF` ; LiteRT-LM `litert-community/Phi-4-mini-instruct`."
        AppLanguage.ENGLISH -> "Enter any Hugging Face repo, hf:// repo, repo page URL, resolve URL, or direct file URL. Hermes will try to prefer a runtime-native file when it can infer one; if the repo does not expose a clear GGUF / LiteRT-LM artifact, Hermes falls back to another likely model artifact and lets the selected backend decide whether it can load it. If you want to pin an exact file, fill in the repo file path. Examples: GGUF `Qwen/Qwen2.5-1.5B-Instruct-GGUF`; LiteRT-LM `litert-community/Phi-4-mini-instruct`."
    }

    fun downloadManagerReliabilityDescription(): String = when (language) {
        AppLanguage.CHINESE -> "意外断线会由 Android DownloadManager 安全处理。如果手机在下载过程中关机，Hermes 会在重启后重新加载已保存的进度。若移动数据一直暂停，请打开系统下载界面，或使用下方按钮在允许移动数据 / 漫游后重新开始。"
        AppLanguage.SPANISH -> "Android DownloadManager maneja con seguridad las pérdidas de conexión inesperadas. Si el teléfono se apaga a mitad de la descarga, Hermes volverá a cargar el progreso guardado al reiniciarse. Si los datos móviles siguen pausados, abre la pantalla de descargas del sistema o reinicia la descarga abajo permitiendo datos móviles / roaming."
        AppLanguage.GERMAN -> "Unerwartete Verbindungsabbrüche werden vom Android-Downloadmanager sicher behandelt. Wenn sich das Telefon mitten im Download ausschaltet, lädt Hermes den gespeicherten Fortschritt nach dem Neustart erneut. Falls mobile Daten weiter pausiert bleiben, öffne die System-Downloads oder starte den Download unten mit erlaubten mobilen Daten / Roaming neu."
        AppLanguage.PORTUGUESE -> "Perdas inesperadas de conexão são tratadas com segurança pelo Android DownloadManager. Se o telefone desligar no meio do download, o Hermes recarrega o progresso salvo após reiniciar. Se os dados móveis continuarem pausados, abra a tela de downloads do sistema ou reinicie abaixo permitindo dados móveis / roaming."
        AppLanguage.FRENCH -> "Les pertes de connexion inattendues sont gérées en toute sécurité par Android DownloadManager. Si le téléphone s’éteint pendant le téléchargement, Hermes recharge la progression enregistrée après le redémarrage. Si les données mobiles restent bloquées, ouvrez l’écran de téléchargements système ou relancez ci-dessous en autorisant les données mobiles / l’itinérance."
        AppLanguage.ENGLISH -> "Unexpected connection loss is handled safely by Android DownloadManager. If the phone shuts down mid-download, Hermes reloads the saved progress after restart. If mobile data stays paused, open the system Downloads screen or restart below with mobile data / roaming allowed."
    }

    fun localDownloadStatusLabel(status: String): String {
        return when (status.trim().lowercase()) {
            "queued" -> when (language) {
                AppLanguage.CHINESE -> "排队中"
                AppLanguage.SPANISH -> "En cola"
                AppLanguage.GERMAN -> "In Warteschlange"
                AppLanguage.PORTUGUESE -> "Na fila"
                AppLanguage.FRENCH -> "En file d’attente"
                AppLanguage.ENGLISH -> "Queued"
            }
            "downloading" -> when (language) {
                AppLanguage.CHINESE -> "下载中"
                AppLanguage.SPANISH -> "Descargando"
                AppLanguage.GERMAN -> "Wird heruntergeladen"
                AppLanguage.PORTUGUESE -> "Baixando"
                AppLanguage.FRENCH -> "Téléchargement"
                AppLanguage.ENGLISH -> "Downloading"
            }
            "paused" -> when (language) {
                AppLanguage.CHINESE -> "已暂停"
                AppLanguage.SPANISH -> "Pausado"
                AppLanguage.GERMAN -> "Pausiert"
                AppLanguage.PORTUGUESE -> "Pausado"
                AppLanguage.FRENCH -> "En pause"
                AppLanguage.ENGLISH -> "Paused"
            }
            "completed" -> when (language) {
                AppLanguage.CHINESE -> "已完成"
                AppLanguage.SPANISH -> "Completado"
                AppLanguage.GERMAN -> "Abgeschlossen"
                AppLanguage.PORTUGUESE -> "Concluído"
                AppLanguage.FRENCH -> "Terminé"
                AppLanguage.ENGLISH -> "Completed"
            }
            "failed" -> when (language) {
                AppLanguage.CHINESE -> "失败"
                AppLanguage.SPANISH -> "Falló"
                AppLanguage.GERMAN -> "Fehlgeschlagen"
                AppLanguage.PORTUGUESE -> "Falhou"
                AppLanguage.FRENCH -> "Échec"
                AppLanguage.ENGLISH -> "Failed"
            }
            "missing" -> when (language) {
                AppLanguage.CHINESE -> "缺失"
                AppLanguage.SPANISH -> "Falta"
                AppLanguage.GERMAN -> "Fehlt"
                AppLanguage.PORTUGUESE -> "Ausente"
                AppLanguage.FRENCH -> "Manquant"
                AppLanguage.ENGLISH -> "Missing"
            }
            else -> status
        }
    }

    fun localDownloadStatusLine(runtimeFlavor: String, status: String): String {
        return "$runtimeFlavor · ${localDownloadStatusLabel(status)}"
    }

    fun restartOnMobileData(): String = when (language) {
        AppLanguage.CHINESE -> "通过移动数据重新开始"
        AppLanguage.SPANISH -> "Reiniciar con datos móviles"
        AppLanguage.GERMAN -> "Über mobile Daten neu starten"
        AppLanguage.PORTUGUESE -> "Reiniciar com dados móveis"
        AppLanguage.FRENCH -> "Redémarrer via les données mobiles"
        AppLanguage.ENGLISH -> "Restart on mobile data"
    }

    fun openSystemDownloads(): String = when (language) {
        AppLanguage.CHINESE -> "打开系统下载"
        AppLanguage.SPANISH -> "Abrir descargas del sistema"
        AppLanguage.GERMAN -> "System-Downloads öffnen"
        AppLanguage.PORTUGUESE -> "Abrir downloads do sistema"
        AppLanguage.FRENCH -> "Ouvrir les téléchargements système"
        AppLanguage.ENGLISH -> "Open system Downloads"
    }

    fun quickLocalModelsTitle(): String = when (language) {
        AppLanguage.CHINESE -> "一键本地模型"
        AppLanguage.SPANISH -> "Modelos locales con un toque"
        AppLanguage.GERMAN -> "Lokale Modelle mit einem Tipp"
        AppLanguage.PORTUGUESE -> "Modelos locais com um toque"
        AppLanguage.FRENCH -> "Modèles locaux en un geste"
        AppLanguage.ENGLISH -> "One-tap local models"
    }

    fun quickLocalModelsDescription(): String = when (language) {
        AppLanguage.CHINESE -> "选择已验证的移动模型。Hermes 会下载、设为首选，并在文件准备好后自动启动本地运行时。"
        AppLanguage.SPANISH -> "Elige un modelo móvil validado. Hermes lo descarga, lo marca como preferido e inicia el runtime local cuando el archivo está listo."
        AppLanguage.GERMAN -> "Wähle ein validiertes Mobilmodell. Hermes lädt es, markiert es als bevorzugt und startet die lokale Laufzeit, sobald die Datei bereit ist."
        AppLanguage.PORTUGUESE -> "Escolha um modelo móvel validado. O Hermes baixa, marca como preferido e inicia o runtime local quando o arquivo estiver pronto."
        AppLanguage.FRENCH -> "Choisissez un modèle mobile validé. Hermes le télécharge, le marque comme préféré et démarre le runtime local dès que le fichier est prêt."
        AppLanguage.ENGLISH -> "Choose a validated mobile model. Hermes downloads it, marks it preferred, and starts the local runtime when the file is ready."
    }

    fun detectedModelCatalogTitle(): String = when (language) {
        AppLanguage.CHINESE -> "已检测模型目录"
        AppLanguage.SPANISH -> "Catálogo de modelos detectados"
        AppLanguage.GERMAN -> "Erkannter Modellkatalog"
        AppLanguage.PORTUGUESE -> "Catálogo de modelos detectados"
        AppLanguage.FRENCH -> "Catalogue de modèles détectés"
        AppLanguage.ENGLISH -> "Detected model catalog"
    }

    fun detectedModelCatalogDescription(): String = when (language) {
        AppLanguage.CHINESE -> "从已签名的 Cloudflare 目录选择一个模型。Hermes 会验证签名，然后通过 Hugging Face 下载所选文件。"
        AppLanguage.SPANISH -> "Elige un modelo del catálogo firmado de Cloudflare. Hermes verifica la firma y descarga el archivo seleccionado desde Hugging Face."
        AppLanguage.GERMAN -> "Wähle ein Modell aus dem signierten Cloudflare-Katalog. Hermes prüft die Signatur und lädt die ausgewählte Datei von Hugging Face."
        AppLanguage.PORTUGUESE -> "Escolha um modelo do catálogo assinado da Cloudflare. O Hermes verifica a assinatura e baixa o arquivo selecionado pelo Hugging Face."
        AppLanguage.FRENCH -> "Choisissez un modèle dans le catalogue Cloudflare signé. Hermes vérifie la signature puis télécharge le fichier choisi depuis Hugging Face."
        AppLanguage.ENGLISH -> "Choose a model from the signed Cloudflare catalog. Hermes verifies the signature, then downloads the selected file from Hugging Face."
    }

    fun detectedModelDropdownPlaceholder(): String = when (language) {
        AppLanguage.CHINESE -> "选择检测到的模型"
        AppLanguage.SPANISH -> "Elegir modelo detectado"
        AppLanguage.GERMAN -> "Erkanntes Modell wählen"
        AppLanguage.PORTUGUESE -> "Escolher modelo detectado"
        AppLanguage.FRENCH -> "Choisir un modèle détecté"
        AppLanguage.ENGLISH -> "Choose detected model"
    }

    fun refreshCatalog(): String = when (language) {
        AppLanguage.CHINESE -> "刷新目录"
        AppLanguage.SPANISH -> "Actualizar catálogo"
        AppLanguage.GERMAN -> "Katalog aktualisieren"
        AppLanguage.PORTUGUESE -> "Atualizar catálogo"
        AppLanguage.FRENCH -> "Actualiser le catalogue"
        AppLanguage.ENGLISH -> "Refresh catalog"
    }

    fun downloadAndStart(): String = when (language) {
        AppLanguage.CHINESE -> "下载并启动"
        AppLanguage.SPANISH -> "Descargar e iniciar"
        AppLanguage.GERMAN -> "Herunterladen und starten"
        AppLanguage.PORTUGUESE -> "Baixar e iniciar"
        AppLanguage.FRENCH -> "Télécharger et démarrer"
        AppLanguage.ENGLISH -> "Download and start"
    }

    fun useAndStart(): String = when (language) {
        AppLanguage.CHINESE -> "使用并启动"
        AppLanguage.SPANISH -> "Usar e iniciar"
        AppLanguage.GERMAN -> "Verwenden und starten"
        AppLanguage.PORTUGUESE -> "Usar e iniciar"
        AppLanguage.FRENCH -> "Utiliser et démarrer"
        AppLanguage.ENGLISH -> "Use and start"
    }

    fun startRuntime(): String = when (language) {
        AppLanguage.CHINESE -> "启动运行时"
        AppLanguage.SPANISH -> "Iniciar runtime"
        AppLanguage.GERMAN -> "Laufzeit starten"
        AppLanguage.PORTUGUESE -> "Iniciar runtime"
        AppLanguage.FRENCH -> "Démarrer le runtime"
        AppLanguage.ENGLISH -> "Start runtime"
    }

    fun remoteFallbackTitle(): String = when (language) {
        AppLanguage.CHINESE -> "远程备用"
        AppLanguage.SPANISH -> "Respaldo remoto"
        AppLanguage.GERMAN -> "Remote-Fallback"
        AppLanguage.PORTUGUESE -> "Fallback remoto"
        AppLanguage.FRENCH -> "Secours distant"
        AppLanguage.ENGLISH -> "Remote fallback"
    }

    fun remoteFallbackDescription(): String = when (language) {
        AppLanguage.CHINESE -> "本地模型不可用时，Hermes 可以使用远程 OpenAI 兼容提供商。点一个提供商即可填入常用默认值；设置会打开官方密钥或登录页面。"
        AppLanguage.SPANISH -> "Cuando no haya un modelo local disponible, Hermes puede usar un proveedor remoto compatible con OpenAI. Toca un proveedor para rellenar valores comunes; la configuración abre la página oficial de claves o inicio de sesión."
        AppLanguage.GERMAN -> "Wenn kein lokales Modell verfügbar ist, kann Hermes einen OpenAI-kompatiblen Remote-Anbieter nutzen. Tippe auf einen Anbieter, um Standardwerte einzutragen; die Einrichtung öffnet die offizielle Schlüssel- oder Anmeldeseite."
        AppLanguage.PORTUGUESE -> "Quando não houver modelo local disponível, o Hermes pode usar um provedor remoto compatível com OpenAI. Toque em um provedor para preencher padrões comuns; a configuração abre a página oficial de chaves ou login."
        AppLanguage.FRENCH -> "Quand aucun modèle local n’est disponible, Hermes peut utiliser un fournisseur distant compatible OpenAI. Touchez un fournisseur pour remplir les valeurs courantes ; la configuration ouvre la page officielle de clés ou de connexion."
        AppLanguage.ENGLISH -> "When no local model is available, Hermes can use a remote OpenAI-compatible provider. Tap a provider to fill common defaults; setup opens the official key or sign-in page."
    }

    fun remoteOnly(): String = when (language) {
        AppLanguage.CHINESE -> "仅远程"
        AppLanguage.SPANISH -> "Solo remoto"
        AppLanguage.GERMAN -> "Nur remote"
        AppLanguage.PORTUGUESE -> "Somente remoto"
        AppLanguage.FRENCH -> "Distant uniquement"
        AppLanguage.ENGLISH -> "Remote only"
    }

    fun gemma4MtpTitle(): String = when (language) {
        AppLanguage.CHINESE -> "Gemma 4 MTP"
        AppLanguage.SPANISH -> "Gemma 4 MTP"
        AppLanguage.GERMAN -> "Gemma 4 MTP"
        AppLanguage.PORTUGUESE -> "Gemma 4 MTP"
        AppLanguage.FRENCH -> "Gemma 4 MTP"
        AppLanguage.ENGLISH -> "Gemma 4 MTP"
    }

    fun gemma4MtpDescription(): String = when (language) {
        AppLanguage.CHINESE -> "控制 LiteRT-LM 的 Gemma 4 多 token 预测。自动会在支持的 ARM64 设备上启用，并在失败时回退。"
        AppLanguage.SPANISH -> "Controla la predicción multitoken Gemma 4 de LiteRT-LM. Auto la activa en dispositivos ARM64 compatibles y vuelve atrás si falla."
        AppLanguage.GERMAN -> "Steuert LiteRT-LM Gemma 4 Multi-Token Prediction. Auto aktiviert sie auf unterstützten ARM64-Geräten und fällt bei Fehlern zurück."
        AppLanguage.PORTUGUESE -> "Controla a previsão multitoken Gemma 4 do LiteRT-LM. Auto ativa em dispositivos ARM64 compatíveis e recua se falhar."
        AppLanguage.FRENCH -> "Contrôle la prédiction multi-jetons Gemma 4 de LiteRT-LM. Auto l’active sur les appareils ARM64 compatibles et revient en arrière en cas d’échec."
        AppLanguage.ENGLISH -> "Controls LiteRT-LM Gemma 4 multi-token prediction. Auto enables it on supported ARM64 devices and falls back if initialization fails."
    }

    fun gemma4MtpAutoLabel(): String = when (language) {
        AppLanguage.CHINESE -> "自动"
        AppLanguage.SPANISH -> "Auto"
        AppLanguage.GERMAN -> "Auto"
        AppLanguage.PORTUGUESE -> "Auto"
        AppLanguage.FRENCH -> "Auto"
        AppLanguage.ENGLISH -> "Auto"
    }

    fun gemma4MtpEnabledLabel(): String = when (language) {
        AppLanguage.CHINESE -> "开启"
        AppLanguage.SPANISH -> "Activado"
        AppLanguage.GERMAN -> "Ein"
        AppLanguage.PORTUGUESE -> "Ativado"
        AppLanguage.FRENCH -> "Activé"
        AppLanguage.ENGLISH -> "On"
    }

    fun gemma4MtpDisabledLabel(): String = when (language) {
        AppLanguage.CHINESE -> "关闭"
        AppLanguage.SPANISH -> "Desactivado"
        AppLanguage.GERMAN -> "Aus"
        AppLanguage.PORTUGUESE -> "Desativado"
        AppLanguage.FRENCH -> "Désactivé"
        AppLanguage.ENGLISH -> "Off"
    }

    fun authBaseUrlMustBeValid(): String = when (language) {
        AppLanguage.CHINESE -> "Corr3xt 基础 URL 必须是有效的 http(s) 地址"
        AppLanguage.SPANISH -> "La URL base de Corr3xt debe ser una URL http(s) válida"
        AppLanguage.GERMAN -> "Die Corr3xt-Basis-URL muss eine gültige http(s)-URL sein"
        AppLanguage.PORTUGUESE -> "A URL base do Corr3xt deve ser uma URL http(s) válida"
        AppLanguage.FRENCH -> "L’URL de base Corr3xt doit être une URL http(s) valide"
        AppLanguage.ENGLISH -> "Corr3xt base URL must be a valid http(s) URL"
    }

    fun authSavedBaseUrl(): String = when (language) {
        AppLanguage.CHINESE -> "已保存 Corr3xt 基础 URL"
        AppLanguage.SPANISH -> "URL base de Corr3xt guardada"
        AppLanguage.GERMAN -> "Corr3xt-Basis-URL gespeichert"
        AppLanguage.PORTUGUESE -> "URL base do Corr3xt salva"
        AppLanguage.FRENCH -> "URL de base Corr3xt enregistrée"
        AppLanguage.ENGLISH -> "Saved Corr3xt base URL"
    }

    fun authOpenedCorr3xt(label: String): String = when (language) {
        AppLanguage.CHINESE -> "已打开 Corr3xt 进行 $label 登录。如果浏览器卡住，请复制登录链接并粘贴到其他浏览器。"
        AppLanguage.SPANISH -> "Corr3xt abierto para iniciar sesión con $label. Si el navegador se queda bloqueado, copia la URL de inicio de sesión y pégala en otro navegador."
        AppLanguage.GERMAN -> "Corr3xt für die Anmeldung mit $label geöffnet. Wenn der Browser hängen bleibt, kopiere die Anmelde-URL und füge sie in einem anderen Browser ein."
        AppLanguage.PORTUGUESE -> "Corr3xt aberto para login com $label. Se o navegador travar, copie a URL de login e cole em outro navegador."
        AppLanguage.FRENCH -> "Corr3xt ouvert pour la connexion avec $label. Si le navigateur se bloque, copiez l’URL de connexion et collez-la dans un autre navigateur."
        AppLanguage.ENGLISH -> "Opened Corr3xt for $label sign-in. If your browser stalls, copy the sign-in URL and paste it into another browser."
    }

    fun languageSwitchedTo(label: String): String = when (language) {
        AppLanguage.CHINESE -> "界面语言已切换为 $label"
        AppLanguage.SPANISH -> "Idioma cambiado a $label"
        AppLanguage.GERMAN -> "Sprache auf $label umgestellt"
        AppLanguage.PORTUGUESE -> "Idioma alterado para $label"
        AppLanguage.FRENCH -> "Langue changée en $label"
        AppLanguage.ENGLISH -> "Language switched to $label"
    }

    fun selectedLanguageDescription(label: String): String = tr(
        "Selected language $label", "已选择语言 $label", "Idioma seleccionado: $label",
        "Ausgewählte Sprache: $label", "Idioma selecionado: $label", "Langue sélectionnée : $label",
    )

    fun switchLanguageDescription(label: String): String = tr(
        "Switch language to $label", "切换语言为 $label", "Cambiar idioma a $label",
        "Sprache auf $label umstellen", "Alterar idioma para $label", "Passer la langue à $label",
    )

    fun kanbanTitle(): String = tr("Kanban", "看板", "Kanban", "Kanban", "Kanban", "Kanban")
    fun kanbanDescription(): String = tr(
        "Human board control for the shared Hermes kanban DB. Workers still need the gateway dispatcher.",
        "管理共享 Hermes 看板数据库。工作代理仍需网关调度器。",
        "Control humano del tablero compartido de Hermes. Los agentes aún necesitan el despachador de la pasarela.",
        "Manuelle Steuerung des gemeinsamen Hermes-Kanban-Boards. Worker benötigen weiterhin den Gateway-Dispatcher.",
        "Controle humano do quadro Hermes compartilhado. Os agentes ainda precisam do despachante do gateway.",
        "Contrôle humain du tableau Hermes partagé. Les agents ont encore besoin du répartiteur de passerelle.",
    )
    fun kanbanRefresh(): String = tr("Refresh board", "刷新看板", "Actualizar tablero", "Board aktualisieren", "Atualizar quadro", "Actualiser le tableau")
    fun kanbanRefreshDescription(): String = tr(
        "Reload tasks from the shared SQLite kanban DB", "从共享 SQLite 看板数据库重新加载任务",
        "Volver a cargar las tareas de la base SQLite compartida", "Aufgaben aus der gemeinsamen SQLite-Kanban-Datenbank neu laden",
        "Recarregar tarefas do banco SQLite compartilhado", "Recharger les tâches depuis la base SQLite partagée",
    )
    fun kanbanFilter(status: String): String = when (status.lowercase()) {
        "all" -> tr("all", "全部", "todas", "alle", "todas", "toutes")
        "ready" -> tr("ready", "就绪", "listas", "bereit", "prontas", "prêtes")
        "running" -> tr("running", "运行中", "en curso", "laufend", "em execução", "en cours")
        "blocked" -> tr("blocked", "已阻止", "bloqueadas", "blockiert", "bloqueadas", "bloquées")
        "todo" -> tr("todo", "待办", "pendientes", "offen", "a fazer", "à faire")
        "triage" -> tr("triage", "分类", "clasificación", "Triage", "triagem", "triage")
        "done" -> tr("done", "已完成", "hechas", "erledigt", "concluídas", "terminées")
        else -> status
    }
    fun kanbanNewTask(): String = tr("New task", "新任务", "Nueva tarea", "Neue Aufgabe", "Nova tarefa", "Nouvelle tâche")
    fun kanbanTaskTitle(): String = tr("Title", "标题", "Título", "Titel", "Título", "Titre")
    fun kanbanTaskDetails(): String = tr("Details (optional)", "详情（可选）", "Detalles (opcional)", "Details (optional)", "Detalhes (opcional)", "Détails (facultatif)")
    fun kanbanCreateTask(): String = tr("Create task", "创建任务", "Crear tarea", "Aufgabe erstellen", "Criar tarefa", "Créer la tâche")
    fun kanbanNoTasks(): String = tr(
        "No tasks yet. Create one above, or complete agent work that writes to the shared board.",
        "暂无任务。可在上方创建，或完成会写入共享看板的代理工作。",
        "Aún no hay tareas. Crea una arriba o completa trabajo del agente que escriba en el tablero compartido.",
        "Noch keine Aufgaben. Erstelle oben eine oder schließe Agentenarbeit ab, die in das gemeinsame Board schreibt.",
        "Ainda não há tarefas. Crie uma acima ou conclua trabalho do agente que grave no quadro compartilhado.",
        "Aucune tâche pour le moment. Créez-en une ci-dessus ou terminez un travail d’agent qui alimente le tableau partagé.",
    )
    fun kanbanUnblock(): String = tr("Unblock", "解除阻止", "Desbloquear", "Entsperren", "Desbloquear", "Débloquer")
    fun kanbanComplete(): String = tr("Complete", "完成", "Completar", "Abschließen", "Concluir", "Terminer")
    fun kanbanComment(): String = tr("Comment", "评论", "Comentario", "Kommentar", "Comentário", "Commentaire")
    fun kanbanAdd(): String = tr("Add", "添加", "Añadir", "Hinzufügen", "Adicionar", "Ajouter")
    fun kanbanRuntimeText(text: String): String = when (text.trim()) {
        "Waiting for Hermes runtime…" -> tr("Waiting for Hermes runtime…", "正在等待 Hermes 运行时…", "Esperando el runtime de Hermes…", "Warten auf die Hermes-Laufzeit…", "Aguardando o runtime do Hermes…", "En attente du runtime Hermes…")
        "Waiting for Hermes Python runtime…" -> tr("Waiting for Hermes Python runtime…", "正在等待 Hermes Python 运行时…", "Esperando el runtime Python de Hermes…", "Warten auf die Hermes-Python-Laufzeit…", "Aguardando o runtime Python do Hermes…", "En attente du runtime Python Hermes…")
        "Board refreshed" -> tr("Board refreshed", "看板已刷新", "Tablero actualizado", "Board aktualisiert", "Quadro atualizado", "Tableau actualisé")
        "Task created" -> tr("Task created", "任务已创建", "Tarea creada", "Aufgabe erstellt", "Tarefa criada", "Tâche créée")
        "Updated" -> tr("Updated", "已更新", "Actualizado", "Aktualisiert", "Atualizado", "Mis à jour")
        "Title is required" -> tr("Title is required", "标题为必填项", "El título es obligatorio", "Titel ist erforderlich", "O título é obrigatório", "Le titre est obligatoire")
        "Shared SQLite board. Multi-agent workers still need gateway dispatch." -> kanbanDescription()
        "Mobile Kanban controls the shared SQLite board. Worker spawn still requires gateway/dispatcher." -> kanbanDescription()
        else -> text
    }

    fun localMemoryTitle(): String = tr("Local memory (hy-memory)", "本地记忆（hy-memory）", "Memoria local (hy-memory)", "Lokaler Speicher (hy-memory)", "Memória local (hy-memory)", "Mémoire locale (hy-memory)")
    fun localMemoryDescription(): String = tr(
        "On-device retain/recall used by the agent (`hy_memory_tool`). Facts stay on this phone.",
        "代理使用的设备端记忆与回忆（`hy_memory_tool`）。事实仅保留在此手机上。",
        "Memoria y recuperación en el dispositivo que usa el agente (`hy_memory_tool`). Los datos permanecen en este teléfono.",
        "Lokales Speichern und Abrufen durch den Agenten (`hy_memory_tool`). Fakten bleiben auf diesem Gerät.",
        "Memória e recuperação no dispositivo usadas pelo agente (`hy_memory_tool`). Os fatos ficam neste telefone.",
        "Mémorisation et rappel sur l’appareil utilisés par l’agent (`hy_memory_tool`). Les faits restent sur ce téléphone.",
    )
    fun loadingLabel(): String = tr("Loading…", "正在加载…", "Cargando…", "Wird geladen…", "Carregando…", "Chargement…")
    fun noStatusLabel(): String = tr("No status", "无状态", "Sin estado", "Kein Status", "Sem status", "Aucun état")
    fun reinforcedAndPromoted(reinforced: Int, promoted: Int): String = tr(
        "Reinforced $reinforced · Promoted $promoted", "已强化 $reinforced · 已提升 $promoted", "Reforzados $reinforced · Promovidos $promoted",
        "Verstärkt $reinforced · Hochgestuft $promoted", "Reforçados $reinforced · Promovidos $promoted", "Renforcés $reinforced · Promus $promoted",
    )
    fun clearAllLabel(): String = tr("Clear all", "全部清除", "Borrar todo", "Alle löschen", "Limpar tudo", "Tout effacer")
    fun localMemoryEmpty(): String = tr(
        "No retained memories yet. Chat facts the agent stores will appear here.", "尚无保留的记忆。代理在聊天中保存的事实将显示在此处。",
        "Aún no hay recuerdos guardados. Aquí aparecerán los datos que guarde el agente durante el chat.", "Noch keine gespeicherten Erinnerungen. Vom Agenten im Chat gespeicherte Fakten erscheinen hier.",
        "Ainda não há memórias salvas. Os fatos guardados pelo agente no chat aparecerão aqui.", "Aucun souvenir conservé. Les faits enregistrés par l’agent pendant le chat apparaîtront ici.",
    )
    fun memoryHits(count: Int, promoted: Boolean): String = tr(
        "hits $count${if (promoted) " · promoted" else ""}", "命中 $count${if (promoted) " · 已提升" else ""}",
        "usos $count${if (promoted) " · promovido" else ""}", "Treffer $count${if (promoted) " · hochgestuft" else ""}",
        "usos $count${if (promoted) " · promovido" else ""}", "rappels $count${if (promoted) " · promu" else ""}",
    )
    fun deleteLabel(): String = tr("Delete", "删除", "Eliminar", "Löschen", "Excluir", "Supprimer")
    fun localMemoryStatusText(text: String): String {
        val count = Regex("hy-memory local companion · (\\d+) facts").matchEntire(text.trim())?.groupValues?.get(1)
            ?: return text
        return tr("hy-memory local companion · $count facts", "hy-memory 本地助手 · $count 条事实", "Compañero local hy-memory · $count datos", "Lokaler hy-memory-Begleiter · $count Fakten", "Companheiro local hy-memory · $count fatos", "Compagnon local hy-memory · $count faits")
    }

    fun automationsTitle(): String = tr("Phone automations", "手机自动化", "Automatizaciones del teléfono", "Telefon-Automatisierungen", "Automações do telefone", "Automatisations du téléphone")
    fun automationsDescription(): String = tr(
        "Scheduled and event-driven tasks on this device (not gateway cron). Enable, run, or delete here.",
        "此设备上的定时和事件驱动任务（不是网关 cron）。可在此启用、运行或删除。",
        "Tareas programadas y activadas por eventos en este dispositivo (no el cron de la pasarela). Actívalas, ejecútalas o elimínalas aquí.",
        "Geplante und ereignisgesteuerte Aufgaben auf diesem Gerät (nicht Gateway-Cron). Hier aktivieren, ausführen oder löschen.",
        "Tarefas agendadas e acionadas por eventos neste dispositivo (não o cron do gateway). Ative, execute ou exclua aqui.",
        "Tâches planifiées et déclenchées par des événements sur cet appareil (pas le cron de la passerelle). Activez-les, exécutez-les ou supprimez-les ici.",
    )
    fun noAutomations(): String = tr("No automations", "无自动化", "Sin automatizaciones", "Keine Automatisierungen", "Sem automações", "Aucune automatisation")
    fun automationsEmpty(): String = tr(
        "No automations yet. Ask Hermes to schedule a task or create one via agent tools.", "暂无自动化。请让 Hermes 安排任务，或通过代理工具创建一个。",
        "Aún no hay automatizaciones. Pide a Hermes que programe una tarea o crea una con las herramientas del agente.", "Noch keine Automatisierungen. Bitte Hermes, eine Aufgabe zu planen, oder erstelle sie mit Agentenwerkzeugen.",
        "Ainda não há automações. Peça ao Hermes para agendar uma tarefa ou crie uma pelas ferramentas do agente.", "Aucune automatisation. Demandez à Hermes de planifier une tâche ou créez-en une avec les outils de l’agent.",
    )
    fun onLabel(): String = tr("On", "开", "Activado", "Ein", "Ligado", "Activé")
    fun offLabel(): String = tr("Off", "关", "Desactivado", "Aus", "Desligado", "Désactivé")
    fun runLabel(): String = tr("Run", "运行", "Ejecutar", "Ausführen", "Executar", "Exécuter")
    fun automationsStatusText(text: String): String {
        val count = Regex("(\\d+) automation\\(s\\) on device").matchEntire(text.trim())?.groupValues?.get(1)
            ?: return text
        return tr("$count automation(s) on device", "设备上有 $count 个自动化", "$count automatizaciones en el dispositivo", "$count Automatisierungen auf dem Gerät", "$count automações no dispositivo", "$count automatisations sur l’appareil")
    }

    fun skillsTitle(): String = tr("Skills", "技能", "Habilidades", "Skills", "Habilidades", "Compétences")
    fun skillsDescription(): String = tr(
        "Installed Hermes skills from hermes-home and bundled skill directories.", "来自 hermes-home 和内置技能目录的已安装 Hermes 技能。",
        "Habilidades de Hermes instaladas desde hermes-home y los directorios incluidos.", "Installierte Hermes-Skills aus hermes-home und den mitgelieferten Skill-Verzeichnissen.",
        "Habilidades do Hermes instaladas do hermes-home e dos diretórios incluídos.", "Compétences Hermes installées depuis hermes-home et les répertoires intégrés.",
    )
    fun skillsEmpty(): String = tr(
        "No skills found yet. They appear after Hermes boot syncs bundled skills.", "尚未找到技能。Hermes 启动并同步内置技能后会显示在此处。",
        "Aún no se encontraron habilidades. Aparecerán después de que Hermes sincronice las incluidas al arrancar.", "Noch keine Skills gefunden. Sie erscheinen, nachdem Hermes beim Start die mitgelieferten Skills synchronisiert hat.",
        "Nenhuma habilidade encontrada. Elas aparecem depois que o Hermes sincroniza as habilidades incluídas ao iniciar.", "Aucune compétence trouvée. Elles apparaîtront après la synchronisation au démarrage de Hermes.",
    )
    fun skillsStatusText(text: String): String {
        val refreshed = Regex("Skills refreshed \\((\\d+)\\)").matchEntire(text.trim())?.groupValues?.get(1)
        if (refreshed != null) return tr("Skills refreshed ($refreshed)", "技能已刷新（$refreshed）", "Habilidades actualizadas ($refreshed)", "Skills aktualisiert ($refreshed)", "Habilidades atualizadas ($refreshed)", "Compétences actualisées ($refreshed)")
        return when (text.trim()) {
            "Waiting for Hermes Python runtime…" -> kanbanRuntimeText(text)
            else -> text
        }
    }
    fun streamableHttpMcpTitle(): String = "Streamable HTTP MCP"
    fun streamableHttpMcpDescription(): String = tr(
        "Streamable HTTP MCP is unavailable in the embedded Android runtime; stored URLs and tokens are not loaded or sent.",
        "Streamable HTTP MCP 在嵌入式 Android 运行时中不可用；已存储的 URL 和令牌不会被加载或发送。",
        "MCP Streamable HTTP no está disponible en el runtime Android integrado; las URL y los tokens guardados no se cargan ni se envían.",
        "Streamable-HTTP-MCP ist in der eingebetteten Android-Laufzeit nicht verfügbar; gespeicherte URLs und Tokens werden weder geladen noch gesendet.",
        "MCP Streamable HTTP não está disponível no runtime Android integrado; URLs e tokens salvos não são carregados nem enviados.",
        "MCP Streamable HTTP n’est pas disponible dans le runtime Android intégré ; les URL et jetons enregistrés ne sont ni chargés ni envoyés.",
    )
    fun mcpServerUrlLabel(): String = tr("MCP server URL", "MCP 服务器 URL", "URL del servidor MCP", "MCP-Server-URL", "URL do servidor MCP", "URL du serveur MCP")
    fun optionalApiTokenLabel(): String = tr("API token (optional)", "API 令牌（可选）", "Token API (opcional)", "API-Token (optional)", "Token de API (opcional)", "Jeton API (facultatif)")

    fun settingsPageLabel(page: String): String = when (page) {
        "Models" -> tr("Models", "模型", "Modelos", "Modelle", "Modelos", "Modèles")
        "Theme" -> tr("Theme", "主题", "Tema", "Design", "Tema", "Thème")
        "Tools" -> tr("Tools", "工具", "Herramientas", "Werkzeuge", "Ferramentas", "Outils")
        else -> tr("General", "常规", "General", "Allgemein", "Geral", "Général")
    }
    fun settingsBreadcrumb(page: String): String = "${sectionSettings}  ›  ${settingsPageLabel(page)}"
    fun showStepsLabel(): String = tr("Show steps", "显示步骤", "Mostrar pasos", "Schritte anzeigen", "Mostrar etapas", "Afficher les étapes")
    fun hideStepsLabel(): String = tr("Hide steps", "隐藏步骤", "Ocultar pasos", "Schritte ausblenden", "Ocultar etapas", "Masquer les étapes")
    fun stopLabel(): String = tr("Stop", "停止", "Detener", "Stopp", "Parar", "Arrêter")
    fun eventTypeLabel(type: String): String = when (type) {
        "thought" -> tr("Think", "思考", "Pensamiento", "Denken", "Pensamento", "Réflexion")
        "tool_call" -> tr("Tool call", "工具调用", "Llamada de herramienta", "Werkzeugaufruf", "Chamada de ferramenta", "Appel d’outil")
        "tool_result" -> tr("Tool result", "工具结果", "Resultado de herramienta", "Werkzeugergebnis", "Resultado da ferramenta", "Résultat de l’outil")
        "file_access" -> tr("File access", "文件访问", "Acceso a archivo", "Dateizugriff", "Acesso a arquivo", "Accès au fichier")
        "process_log" -> tr("Process log", "进程日志", "Registro del proceso", "Prozessprotokoll", "Log do processo", "Journal du processus")
        else -> tr("Answer", "回答", "Respuesta", "Antwort", "Resposta", "Réponse")
    }
    fun terminalTitle(): String = tr("Manual Linux terminal", "手动 Linux 终端", "Terminal Linux manual", "Manuelles Linux-Terminal", "Terminal Linux manual", "Terminal Linux manuel")
    fun terminalDescription(): String = tr(
        "Run host commands directly. For PRoot, use proot-distro list or proot-distro login <name> -- /bin/sh -lc 'uname -a'.",
        "直接运行主机命令。PRoot 可使用 proot-distro list 或 proot-distro login <名称> -- /bin/sh -lc 'uname -a'。",
        "Ejecuta comandos del host directamente. Para PRoot usa proot-distro list o proot-distro login <nombre> -- /bin/sh -lc 'uname -a'.",
        "Führe Host-Befehle direkt aus. Für PRoot: proot-distro list oder proot-distro login <Name> -- /bin/sh -lc 'uname -a'.",
        "Execute comandos do host diretamente. Para PRoot use proot-distro list ou proot-distro login <nome> -- /bin/sh -lc 'uname -a'.",
        "Exécutez directement des commandes hôte. Pour PRoot : proot-distro list ou proot-distro login <nom> -- /bin/sh -lc 'uname -a'.",
    )
    fun signalToolsToggleLabel(showing: Boolean): String = if (showing) {
        tr("Hide signal tools", "隐藏信号工具", "Ocultar herramientas de señal", "Signalwerkzeuge ausblenden", "Ocultar ferramentas de sinal", "Masquer les outils de signal")
    } else {
        tr("Show signal tools", "显示信号工具", "Mostrar herramientas de señal", "Signalwerkzeuge anzeigen", "Mostrar ferramentas de sinal", "Afficher les outils de signal")
    }
    fun commandLabel(): String = tr("Command", "命令", "Comando", "Befehl", "Comando", "Commande")
    fun runningLabel(): String = tr("Running…", "正在运行…", "Ejecutando…", "Läuft…", "Executando…", "Exécution…")
    fun noCommandOutputLabel(): String = tr("(no output)", "（无输出）", "(sin salida)", "(keine Ausgabe)", "(sem saída)", "(aucune sortie)")
    fun commandFailedLabel(): String = tr("Command failed", "命令失败", "El comando falló", "Befehl fehlgeschlagen", "Falha no comando", "Échec de la commande")
    fun exitCodeLabel(code: Int): String = tr("Exit code $code", "退出码 $code", "Código de salida $code", "Exit-Code $code", "Código de saída $code", "Code de sortie $code")
    fun terminalSandboxSessionLabel(name: String): String = tr(
        "Linux session · $name", "Linux 会话 · $name", "Sesión Linux · $name",
        "Linux-Sitzung · $name", "Sessão Linux · $name", "Session Linux · $name",
    )
    fun terminalSandboxSessionOpened(): String = tr(
        "Session opened. Following commands run inside this sandbox; type exit to return to the host.",
        "会话已打开。后续命令将在此沙箱中运行；输入 exit 返回主机。",
        "Sesión abierta. Los comandos siguientes se ejecutan en este sandbox; escribe exit para volver al host.",
        "Sitzung geöffnet. Folgende Befehle laufen in dieser Sandbox; mit exit geht es zum Host zurück.",
        "Sessão aberta. Os próximos comandos são executados neste sandbox; digite exit para voltar ao host.",
        "Session ouverte. Les commandes suivantes s’exécutent dans ce bac à sable ; saisissez exit pour revenir à l’hôte.",
    )
    fun terminalSandboxSessionClosed(): String = tr(
        "Returned to the Hermes host shell.", "已返回 Hermes 主机 shell。", "Se volvió al shell anfitrión de Hermes.",
        "Zur Hermes-Host-Shell zurückgekehrt.", "Retornou ao shell host do Hermes.", "Retour au shell hôte Hermes.",
    )
    fun terminalSandboxCommandLabel(name: String): String = tr(
        "Command in $name", "$name 中的命令", "Comando en $name", "Befehl in $name", "Comando em $name", "Commande dans $name",
    )
    fun uiFontSizeLabel(scale: Float): String {
        val percent = (scale * 100).toInt()
        return tr("UI font size: $percent%", "界面字体大小：$percent%", "Tamaño de fuente: $percent%", "UI-Schriftgröße: $percent%", "Tamanho da fonte: $percent%", "Taille de police : $percent%")
    }

    fun modelConfigurationSaved(): String = tr(
        "Model configuration saved", "模型配置已保存", "Configuración del modelo guardada",
        "Modellkonfiguration gespeichert", "Configuração do modelo salva", "Configuration du modèle enregistrée",
    )

    fun genericProviderLabel(): String = tr(
        "provider", "提供商", "proveedor", "Anbieter", "provedor", "fournisseur",
    )

    fun offlineProviderSetupBlocked(checking: Boolean): String = if (checking) {
        tr(
            "Offline airplane mode is on; Hermes blocked this provider setup check so the app stays phone-local.",
            "离线飞行模式已开启；Hermes 已阻止提供商设置检查，使应用保持仅在手机本地运行。",
            "El modo avión sin conexión está activado; Hermes bloqueó la comprobación del proveedor para mantener la app en el teléfono.",
            "Der Offline-Flugmodus ist aktiv; Hermes hat die Anbieterprüfung blockiert, damit die App auf dem Telefon bleibt.",
            "O modo avião offline está ativado; o Hermes bloqueou a verificação do provedor para manter o app no telefone.",
            "Le mode avion hors ligne est activé ; Hermes a bloqué la vérification du fournisseur pour garder l’application locale au téléphone.",
        )
    } else {
        tr(
            "Offline airplane mode is on; Hermes blocked this provider setup page so the app stays phone-local.",
            "离线飞行模式已开启；Hermes 已阻止提供商设置页面，使应用保持仅在手机本地运行。",
            "El modo avión sin conexión está activado; Hermes bloqueó la página del proveedor para mantener la app en el teléfono.",
            "Der Offline-Flugmodus ist aktiv; Hermes hat die Anbieter-Seite blockiert, damit die App auf dem Telefon bleibt.",
            "O modo avião offline está ativado; o Hermes bloqueou a página do provedor para manter o app no telefone.",
            "Le mode avion hors ligne est activé ; Hermes a bloqué la page du fournisseur pour garder l’application locale au téléphone.",
        )
    }

    fun openSignInTitle(label: String): String = tr(
        "Open $label sign-in", "打开 $label 登录", "Abrir inicio de sesión de $label",
        "$label-Anmeldung öffnen", "Abrir login do $label", "Ouvrir la connexion $label",
    )

    fun authSignInClipboardLabel(): String = tr(
        "Hermes sign-in URL", "Hermes 登录链接", "URL de inicio de sesión de Hermes",
        "Hermes-Anmelde-URL", "URL de login do Hermes", "URL de connexion Hermes",
    )

    fun authOpenedOpenRouterInApp(): String = tr(
        "Opened OpenRouter sign-in in the in-app browser. Approve Hermes; the app will receive the secure callback and save the key.",
        "已在应用内浏览器中打开 OpenRouter 登录。批准 Hermes 后，应用会接收安全回调并保存密钥。",
        "Se abrió OpenRouter en el navegador integrado. Autoriza Hermes; la app recibirá el callback seguro y guardará la clave.",
        "Die OpenRouter-Anmeldung wurde im In-App-Browser geöffnet. Autorisiere Hermes; die App empfängt den sicheren Callback und speichert den Schlüssel.",
        "O login do OpenRouter foi aberto no navegador do app. Autorize o Hermes; o app receberá o callback seguro e salvará a chave.",
        "La connexion OpenRouter a été ouverte dans le navigateur intégré. Autorisez Hermes ; l’application recevra le callback sécurisé et enregistrera la clé.",
    )

    fun authOpenRouterInAppFailed(errorName: String): String = tr(
        "Unable to open OpenRouter sign-in ($errorName); copied the URL. You can paste an OpenRouter API key below.",
        "无法打开 OpenRouter 登录（$errorName）；已复制链接。你也可以在下方粘贴 OpenRouter API 密钥。",
        "No se pudo abrir el inicio de sesión de OpenRouter ($errorName); se copió la URL. También puedes pegar abajo una clave API de OpenRouter.",
        "Die OpenRouter-Anmeldung konnte nicht geöffnet werden ($errorName); die URL wurde kopiert. Du kannst unten auch einen OpenRouter-API-Schlüssel einfügen.",
        "Não foi possível abrir o login do OpenRouter ($errorName); a URL foi copiada. Você também pode colar abaixo uma chave de API do OpenRouter.",
        "Impossible d’ouvrir la connexion OpenRouter ($errorName) ; l’URL a été copiée. Vous pouvez aussi coller une clé API OpenRouter ci-dessous.",
    )

    fun authOpenedOpenRouterExternal(): String = tr(
        "Opened OpenRouter sign-in in an external browser because WebView is unavailable. Approve Hermes; the local callback will save the API key securely.",
        "由于 WebView 不可用，已在外部浏览器中打开 OpenRouter 登录。批准 Hermes 后，本地回调会安全保存 API 密钥。",
        "Se abrió OpenRouter en un navegador externo porque WebView no está disponible. Autoriza Hermes; el callback local guardará la clave API de forma segura.",
        "Die OpenRouter-Anmeldung wurde in einem externen Browser geöffnet, da WebView nicht verfügbar ist. Autorisiere Hermes; der lokale Callback speichert den API-Schlüssel sicher.",
        "O login do OpenRouter foi aberto em um navegador externo porque o WebView não está disponível. Autorize o Hermes; o callback local salvará a chave com segurança.",
        "La connexion OpenRouter a été ouverte dans un navigateur externe car WebView est indisponible. Autorisez Hermes ; le callback local enregistrera la clé API de façon sécurisée.",
    )

    fun authOpenRouterExternalFailed(): String = tr(
        "Unable to open OpenRouter sign-in; copied the URL. Paste an OpenRouter API key below if needed.",
        "无法打开 OpenRouter 登录；已复制链接。如有需要，请在下方粘贴 OpenRouter API 密钥。",
        "No se pudo abrir el inicio de sesión de OpenRouter; se copió la URL. Pega abajo una clave API si la necesitas.",
        "Die OpenRouter-Anmeldung konnte nicht geöffnet werden; die URL wurde kopiert. Füge bei Bedarf unten einen API-Schlüssel ein.",
        "Não foi possível abrir o login do OpenRouter; a URL foi copiada. Cole abaixo uma chave de API se necessário.",
        "Impossible d’ouvrir la connexion OpenRouter ; l’URL a été copiée. Collez une clé API ci-dessous si nécessaire.",
    )

    fun authStartingXai(): String = tr(
        "Starting xAI Grok OAuth…", "正在启动 xAI Grok OAuth…", "Iniciando OAuth de xAI Grok…",
        "xAI-Grok-OAuth wird gestartet…", "Iniciando OAuth do xAI Grok…", "Démarrage d’OAuth xAI Grok…",
    )

    fun authXaiCallbackBindFailed(errorName: String): String = tr(
        "Unable to bind the xAI callback on 127.0.0.1:56121 ($errorName). Close other apps using that port, or paste an xAI API key below.",
        "无法在 127.0.0.1:56121 绑定 xAI 回调（$errorName）。请关闭占用该端口的其他应用，或在下方粘贴 xAI API 密钥。",
        "No se pudo enlazar el callback de xAI en 127.0.0.1:56121 ($errorName). Cierra otras apps que usen el puerto o pega abajo una clave API de xAI.",
        "Der xAI-Callback konnte nicht an 127.0.0.1:56121 gebunden werden ($errorName). Schließe andere Apps an diesem Port oder füge unten einen xAI-API-Schlüssel ein.",
        "Não foi possível vincular o callback do xAI em 127.0.0.1:56121 ($errorName). Feche outros apps que usam a porta ou cole abaixo uma chave de API do xAI.",
        "Impossible de lier le callback xAI sur 127.0.0.1:56121 ($errorName). Fermez les autres applications utilisant ce port ou collez une clé API xAI ci-dessous.",
    )

    fun authXaiOpenFailed(errorName: String, url: String): String = tr(
        "Unable to open the xAI authorization page ($errorName). URL: $url",
        "无法打开 xAI 授权页面（$errorName）。链接：$url",
        "No se pudo abrir la página de autorización de xAI ($errorName). URL: $url",
        "Die xAI-Autorisierungsseite konnte nicht geöffnet werden ($errorName). URL: $url",
        "Não foi possível abrir a página de autorização do xAI ($errorName). URL: $url",
        "Impossible d’ouvrir la page d’autorisation xAI ($errorName). URL : $url",
    )

    fun authXaiOpened(): String = tr(
        "Opened xAI Grok OAuth in the in-app browser. Approve SuperGrok; the local callback will return to Hermes and save tokens securely.",
        "已在应用内浏览器中打开 xAI Grok OAuth。批准 SuperGrok 后，本地回调会返回 Hermes 并安全保存令牌。",
        "Se abrió OAuth de xAI Grok en el navegador integrado. Autoriza SuperGrok; el callback local volverá a Hermes y guardará los tokens de forma segura.",
        "xAI-Grok-OAuth wurde im In-App-Browser geöffnet. Autorisiere SuperGrok; der lokale Callback kehrt zu Hermes zurück und speichert die Tokens sicher.",
        "O OAuth do xAI Grok foi aberto no navegador do app. Autorize o SuperGrok; o callback local voltará ao Hermes e salvará os tokens com segurança.",
        "OAuth xAI Grok a été ouvert dans le navigateur intégré. Autorisez SuperGrok ; le callback local reviendra dans Hermes et enregistrera les jetons de façon sécurisée.",
    )

    fun authXaiFailed(errorName: String): String = tr(
        "xAI OAuth failed: $errorName", "xAI OAuth 失败：$errorName", "Falló OAuth de xAI: $errorName",
        "xAI-OAuth fehlgeschlagen: $errorName", "Falha no OAuth do xAI: $errorName", "Échec d’OAuth xAI : $errorName",
    )

    fun authStartingCodex(): String = tr(
        "Starting ChatGPT/Codex OAuth…", "正在启动 ChatGPT/Codex OAuth…", "Iniciando OAuth de ChatGPT/Codex…",
        "ChatGPT/Codex-OAuth wird gestartet…", "Iniciando OAuth do ChatGPT/Codex…", "Démarrage d’OAuth ChatGPT/Codex…",
    )

    fun authCodexOpened(port: Int): String = tr(
        "Opened ChatGPT/Codex OAuth in the in-app browser. Approve access; the callback returns to localhost:$port.",
        "已在应用内浏览器中打开 ChatGPT/Codex OAuth。批准访问后，回调会返回 localhost:$port。",
        "Se abrió OAuth de ChatGPT/Codex en el navegador integrado. Autoriza el acceso; el callback vuelve a localhost:$port.",
        "ChatGPT/Codex-OAuth wurde im In-App-Browser geöffnet. Genehmige den Zugriff; der Callback kehrt zu localhost:$port zurück.",
        "O OAuth do ChatGPT/Codex foi aberto no navegador do app. Autorize o acesso; o callback retorna para localhost:$port.",
        "OAuth ChatGPT/Codex a été ouvert dans le navigateur intégré. Autorisez l’accès ; le callback revient sur localhost:$port.",
    )

    fun authBrowserOauthUnavailable(errorName: String): String = tr(
        "Browser OAuth is unavailable ($errorName); trying a device code…",
        "浏览器 OAuth 不可用（$errorName）；正在尝试设备代码…",
        "OAuth en el navegador no está disponible ($errorName); probando un código de dispositivo…",
        "Browser-OAuth ist nicht verfügbar ($errorName); Gerätecode wird versucht…",
        "O OAuth no navegador não está disponível ($errorName); tentando um código do dispositivo…",
        "OAuth dans le navigateur est indisponible ($errorName) ; tentative avec un code d’appareil…",
    )

    fun authRequestingOpenAiDeviceCode(): String = tr(
        "Requesting an OpenAI device code…", "正在请求 OpenAI 设备代码…", "Solicitando un código de dispositivo de OpenAI…",
        "OpenAI-Gerätecode wird angefordert…", "Solicitando um código de dispositivo da OpenAI…", "Demande d’un code d’appareil OpenAI…",
    )

    fun authOpenAiDeviceCodeFailed(errorName: String): String = tr(
        "OpenAI device code failed: $errorName. You can still paste a ChatGPT/Codex token below.",
        "OpenAI 设备代码失败：$errorName。你仍可在下方粘贴 ChatGPT/Codex 令牌。",
        "Falló el código de dispositivo de OpenAI: $errorName. Aún puedes pegar abajo un token de ChatGPT/Codex.",
        "OpenAI-Gerätecode fehlgeschlagen: $errorName. Du kannst unten weiterhin ein ChatGPT/Codex-Token einfügen.",
        "Falha no código de dispositivo da OpenAI: $errorName. Você ainda pode colar abaixo um token do ChatGPT/Codex.",
        "Échec du code d’appareil OpenAI : $errorName. Vous pouvez toujours coller un jeton ChatGPT/Codex ci-dessous.",
    )

    fun authOpenAiDeviceLoginTitle(): String = tr(
        "OpenAI device login", "OpenAI 设备登录", "Inicio de sesión de dispositivo OpenAI",
        "OpenAI-Geräteanmeldung", "Login de dispositivo OpenAI", "Connexion d’appareil OpenAI",
    )

    fun authOpenAiEnterCode(code: String, url: String): String = tr(
        "Enter code $code at $url. The page opened in-app; waiting for approval…",
        "请在 $url 输入代码 $code。页面已在应用内打开；正在等待批准…",
        "Introduce el código $code en $url. La página se abrió en la app; esperando autorización…",
        "Gib den Code $code unter $url ein. Die Seite wurde in der App geöffnet; warte auf Freigabe…",
        "Digite o código $code em $url. A página foi aberta no app; aguardando autorização…",
        "Saisissez le code $code sur $url. La page est ouverte dans l’application ; attente de l’autorisation…",
    )

    fun authOpenAiPollError(errorName: String): String = tr(
        "OpenAI device check failed: $errorName", "OpenAI 设备检查失败：$errorName", "Falló la comprobación del dispositivo OpenAI: $errorName",
        "OpenAI-Geräteprüfung fehlgeschlagen: $errorName", "Falha na verificação do dispositivo OpenAI: $errorName", "Échec de la vérification de l’appareil OpenAI : $errorName",
    )

    fun authOpenAiTimedOut(): String = tr(
        "OpenAI device sign-in timed out. Tap Sign in to try again.", "OpenAI 设备登录超时。点按“登录”重试。",
        "El inicio de sesión de dispositivo OpenAI agotó el tiempo. Toca Iniciar sesión para reintentarlo.",
        "Zeitüberschreitung bei der OpenAI-Geräteanmeldung. Tippe zum erneuten Versuch auf Anmelden.",
        "O login de dispositivo OpenAI expirou. Toque em Entrar para tentar novamente.",
        "La connexion d’appareil OpenAI a expiré. Touchez Se connecter pour réessayer.",
    )

    fun authStartingNousDeviceCode(): String = tr(
        "Starting a Nous Portal device code…", "正在启动 Nous Portal 设备代码…", "Iniciando un código de dispositivo de Nous Portal…",
        "Nous-Portal-Gerätecode wird gestartet…", "Iniciando um código de dispositivo do Nous Portal…", "Démarrage d’un code d’appareil Nous Portal…",
    )

    fun authNousDeviceCodeFailed(errorName: String): String = tr(
        "Nous device code failed: $errorName", "Nous 设备代码失败：$errorName", "Falló el código de dispositivo de Nous: $errorName",
        "Nous-Gerätecode fehlgeschlagen: $errorName", "Falha no código de dispositivo do Nous: $errorName", "Échec du code d’appareil Nous : $errorName",
    )

    fun authNousSignInTitle(): String = tr(
        "Nous Portal sign-in", "Nous Portal 登录", "Inicio de sesión de Nous Portal",
        "Nous-Portal-Anmeldung", "Login do Nous Portal", "Connexion Nous Portal",
    )

    fun authNousEnterCode(code: String): String = tr(
        "Nous code $code. Approve it in the in-app browser; waiting…", "Nous 代码为 $code。请在应用内浏览器中批准；正在等待…",
        "Código de Nous: $code. Autorízalo en el navegador integrado; esperando…", "Nous-Code $code. Genehmige ihn im In-App-Browser; warte…",
        "Código do Nous: $code. Autorize no navegador do app; aguardando…", "Code Nous $code. Autorisez-le dans le navigateur intégré ; attente…",
    )

    fun authNousPollError(errorName: String): String = tr(
        "Nous device check failed: $errorName", "Nous 设备检查失败：$errorName", "Falló la comprobación del dispositivo Nous: $errorName",
        "Nous-Geräteprüfung fehlgeschlagen: $errorName", "Falha na verificação do dispositivo Nous: $errorName", "Échec de la vérification de l’appareil Nous : $errorName",
    )

    fun authNousTimedOut(): String = tr(
        "Nous sign-in timed out. Tap Sign in to try again.", "Nous 登录超时。点按“登录”重试。",
        "El inicio de sesión de Nous agotó el tiempo. Toca Iniciar sesión para reintentarlo.",
        "Zeitüberschreitung bei der Nous-Anmeldung. Tippe zum erneuten Versuch auf Anmelden.",
        "O login do Nous expirou. Toque em Entrar para tentar novamente.",
        "La connexion Nous a expiré. Touchez Se connecter pour réessayer.",
    )

    fun authCredentialRequired(label: String): String = tr(
        "Paste an API key, token, or CLI environment line for $label first.", "请先粘贴 $label 的 API 密钥、令牌或 CLI 环境变量行。",
        "Primero pega una clave API, un token o una línea de entorno CLI para $label.",
        "Füge zuerst einen API-Schlüssel, ein Token oder eine CLI-Umgebungszeile für $label ein.",
        "Primeiro cole uma chave de API, token ou linha de ambiente CLI para $label.",
        "Collez d’abord une clé API, un jeton ou une ligne d’environnement CLI pour $label.",
    )

    fun authSavedCredential(label: String, sourceLabel: String): String {
        val source = if (sourceLabel.isBlank()) "" else tr(
            " from $sourceLabel", "（来源：$sourceLabel）", " desde $sourceLabel",
            " aus $sourceLabel", " de $sourceLabel", " depuis $sourceLabel",
        )
        return tr(
            "Saved $label credential$source and queued a Hermes runtime restart.", "已保存 $label 凭据$source，并已安排重启 Hermes 运行时。",
            "Credencial de $label guardada$source; se ha programado el reinicio del runtime de Hermes.",
            "$label-Zugangsdaten$source gespeichert; ein Neustart der Hermes-Runtime wurde eingeplant.",
            "Credencial do $label salva$source; a reinicialização do runtime do Hermes foi agendada.",
            "Identifiant $label enregistré$source ; le redémarrage du runtime Hermes est planifié.",
        )
    }

    fun authSavingCredential(label: String): String = tr(
        "Saving $label credential and restarting Hermes…", "正在保存 $label 凭据并重启 Hermes…",
        "Guardando la credencial de $label y reiniciando Hermes…", "$label-Zugangsdaten werden gespeichert und Hermes wird neu gestartet…",
        "Salvando a credencial do $label e reiniciando o Hermes…", "Enregistrement de l’identifiant $label et redémarrage de Hermes…",
    )

    fun authSaveCredentialFailed(label: String, errorName: String): String = tr(
        "Unable to save $label credential ($errorName).", "无法保存 $label 凭据（$errorName）。",
        "No se pudo guardar la credencial de $label ($errorName).", "$label-Zugangsdaten konnten nicht gespeichert werden ($errorName).",
        "Não foi possível salvar a credencial do $label ($errorName).", "Impossible d’enregistrer l’identifiant $label ($errorName).",
    )

    fun providerSetupUrlInvalid(): String = tr(
        "Provider setup URL must start with https:// or http://", "提供商设置链接必须以 https:// 或 http:// 开头",
        "La URL de configuración del proveedor debe empezar por https:// o http://", "Die Anbieter-Setup-URL muss mit https:// oder http:// beginnen",
        "A URL de configuração do provedor deve começar com https:// ou http://", "L’URL de configuration du fournisseur doit commencer par https:// ou http://",
    )

    fun providerSetupTitle(label: String): String = tr(
        "$label setup", "$label 设置", "Configuración de $label", "$label-Einrichtung", "Configuração do $label", "Configuration de $label",
    )

    fun openProviderSetupTitle(label: String): String = tr(
        "Open $label setup page", "打开 $label 设置页面", "Abrir la página de configuración de $label",
        "$label-Setup-Seite öffnen", "Abrir a página de configuração do $label", "Ouvrir la page de configuration de $label",
    )

    fun providerSetupOpenFailed(label: String, errorName: String): String = tr(
        "Unable to open the $label setup page ($errorName); copied the official setup URLs.", "无法打开 $label 设置页面（$errorName）；已复制官方设置链接。",
        "No se pudo abrir la página de configuración de $label ($errorName); se copiaron las URL oficiales.",
        "Die $label-Setup-Seite konnte nicht geöffnet werden ($errorName); die offiziellen URLs wurden kopiert.",
        "Não foi possível abrir a página de configuração do $label ($errorName); as URLs oficiais foram copiadas.",
        "Impossible d’ouvrir la page de configuration de $label ($errorName) ; les URL officielles ont été copiées.",
    )

    fun providerSetupUrlsMissing(label: String): String = tr(
        "No setup URLs are configured for $label.", "未为 $label 配置设置链接。", "No hay URL de configuración para $label.",
        "Für $label sind keine Setup-URLs konfiguriert.", "Não há URLs de configuração para $label.", "Aucune URL de configuration n’est définie pour $label.",
    )

    fun providerSetupChecking(label: String): String = tr(
        "Checking $label setup pages from this device…", "正在从此设备检查 $label 设置页面…",
        "Comprobando desde este dispositivo las páginas de configuración de $label…", "$label-Setup-Seiten werden von diesem Gerät geprüft…",
        "Verificando neste dispositivo as páginas de configuração do $label…", "Vérification des pages de configuration de $label depuis cet appareil…",
    )

    fun providerSetupReachable(
        label: String,
        url: String,
        statusLabel: String,
        reachableCount: Int,
        totalCount: Int,
        failedFallbackCount: Int,
    ): String {
        val fallbackHint = if (failedFallbackCount > 0) tr(
            " $failedFallbackCount fallback page(s) did not respond cleanly; tap Open again to try the next official alternative.",
            " 有 $failedFallbackCount 个备用页面未正常响应；再次点按“打开”可尝试下一个官方备用页面。",
            " $failedFallbackCount página(s) alternativa(s) no respondieron correctamente; toca Abrir otra vez para probar la siguiente opción oficial.",
            " $failedFallbackCount Ausweichseite(n) antworteten nicht korrekt; tippe erneut auf Öffnen, um die nächste offizielle Alternative zu testen.",
            " $failedFallbackCount página(s) alternativa(s) não responderam corretamente; toque em Abrir novamente para tentar a próxima opção oficial.",
            " $failedFallbackCount page(s) de secours n’ont pas répondu correctement ; touchez de nouveau Ouvrir pour essayer l’alternative officielle suivante.",
        ) else ""
        return tr(
            "$label setup is reachable from Hermes: $url ($statusLabel). $reachableCount/$totalCount official page(s) responded; copied all setup URLs.$fallbackHint",
            "Hermes 可以访问 $label 设置页面：$url（$statusLabel）。$reachableCount/$totalCount 个官方页面已响应；已复制所有设置链接。$fallbackHint",
            "Hermes puede acceder a la configuración de $label: $url ($statusLabel). Respondieron $reachableCount/$totalCount páginas oficiales; se copiaron todas las URL.$fallbackHint",
            "Das $label-Setup ist von Hermes erreichbar: $url ($statusLabel). $reachableCount/$totalCount offizielle Seiten antworteten; alle Setup-URLs wurden kopiert.$fallbackHint",
            "A configuração do $label está acessível pelo Hermes: $url ($statusLabel). $reachableCount/$totalCount páginas oficiais responderam; todas as URLs foram copiadas.$fallbackHint",
            "La configuration de $label est accessible depuis Hermes : $url ($statusLabel). $reachableCount/$totalCount pages officielles ont répondu ; toutes les URL ont été copiées.$fallbackHint",
        )
    }

    fun providerSetupUnreachable(label: String, failureSummary: String): String = tr(
        "No $label setup page responded from Hermes. Copied all setup URLs. $failureSummary",
        "Hermes 未收到任何 $label 设置页面的响应。已复制所有设置链接。$failureSummary",
        "Ninguna página de configuración de $label respondió desde Hermes. Se copiaron todas las URL. $failureSummary",
        "Keine $label-Setup-Seite antwortete aus Hermes. Alle Setup-URLs wurden kopiert. $failureSummary",
        "Nenhuma página de configuração do $label respondeu pelo Hermes. Todas as URLs foram copiadas. $failureSummary",
        "Aucune page de configuration de $label n’a répondu depuis Hermes. Toutes les URL ont été copiées. $failureSummary",
    )

    fun providerSetupOpened(label: String, providerId: String, displayIndex: Int, total: Int): String {
        val browserHint = if (total > 1) tr(
            " in your browser ($displayIndex/$total); copied all official setup URLs. Tap Open again for the next alternative if this page stalls.",
            "（浏览器中第 $displayIndex/$total 个）；已复制所有官方设置链接。如果页面卡住，请再次点按“打开”尝试下一个备用页面。",
            " en el navegador ($displayIndex/$total); se copiaron todas las URL oficiales. Toca Abrir otra vez si la página se bloquea.",
            " im Browser ($displayIndex/$total); alle offiziellen Setup-URLs wurden kopiert. Tippe bei einem Stillstand erneut auf Öffnen.",
            " no navegador ($displayIndex/$total); todas as URLs oficiais foram copiadas. Toque em Abrir novamente se a página travar.",
            " dans le navigateur ($displayIndex/$total) ; toutes les URL officielles ont été copiées. Touchez de nouveau Ouvrir si la page se bloque.",
        ) else tr(
            " in your browser. If it stalls, copy the setup URL into another browser.", "（浏览器中）。如果页面卡住，请将设置链接复制到其他浏览器。",
            " en el navegador. Si se bloquea, copia la URL en otro navegador.", " im Browser. Falls die Seite hängt, kopiere die Setup-URL in einen anderen Browser.",
            " no navegador. Se travar, copie a URL para outro navegador.", " dans le navigateur. Si la page se bloque, copiez l’URL dans un autre navigateur.",
        )
        val legacyHint = if (providerId == "qwen-oauth") tr(
            " Qwen OAuth is legacy; choose Qwen Cloud for new API-key setup.", " Qwen OAuth 为旧版；新的 API 密钥设置请选择 Qwen Cloud。",
            " Qwen OAuth es heredado; elige Qwen Cloud para configurar una clave API nueva.", " Qwen OAuth ist veraltet; nutze Qwen Cloud für neue API-Schlüssel.",
            " O Qwen OAuth é legado; escolha Qwen Cloud para configurar uma nova chave de API.", " Qwen OAuth est ancien ; choisissez Qwen Cloud pour une nouvelle clé API.",
        ) else ""
        return tr(
            "Opened $label setup page$browserHint$legacyHint", "已打开 $label 设置页面$browserHint$legacyHint",
            "Se abrió la página de configuración de $label$browserHint$legacyHint", "$label-Setup-Seite geöffnet$browserHint$legacyHint",
            "Página de configuração do $label aberta$browserHint$legacyHint", "Page de configuration de $label ouverte$browserHint$legacyHint",
        )
    }

    fun providerSetupClipboardLabel(label: String): String = tr(
        "Hermes $label setup URLs", "Hermes $label 设置链接", "URL de configuración de Hermes para $label",
        "Hermes-$label-Setup-URLs", "URLs de configuração do Hermes para $label", "URL de configuration Hermes pour $label",
    )

    fun providerSetupCopied(label: String, fallbackCount: Int): String {
        val alternatives = when (fallbackCount) {
            0 -> ""
            1 -> tr(" and 1 official alternative", "及 1 个官方备用页面", " y 1 alternativa oficial", " und 1 offizielle Alternative", " e 1 alternativa oficial", " et 1 alternative officielle")
            else -> tr(" and $fallbackCount official alternatives", "及 $fallbackCount 个官方备用页面", " y $fallbackCount alternativas oficiales", " und $fallbackCount offizielle Alternativen", " e $fallbackCount alternativas oficiais", " et $fallbackCount alternatives officielles")
        }
        return tr(
            "Copied $label setup URL$alternatives.", "已复制 $label 设置链接$alternatives。", "URL de configuración de $label copiada$alternatives.",
            "$label-Setup-URL$alternatives kopiert.", "URL de configuração do $label copiada$alternatives.", "URL de configuration de $label copiée$alternatives.",
        )
    }

    fun chooseSavedProviderCredential(): String = tr(
        "Choose a saved provider before importing a Hermes credential.", "导入 Hermes 凭据前，请先选择已保存的提供商。",
        "Elige un proveedor guardado antes de importar una credencial de Hermes.", "Wähle vor dem Importieren von Hermes-Zugangsdaten einen gespeicherten Anbieter.",
        "Escolha um provedor salvo antes de importar uma credencial do Hermes.", "Choisissez un fournisseur enregistré avant d’importer un identifiant Hermes.",
    )

    fun checkingSavedProviderCredential(label: String): String = tr(
        "Checking the saved Hermes credential for $label…", "正在检查 $label 的已保存 Hermes 凭据…",
        "Comprobando la credencial Hermes guardada para $label…", "Gespeicherte Hermes-Zugangsdaten für $label werden geprüft…",
        "Verificando a credencial do Hermes salva para $label…", "Vérification de l’identifiant Hermes enregistré pour $label…",
    )

    fun unableToReadSavedProviderCredential(errorName: String): String = tr(
        "Unable to read the saved Hermes credential ($errorName).", "无法读取已保存的 Hermes 凭据（$errorName）。",
        "No se pudo leer la credencial Hermes guardada ($errorName).", "Gespeicherte Hermes-Zugangsdaten konnten nicht gelesen werden ($errorName).",
        "Não foi possível ler a credencial do Hermes salva ($errorName).", "Impossible de lire l’identifiant Hermes enregistré ($errorName).",
    )

    fun savedProviderCredentialCouldNotBeDecoded(label: String): String = tr(
        "The saved Hermes credential for $label could not be decoded.", "无法解码 $label 的已保存 Hermes 凭据。",
        "No se pudo decodificar la credencial Hermes guardada para $label.", "Die gespeicherten Hermes-Zugangsdaten für $label konnten nicht dekodiert werden.",
        "Não foi possível decodificar a credencial do Hermes salva para $label.", "Impossible de décoder l’identifiant Hermes enregistré pour $label.",
    )

    fun noSavedProviderCredential(label: String): String = tr(
        "No saved Hermes credential was found for $label.", "未找到 $label 的已保存 Hermes 凭据。",
        "No se encontró una credencial Hermes guardada para $label.", "Keine gespeicherten Hermes-Zugangsdaten für $label gefunden.",
        "Nenhuma credencial do Hermes salva foi encontrada para $label.", "Aucun identifiant Hermes enregistré n’a été trouvé pour $label.",
    )

    fun importedSavedProviderCredential(label: String): String = tr(
        "Imported the saved Hermes credential for $label and restarted the runtime.", "已导入 $label 的 Hermes 凭据并重启运行时。",
        "Se importó la credencial Hermes guardada para $label y se reinició el runtime.", "Gespeicherte Hermes-Zugangsdaten für $label importiert und Runtime neu gestartet.",
        "A credencial do Hermes salva para $label foi importada e o runtime reiniciado.", "L’identifiant Hermes enregistré pour $label a été importé et le runtime redémarré.",
    )

    fun savedProviderCredentialImportFailed(errorName: String): String = tr(
        "Saved Hermes credential import failed ($errorName).", "导入已保存的 Hermes 凭据失败（$errorName）。",
        "Falló la importación de la credencial Hermes guardada ($errorName).", "Import gespeicherter Hermes-Zugangsdaten fehlgeschlagen ($errorName).",
        "Falha ao importar a credencial do Hermes salva ($errorName).", "Échec de l’importation de l’identifiant Hermes enregistré ($errorName).",
    )

    fun startingLocalHermesRuntime(): String = tr(
        "Starting the local Hermes runtime…", "正在启动本地 Hermes 运行时…", "Iniciando el runtime local de Hermes…",
        "Lokale Hermes-Runtime wird gestartet…", "Iniciando o runtime local do Hermes…", "Démarrage du runtime Hermes local…",
    )

    fun localBackendReady(backend: String, model: String): String = tr(
        "$backend ready · $model", "$backend 已就绪 · $model", "$backend listo · $model",
        "$backend bereit · $model", "$backend pronto · $model", "$backend prêt · $model",
    )

    private fun tr(en: String, zh: String, es: String, de: String, pt: String, fr: String): String = when (language) {
        AppLanguage.CHINESE -> zh
        AppLanguage.SPANISH -> es
        AppLanguage.GERMAN -> de
        AppLanguage.PORTUGUESE -> pt
        AppLanguage.FRENCH -> fr
        AppLanguage.ENGLISH -> en
    }

    fun authSignedInWith(label: String): String = when (language) {
        AppLanguage.CHINESE -> "已通过 $label 登录"
        AppLanguage.SPANISH -> "Sesión iniciada con $label"
        AppLanguage.GERMAN -> "Angemeldet mit $label"
        AppLanguage.PORTUGUESE -> "Sessão iniciada com $label"
        AppLanguage.FRENCH -> "Connecté avec $label"
        AppLanguage.ENGLISH -> "Signed in with $label"
    }

    fun floatingOverlayPermissionHint(): String = when (language) {
        AppLanguage.CHINESE -> "若要让浮动 Hermes 按钮在其他应用上保持显示，请在 Android 设置中允许 Hermes 显示在其他应用上层。"
        AppLanguage.SPANISH -> "Para mantener el botón flotante de Hermes sobre otras apps, permite que Hermes se muestre sobre otras apps en Ajustes de Android."
        AppLanguage.GERMAN -> "Damit die schwebende Hermes-Schaltfläche über anderen Apps sichtbar bleibt, erlaube Hermes in den Android-Einstellungen die Anzeige über anderen Apps."
        AppLanguage.PORTUGUESE -> "Para manter o botão flutuante do Hermes sobre outros apps, permita que o Hermes apareça sobre outros apps nas configurações do Android."
        AppLanguage.FRENCH -> "Pour garder le bouton flottant Hermes au-dessus des autres apps, autorisez Hermes à s’afficher par-dessus les autres apps dans les paramètres Android."
        AppLanguage.ENGLISH -> "To keep the floating Hermes button available over other apps, allow Hermes to draw over other apps in Android settings."
    }
}

private val SIGNAL_QUICK_ACTION_TRANSLATIONS: Map<AppLanguage, Map<String, String>> = mapOf(
    AppLanguage.CHINESE to mapOf(
        "signal_overview" to "信号概览",
        "signal_briefing" to "信号简报",
        "signal_session_snapshot" to "会话快照",
        "signal_proof_audit" to "证据审计",
        "signal_replay_export" to "回放导出",
        "signal_replay_freshness" to "回放新鲜度",
        "signal_observation_packet" to "观察包",
        "signal_card_deck" to "卡片组",
        "card_refresh_plan" to "刷新计划",
        "card_refresh_status" to "刷新状态",
        "signal_timeline" to "信号时间线",
        "signal_evidence" to "证据包",
        "workflow_handoff" to "流程交接",
        "permission_runbook" to "权限手册",
        "rf_coexistence" to "射频共存",
        "agent_environment" to "代理环境",
        "agent_self_check" to "自检",
        "agent_observation" to "代理观察",
        "card_manifest" to "卡片清单",
        "top_cards" to "重点卡片",
        "mcp_registry" to "MCP 注册表",
        "upgrade_audit" to "升级审计",
        "objective_coverage" to "目标覆盖",
        "release_validation" to "发布验证",
        "soc_compatibility" to "SOC 兼容",
        "mediatek_readiness" to "联发科就绪",
        "mediatek_signal_stack" to "MTK 信号",
        "mediatek_device_validation" to "设备证明",
        "device_evidence_export" to "证明导出",
        "accelerator_preflight" to "加速预检",
        "non_adreno_backend_advisor" to "后端建议",
        "mediatek_launch_checklist" to "MTK 启动",
        "backend_risk" to "后端风险",
        "inference_compatibility" to "推理适配",
        "runtime_backend" to "运行后端",
        "runtime_stability" to "运行稳定",
        "wifi_analyzer" to "Wi-Fi 分析",
        "wifi_advisor" to "Wi-Fi 建议",
        "wifi_channel_decision" to "Wi-Fi 决策",
        "wifi_link" to "Wi-Fi 链路",
        "wifi_nearby" to "附近 Wi-Fi",
        "wifi_occupancy" to "Wi-Fi 占用",
        "bluetooth_analyzer" to "蓝牙分析",
        "bluetooth_advisor" to "蓝牙建议",
        "bluetooth_decision" to "蓝牙决策",
        "bluetooth_history" to "蓝牙趋势",
        "bluetooth_details" to "蓝牙详情",
        "sensor_analyzer" to "传感器分析",
        "sensor_advisor" to "传感器建议",
        "motion_decision" to "运动决策",
        "motion_history" to "运动趋势",
        "motion_quality" to "运动质量",
        "radio_limits" to "无线电信号",
        "radio_advisor" to "无线电建议",
        "radio_decision" to "无线电决策",
    ),
    AppLanguage.SPANISH to mapOf(
        "signal_overview" to "Resumen señal",
        "signal_briefing" to "Informe señal",
        "signal_session_snapshot" to "Instantánea",
        "signal_proof_audit" to "Auditoría prueba",
        "signal_replay_export" to "Exportar replay",
        "signal_replay_freshness" to "Vigencia replay",
        "signal_observation_packet" to "Paquete visual",
        "signal_card_deck" to "Tarjetas señal",
        "card_refresh_plan" to "Plan refresco",
        "card_refresh_status" to "Estado refresco",
        "signal_timeline" to "Cronología señal",
        "signal_evidence" to "Evidencias",
        "workflow_handoff" to "Traspaso flujo",
        "permission_runbook" to "Permisos",
        "rf_coexistence" to "Coexistencia RF",
        "agent_environment" to "Entorno agente",
        "agent_self_check" to "Autochequeo",
        "agent_observation" to "Observación",
        "card_manifest" to "Manifiesto",
        "top_cards" to "Tarjetas top",
        "mcp_registry" to "Registro MCP",
        "upgrade_audit" to "Auditoría mejora",
        "objective_coverage" to "Cobertura objetivo",
        "release_validation" to "Validar release",
        "soc_compatibility" to "Compat. SOC",
        "mediatek_readiness" to "MediaTek listo",
        "mediatek_signal_stack" to "Señales MTK",
        "mediatek_device_validation" to "Prueba dispositivo",
        "device_evidence_export" to "Exportar prueba",
        "accelerator_preflight" to "Prevuelo accel",
        "non_adreno_backend_advisor" to "Consejo backend",
        "mediatek_launch_checklist" to "Lanzar MTK",
        "backend_risk" to "Riesgo backend",
        "inference_compatibility" to "Ajuste inferencia",
        "runtime_backend" to "Backend runtime",
        "runtime_stability" to "Estabilidad",
        "wifi_analyzer" to "Analizar Wi-Fi",
        "wifi_advisor" to "Consejo Wi-Fi",
        "wifi_channel_decision" to "Decisión Wi-Fi",
        "wifi_link" to "Enlace Wi-Fi",
        "wifi_nearby" to "Wi-Fi cercano",
        "wifi_occupancy" to "Ocupación Wi-Fi",
        "bluetooth_analyzer" to "Analizar BT",
        "bluetooth_advisor" to "Consejo BT",
        "bluetooth_decision" to "Decisión BT",
        "bluetooth_history" to "Tendencias BT",
        "bluetooth_details" to "Detalles BT",
        "sensor_analyzer" to "Analizar sensor",
        "sensor_advisor" to "Consejo sensor",
        "motion_decision" to "Decisión mov.",
        "motion_history" to "Tendencias mov.",
        "motion_quality" to "Calidad mov.",
        "radio_limits" to "Señales radio",
        "radio_advisor" to "Consejo radio",
        "radio_decision" to "Decisión radio",
    ),
    AppLanguage.GERMAN to mapOf(
        "signal_overview" to "Signalüberblick",
        "signal_briefing" to "Signalbriefing",
        "signal_session_snapshot" to "Sitzungsbild",
        "signal_proof_audit" to "Nachweis-Audit",
        "signal_replay_export" to "Replay-Export",
        "signal_replay_freshness" to "Replay-Aktualität",
        "signal_observation_packet" to "Sichtpaket",
        "signal_card_deck" to "Signalkarten",
        "card_refresh_plan" to "Refresh-Plan",
        "card_refresh_status" to "Refresh-Status",
        "signal_timeline" to "Signalzeitlinie",
        "signal_evidence" to "Nachweispaket",
        "workflow_handoff" to "Workflow-Übergabe",
        "permission_runbook" to "Berechtigungen",
        "rf_coexistence" to "RF-Koexistenz",
        "agent_environment" to "Agent-Umgebung",
        "agent_self_check" to "Selbsttest",
        "agent_observation" to "Agent-Beobachtung",
        "card_manifest" to "Kartenmanifest",
        "top_cards" to "Top-Karten",
        "mcp_registry" to "MCP-Registry",
        "upgrade_audit" to "Upgrade-Audit",
        "objective_coverage" to "Zielabdeckung",
        "release_validation" to "Release-Prüfung",
        "soc_compatibility" to "SOC-Kompat.",
        "mediatek_readiness" to "MediaTek bereit",
        "mediatek_signal_stack" to "MTK-Signale",
        "mediatek_device_validation" to "Gerätenachweis",
        "device_evidence_export" to "Nachweis-Export",
        "accelerator_preflight" to "Accel-Prüfung",
        "non_adreno_backend_advisor" to "Backend-Rat",
        "mediatek_launch_checklist" to "MTK-Start",
        "backend_risk" to "Backend-Risiko",
        "inference_compatibility" to "Inferenz-Fit",
        "runtime_backend" to "Runtime-Backend",
        "runtime_stability" to "Stabilität",
        "wifi_analyzer" to "Wi-Fi-Analyse",
        "wifi_advisor" to "Wi-Fi-Rat",
        "wifi_channel_decision" to "Wi-Fi-Entscheid",
        "wifi_link" to "Wi-Fi-Link",
        "wifi_nearby" to "Nahes Wi-Fi",
        "wifi_occupancy" to "Wi-Fi-Belegung",
        "bluetooth_analyzer" to "BT-Analyse",
        "bluetooth_advisor" to "BT-Rat",
        "bluetooth_decision" to "BT-Entscheid",
        "bluetooth_history" to "BT-Trends",
        "bluetooth_details" to "BT-Details",
        "sensor_analyzer" to "Sensoranalyse",
        "sensor_advisor" to "Sensor-Rat",
        "motion_decision" to "Bewegungsentscheid",
        "motion_history" to "Bewegungstrends",
        "motion_quality" to "Bewegungsqualität",
        "radio_limits" to "Funksignale",
        "radio_advisor" to "Funk-Rat",
        "radio_decision" to "Funk-Entscheid",
    ),
    AppLanguage.PORTUGUESE to mapOf(
        "signal_overview" to "Visão de sinais",
        "signal_briefing" to "Resumo sinais",
        "signal_session_snapshot" to "Instantâneo",
        "signal_proof_audit" to "Auditoria prova",
        "signal_replay_export" to "Exportar replay",
        "signal_replay_freshness" to "Atualidade replay",
        "signal_observation_packet" to "Pacote visual",
        "signal_card_deck" to "Cartões sinal",
        "card_refresh_plan" to "Plano refresh",
        "card_refresh_status" to "Estado refresh",
        "signal_timeline" to "Linha do sinal",
        "signal_evidence" to "Pacote prova",
        "workflow_handoff" to "Passagem fluxo",
        "permission_runbook" to "Permissões",
        "rf_coexistence" to "Coexistência RF",
        "agent_environment" to "Ambiente agente",
        "agent_self_check" to "Autoteste",
        "agent_observation" to "Observação",
        "card_manifest" to "Manifesto",
        "top_cards" to "Cartões top",
        "mcp_registry" to "Registro MCP",
        "upgrade_audit" to "Auditoria upgrade",
        "objective_coverage" to "Cobertura objetivo",
        "release_validation" to "Validar release",
        "soc_compatibility" to "Compat. SOC",
        "mediatek_readiness" to "MediaTek pronto",
        "mediatek_signal_stack" to "Sinais MTK",
        "mediatek_device_validation" to "Prova aparelho",
        "device_evidence_export" to "Exportar prova",
        "accelerator_preflight" to "Pré-voo accel",
        "non_adreno_backend_advisor" to "Conselho backend",
        "mediatek_launch_checklist" to "Lançar MTK",
        "backend_risk" to "Risco backend",
        "inference_compatibility" to "Ajuste inferência",
        "runtime_backend" to "Backend runtime",
        "runtime_stability" to "Estabilidade",
        "wifi_analyzer" to "Analisar Wi-Fi",
        "wifi_advisor" to "Conselho Wi-Fi",
        "wifi_channel_decision" to "Decisão Wi-Fi",
        "wifi_link" to "Link Wi-Fi",
        "wifi_nearby" to "Wi-Fi próximo",
        "wifi_occupancy" to "Ocupação Wi-Fi",
        "bluetooth_analyzer" to "Analisar BT",
        "bluetooth_advisor" to "Conselho BT",
        "bluetooth_decision" to "Decisão BT",
        "bluetooth_history" to "Tendências BT",
        "bluetooth_details" to "Detalhes BT",
        "sensor_analyzer" to "Analisar sensor",
        "sensor_advisor" to "Conselho sensor",
        "motion_decision" to "Decisão mov.",
        "motion_history" to "Tendências mov.",
        "motion_quality" to "Qualidade mov.",
        "radio_limits" to "Sinais rádio",
        "radio_advisor" to "Conselho rádio",
        "radio_decision" to "Decisão rádio",
    ),
    AppLanguage.FRENCH to mapOf(
        "signal_overview" to "Vue signaux",
        "signal_briefing" to "Brief signaux",
        "signal_session_snapshot" to "Instantané",
        "signal_proof_audit" to "Audit preuve",
        "signal_replay_export" to "Export replay",
        "signal_replay_freshness" to "Fraîcheur replay",
        "signal_observation_packet" to "Paquet visuel",
        "signal_card_deck" to "Cartes signaux",
        "card_refresh_plan" to "Plan refresh",
        "card_refresh_status" to "Statut refresh",
        "signal_timeline" to "Chronologie",
        "signal_evidence" to "Preuves",
        "workflow_handoff" to "Relais flux",
        "permission_runbook" to "Permissions",
        "rf_coexistence" to "Coexistence RF",
        "agent_environment" to "Environnement",
        "agent_self_check" to "Auto-test",
        "agent_observation" to "Observation",
        "card_manifest" to "Manifeste",
        "top_cards" to "Cartes clés",
        "mcp_registry" to "Registre MCP",
        "upgrade_audit" to "Audit upgrade",
        "objective_coverage" to "Couverture objectif",
        "release_validation" to "Validation release",
        "soc_compatibility" to "Compat. SOC",
        "mediatek_readiness" to "MediaTek prêt",
        "mediatek_signal_stack" to "Signaux MTK",
        "mediatek_device_validation" to "Preuve appareil",
        "device_evidence_export" to "Export preuve",
        "accelerator_preflight" to "Prévol accel",
        "non_adreno_backend_advisor" to "Conseil backend",
        "mediatek_launch_checklist" to "Lancer MTK",
        "backend_risk" to "Risque backend",
        "inference_compatibility" to "Ajustement inf.",
        "runtime_backend" to "Backend runtime",
        "runtime_stability" to "Stabilité",
        "wifi_analyzer" to "Analyse Wi-Fi",
        "wifi_advisor" to "Conseil Wi-Fi",
        "wifi_channel_decision" to "Décision Wi-Fi",
        "wifi_link" to "Lien Wi-Fi",
        "wifi_nearby" to "Wi-Fi proche",
        "wifi_occupancy" to "Occupation Wi-Fi",
        "bluetooth_analyzer" to "Analyse BT",
        "bluetooth_advisor" to "Conseil BT",
        "bluetooth_decision" to "Décision BT",
        "bluetooth_history" to "Tendances BT",
        "bluetooth_details" to "Détails BT",
        "sensor_analyzer" to "Analyse capteur",
        "sensor_advisor" to "Conseil capteur",
        "motion_decision" to "Décision mouvement",
        "motion_history" to "Tendances mouv.",
        "motion_quality" to "Qualité mouv.",
        "radio_limits" to "Signaux radio",
        "radio_advisor" to "Conseil radio",
        "radio_decision" to "Décision radio",
    ),
)

val LocalHermesStrings = staticCompositionLocalOf { hermesStringsFor(AppLanguage.ENGLISH) }

fun hermesStringsFor(language: AppLanguage): HermesStrings {
    return when (language) {
        AppLanguage.CHINESE -> HermesStrings(
            language = language,
            alphaBadge = "预览版",
            sectionHermes = "Hermes Fork",
            sectionAccounts = "账户",
            sectionPortal = "门户",
            sectionDevice = "设备",
            sectionSettings = "设置",
            subtitleHermes = "聊天、命令与语音",
            subtitleAccounts = "Corr3xt 登录与提供商访问",
            subtitlePortal = "Portal 预览与浏览器回退",
            subtitleDevice = "文件、Linux 套件与手机控制",
            subtitleSettings = "运行时提供商与 API 配置",
            runtimeSetupAndOnboarding = "运行时设置与引导",
            openPageActions = "打开页面操作",
            hermesLogoDescription = "Hermes 标志",
            settingsNewHereTitle = "首次使用？",
            settingsHelpStart = "如果你已经有 API 密钥，请先从 OpenRouter 或其他 API 提供商开始。",
            settingsHelpAccounts = "如果你想使用邮箱、电话或 Google 的 Corr3xt 应用登录流程，请使用账户页面；提供商密钥保留在设置中。",
            appLanguageTitle = "应用语言",
            appLanguageDescription = "轻点旗帜即可立即保存并切换应用语言。",
            onDeviceInferenceTitle = "端侧推理",
            onDeviceInferenceDescription = "选择一个本地推理后端，让 Hermes 在手机上运行模型。",
            llamaCppLabel = "llama.cpp (GGUF)",
            llamaCppDescription = "使用嵌入式 Linux 套件和 GGUF 模型运行本地代理。",
            liteRtLmLabel = "LiteRT-LM",
            liteRtLmDescription = "使用 Google 的 LiteRT-LM Android 运行时加载 .litertlm 模型。",
            noCompatibleLocalModel = "尚未选择兼容的本地模型。请先下载并设为首选模型。",
            chatTitle = "Hermes 聊天",
            openHistory = "打开历史记录",
            history = "历史记录",
            newChat = "新聊天",
            backToChat = "返回聊天",
            clearConversation = "清空对话",
            speakLastReply = "朗读上一条回复",
            welcomeToHermes = "欢迎使用 Hermes",
            welcomeDescription = "可使用聊天、语音输入，或 /help、/history、/provider、/signin 等原生命令。",
            accounts = "账户",
            settings = "设置",
            messageHermes = "向 Hermes 发送消息",
            send = "发送",
            authIntro = "Corr3xt 用于应用登录；提供商访问使用设置中的安全 API 密钥或令牌。",
            corr3xtAuthBaseUrl = "Corr3xt 认证基础 URL",
            saveAuthUrl = "保存认证 URL",
            refresh = "刷新",
            pendingCorr3xtSignIn = "等待中的 Corr3xt 登录",
            signIn = "登录",
            signOut = "退出登录",
            reconnect = "重新连接",
            hermesProviderPrefix = "Hermes 提供商",
            portalTitle = "提供商门户",
            portalEmbeddedDescription = "该页面现在会自动加载嵌入式提供商门户。使用右上角按钮全屏或还原，必要时回退到浏览器。",
            fullScreenPortal = "门户全屏",
            minimizePortal = "还原门户",
            openExternally = "在外部打开",
            refreshPortal = "刷新门户",
            localDownloadsTitle = "Hugging Face 本地模型下载",
            localDownloadsDescription = "直接把完整模型文件下载到手机，使用 Android 系统下载管理器保存进度，并在断网或重启后安全恢复。",
            dataSaverModeTitle = "省流模式",
            dataSaverModeDescription = "启用后，大型模型下载会等待 Wi‑Fi / 非计费网络，以尽量减少移动数据使用。",
            huggingFaceTokenOptional = "Hugging Face 令牌（可选）",
            saveToken = "保存令牌",
            refreshDownloads = "刷新下载",
            repoIdOrDirectUrl = "仓库 ID 或直接 URL",
            filePathInsideRepo = "仓库内文件路径",
            revision = "版本",
            runtimeTarget = "运行目标",
            inspect = "检查",
            download = "下载",
            downloadManagerTitle = "下载管理器",
            noLocalModelDownloadsYet = "还没有本地模型下载。",
            preferredLocalModel = "首选本地模型",
            setPreferred = "设为首选",
            remove = "移除",
        )
        AppLanguage.SPANISH -> HermesStrings(
            language = language,
            alphaBadge = "ALFA",
            sectionHermes = "Hermes Fork",
            sectionAccounts = "Cuentas",
            sectionPortal = "Portal",
            sectionDevice = "Dispositivo",
            sectionSettings = "Ajustes",
            subtitleHermes = "Chat, comandos y voz",
            subtitleAccounts = "Inicio de sesión Corr3xt y acceso a proveedores",
            subtitlePortal = "Vista previa del portal y apertura en navegador",
            subtitleDevice = "Archivos, suite Linux y controles del teléfono",
            subtitleSettings = "Proveedor de runtime y configuración de API",
            runtimeSetupAndOnboarding = "Configuración del runtime y bienvenida",
            openPageActions = "Abrir acciones de la página",
            hermesLogoDescription = "Logo de Hermes",
            settingsNewHereTitle = "¿Nuevo aquí?",
            settingsHelpStart = "Empieza con OpenRouter u otro proveedor con API si ya tienes una clave.",
            settingsHelpAccounts = "Usa Cuentas para flujos Corr3xt de la app con correo, teléfono o Google; las claves de proveedores quedan en Ajustes.",
            appLanguageTitle = "Idioma de la app",
            appLanguageDescription = "Toca una bandera para guardar y cambiar el idioma al instante.",
            onDeviceInferenceTitle = "Inferencia en el dispositivo",
            onDeviceInferenceDescription = "Elige un backend local para que Hermes ejecute modelos en el teléfono.",
            llamaCppLabel = "llama.cpp (GGUF)",
            llamaCppDescription = "Ejecuta el agente local con la suite Linux integrada y modelos GGUF.",
            liteRtLmLabel = "LiteRT-LM",
            liteRtLmDescription = "Carga modelos .litertlm con el runtime Android de LiteRT-LM de Google.",
            noCompatibleLocalModel = "Aún no hay un modelo local compatible seleccionado. Descárgalo y márcalo como preferido primero.",
            chatTitle = "Chat de Hermes",
            openHistory = "Abrir historial",
            history = "Historial",
            newChat = "Nuevo chat",
            backToChat = "Volver al chat",
            clearConversation = "Borrar conversación",
            speakLastReply = "Leer la última respuesta",
            welcomeToHermes = "Bienvenido a Hermes",
            welcomeDescription = "Usa el chat, la voz o comandos nativos como /help, /history, /provider y /signin.",
            accounts = "Cuentas",
            settings = "Ajustes",
            messageHermes = "Enviar mensaje a Hermes",
            send = "Enviar",
            authIntro = "Corr3xt se usa para iniciar sesión en la app; los proveedores usan claves API o tokens seguros en Ajustes.",
            corr3xtAuthBaseUrl = "URL base de autenticación Corr3xt",
            saveAuthUrl = "Guardar URL de autenticación",
            refresh = "Actualizar",
            pendingCorr3xtSignIn = "Inicio de sesión Corr3xt pendiente",
            signIn = "Iniciar sesión",
            signOut = "Cerrar sesión",
            reconnect = "Reconectar",
            hermesProviderPrefix = "Proveedor de Hermes",
            portalTitle = "Portal del proveedor",
            portalEmbeddedDescription = "El portal incrustado ahora se carga automáticamente aquí. Usa el botón superior derecho para maximizar o minimizar la vista previa, o abre el navegador si hace falta.",
            fullScreenPortal = "Portal a pantalla completa",
            minimizePortal = "Minimizar portal",
            openExternally = "Abrir fuera",
            refreshPortal = "Actualizar portal",
            localDownloadsTitle = "Descargas locales de modelos desde Hugging Face",
            localDownloadsDescription = "Descarga archivos completos del modelo al teléfono, conserva el progreso en el gestor de descargas de Android y reanuda con seguridad tras cortes de red o reinicios.",
            dataSaverModeTitle = "Modo ahorro de datos",
            dataSaverModeDescription = "Cuando está activo, las descargas grandes esperan Wi‑Fi / redes no medidas para minimizar el uso de datos móviles.",
            huggingFaceTokenOptional = "Token de Hugging Face (opcional)",
            saveToken = "Guardar token",
            refreshDownloads = "Actualizar descargas",
            repoIdOrDirectUrl = "ID del repositorio o URL directa",
            filePathInsideRepo = "Ruta del archivo dentro del repo",
            revision = "Revisión",
            runtimeTarget = "Objetivo de runtime",
            inspect = "Inspeccionar",
            download = "Descargar",
            downloadManagerTitle = "Gestor de descargas",
            noLocalModelDownloadsYet = "Todavía no hay descargas locales de modelos.",
            preferredLocalModel = "Modelo local preferido",
            setPreferred = "Marcar preferido",
            remove = "Eliminar",
        )
        AppLanguage.GERMAN -> HermesStrings(
            language = language,
            alphaBadge = "ALPHA",
            sectionHermes = "Hermes Fork",
            sectionAccounts = "Konten",
            sectionPortal = "Portal",
            sectionDevice = "Gerät",
            sectionSettings = "Einstellungen",
            subtitleHermes = "Chat, Befehle und Sprache",
            subtitleAccounts = "Corr3xt-Anmeldung und Anbieterzugang",
            subtitlePortal = "Portal-Vorschau und Browser-Fallback",
            subtitleDevice = "Dateien, Linux-Suite und Telefonsteuerung",
            subtitleSettings = "Runtime-Anbieter und API-Konfiguration",
            runtimeSetupAndOnboarding = "Runtime-Einrichtung und Onboarding",
            openPageActions = "Seitenaktionen öffnen",
            hermesLogoDescription = "Hermes-Logo",
            settingsNewHereTitle = "Neu hier?",
            settingsHelpStart = "Beginne mit OpenRouter oder einem anderen API-Anbieter, wenn du bereits einen Schlüssel hast.",
            settingsHelpAccounts = "Nutze Konten für Corr3xt-App-Anmeldungen mit E-Mail, Telefon oder Google; Anbieter-Schlüssel bleiben in den Einstellungen.",
            appLanguageTitle = "App-Sprache",
            appLanguageDescription = "Tippe auf eine Flagge, um die Sprache sofort zu speichern und zu wechseln.",
            onDeviceInferenceTitle = "On-Device-Inferenz",
            onDeviceInferenceDescription = "Wähle ein lokales Backend, damit Hermes Modelle direkt auf dem Telefon ausführt.",
            llamaCppLabel = "llama.cpp (GGUF)",
            llamaCppDescription = "Führe den lokalen Agenten mit der eingebetteten Linux-Suite und GGUF-Modellen aus.",
            liteRtLmLabel = "LiteRT-LM",
            liteRtLmDescription = "Lade .litertlm-Modelle mit Googles LiteRT-LM-Android-Runtime.",
            noCompatibleLocalModel = "Noch kein kompatibles lokales Modell ausgewählt. Bitte zuerst herunterladen und als bevorzugt markieren.",
            chatTitle = "Hermes-Chat",
            openHistory = "Verlauf öffnen",
            history = "Verlauf",
            newChat = "Neuer Chat",
            backToChat = "Zurück zum Chat",
            clearConversation = "Unterhaltung leeren",
            speakLastReply = "Letzte Antwort vorlesen",
            welcomeToHermes = "Willkommen bei Hermes",
            welcomeDescription = "Nutze Chat, Spracheingabe oder native Befehle wie /help, /history, /provider und /signin.",
            accounts = "Konten",
            settings = "Einstellungen",
            messageHermes = "Hermes Nachricht senden",
            send = "Senden",
            authIntro = "Corr3xt wird für die App-Anmeldung genutzt; Anbieter verwenden sichere API-Schlüssel oder Tokens in den Einstellungen.",
            corr3xtAuthBaseUrl = "Corr3xt-Auth-Basis-URL",
            saveAuthUrl = "Auth-URL speichern",
            refresh = "Aktualisieren",
            pendingCorr3xtSignIn = "Ausstehende Corr3xt-Anmeldung",
            signIn = "Anmelden",
            signOut = "Abmelden",
            reconnect = "Neu verbinden",
            hermesProviderPrefix = "Hermes-Anbieter",
            portalTitle = "Anbieterportal",
            portalEmbeddedDescription = "Das eingebettete Portal wird jetzt automatisch geladen. Nutze die Schaltfläche oben rechts zum Maximieren oder Minimieren oder wechsle bei Bedarf in den Browser.",
            fullScreenPortal = "Portal im Vollbild",
            minimizePortal = "Portal minimieren",
            openExternally = "Extern öffnen",
            refreshPortal = "Portal aktualisieren",
            localDownloadsTitle = "Lokale Modell-Downloads von Hugging Face",
            localDownloadsDescription = "Lade komplette Modelldateien direkt auf das Telefon, speichere den Fortschritt im Android-Downloadmanager und setze sicher nach Netzverlust oder Neustart fort.",
            dataSaverModeTitle = "Datensparmodus",
            dataSaverModeDescription = "Wenn aktiviert, warten große Downloads auf Wi‑Fi / ungedrosselte Netze, damit nur minimale mobile Daten verwendet werden.",
            huggingFaceTokenOptional = "Hugging Face Token (optional)",
            saveToken = "Token speichern",
            refreshDownloads = "Downloads aktualisieren",
            repoIdOrDirectUrl = "Repo-ID oder direkte URL",
            filePathInsideRepo = "Dateipfad im Repo",
            revision = "Revision",
            runtimeTarget = "Runtime-Ziel",
            inspect = "Prüfen",
            download = "Herunterladen",
            downloadManagerTitle = "Downloadmanager",
            noLocalModelDownloadsYet = "Noch keine lokalen Modell-Downloads.",
            preferredLocalModel = "Bevorzugtes lokales Modell",
            setPreferred = "Bevorzugen",
            remove = "Entfernen",
        )
        AppLanguage.PORTUGUESE -> HermesStrings(
            language = language,
            alphaBadge = "ALFA",
            sectionHermes = "Hermes Fork",
            sectionAccounts = "Contas",
            sectionPortal = "Portal",
            sectionDevice = "Dispositivo",
            sectionSettings = "Configurações",
            subtitleHermes = "Chat, comandos e voz",
            subtitleAccounts = "Login Corr3xt e acesso a provedores",
            subtitlePortal = "Prévia do portal e fallback no navegador",
            subtitleDevice = "Arquivos, suíte Linux e controles do telefone",
            subtitleSettings = "Provedor de runtime e configuração de API",
            runtimeSetupAndOnboarding = "Configuração do runtime e introdução",
            openPageActions = "Abrir ações da página",
            hermesLogoDescription = "Logo do Hermes",
            settingsNewHereTitle = "Novo por aqui?",
            settingsHelpStart = "Comece com OpenRouter ou outro provedor de API se você já tiver uma chave.",
            settingsHelpAccounts = "Use Contas para fluxos Corr3xt do app com e-mail, telefone ou Google; chaves de provedores ficam nas Configurações.",
            appLanguageTitle = "Idioma do app",
            appLanguageDescription = "Toque em uma bandeira para salvar e trocar o idioma imediatamente.",
            onDeviceInferenceTitle = "Inferência no dispositivo",
            onDeviceInferenceDescription = "Escolha um backend local para que o Hermes execute modelos no telefone.",
            llamaCppLabel = "llama.cpp (GGUF)",
            llamaCppDescription = "Execute o agente local com a suíte Linux integrada e modelos GGUF.",
            liteRtLmLabel = "LiteRT-LM",
            liteRtLmDescription = "Carregue modelos .litertlm com o runtime Android LiteRT-LM do Google.",
            noCompatibleLocalModel = "Ainda não existe um modelo local compatível selecionado. Baixe e marque um como preferido primeiro.",
            chatTitle = "Chat Hermes",
            openHistory = "Abrir histórico",
            history = "Histórico",
            newChat = "Novo chat",
            backToChat = "Voltar ao chat",
            clearConversation = "Limpar conversa",
            speakLastReply = "Ler última resposta",
            welcomeToHermes = "Bem-vindo ao Hermes",
            welcomeDescription = "Use o chat, entrada por voz ou comandos nativos como /help, /history, /provider e /signin.",
            accounts = "Contas",
            settings = "Configurações",
            messageHermes = "Mensagem para Hermes",
            send = "Enviar",
            authIntro = "O Corr3xt é usado para login no app; provedores usam chaves API ou tokens seguros nas Configurações.",
            corr3xtAuthBaseUrl = "URL base de autenticação Corr3xt",
            saveAuthUrl = "Salvar URL de autenticação",
            refresh = "Atualizar",
            pendingCorr3xtSignIn = "Login Corr3xt pendente",
            signIn = "Entrar",
            signOut = "Sair",
            reconnect = "Reconectar",
            hermesProviderPrefix = "Provedor Hermes",
            portalTitle = "Portal do provedor",
            portalEmbeddedDescription = "O portal incorporado agora carrega automaticamente aqui. Use o botão no canto superior direito para maximizar ou minimizar a prévia, ou abra no navegador se precisar.",
            fullScreenPortal = "Portal em tela cheia",
            minimizePortal = "Minimizar portal",
            openExternally = "Abrir externamente",
            refreshPortal = "Atualizar portal",
            localDownloadsTitle = "Downloads locais de modelos do Hugging Face",
            localDownloadsDescription = "Baixe arquivos completos de modelos diretamente para o telefone, mantenha o progresso no gerenciador de downloads do Android e retome com segurança após queda de rede ou reinício.",
            dataSaverModeTitle = "Modo economia de dados",
            dataSaverModeDescription = "Quando ativado, downloads grandes aguardam Wi‑Fi / rede não tarifada para reduzir o uso de dados móveis.",
            huggingFaceTokenOptional = "Token do Hugging Face (opcional)",
            saveToken = "Salvar token",
            refreshDownloads = "Atualizar downloads",
            repoIdOrDirectUrl = "ID do repositório ou URL direta",
            filePathInsideRepo = "Caminho do arquivo no repositório",
            revision = "Revisão",
            runtimeTarget = "Alvo do runtime",
            inspect = "Inspecionar",
            download = "Baixar",
            downloadManagerTitle = "Gerenciador de downloads",
            noLocalModelDownloadsYet = "Ainda não há downloads locais de modelos.",
            preferredLocalModel = "Modelo local preferido",
            setPreferred = "Definir preferido",
            remove = "Remover",
        )
        AppLanguage.FRENCH -> HermesStrings(
            language = language,
            alphaBadge = "ALPHA",
            sectionHermes = "Hermes Fork",
            sectionAccounts = "Comptes",
            sectionPortal = "Portal",
            sectionDevice = "Appareil",
            sectionSettings = "Réglages",
            subtitleHermes = "Chat, commandes et voix",
            subtitleAccounts = "Connexion Corr3xt et accès aux fournisseurs",
            subtitlePortal = "Aperçu du portail et ouverture navigateur",
            subtitleDevice = "Fichiers, suite Linux et contrôles du téléphone",
            subtitleSettings = "Fournisseur de runtime et configuration API",
            runtimeSetupAndOnboarding = "Configuration du runtime et accueil",
            openPageActions = "Ouvrir les actions de la page",
            hermesLogoDescription = "Logo Hermes",
            settingsNewHereTitle = "Nouveau ici ?",
            settingsHelpStart = "Commencez avec OpenRouter ou un autre fournisseur API si vous avez déjà une clé.",
            settingsHelpAccounts = "Utilisez Comptes pour les flux Corr3xt de l’application avec e-mail, téléphone ou Google ; les clés fournisseur restent dans Paramètres.",
            appLanguageTitle = "Langue de l’application",
            appLanguageDescription = "Touchez un drapeau pour enregistrer et changer la langue immédiatement.",
            onDeviceInferenceTitle = "Inférence sur l’appareil",
            onDeviceInferenceDescription = "Choisissez un backend local pour que Hermes exécute des modèles sur le téléphone.",
            llamaCppLabel = "llama.cpp (GGUF)",
            llamaCppDescription = "Exécutez l’agent local avec la suite Linux intégrée et des modèles GGUF.",
            liteRtLmLabel = "LiteRT-LM",
            liteRtLmDescription = "Chargez des modèles .litertlm avec le runtime Android LiteRT-LM de Google.",
            noCompatibleLocalModel = "Aucun modèle local compatible n’est encore sélectionné. Téléchargez-en un puis marquez-le comme préféré.",
            chatTitle = "Chat Hermes",
            openHistory = "Ouvrir l’historique",
            history = "Historique",
            newChat = "Nouveau chat",
            backToChat = "Retour au chat",
            clearConversation = "Effacer la conversation",
            speakLastReply = "Lire la dernière réponse",
            welcomeToHermes = "Bienvenue dans Hermes",
            welcomeDescription = "Utilisez le chat, la voix ou des commandes natives comme /help, /history, /provider et /signin.",
            accounts = "Comptes",
            settings = "Réglages",
            messageHermes = "Message à Hermes",
            send = "Envoyer",
            authIntro = "Corr3xt sert à la connexion à l’application ; les fournisseurs utilisent des clés API ou jetons sécurisés dans Paramètres.",
            corr3xtAuthBaseUrl = "URL de base d’authentification Corr3xt",
            saveAuthUrl = "Enregistrer l’URL d’authentification",
            refresh = "Actualiser",
            pendingCorr3xtSignIn = "Connexion Corr3xt en attente",
            signIn = "Se connecter",
            signOut = "Se déconnecter",
            reconnect = "Reconnecter",
            hermesProviderPrefix = "Fournisseur Hermes",
            portalTitle = "Portail fournisseur",
            portalEmbeddedDescription = "Le portail intégré se charge maintenant automatiquement ici. Utilisez le bouton en haut à droite pour agrandir ou réduire l’aperçu, ou ouvrez le navigateur si nécessaire.",
            fullScreenPortal = "Portail plein écran",
            minimizePortal = "Réduire le portail",
            openExternally = "Ouvrir à l’extérieur",
            refreshPortal = "Actualiser le portail",
            localDownloadsTitle = "Téléchargements locaux de modèles depuis Hugging Face",
            localDownloadsDescription = "Téléchargez des fichiers de modèle complets directement sur le téléphone, conservez la progression dans le gestionnaire de téléchargements Android et reprenez en toute sécurité après une perte réseau ou un redémarrage.",
            dataSaverModeTitle = "Mode économie de données",
            dataSaverModeDescription = "Lorsqu’il est activé, les gros téléchargements attendent le Wi‑Fi / un réseau non limité afin de minimiser les données mobiles.",
            huggingFaceTokenOptional = "Jeton Hugging Face (optionnel)",
            saveToken = "Enregistrer le jeton",
            refreshDownloads = "Actualiser les téléchargements",
            repoIdOrDirectUrl = "ID du dépôt ou URL directe",
            filePathInsideRepo = "Chemin du fichier dans le dépôt",
            revision = "Révision",
            runtimeTarget = "Cible du runtime",
            inspect = "Inspecter",
            download = "Télécharger",
            downloadManagerTitle = "Gestionnaire de téléchargements",
            noLocalModelDownloadsYet = "Aucun téléchargement local de modèle pour l’instant.",
            preferredLocalModel = "Modèle local préféré",
            setPreferred = "Définir comme préféré",
            remove = "Supprimer",
        )
        AppLanguage.ENGLISH -> HermesStrings(
            language = language,
            alphaBadge = "ALPHA",
            sectionHermes = "Hermes Fork",
            sectionAccounts = "Accounts",
            sectionPortal = "Portal",
            sectionDevice = "Device",
            sectionSettings = "Settings",
            subtitleHermes = "Forked mobile AI chat, commands, and voice",
            subtitleAccounts = "Corr3xt sign-in and provider access",
            subtitlePortal = "Portal preview and browser fallback",
            subtitleDevice = "Files, Linux suite, and phone controls",
            subtitleSettings = "Runtime provider and API configuration",
            runtimeSetupAndOnboarding = "Runtime setup and onboarding",
            openPageActions = "Open page actions",
            hermesLogoDescription = "Hermes Agent Fork logo",
            settingsNewHereTitle = "Hermes Agent Fork",
            settingsHelpStart = "Start with OpenRouter or another API provider if you already have a key.",
            settingsHelpAccounts = "Use Accounts for Corr3xt app sign-in with email, phone, or Google; keep provider keys in Settings.",
            appLanguageTitle = "App language",
            appLanguageDescription = "Tap a flag to save and switch the app language immediately.",
            onDeviceInferenceTitle = "On-device inference",
            onDeviceInferenceDescription = "Choose a local backend so Hermes can run models directly on the phone.",
            llamaCppLabel = "llama.cpp (GGUF)",
            llamaCppDescription = "Run the local agent with the embedded Linux suite and GGUF models.",
            liteRtLmLabel = "LiteRT-LM",
            liteRtLmDescription = "Load .litertlm models with Google’s LiteRT-LM Android runtime.",
            noCompatibleLocalModel = "No compatible local model is selected yet. Download one and mark it as preferred first.",
            chatTitle = "Hermes Fork Chat",
            openHistory = "Open history",
            history = "History",
            newChat = "New chat",
            backToChat = "Back to chat",
            clearConversation = "Clear conversation",
            speakLastReply = "Speak last reply",
            welcomeToHermes = "Welcome to Hermes Agent Fork",
            welcomeDescription = "Use chat for normal prompts, voice input, or native app commands like /help, /history, /provider, and /signin.",
            accounts = "Accounts",
            settings = "Settings",
            messageHermes = "Message Hermes Fork",
            send = "Send",
            authIntro = "Corr3xt is used for app sign-in; providers use secure API keys or tokens in Settings.",
            corr3xtAuthBaseUrl = "Corr3xt auth base URL",
            saveAuthUrl = "Save auth URL",
            refresh = "Refresh",
            pendingCorr3xtSignIn = "Pending Corr3xt sign-in",
            signIn = "Sign in",
            signOut = "Sign out",
            reconnect = "Reconnect",
            hermesProviderPrefix = "Hermes provider",
            portalTitle = "Provider Portal",
            portalEmbeddedDescription = "The embedded portal now auto-loads on this page. Use the top-right full screen button to maximize or minimize the preview, or fall back to the browser if verification gets stuck.",
            fullScreenPortal = "Full screen portal",
            minimizePortal = "Minimize portal",
            openExternally = "Open externally",
            refreshPortal = "Refresh portal",
            localDownloadsTitle = "Hugging Face local model downloads",
            localDownloadsDescription = "Download full model files directly to the phone, keep progress in Android’s system download manager, and resume safely after network loss or a phone restart.",
            dataSaverModeTitle = "Data saver mode",
            dataSaverModeDescription = "When enabled, large model downloads wait for Wi‑Fi / unmetered connectivity so Hermes uses only minimal mobile data.",
            huggingFaceTokenOptional = "Hugging Face token (optional)",
            saveToken = "Save token",
            refreshDownloads = "Refresh downloads",
            repoIdOrDirectUrl = "Repo ID or direct URL",
            filePathInsideRepo = "File path inside repo",
            revision = "Revision",
            runtimeTarget = "Runtime target",
            inspect = "Inspect",
            download = "Download",
            downloadManagerTitle = "Download manager",
            noLocalModelDownloadsYet = "No local model downloads yet.",
            preferredLocalModel = "Preferred local model",
            setPreferred = "Set preferred",
            remove = "Remove",
        )
    }
}
