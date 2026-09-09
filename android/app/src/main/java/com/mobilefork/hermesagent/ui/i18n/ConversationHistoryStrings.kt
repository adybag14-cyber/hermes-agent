package com.mobilefork.hermesagent.ui.i18n

enum class ConversationHistoryText(
    private val en: String, private val zh: String, private val es: String,
    private val de: String, private val pt: String, private val fr: String,
) {
    ACTIONS("Chat actions", "聊天操作", "Acciones del chat", "Chat-Aktionen", "Ações do chat", "Actions du chat"),
    RENAME("Rename", "重命名", "Renombrar", "Umbenennen", "Renomear", "Renommer"),
    REGENERATE("Regenerate title locally", "在本地重新生成标题", "Regenerar título localmente", "Titel lokal neu erzeugen", "Gerar título localmente", "Régénérer le titre localement"),
    TITLE("Chat title", "聊天标题", "Título del chat", "Chat-Titel", "Título do chat", "Titre du chat"),
    LOCAL_ONLY(
        "Uses only this chat's text on your device. No AI request is sent.",
        "仅使用设备上此聊天的文字，不发送任何 AI 请求。",
        "Solo usa el texto de este chat en tu dispositivo. No se envía ninguna solicitud de IA.",
        "Verwendet nur diesen Chat-Text auf Ihrem Gerät. Keine KI-Anfrage wird gesendet.",
        "Usa apenas o texto deste chat no dispositivo. Nenhum pedido de IA é enviado.",
        "Utilise seulement le texte de ce chat sur votre appareil. Aucune requête IA n'est envoyée.",
    ),
    DELETE("Delete chat", "删除聊天", "Eliminar chat", "Chat löschen", "Excluir chat", "Supprimer le chat"),
    DELETE_CONFIRM(
        "Remove this chat from the app's history? This cannot be undone. Saved memories and copies held by your provider are not removed.",
        "从应用历史记录中删除此聊天？此操作无法撤销。已保存的记忆及服务商保留的副本不会被删除。",
        "¿Quitar este chat del historial de la app? No se puede deshacer. No se borran las memorias guardadas ni las copias del proveedor.",
        "Diesen Chat aus dem App-Verlauf entfernen? Dies ist endgültig. Gespeicherte Erinnerungen und Kopien beim Anbieter bleiben erhalten.",
        "Remover este chat do histórico do app? Não é possível desfazer. Memórias salvas e cópias do provedor não são removidas.",
        "Retirer ce chat de l'historique de l'application ? Cette action est irréversible. Les souvenirs enregistrés et les copies du fournisseur sont conservés.",
    ),
    SAVE("Save", "保存", "Guardar", "Speichern", "Salvar", "Enregistrer"),
    CANCEL("Cancel", "取消", "Cancelar", "Abbrechen", "Cancelar", "Annuler");

    fun inLanguage(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> en
        AppLanguage.CHINESE -> zh
        AppLanguage.SPANISH -> es
        AppLanguage.GERMAN -> de
        AppLanguage.PORTUGUESE -> pt
        AppLanguage.FRENCH -> fr
    }
}

fun HermesStrings.historyText(text: ConversationHistoryText): String = text.inLanguage(language)
