package com.mobilefork.hermesagent.ui.i18n

fun HermesStrings.accessibilityDisclosureTitle(): String = when (language) {
    AppLanguage.ENGLISH -> "Accessibility access and device actions"
    AppLanguage.CHINESE -> "无障碍访问与设备操作"
    AppLanguage.SPANISH -> "Acceso de accesibilidad y acciones del dispositivo"
    AppLanguage.GERMAN -> "Bedienungshilfezugriff und Geräteaktionen"
    AppLanguage.PORTUGUESE -> "Acesso de acessibilidade e ações do dispositivo"
    AppLanguage.FRENCH -> "Accès d’accessibilité et actions de l’appareil"
}

fun HermesStrings.accessibilityDisclosureBody(): String = when (language) {
    AppLanguage.ENGLISH -> "Hermes Agent Fork is a general-purpose AI assistant, not an accessibility tool for people with disabilities. In the full GitHub/F-Droid edition, optional accessibility access lets Hermes read visible screen text, controls and foreground app identity, capture supported non-secure screens, and perform taps, swipes, typing and system navigation for your device-control requests and enabled automations. Screen and app data can include personal information. Tool results may be included in the conversation and sent to your selected remote AI provider when remote processing is enabled; a local model processes them on this device. Saved chats and automation records may retain results until you delete them. Android permission alone does not mean you agree. Declining leaves ordinary chat available. You can revoke this consent in Settings → Privacy and safety and switch off the service in Android accessibility settings. Never use it to access accounts or data you are not authorized to control. Agreeing opens Android settings; you must enable Hermes there separately."
    AppLanguage.CHINESE -> "Hermes Agent Fork 是通用 AI 助手，并非为残障人士提供的无障碍工具。在完整的 GitHub/F-Droid 版中，可选的无障碍访问允许 Hermes 读取可见屏幕文本、控件和前台应用标识，截取受支持的非安全屏幕，并根据您的设备控制请求和已启用的自动化执行点击、滑动、输入和系统导航。屏幕和应用数据可能包含个人信息。启用远程处理后，工具结果可能随对话发送给您选择的远程 AI 服务商；本地模型在此设备处理。保存的聊天和自动化记录可能保留结果，直到您删除。Android 权限不代表您已同意。拒绝后仍可使用普通聊天。可在设置 → 隐私与安全中撤销同意，并在 Android 无障碍设置中关闭服务。不得用于访问未经授权的账户或数据。同意后将打开 Android 设置；还需单独启用 Hermes。"
    AppLanguage.SPANISH -> "Hermes Agent Fork es un asistente de IA general, no una herramienta de accesibilidad para personas con discapacidad. En la edición completa de GitHub/F-Droid, el acceso opcional permite leer texto, controles e identidad de la app visible, capturar pantallas no protegidas compatibles y realizar toques, deslizamientos, escritura y navegación para tus solicitudes y automatizaciones activadas. Estos datos pueden contener información personal. Los resultados pueden guardarse en la conversación y enviarse al proveedor de IA remoto elegido si activas el procesamiento remoto; un modelo local los procesa en este dispositivo. Los chats y registros pueden conservar resultados hasta que los elimines. El permiso de Android no implica consentimiento. Rechazar permite seguir usando el chat normal. Revoca el consentimiento en Ajustes → Privacidad y seguridad y desactiva el servicio en la accesibilidad de Android. No accedas a cuentas o datos sin autorización. Aceptar abre los ajustes de Android; allí debes habilitar Hermes por separado."
    AppLanguage.GERMAN -> "Hermes Agent Fork ist ein allgemeiner KI-Assistent, kein Hilfsmittel für Menschen mit Behinderungen. In der vollständigen GitHub/F-Droid-Edition erlaubt der optionale Zugriff das Lesen sichtbarer Texte, Bedienelemente und der Vordergrund-App, Aufnahmen unterstützter ungeschützter Bildschirme sowie Tippen, Wischen, Texteingabe und Systemnavigation für Ihre Anfragen und aktivierten Automatisierungen. Diese Daten können persönlich sein. Ergebnisse können im Chat gespeichert und bei aktivierter Fernverarbeitung an Ihren gewählten KI-Anbieter gesendet werden; ein lokales Modell verarbeitet sie auf diesem Gerät. Chats und Automatisierungsprotokolle können Ergebnisse bis zur Löschung behalten. Die Android-Berechtigung allein ist keine Zustimmung. Ablehnen lässt normalen Chat verfügbar. Widerruf unter Einstellungen → Datenschutz und Sicherheit; den Dienst zusätzlich in Android deaktivieren. Keine unbefugten Konten oder Daten verwenden. Zustimmung öffnet die Android-Einstellungen, wo Hermes gesondert aktiviert werden muss."
    AppLanguage.PORTUGUESE -> "Hermes Agent Fork é um assistente geral de IA, não uma ferramenta de acessibilidade para pessoas com deficiência. Na edição completa GitHub/F-Droid, o acesso opcional permite ler texto, controles e a identidade do app em primeiro plano, capturar telas compatíveis não protegidas e executar toques, deslizes, digitação e navegação para seus pedidos e automações ativadas. Esses dados podem conter informações pessoais. Resultados podem ser salvos na conversa e enviados ao provedor remoto escolhido quando o processamento remoto está ativado; um modelo local os processa neste dispositivo. Chats e registros podem reter resultados até você excluí-los. A permissão do Android não é consentimento. Recusar mantém o chat comum disponível. Revogue em Configurações → Privacidade e segurança e desative o serviço na acessibilidade do Android. Não acesse contas ou dados sem autorização. Concordar abre as configurações do Android; ative Hermes separadamente lá."
    AppLanguage.FRENCH -> "Hermes Agent Fork est un assistant IA généraliste, pas un outil d’accessibilité pour personnes handicapées. Dans l’édition complète GitHub/F-Droid, cet accès facultatif permet de lire le texte, les commandes et l’identité de l’app au premier plan, de capturer les écrans non sécurisés compatibles et d’effectuer des touchers, balayages, saisies et navigations pour vos demandes et automatisations activées. Ces données peuvent être personnelles. Les résultats peuvent être conservés dans la conversation et envoyés au fournisseur IA distant choisi si le traitement distant est activé ; un modèle local les traite sur cet appareil. Les chats et journaux peuvent conserver les résultats jusqu’à leur suppression. L’autorisation Android ne vaut pas consentement. Refuser laisse le chat ordinaire disponible. Révoquez dans Paramètres → Confidentialité et sécurité et désactivez le service dans l’accessibilité Android. N’accédez pas à des comptes ou données sans autorisation. Accepter ouvre les paramètres Android ; vous devez y activer Hermes séparément."
}

fun HermesStrings.acceptAccessibilityDisclosure(): String = when (language) {
    AppLanguage.ENGLISH -> "I agree — open Android settings"
    AppLanguage.CHINESE -> "我同意 — 打开 Android 设置"
    AppLanguage.SPANISH -> "Acepto — abrir ajustes de Android"
    AppLanguage.GERMAN -> "Ich stimme zu — Android-Einstellungen öffnen"
    AppLanguage.PORTUGUESE -> "Concordo — abrir configurações do Android"
    AppLanguage.FRENCH -> "J’accepte — ouvrir les paramètres Android"
}

fun HermesStrings.revokeAccessibilityDisclosure(): String = when (language) {
    AppLanguage.ENGLISH -> "Revoke accessibility consent"
    AppLanguage.CHINESE -> "撤销无障碍访问同意"
    AppLanguage.SPANISH -> "Revocar consentimiento de accesibilidad"
    AppLanguage.GERMAN -> "Zustimmung zur Bedienungshilfe widerrufen"
    AppLanguage.PORTUGUESE -> "Revogar consentimento de acessibilidade"
    AppLanguage.FRENCH -> "Révoquer le consentement d’accessibilité"
}
