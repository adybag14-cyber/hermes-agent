package com.mobilefork.hermesagent.ui.i18n

fun HermesStrings.toolStreamInterrupted(): String = when (language) {
    AppLanguage.ENGLISH -> "The stream was interrupted. The agent may already have performed actions, even if their results did not arrive. Hermes did not resend this request; check the results before retrying."
    AppLanguage.CHINESE -> "流连接中断。即使结果尚未到达，代理也可能已执行操作。Hermes 未重新发送此请求；重试前请检查结果。"
    AppLanguage.SPANISH -> "El flujo se interrumpió. El agente puede haber actuado aunque no hayan llegado los resultados. Hermes no reenvió la solicitud; comprueba los resultados antes de reintentar."
    AppLanguage.GERMAN -> "Der Stream wurde unterbrochen. Der Agent könnte bereits Aktionen ausgeführt haben, auch wenn Ergebnisse fehlen. Hermes hat die Anfrage nicht erneut gesendet; prüfen Sie vor einem neuen Versuch die Ergebnisse."
    AppLanguage.PORTUGUESE -> "O fluxo foi interrompido. O agente pode já ter executado ações, mesmo sem resultados recebidos. Hermes não reenviou o pedido; confira os resultados antes de tentar novamente."
    AppLanguage.FRENCH -> "Le flux a été interrompu. L'agent a peut-être déjà agi, même si les résultats ne sont pas arrivés. Hermes n'a pas renvoyé la requête ; vérifiez les résultats avant de réessayer."
}

internal fun savedFactsLabel(language: AppLanguage): String = when (language) {
    AppLanguage.ENGLISH -> "saved facts"
    AppLanguage.CHINESE -> "已存记忆"
    AppLanguage.SPANISH -> "datos guardados"
    AppLanguage.GERMAN -> "gespeicherte Fakten"
    AppLanguage.PORTUGUESE -> "fatos salvos"
    AppLanguage.FRENCH -> "faits enregistrés"
}
