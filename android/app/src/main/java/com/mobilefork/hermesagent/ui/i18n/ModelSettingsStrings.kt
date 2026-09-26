package com.mobilefork.hermesagent.ui.i18n

/** Complete localized copy for the model-first settings flow. */
internal fun modelSettingsText(language: AppLanguage, key: String): String {
    val values = when (key) {
        "choose" -> listOf("Choose a model", "选择模型", "Elige un modelo", "Modell auswählen", "Escolha um modelo", "Choisir un modèle")
        "choose_help" -> listOf("Import a file already on your device, or choose a downloaded model below. No account or internet is needed for local files.", "导入设备上的文件，或选择下方已下载的模型。本地文件无需账号或网络。", "Importa un archivo del dispositivo o elige un modelo descargado. Los archivos locales no requieren cuenta ni internet.", "Datei vom Gerät importieren oder ein heruntergeladenes Modell wählen. Lokale Dateien benötigen weder Konto noch Internet.", "Importe um arquivo do dispositivo ou escolha um modelo baixado. Arquivos locais não precisam de conta nem internet.", "Importez un fichier de cet appareil ou choisissez un modèle téléchargé. Aucun compte ni internet requis pour les fichiers locaux.")
        "import" -> listOf("Import model file", "导入模型文件", "Importar archivo de modelo", "Modelldatei importieren", "Importar arquivo de modelo", "Importer un fichier de modèle")
        "formats" -> listOf("GGUF, LiteRT-LM (.litertlm), or Android .task. The app detects the runtime; you do not need to choose it first.", "支持 GGUF、LiteRT-LM（.litertlm）或 Android .task。应用会识别运行时，无需提前选择。", "GGUF, LiteRT-LM (.litertlm) o Android .task. La app detecta el motor; no necesitas elegirlo primero.", "GGUF, LiteRT-LM (.litertlm) oder Android .task. Die App erkennt die Laufzeit automatisch.", "GGUF, LiteRT-LM (.litertlm) ou Android .task. O app detecta o motor automaticamente.", "GGUF, LiteRT-LM (.litertlm) ou Android .task. L’application détecte le moteur automatiquement.")
        "installed" -> listOf("Models on this device", "此设备上的模型", "Modelos en este dispositivo", "Modelle auf diesem Gerät", "Modelos neste dispositivo", "Modèles sur cet appareil")
        "empty" -> listOf("No models yet. Import a model file above or open Download models below.", "暂无模型。请导入上方的模型文件，或展开下方的下载模型。", "Aún no hay modelos. Importa un archivo arriba o abre Descargar modelos abajo.", "Noch keine Modelle. Oben eine Datei importieren oder unten Modelle herunterladen öffnen.", "Ainda não há modelos. Importe um arquivo acima ou abra Baixar modelos abaixo.", "Aucun modèle. Importez un fichier ci-dessus ou ouvrez Télécharger des modèles ci-dessous.")
        "download" -> listOf("Download models", "下载模型", "Descargar modelos", "Modelle herunterladen", "Baixar modelos", "Télécharger des modèles")
        "download_help" -> listOf("Browse the model catalog. Internet is required only for downloads.", "浏览模型目录。仅下载需要网络。", "Explora el catálogo. Solo las descargas requieren internet.", "Modellkatalog durchsuchen. Nur Downloads benötigen Internet.", "Explore o catálogo. Apenas os downloads precisam de internet.", "Parcourez le catalogue. Seuls les téléchargements nécessitent internet.")
        "download_options" -> listOf("Download options", "下载选项", "Opciones de descarga", "Downloadoptionen", "Opções de download", "Options de téléchargement")
        "download_options_help" -> listOf("Wi-Fi preference and optional Hugging Face access token.", "Wi-Fi 偏好及可选的 Hugging Face 访问令牌。", "Preferencia de Wi-Fi y token opcional de Hugging Face.", "WLAN-Präferenz und optionales Hugging-Face-Zugriffstoken.", "Preferência de Wi-Fi e token opcional do Hugging Face.", "Préférence Wi-Fi et jeton Hugging Face facultatif.")
        "provider" -> listOf("Online provider and model", "在线服务商及模型", "Proveedor y modelo en línea", "Online-Anbieter und Modell", "Provedor e modelo online", "Fournisseur et modèle en ligne")
        "provider_help" -> listOf("Use a remote model with your provider account instead of a local file.", "使用服务商账号连接远程模型，而非本地文件。", "Usa un modelo remoto con tu cuenta en lugar de un archivo local.", "Ein entferntes Modell mit Ihrem Anbieterkonto statt einer lokalen Datei nutzen.", "Use um modelo remoto com sua conta em vez de um arquivo local.", "Utilisez un modèle distant avec votre compte plutôt qu’un fichier local.")
        "generation" -> listOf("Response settings", "回复设置", "Ajustes de respuesta", "Antworteinstellungen", "Configurações de resposta", "Paramètres des réponses")
        "generation_help" -> listOf("Response length, creativity, system prompt, and tool guidance.", "回复长度、创造性、系统提示词及工具指引。", "Longitud, creatividad, instrucciones del sistema y herramientas.", "Länge, Kreativität, Systemanweisung und Werkzeughinweise.", "Comprimento, criatividade, instruções do sistema e ferramentas.", "Longueur, créativité, instructions système et outils.")
        "runtime" -> listOf("Runtime and performance", "运行时及性能", "Motor y rendimiento", "Laufzeit und Leistung", "Motor e desempenho", "Moteur et performances")
        "runtime_help" -> listOf("Inspect or restart the local engine. Defaults work for most models.", "查看或重启本地引擎。默认值适用于多数模型。", "Revisa o reinicia el motor local. Los valores predeterminados sirven para la mayoría de modelos.", "Lokale Engine prüfen oder neu starten. Standardwerte passen für die meisten Modelle.", "Veja ou reinicie o motor local. Os padrões servem para a maioria dos modelos.", "Consultez ou redémarrez le moteur local. Les réglages par défaut conviennent à la plupart des modèles.")
        "advanced" -> listOf("Advanced GGUF settings", "GGUF 高级设置", "Ajustes avanzados de GGUF", "Erweiterte GGUF-Einstellungen", "Configurações avançadas de GGUF", "Paramètres GGUF avancés")
        "advanced_help" -> listOf("llama.cpp runtime lane, cache, and expert launch arguments.", "llama.cpp 运行通道、缓存及专家启动参数。", "Canal de llama.cpp, caché y argumentos de inicio para expertos.", "llama.cpp-Laufzeit, Cache und Startargumente für Experten.", "Canal do llama.cpp, cache e argumentos de inicialização avançados.", "Variante llama.cpp, cache et arguments de démarrage experts.")
        "sharing" -> listOf("Share the local API", "共享本地 API", "Compartir la API local", "Lokale API teilen", "Compartilhar a API local", "Partager l’API locale")
        "sharing_help" -> listOf("Optional connection details for other apps on your device or network.", "可选：供设备或网络上的其他应用连接的详情。", "Datos opcionales de conexión para otras apps del dispositivo o de la red.", "Optionale Verbindungsdaten für andere Apps auf Ihrem Gerät oder im Netzwerk.", "Dados opcionais de conexão para outros apps no dispositivo ou na rede.", "Connexion facultative pour d’autres applications sur cet appareil ou le réseau.")
        "expanded" -> listOf("Expanded", "已展开", "Expandido", "Erweitert", "Expandido", "Développé")
        "collapsed" -> listOf("Collapsed", "已折叠", "Contraído", "Eingeklappt", "Recolhido", "Réduit")
        "remove_title" -> listOf("Remove this model?", "移除此模型？", "¿Eliminar este modelo?", "Dieses Modell entfernen?", "Remover este modelo?", "Supprimer ce modèle ?")
        "remove_help" -> listOf("This removes the app’s copy, not your original imported file. You can import or download it again.", "这会移除应用中的副本，不会删除导入的原始文件。您可以再次导入或下载。", "Se eliminará la copia de la app, no el archivo original importado. Podrás importarlo o descargarlo de nuevo.", "Dies entfernt die App-Kopie, nicht die importierte Originaldatei. Ein erneuter Import oder Download ist möglich.", "Isso remove a cópia do app, não o arquivo original importado. Você pode importar ou baixar novamente.", "Cela supprime la copie de l’application, pas le fichier importé d’origine. Vous pourrez le réimporter ou le télécharger.")
        "cancel" -> listOf("Cancel", "取消", "Cancelar", "Abbrechen", "Cancelar", "Annuler")
        else -> error("Unknown model settings label: $key")
    }
    val index = when (language) {
        AppLanguage.ENGLISH -> 0
        AppLanguage.CHINESE -> 1
        AppLanguage.SPANISH -> 2
        AppLanguage.GERMAN -> 3
        AppLanguage.PORTUGUESE -> 4
        AppLanguage.FRENCH -> 5
    }
    return values[index]
}
