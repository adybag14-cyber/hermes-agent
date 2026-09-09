package com.mobilefork.hermesagent.ui.i18n

fun HermesStrings.playEditionSummary(): String = when (language) {
    AppLanguage.ENGLISH -> "Play edition · AI-generated replies · Foreground chat and packaged local inference. No Linux installs, device automation or persistent background agent."
    AppLanguage.CHINESE -> "Play 版 · AI 生成回复 · 前台聊天与应用内置本地推理。不支持 Linux 安装、设备自动化或持续后台智能体。"
    AppLanguage.SPANISH -> "Edición Play · Respuestas de IA · Chat en primer plano e inferencia local incluida. Sin instalaciones Linux, automatización del dispositivo ni agente persistente en segundo plano."
    AppLanguage.GERMAN -> "Play-Edition · KI-generierte Antworten · Vordergrund-Chat und mitgelieferte lokale Inferenz. Keine Linux-Installationen, Geräteautomatisierung oder dauerhaften Hintergrundagenten."
    AppLanguage.PORTUGUESE -> "Edição Play · Respostas de IA · Chat em primeiro plano e inferência local incluída. Sem instalações Linux, automação do dispositivo ou agente persistente em segundo plano."
    AppLanguage.FRENCH -> "Édition Play · Réponses IA · Chat au premier plan et inférence locale intégrée. Sans installation Linux, automatisation de l’appareil ni agent permanent en arrière-plan."
}

object PlaySettingsText {
    fun localModelHelp(strings: HermesStrings, playEdition: Boolean): String {
        if (!playEdition) return strings.llamaCppDescription
        return when (strings.language) {
            AppLanguage.ENGLISH -> "Run supported GGUF models on this device with the inference engine packaged in the Play app. No Linux installation is needed."
            AppLanguage.CHINESE -> "使用 Play 应用内置的推理引擎，在此设备上运行支持的 GGUF 模型。无需安装 Linux。"
            AppLanguage.SPANISH -> "Ejecuta modelos GGUF compatibles en este dispositivo con el motor de inferencia incluido en la app Play. No necesitas instalar Linux."
            AppLanguage.GERMAN -> "Führen Sie unterstützte GGUF-Modelle mit der in der Play-App enthaltenen Inferenz-Engine auf diesem Gerät aus. Keine Linux-Installation erforderlich."
            AppLanguage.PORTUGUESE -> "Execute modelos GGUF compatíveis neste dispositivo com o motor de inferência incluído no app Play. Não é necessário instalar Linux."
            AppLanguage.FRENCH -> "Exécutez les modèles GGUF compatibles sur cet appareil avec le moteur d'inférence intégré à l'application Play. Aucune installation Linux n'est nécessaire."
        }
    }

    fun remoteProviderHelp(strings: HermesStrings, playEdition: Boolean): String {
        if (!playEdition) return strings.remoteFallbackDescription()
        return when (strings.language) {
            AppLanguage.ENGLISH -> "Choose a supported remote provider and enter an existing API credential. Tapping a provider fills common defaults; this Play app does not open account signup or payment pages. Sending a message requires your consent."
            AppLanguage.CHINESE -> "选择支持的远程服务商，并输入已有的 API 凭据。点击服务商可填入常用默认值；此 Play 应用不会打开账户注册或付款页面。发送消息需要您的同意。"
            AppLanguage.SPANISH -> "Elige un proveedor remoto compatible e introduce una credencial API existente. Al tocarlo se rellenan valores comunes; esta app Play no abre páginas de registro ni pago. Enviar mensajes requiere tu consentimiento."
            AppLanguage.GERMAN -> "Wählen Sie einen unterstützten Fernanbieter und geben Sie einen vorhandenen API-Schlüssel ein. Antippen setzt Standardwerte; die Play-App öffnet keine Registrierungs- oder Zahlungsseiten. Nachrichten erfordern Ihre Zustimmung."
            AppLanguage.PORTUGUESE -> "Escolha um provedor remoto compatível e insira uma credencial API existente. Ao tocar nele, os padrões são preenchidos; este app Play não abre páginas de cadastro ou pagamento. O envio exige seu consentimento."
            AppLanguage.FRENCH -> "Choisissez un fournisseur distant compatible et saisissez un identifiant API existant. Le toucher remplit les valeurs courantes ; cette application Play n'ouvre pas de pages d'inscription ou de paiement. L'envoi requiert votre consentement."
        }
    }

    fun conventionalCache(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Conventional cache profile"
        AppLanguage.CHINESE -> "常规缓存配置"
        AppLanguage.SPANISH -> "Perfil de caché convencional"
        AppLanguage.GERMAN -> "Konventionelles Cache-Profil"
        AppLanguage.PORTUGUESE -> "Perfil de cache convencional"
        AppLanguage.FRENCH -> "Profil de cache classique"
    }

    fun packagedEngine(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Play uses the packaged static e30664a GGUF engine for both cache profiles, not the full edition's Termux Stable executable. No shell or executable download is used. Clear additional CLI arguments; cache and Flash Attention settings are supported."
        AppLanguage.CHINESE -> "Play 两种缓存配置均使用内置静态 e30664a GGUF 引擎，而非完整版的 Termux Stable 程序。不使用 shell 或可执行文件下载。请清空额外命令行参数；支持缓存和 Flash Attention 设置。"
        AppLanguage.SPANISH -> "Play usa el motor GGUF estático e30664a incluido para ambos perfiles, no el ejecutable Termux Stable de la edición completa. Sin shell ni descargas ejecutables. Borra los argumentos CLI adicionales; admite caché y Flash Attention."
        AppLanguage.GERMAN -> "Play nutzt für beide Cache-Profile die mitgelieferte statische e30664a-GGUF-Engine, nicht Termux Stable der Vollversion. Keine Shell oder ausführbaren Downloads. Zusätzliche CLI-Argumente löschen; Cache und Flash Attention werden unterstützt."
        AppLanguage.PORTUGUESE -> "Play usa o motor GGUF estático e30664a incluído nos dois perfis, não o Termux Stable da edição completa. Sem shell ou downloads executáveis. Limpe argumentos CLI extras; cache e Flash Attention são suportados."
        AppLanguage.FRENCH -> "Play utilise le moteur GGUF statique e30664a intégré pour les deux profils, pas Termux Stable de l’édition complète. Sans shell ni téléchargement exécutable. Effacez les arguments CLI supplémentaires ; cache et Flash Attention sont pris en charge."
    }

    fun saved(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Settings saved. Remote chat starts only when you send a message and consent to the selected provider."
        AppLanguage.CHINESE -> "设置已保存。仅在您发送消息并同意使用所选服务商后才会开始远程聊天。"
        AppLanguage.SPANISH -> "Ajustes guardados. El chat remoto empieza al enviar un mensaje y aceptar el proveedor elegido."
        AppLanguage.GERMAN -> "Einstellungen gespeichert. Fern-Chat startet erst beim Senden einer Nachricht mit Zustimmung zum gewählten Anbieter."
        AppLanguage.PORTUGUESE -> "Configurações salvas. O chat remoto começa ao enviar uma mensagem e consentir com o provedor escolhido."
        AppLanguage.FRENCH -> "Paramètres enregistrés. Le chat distant commence lors de l’envoi d’un message avec votre accord pour le fournisseur choisi."
    }
}
