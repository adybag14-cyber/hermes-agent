package com.mobilefork.hermesagent.ui.i18n

enum class McpRuntimeText(
    private val en: String, private val zh: String, private val es: String,
    private val de: String, private val pt: String, private val fr: String,
) {
    DESCRIPTION(
        "External MCP tools for Full-edition Python-agent chats. On-device native-model chat is unchanged.",
        "为完整版 Python 智能体聊天连接外部 MCP 工具。设备端原生模型聊天保持不变。",
        "Herramientas MCP externas para chats del agente Python de la edición Full. El chat nativo local no cambia.",
        "Externe MCP-Tools für Python-Agent-Chats der Full-Version. Native lokale Modell-Chats bleiben unverändert.",
        "Ferramentas MCP externas para chats do agente Python da edição Full. O chat nativo local não muda.",
        "Outils MCP externes pour les chats de l'agent Python de l'édition Full. Le chat natif local ne change pas.",
    ),
    ENABLE("Allow external MCP servers", "允许外部 MCP 服务器", "Permitir servidores MCP externos", "Externe MCP-Server erlauben", "Permitir servidores MCP externos", "Autoriser les serveurs MCP externes"),
    DISCLOSURE(
        "Configured servers can run commands and receive tool inputs. Results may be sent to your selected AI provider. Enable only servers you trust. Auto-start entries can start with the Python runtime. Turn this off to stop and revoke them.",
        "已配置的服务器可以执行命令并接收工具输入，结果可能发送给您选择的 AI 服务商。仅启用您信任的服务器。自动启动项可随 Python 运行时启动。关闭此开关即可停止并撤销访问。",
        "Los servidores configurados pueden ejecutar comandos y recibir entradas de herramientas. Los resultados pueden enviarse a tu proveedor de IA. Activa solo servidores de confianza. Las entradas de inicio automático pueden arrancar con Python. Desactívalo para detenerlos y revocar el acceso.",
        "Konfigurierte Server können Befehle ausführen und Tool-Eingaben empfangen. Ergebnisse können an Ihren KI-Anbieter gesendet werden. Erlauben Sie nur vertrauenswürdige Server. Autostart-Einträge können mit Python starten. Ausschalten stoppt sie und entzieht den Zugriff.",
        "Servidores configurados podem executar comandos e receber entradas de ferramentas. Resultados podem ser enviados ao provedor de IA escolhido. Ative apenas servidores confiáveis. Entradas automáticas podem iniciar com o Python. Desative para parar e revogar o acesso.",
        "Les serveurs configurés peuvent exécuter des commandes et recevoir les entrées des outils. Les résultats peuvent être envoyés à votre fournisseur d'IA. N'activez que des serveurs fiables. Les entrées automatiques peuvent démarrer avec Python. Désactivez pour les arrêter et révoquer l'accès.",
    ),
    FROZEN(
        "Reloaded tools apply to new chats. Existing chats retain their tool schemas; revoked tools cannot run.",
        "重新加载的工具适用于新聊天。现有聊天保留原有工具定义；已撤销的工具无法运行。",
        "Las herramientas recargadas se aplican a chats nuevos. Los existentes conservan sus esquemas; las herramientas revocadas no pueden ejecutarse.",
        "Neu geladene Tools gelten für neue Chats. Bestehende Chats behalten ihre Schemata; entzogene Tools können nicht ausgeführt werden.",
        "Ferramentas recarregadas valem para novos chats. Chats existentes mantêm os esquemas; ferramentas revogadas não executam.",
        "Les outils rechargés s'appliquent aux nouveaux chats. Les chats existants gardent leurs schémas ; les outils révoqués ne s'exécutent plus.",
    ),
    STDIO_HELP(
        "Stdio needs an Android-compatible executable already installed on the device. Add arguments in Advanced JSON. Hermes does not install Python, Node or server packages automatically.",
        "Stdio 需要设备上已安装且兼容 Android 的可执行程序。请在高级 JSON 中添加参数。Hermes 不会自动安装 Python、Node 或服务器软件包。",
        "Stdio necesita un ejecutable compatible con Android ya instalado. Añade argumentos en JSON avanzado. Hermes no instala Python, Node ni paquetes de servidor automáticamente.",
        "Stdio benötigt ein bereits installiertes Android-kompatibles Programm. Argumente im erweiterten JSON hinzufügen. Hermes installiert Python, Node oder Serverpakete nicht automatisch.",
        "Stdio exige um executável compatível com Android já instalado. Adicione argumentos no JSON avançado. O Hermes não instala Python, Node ou pacotes de servidor automaticamente.",
        "Stdio nécessite un exécutable compatible Android déjà installé. Ajoutez les arguments dans le JSON avancé. Hermes n'installe pas automatiquement Python, Node ou les paquets serveur.",
    ),
    HTTP_ADD("Add Streamable HTTP", "添加流式 HTTP", "Añadir HTTP transmisible", "Streamable HTTP hinzufügen", "Adicionar HTTP transmissível", "Ajouter HTTP en continu"),
    EXECUTABLE("Installed executable path or name", "已安装的可执行程序路径或名称", "Ruta o nombre del ejecutable instalado", "Pfad oder Name des installierten Programms", "Caminho ou nome do executável instalado", "Chemin ou nom de l'exécutable installé"),
    BUSY("Connecting or stopping MCP…", "正在连接或停止 MCP…", "Conectando o deteniendo MCP…", "MCP wird verbunden oder gestoppt…", "Conectando ou parando MCP…", "Connexion ou arrêt de MCP…"),
    DISABLED("External MCP is off. Stored configuration is not executed.", "外部 MCP 已关闭，不会执行已保存的配置。", "MCP externo está desactivado. La configuración guardada no se ejecuta.", "Externes MCP ist aus. Gespeicherte Konfiguration wird nicht ausgeführt.", "MCP externo está desativado. A configuração salva não executa.", "MCP externe est désactivé. La configuration enregistrée n'est pas exécutée."),
    NEEDS_RUNTIME("Configuration saved. Start the Python agent runtime, then reload MCP.", "配置已保存。请启动 Python 智能体运行时，然后重新加载 MCP。", "Configuración guardada. Inicia el agente Python y recarga MCP.", "Konfiguration gespeichert. Python-Agent-Laufzeit starten und MCP neu laden.", "Configuração salva. Inicie o agente Python e recarregue o MCP.", "Configuration enregistrée. Démarrez l'agent Python, puis rechargez MCP."),
    READY("MCP ready", "MCP 已就绪", "MCP listo", "MCP bereit", "MCP pronto", "MCP prêt"),
    PARTIAL("Some MCP servers could not connect. Check URLs, credentials and installed commands, then reload.", "部分 MCP 服务器无法连接。请检查 URL、凭据和已安装的命令，然后重新加载。", "Algunos servidores MCP no pudieron conectarse. Revisa las URL, credenciales y comandos instalados y recarga.", "Einige MCP-Server konnten nicht verbunden werden. URLs, Zugangsdaten und installierte Programme prüfen und neu laden.", "Alguns servidores MCP não conectaram. Verifique URLs, credenciais e comandos instalados e recarregue.", "Certains serveurs MCP n'ont pas pu se connecter. Vérifiez les URL, identifiants et commandes installées, puis rechargez."),
    FAILED("MCP configuration could not be applied. Check the JSON and connection settings, then retry.", "无法应用 MCP 配置。请检查 JSON 和连接设置后重试。", "No se pudo aplicar la configuración MCP. Revisa el JSON y la conexión e inténtalo de nuevo.", "MCP-Konfiguration konnte nicht angewendet werden. JSON und Verbindung prüfen und erneut versuchen.", "Não foi possível aplicar a configuração MCP. Verifique o JSON e a conexão e tente novamente.", "La configuration MCP n'a pas pu être appliquée. Vérifiez le JSON et la connexion, puis réessayez."),
    RESTART("MCP cleanup was not verified. Force stop and reopen Hermes before trying again.", "无法验证 MCP 是否已完全停止。请强行停止并重新打开 Hermes 后再试。", "No se verificó el cierre de MCP. Fuerza el cierre de Hermes y vuelve a abrirlo antes de reintentar.", "MCP-Bereinigung nicht bestätigt. Hermes zwangsweise beenden und vor einem neuen Versuch öffnen.", "O encerramento do MCP não foi verificado. Force a parada e reabra o Hermes antes de tentar novamente.", "L'arrêt de MCP n'a pas été vérifié. Forcez l'arrêt de Hermes et rouvrez-le avant de réessayer."),
    CONNECTED("Connected servers", "已连接的服务器", "Servidores conectados", "Verbundene Server", "Servidores conectados", "Serveurs connectés"),
    TOOLS("Available tools", "可用工具", "Herramientas disponibles", "Verfügbare Tools", "Ferramentas disponíveis", "Outils disponibles"),
    ENABLE_CONFIRM("Enable MCP and reload", "启用并重新加载 MCP", "Activar MCP y recargar", "MCP aktivieren und neu laden", "Ativar MCP e recarregar", "Activer MCP et recharger");

    fun inLanguage(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> en
        AppLanguage.CHINESE -> zh
        AppLanguage.SPANISH -> es
        AppLanguage.GERMAN -> de
        AppLanguage.PORTUGUESE -> pt
        AppLanguage.FRENCH -> fr
    }
}

fun HermesStrings.mcpRuntimeText(text: McpRuntimeText): String = text.inLanguage(language)
