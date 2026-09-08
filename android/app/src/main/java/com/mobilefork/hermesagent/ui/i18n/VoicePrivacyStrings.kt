package com.mobilefork.hermesagent.ui.i18n

object VoicePrivacyText {
    fun title(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Voice input and speech service"
        AppLanguage.CHINESE -> "语音输入与语音服务"
        AppLanguage.SPANISH -> "Entrada de voz y servicio de reconocimiento"
        AppLanguage.GERMAN -> "Spracheingabe und Spracherkennungsdienst"
        AppLanguage.PORTUGUESE -> "Entrada de voz e serviço de reconhecimento"
        AppLanguage.FRENCH -> "Saisie vocale et service de reconnaissance"
    }

    fun body(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Your selected Android speech-recognition app processes microphone audio and may send it to its own provider under that provider’s terms. Offline preference does not guarantee on-device processing. Hermes receives the recognized text and places it in the message composer; it is not sent to your AI provider until you choose Send. Continue only if you agree to use that speech service. Cancel does not open the microphone or request permission."
        AppLanguage.CHINESE -> "您选择的 Android 语音识别应用会处理麦克风音频，并可能依其条款发送给自己的服务商。优先离线不保证设备本地处理。Hermes 仅接收识别文本并填入消息框；在您点击发送前不会发给 AI 服务商。只有同意使用该语音服务时才继续。取消不会打开麦克风或请求权限。"
        AppLanguage.SPANISH -> "La app de reconocimiento de Android elegida procesa el audio del micrófono y puede enviarlo a su proveedor según sus condiciones. Preferir el modo sin conexión no garantiza procesamiento local. Hermes recibe el texto y lo coloca en el editor; no se envía al proveedor de IA hasta que pulses Enviar. Continúa solo si aceptas ese servicio. Cancelar no abre el micrófono ni solicita permiso."
        AppLanguage.GERMAN -> "Die gewählte Android-Spracherkennungsapp verarbeitet Mikrofon-Audio und kann es nach ihren Bedingungen an ihren Anbieter senden. Die Offline-Präferenz garantiert keine lokale Verarbeitung. Hermes erhält den erkannten Text im Eingabefeld; erst Senden übermittelt ihn an den KI-Anbieter. Nur bei Zustimmung zum Sprachdienst fortfahren. Abbrechen öffnet weder das Mikrofon noch eine Berechtigungsanfrage."
        AppLanguage.PORTUGUESE -> "O app Android de reconhecimento escolhido processa o áudio do microfone e pode enviá-lo ao próprio provedor conforme seus termos. Preferência off-line não garante processamento local. Hermes recebe o texto no editor; ele só vai ao provedor de IA quando você toca em Enviar. Continue apenas se concordar com esse serviço. Cancelar não abre o microfone nem solicita permissão."
        AppLanguage.FRENCH -> "L’app Android de reconnaissance choisie traite l’audio du microphone et peut l’envoyer à son fournisseur selon ses conditions. La préférence hors ligne ne garantit pas un traitement local. Hermes reçoit le texte dans le champ de saisie ; il n’est envoyé au fournisseur IA qu’après votre action Envoyer. Continuez uniquement si vous acceptez ce service. Annuler n’ouvre pas le microphone et ne demande aucune autorisation."
    }

    fun accept(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Agree and open voice input"
        AppLanguage.CHINESE -> "同意并打开语音输入"
        AppLanguage.SPANISH -> "Aceptar y abrir entrada de voz"
        AppLanguage.GERMAN -> "Zustimmen und Spracheingabe öffnen"
        AppLanguage.PORTUGUESE -> "Concordar e abrir entrada de voz"
        AppLanguage.FRENCH -> "Accepter et ouvrir la saisie vocale"
    }
}
