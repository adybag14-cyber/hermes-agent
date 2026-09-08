package com.mobilefork.hermesagent.ui.i18n

fun HermesStrings.remoteProcessingTitle(): String = when (language) {
    AppLanguage.ENGLISH -> "Allow remote AI processing?"
    AppLanguage.CHINESE -> "允许远程 AI 处理？"
    AppLanguage.SPANISH -> "¿Permitir procesamiento remoto de IA?"
    AppLanguage.GERMAN -> "KI-Fernverarbeitung erlauben?"
    AppLanguage.PORTUGUESE -> "Permitir processamento remoto de IA?"
    AppLanguage.FRENCH -> "Autoriser le traitement IA distant ?"
}

fun HermesStrings.remoteProcessingDisclosure(): String = when (language) {
    AppLanguage.ENGLISH -> "Sending to this provider transmits your message, relevant chat history, selected attachments, custom instructions and relevant memory or tool results for AI processing. These may contain personal data. Your saved provider credential authenticates the request. The provider receives connection metadata such as your IP address and applies its own privacy and retention terms. Hermes does not send chats to its developer automatically. This permission is optional and specific to the provider and configured endpoint shown below; changing either asks again. Decline to keep this message unsent, or choose an on-device model. Revoke in Settings → Privacy and safety; revocation cannot recall data already received by the provider."
    AppLanguage.CHINESE -> "发送给此服务商会传输您的消息、相关聊天记录、所选附件、自定义指令以及相关记忆或工具结果，以便 AI 处理。这些内容可能包含个人资料。已保存的服务商凭据用于验证请求。服务商会收到 IP 地址等连接信息，并适用其自身的隐私和保留条款。Hermes 不会自动向开发者发送聊天。此权限是可选的，仅针对下方服务商及配置的地址；更改任一项会再次询问。拒绝将不发送此消息，也可选择设备本地模型。可在设置 → 隐私与安全中撤销；撤销无法收回服务商已收到的数据。"
    AppLanguage.SPANISH -> "Se enviarán tu mensaje, historial relevante, adjuntos elegidos, instrucciones personalizadas y memoria o resultados de herramientas relevantes para que el proveedor los procese con IA. Pueden contener datos personales. La credencial guardada autentica la solicitud. El proveedor recibe metadatos como tu IP y aplica sus propias condiciones de privacidad y retención. Hermes no envía chats automáticamente al desarrollador. Es opcional y específico del proveedor y dirección configurada mostrados abajo; cambiar cualquiera requiere nuevo consentimiento. Rechaza para no enviar este mensaje o elige un modelo local. Revoca en Ajustes → Privacidad y seguridad; no se recuperan datos ya recibidos por el proveedor."
    AppLanguage.GERMAN -> "Ihre Nachricht, relevanter Chatverlauf, ausgewählte Anhänge, eigene Anweisungen sowie relevante Erinnerungen oder Werkzeugergebnisse werden zur KI-Verarbeitung an diesen Anbieter gesendet. Sie können persönliche Daten enthalten. Der gespeicherte Zugangsschlüssel authentifiziert die Anfrage. Der Anbieter erhält Verbindungsdaten wie Ihre IP-Adresse; seine Datenschutz- und Speicherregeln gelten. Hermes sendet Chats nicht automatisch an den Entwickler. Die optionale Zustimmung gilt nur für den unten gezeigten Anbieter und konfigurierten Endpunkt; Änderungen erfordern neue Zustimmung. Ablehnen lässt die Nachricht ungesendet; alternativ ein lokales Modell wählen. Widerruf unter Einstellungen → Datenschutz und Sicherheit; bereits empfangene Daten lassen sich dadurch nicht zurückholen."
    AppLanguage.PORTUGUESE -> "Serão enviados sua mensagem, histórico relevante, anexos selecionados, instruções personalizadas e memória ou resultados de ferramentas relevantes para processamento de IA por este provedor. Podem conter dados pessoais. A credencial salva autentica o pedido. O provedor recebe metadados como seu IP e aplica seus próprios termos de privacidade e retenção. Hermes não envia chats automaticamente ao desenvolvedor. É opcional e específico do provedor e endereço configurado abaixo; alterar qualquer um exige novo consentimento. Recuse para não enviar a mensagem ou escolha um modelo local. Revogue em Configurações → Privacidade e segurança; isso não recupera dados já recebidos pelo provedor."
    AppLanguage.FRENCH -> "Votre message, l’historique pertinent, les pièces jointes choisies, les instructions personnalisées et les souvenirs ou résultats d’outils pertinents seront envoyés à ce fournisseur pour traitement IA. Ils peuvent contenir des données personnelles. L’identifiant enregistré authentifie la requête. Le fournisseur reçoit des métadonnées comme votre adresse IP et applique ses propres règles de confidentialité et conservation. Hermes n’envoie pas automatiquement les chats au développeur. Ce consentement facultatif concerne uniquement le fournisseur et l’adresse configurée ci-dessous ; leur modification exige un nouvel accord. Refusez pour ne pas envoyer ce message ou choisissez un modèle local. Révocation dans Paramètres → Confidentialité et sécurité ; elle ne rappelle pas les données déjà reçues."
}

fun HermesStrings.acceptRemoteProcessing(): String = when (language) {
    AppLanguage.ENGLISH -> "Allow and send this message"
    AppLanguage.CHINESE -> "允许并发送此消息"
    AppLanguage.SPANISH -> "Permitir y enviar este mensaje"
    AppLanguage.GERMAN -> "Erlauben und diese Nachricht senden"
    AppLanguage.PORTUGUESE -> "Permitir e enviar esta mensagem"
    AppLanguage.FRENCH -> "Autoriser et envoyer ce message"
}

fun HermesStrings.revokeRemoteProcessing(): String = when (language) {
    AppLanguage.ENGLISH -> "Revoke remote AI processing consent"
    AppLanguage.CHINESE -> "撤销远程 AI 处理同意"
    AppLanguage.SPANISH -> "Revocar consentimiento de IA remota"
    AppLanguage.GERMAN -> "Zustimmung zur KI-Fernverarbeitung widerrufen"
    AppLanguage.PORTUGUESE -> "Revogar consentimento de IA remota"
    AppLanguage.FRENCH -> "Révoquer le consentement IA distant"
}
