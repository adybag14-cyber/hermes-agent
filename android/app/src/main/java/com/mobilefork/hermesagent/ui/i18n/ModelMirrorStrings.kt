package com.mobilefork.hermesagent.ui.i18n

fun HermesStrings.modelScopeMirrorButton(): String = when (language) {
    AppLanguage.ENGLISH -> "Download via ModelScope"
    AppLanguage.CHINESE -> "通过魔搭下载"
    AppLanguage.SPANISH -> "Descargar desde ModelScope"
    AppLanguage.GERMAN -> "Über ModelScope herunterladen"
    AppLanguage.PORTUGUESE -> "Baixar pelo ModelScope"
    AppLanguage.FRENCH -> "Télécharger via ModelScope"
}

fun HermesStrings.modelScopeMirrorNote(): String = when (language) {
    AppLanguage.ENGLISH -> "Public mirror with the same verified model bytes. No token needed. Network availability varies; interrupted downloads may restart."
    AppLanguage.CHINESE -> "公开镜像与原模型校验一致，无需令牌。可用性因网络而异，中断后可能需要重新下载。"
    AppLanguage.SPANISH -> "Espejo público con los mismos bytes verificados, sin token. La disponibilidad varía; una descarga interrumpida puede reiniciarse."
    AppLanguage.GERMAN -> "Öffentlicher Spiegel mit denselben geprüften Modelldaten, ohne Token. Die Verfügbarkeit hängt vom Netz ab; unterbrochene Downloads können neu beginnen."
    AppLanguage.PORTUGUESE -> "Espelho público com os mesmos dados verificados, sem token. A disponibilidade varia; downloads interrompidos podem recomeçar."
    AppLanguage.FRENCH -> "Miroir public contenant les mêmes données vérifiées, sans jeton. La disponibilité varie ; un téléchargement interrompu peut recommencer."
}

fun HermesStrings.modelScopeResearchNotice(): String = when (language) {
    AppLanguage.ENGLISH -> "Research/evaluation only unless separately licensed. VibeThinker's MIT label does not establish clearance from its Qwen base-model research terms. Review the licences and notices on the mirror before use."
    AppLanguage.CHINESE -> "除非另获授权，请仅用于研究或评估。VibeThinker 的 MIT 标注不代表已解除 Qwen 基础模型的研究许可限制。使用前请阅读镜像中的许可和声明。"
    AppLanguage.SPANISH -> "Solo investigación/evaluación salvo licencia adicional. La etiqueta MIT de VibeThinker no acredita la exención de los términos de investigación de Qwen. Revisa las licencias del espejo."
    AppLanguage.GERMAN -> "Ohne gesonderte Lizenz nur Forschung/Evaluierung. VibeThinkers MIT-Angabe belegt keine Freigabe von den Qwen-Forschungsbedingungen. Bitte die Lizenzen im Spiegel prüfen."
    AppLanguage.PORTUGUESE -> "Apenas pesquisa/avaliação, salvo licença adicional. A indicação MIT do VibeThinker não comprova dispensa dos termos de pesquisa do Qwen. Consulte as licenças no espelho."
    AppLanguage.FRENCH -> "Recherche/évaluation uniquement, sauf licence distincte. La mention MIT de VibeThinker ne prouve pas une dérogation aux conditions de recherche de Qwen. Consultez les licences du miroir."
}

fun HermesStrings.modelScopeLicencesButton(): String = when (language) {
    AppLanguage.ENGLISH -> "ModelScope details and licences"
    AppLanguage.CHINESE -> "魔搭详情与许可"
    AppLanguage.SPANISH -> "Detalles y licencias en ModelScope"
    AppLanguage.GERMAN -> "ModelScope-Details und Lizenzen"
    AppLanguage.PORTUGUESE -> "Detalhes e licenças no ModelScope"
    AppLanguage.FRENCH -> "Détails et licences sur ModelScope"
}
