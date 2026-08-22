package com.mobilefork.hermesagent.ui.i18n

/** Focused six-language copy for the expert llama.cpp runtime controls. */
internal fun llamaCppAdvancedText(language: AppLanguage, key: String): String {
    return when (key) {
        "title" -> when (language) {
            AppLanguage.CHINESE -> "llama.cpp 高级设置"
            AppLanguage.SPANISH -> "Ajustes avanzados de llama.cpp"
            AppLanguage.GERMAN -> "Erweiterte llama.cpp-Einstellungen"
            AppLanguage.PORTUGUESE -> "Ajustes avançados do llama.cpp"
            AppLanguage.FRENCH -> "Réglages avancés de llama.cpp"
            AppLanguage.ENGLISH -> "llama.cpp advanced"
        }
        "description" -> when (language) {
            AppLanguage.CHINESE -> "选择运行通道和安全传递给 llama-server 的参数。更改仅在应用并重启本地运行时后生效。"
            AppLanguage.SPANISH -> "Elige el canal y los argumentos que se pasan de forma segura a llama-server. Los cambios se aplican al reiniciar el runtime local."
            AppLanguage.GERMAN -> "Wähle Laufzeitspur und Argumente, die sicher an llama-server übergeben werden. Änderungen gelten nach dem lokalen Neustart."
            AppLanguage.PORTUGUESE -> "Escolha a faixa e os argumentos enviados com segurança ao llama-server. As mudanças valem após reiniciar o runtime local."
            AppLanguage.FRENCH -> "Choisissez le canal et les arguments transmis de façon sûre à llama-server. Les changements s’appliquent après redémarrage local."
            AppLanguage.ENGLISH -> "Choose the runtime lane and arguments passed safely to llama-server. Changes take effect after applying and restarting the local runtime."
        }
        "lane" -> when (language) {
            AppLanguage.CHINESE -> "运行通道"
            AppLanguage.SPANISH -> "Canal de ejecución"
            AppLanguage.GERMAN -> "Laufzeitspur"
            AppLanguage.PORTUGUESE -> "Faixa de execução"
            AppLanguage.FRENCH -> "Canal d’exécution"
            AppLanguage.ENGLISH -> "Runtime lane"
        }
        "stable" -> when (language) {
            AppLanguage.CHINESE -> "稳定兼容通道"
            AppLanguage.SPANISH -> "Canal estable de compatibilidad"
            AppLanguage.GERMAN -> "Stabile Kompatibilitätsspur"
            AppLanguage.PORTUGUESE -> "Faixa estável de compatibilidade"
            AppLanguage.FRENCH -> "Canal stable de compatibilité"
            AppLanguage.ENGLISH -> "Stable compatibility"
        }
        "stable_description" -> when (language) {
            AppLanguage.CHINESE -> "使用经过验证的内置后端；它不识别 Nanbeige 架构或 Turbo 缓存类型。"
            AppLanguage.SPANISH -> "Usa el backend integrado validado; no reconoce la arquitectura Nanbeige ni cachés Turbo."
            AppLanguage.GERMAN -> "Nutzt das geprüfte integrierte Backend; Nanbeige und Turbo-Cachetypen werden nicht erkannt."
            AppLanguage.PORTUGUESE -> "Usa o backend integrado validado; ele não reconhece Nanbeige nem caches Turbo."
            AppLanguage.FRENCH -> "Utilise le backend intégré validé ; Nanbeige et les caches Turbo ne sont pas reconnus."
            AppLanguage.ENGLISH -> "Uses the validated built-in backend; it does not recognize Nanbeige architecture or Turbo cache types."
        }
        "experimental" -> when (language) {
            AppLanguage.CHINESE -> "实验性 TurboQuant / Nanbeige"
            AppLanguage.SPANISH -> "TurboQuant / Nanbeige experimental"
            AppLanguage.GERMAN -> "Experimentell: TurboQuant / Nanbeige"
            AppLanguage.PORTUGUESE -> "TurboQuant / Nanbeige experimental"
            AppLanguage.FRENCH -> "TurboQuant / Nanbeige expérimental"
            AppLanguage.ENGLISH -> "Experimental TurboQuant / Nanbeige"
        }
        "experimental_description" -> when (language) {
            AppLanguage.CHINESE -> "选择独立的实验性分支，支持 Nanbeige 和 turbo2、turbo3、turbo4 KV 缓存。它尚未达到稳定通道的认证级别。"
            AppLanguage.SPANISH -> "Selecciona un fork experimental separado con Nanbeige y caché KV turbo2, turbo3 y turbo4. Aún no tiene la certificación del canal estable."
            AppLanguage.GERMAN -> "Wählt einen separaten experimentellen Fork mit Nanbeige sowie turbo2-, turbo3- und turbo4-KV-Cache. Noch nicht wie die stabile Spur zertifiziert."
            AppLanguage.PORTUGUESE -> "Seleciona um fork experimental separado com Nanbeige e cache KV turbo2, turbo3 e turbo4. Ainda não tem a certificação da faixa estável."
            AppLanguage.FRENCH -> "Sélectionne un fork expérimental distinct avec Nanbeige et cache KV turbo2, turbo3 et turbo4. Il n’a pas encore la certification du canal stable."
            AppLanguage.ENGLISH -> "Selects a separate experimental fork with Nanbeige and turbo2, turbo3, and turbo4 KV cache. It is not yet certified to the stable lane’s level."
        }
        "cache_k" -> when (language) {
            AppLanguage.CHINESE -> "K 缓存类型"
            AppLanguage.SPANISH -> "Tipo de caché K"
            AppLanguage.GERMAN -> "K-Cachetyp"
            AppLanguage.PORTUGUESE -> "Tipo de cache K"
            AppLanguage.FRENCH -> "Type de cache K"
            AppLanguage.ENGLISH -> "K cache type"
        }
        "cache_v" -> when (language) {
            AppLanguage.CHINESE -> "V 缓存类型"
            AppLanguage.SPANISH -> "Tipo de caché V"
            AppLanguage.GERMAN -> "V-Cachetyp"
            AppLanguage.PORTUGUESE -> "Tipo de cache V"
            AppLanguage.FRENCH -> "Type de cache V"
            AppLanguage.ENGLISH -> "V cache type"
        }
        "q5_explanation" -> when (language) {
            AppLanguage.CHINESE -> "Q5 不是单一选项：可为 K 和 V 分别选择 q5_0 或 q5_1。"
            AppLanguage.SPANISH -> "Q5 no es una sola opción: elige q5_0 o q5_1 por separado para K y V."
            AppLanguage.GERMAN -> "Q5 ist keine einzelne Option: Wähle q5_0 oder q5_1 getrennt für K und V."
            AppLanguage.PORTUGUESE -> "Q5 não é uma única opção: escolha q5_0 ou q5_1 separadamente para K e V."
            AppLanguage.FRENCH -> "Q5 n’est pas une option unique : choisissez q5_0 ou q5_1 séparément pour K et V."
            AppLanguage.ENGLISH -> "Q5 is not one setting: choose q5_0 or q5_1 independently for K and V."
        }
        "flash_attention" -> when (language) {
            AppLanguage.CHINESE -> "Flash Attention"
            AppLanguage.SPANISH -> "Flash Attention"
            AppLanguage.GERMAN -> "Flash Attention"
            AppLanguage.PORTUGUESE -> "Flash Attention"
            AppLanguage.FRENCH -> "Flash Attention"
            AppLanguage.ENGLISH -> "Flash Attention"
        }
        "default" -> when (language) {
            AppLanguage.CHINESE -> "默认"
            AppLanguage.SPANISH -> "Predeterminado"
            AppLanguage.GERMAN -> "Standard"
            AppLanguage.PORTUGUESE -> "Padrão"
            AppLanguage.FRENCH -> "Par défaut"
            AppLanguage.ENGLISH -> "Default"
        }
        "auto" -> when (language) {
            AppLanguage.CHINESE -> "自动"
            AppLanguage.SPANISH -> "Auto"
            AppLanguage.GERMAN -> "Auto"
            AppLanguage.PORTUGUESE -> "Auto"
            AppLanguage.FRENCH -> "Auto"
            AppLanguage.ENGLISH -> "Auto"
        }
        "on" -> when (language) {
            AppLanguage.CHINESE -> "开启"
            AppLanguage.SPANISH -> "Activado"
            AppLanguage.GERMAN -> "Ein"
            AppLanguage.PORTUGUESE -> "Ligado"
            AppLanguage.FRENCH -> "Activé"
            AppLanguage.ENGLISH -> "On"
        }
        "off" -> when (language) {
            AppLanguage.CHINESE -> "关闭"
            AppLanguage.SPANISH -> "Desactivado"
            AppLanguage.GERMAN -> "Aus"
            AppLanguage.PORTUGUESE -> "Desligado"
            AppLanguage.FRENCH -> "Désactivé"
            AppLanguage.ENGLISH -> "Off"
        }
        "turbo_requirement" -> when (language) {
            AppLanguage.CHINESE -> "Turbo3 需要实验性通道和 Flash Attention；默认或自动模式会启用它，关闭模式会被拒绝。"
            AppLanguage.SPANISH -> "Turbo3 requiere el canal experimental y Flash Attention; Predeterminado o Auto lo activan y Desactivado se rechaza."
            AppLanguage.GERMAN -> "Turbo3 benötigt die experimentelle Spur und Flash Attention; Standard oder Auto aktiviert es, Aus wird abgewiesen."
            AppLanguage.PORTUGUESE -> "Turbo3 exige a faixa experimental e Flash Attention; Padrão ou Auto o ativam e Desligado é rejeitado."
            AppLanguage.FRENCH -> "Turbo3 exige le canal expérimental et Flash Attention ; Par défaut ou Auto l’active, Désactivé est refusé."
            AppLanguage.ENGLISH -> "Turbo3 requires the experimental lane and Flash Attention; Default or Auto enables it, while Off is rejected."
        }
        "additional_arguments" -> when (language) {
            AppLanguage.CHINESE -> "专家附加参数"
            AppLanguage.SPANISH -> "Argumentos adicionales expertos"
            AppLanguage.GERMAN -> "Zusätzliche Expertenargumente"
            AppLanguage.PORTUGUESE -> "Argumentos avançados adicionais"
            AppLanguage.FRENCH -> "Arguments experts supplémentaires"
            AppLanguage.ENGLISH -> "Expert additional arguments"
        }
        "arguments_placeholder" -> when (language) {
            AppLanguage.CHINESE -> "每行一个 argv 参数，例如：\n--threads-batch\n4\n--perf"
            AppLanguage.SPANISH -> "Un argumento argv por línea, por ejemplo:\n--threads-batch\n4\n--perf"
            AppLanguage.GERMAN -> "Ein argv-Argument pro Zeile, zum Beispiel:\n--threads-batch\n4\n--perf"
            AppLanguage.PORTUGUESE -> "Um argumento argv por linha, por exemplo:\n--threads-batch\n4\n--perf"
            AppLanguage.FRENCH -> "Un argument argv par ligne, par exemple :\n--threads-batch\n4\n--perf"
            AppLanguage.ENGLISH -> "One argv token per line, for example:\n--threads-batch\n4\n--perf"
        }
        "arguments_description" -> when (language) {
            AppLanguage.CHINESE -> "每一行都是一个独立的 argv 参数，不是 shell 命令。标志的值必须放在下一行；Hermes 会先拒绝不安全格式、受应用管理的参数及已审核标志的错误参数数量，所选后端会在受控重启时验证其余语义。"
            AppLanguage.SPANISH -> "Cada línea es un token argv, no un comando de shell. Pon cada valor en otra línea; Hermes rechaza el formato inseguro, los controles de la app y la aridad errónea de opciones revisadas. El backend elegido valida el resto al reiniciar de forma controlada."
            AppLanguage.GERMAN -> "Jede Zeile ist ein argv-Argument, kein Shell-Befehl. Werte stehen in eigenen Zeilen; Hermes weist unsicheres Format, app-eigene Optionen und falsche Wertanzahlen geprüfter Flags ab. Weitere Semantik prüft das gewählte Backend beim kontrollierten Neustart."
            AppLanguage.PORTUGUESE -> "Cada linha é um token argv, não um comando de shell. Coloque cada valor noutra linha; o Hermes rejeita formato inseguro, controles do app e aridade errada de flags revistas. O backend escolhido valida o restante no reinício controlado."
            AppLanguage.FRENCH -> "Chaque ligne est un élément argv, pas une commande shell. Placez chaque valeur sur sa propre ligne ; Hermes refuse le format dangereux, les options gérées par l’app et l’arité erronée des indicateurs vérifiés. Le backend choisi valide le reste au redémarrage contrôlé."
            AppLanguage.ENGLISH -> "Each line is one argv token, not a shell command. Put each value on its own line; Hermes rejects unsafe syntax, app-owned controls, and wrong arity for reviewed flags. The selected backend validates remaining semantics during the controlled restart."
        }
        "effective" -> when (language) {
            AppLanguage.CHINESE -> "有效配置"
            AppLanguage.SPANISH -> "Configuración efectiva"
            AppLanguage.GERMAN -> "Wirksame Konfiguration"
            AppLanguage.PORTUGUESE -> "Configuração efetiva"
            AppLanguage.FRENCH -> "Configuration effective"
            AppLanguage.ENGLISH -> "Effective configuration"
        }
        "apply_restart" -> when (language) {
            AppLanguage.CHINESE -> "应用并重启 llama.cpp"
            AppLanguage.SPANISH -> "Aplicar y reiniciar llama.cpp"
            AppLanguage.GERMAN -> "Anwenden und llama.cpp neu starten"
            AppLanguage.PORTUGUESE -> "Aplicar e reiniciar o llama.cpp"
            AppLanguage.FRENCH -> "Appliquer et redémarrer llama.cpp"
            AppLanguage.ENGLISH -> "Apply and restart llama.cpp"
        }
        "invalid_stable_turbo" -> when (language) {
            AppLanguage.CHINESE -> "Turbo 缓存类型只能用于实验性 TurboQuant 通道。"
            AppLanguage.SPANISH -> "Los tipos de caché Turbo solo están disponibles en el canal TurboQuant experimental."
            AppLanguage.GERMAN -> "Turbo-Cachetypen sind nur in der experimentellen TurboQuant-Spur verfügbar."
            AppLanguage.PORTUGUESE -> "Tipos de cache Turbo só estão disponíveis na faixa TurboQuant experimental."
            AppLanguage.FRENCH -> "Les caches Turbo ne sont disponibles que dans le canal TurboQuant expérimental."
            AppLanguage.ENGLISH -> "Turbo cache types are available only in the experimental TurboQuant lane."
        }
        "invalid_turbo_flash_off" -> when (language) {
            AppLanguage.CHINESE -> "使用 Turbo 缓存时不能关闭 Flash Attention。"
            AppLanguage.SPANISH -> "Flash Attention no puede estar desactivado con una caché Turbo."
            AppLanguage.GERMAN -> "Flash Attention darf mit einem Turbo-Cache nicht ausgeschaltet sein."
            AppLanguage.PORTUGUESE -> "Flash Attention não pode ficar desligado com cache Turbo."
            AppLanguage.FRENCH -> "Flash Attention ne peut pas être désactivé avec un cache Turbo."
            AppLanguage.ENGLISH -> "Flash Attention cannot be Off when a Turbo cache is selected."
        }
        "invalid_quantized_v_flash_off" -> when (language) {
            AppLanguage.CHINESE -> "量化 V 缓存需要 Flash Attention；不能将其设为关闭。"
            AppLanguage.SPANISH -> "Una caché V cuantizada requiere Flash Attention; no puede estar desactivado."
            AppLanguage.GERMAN -> "Ein quantisierter V-Cache benötigt Flash Attention; Aus ist nicht zulässig."
            AppLanguage.PORTUGUESE -> "Um cache V quantizado exige Flash Attention; ele não pode ficar desligado."
            AppLanguage.FRENCH -> "Un cache V quantifié exige Flash Attention ; il ne peut pas être désactivé."
            AppLanguage.ENGLISH -> "A quantized V cache requires Flash Attention; it cannot be set to Off."
        }
        "invalid_arguments" -> when (language) {
            AppLanguage.CHINESE -> "附加参数无效。每行必须是一个 argv 参数，值紧跟在标志后的下一行；不支持 --flag=value。Hermes 管理的模型、端口、缓存、Flash Attention 和线程标志不能在此覆盖。"
            AppLanguage.SPANISH -> "Los argumentos adicionales no son válidos. Cada línea debe ser un token argv y el valor va en la línea posterior a su bandera; --flag=valor no es compatible. Aquí no se pueden sustituir los controles gestionados por Hermes."
            AppLanguage.GERMAN -> "Die Zusatzargumente sind ungültig. Jede Zeile muss ein argv-Argument sein; der Wert folgt seinem Flag in der nächsten Zeile. --flag=wert wird nicht unterstützt, und von Hermes verwaltete Einstellungen sind gesperrt."
            AppLanguage.PORTUGUESE -> "Os argumentos adicionais são inválidos. Cada linha deve ser um token argv, com o valor na linha após a flag; --flag=valor não é aceito. Controles geridos pelo Hermes não podem ser substituídos aqui."
            AppLanguage.FRENCH -> "Les arguments supplémentaires sont invalides. Chaque ligne doit être un élément argv, la valeur suivant son indicateur à la ligne suivante ; --flag=valeur n’est pas accepté. Les réglages gérés par Hermes sont verrouillés."
            AppLanguage.ENGLISH -> "Additional arguments are invalid. Each line must be one argv token, with a value on the line after its flag; --flag=value is not supported. Hermes-managed controls cannot be overridden here."
        }
        "saved" -> when (language) {
            AppLanguage.CHINESE -> "llama.cpp 高级设置已保存；正在应用本地运行时。"
            AppLanguage.SPANISH -> "Ajustes avanzados de llama.cpp guardados; aplicando el runtime local."
            AppLanguage.GERMAN -> "Erweiterte llama.cpp-Einstellungen gespeichert; lokale Laufzeit wird angewendet."
            AppLanguage.PORTUGUESE -> "Ajustes avançados do llama.cpp salvos; aplicando o runtime local."
            AppLanguage.FRENCH -> "Réglages avancés de llama.cpp enregistrés ; application du runtime local."
            AppLanguage.ENGLISH -> "llama.cpp advanced settings saved; applying the local runtime."
        }
        "danger_title" -> when (language) {
            AppLanguage.CHINESE -> "危险的单次内存覆盖"
            AppLanguage.SPANISH -> "Excepción de RAM peligrosa de un solo uso"
            AppLanguage.GERMAN -> "Gefährliche einmalige RAM-Umgehung"
            AppLanguage.PORTUGUESE -> "Exceção perigosa de RAM para uma tentativa"
            AppLanguage.FRENCH -> "Contournement RAM dangereux pour un seul essai"
            AppLanguage.ENGLISH -> "Dangerous one-shot RAM override"
        }
        "danger_description" -> when (language) {
            AppLanguage.CHINESE -> "仅对下一次启动尝试跳过 Hermes 的 RAM 容量阻止。该覆盖不会保存或导出；Android 仍可能杀死应用或使设备无响应。"
            AppLanguage.SPANISH -> "Omite el bloqueo de capacidad de RAM de Hermes solo en el próximo intento. No se guarda ni exporta; Android aún puede cerrar la app o bloquear el dispositivo."
            AppLanguage.GERMAN -> "Überspringt die Hermes-RAM-Sperre nur beim nächsten Startversuch. Nicht gespeichert oder exportiert; Android kann die App trotzdem beenden oder das Gerät blockieren."
            AppLanguage.PORTUGUESE -> "Ignora o bloqueio de capacidade de RAM do Hermes apenas na próxima tentativa. Não é salvo nem exportado; o Android ainda pode encerrar o app ou travar o aparelho."
            AppLanguage.FRENCH -> "Ignore le blocage de capacité RAM de Hermes uniquement au prochain essai. Rien n’est enregistré ni exporté ; Android peut toujours arrêter l’app ou figer l’appareil."
            AppLanguage.ENGLISH -> "Skips Hermes’ RAM-capacity block for the next startup attempt only. It is not saved or exported; Android may still kill the app or make the device unresponsive."
        }
        "danger_button" -> when (language) {
            AppLanguage.CHINESE -> "忽略 RAM 警告尝试一次"
            AppLanguage.SPANISH -> "Probar una vez pese al aviso de RAM"
            AppLanguage.GERMAN -> "Einmal trotz RAM-Warnung versuchen"
            AppLanguage.PORTUGUESE -> "Tentar uma vez apesar do aviso de RAM"
            AppLanguage.FRENCH -> "Essayer une fois malgré l’alerte RAM"
            AppLanguage.ENGLISH -> "Try once despite RAM warning"
        }
        "danger_dialog_title" -> when (language) {
            AppLanguage.CHINESE -> "确认危险的启动尝试？"
            AppLanguage.SPANISH -> "¿Confirmar intento de inicio peligroso?"
            AppLanguage.GERMAN -> "Gefährlichen Startversuch bestätigen?"
            AppLanguage.PORTUGUESE -> "Confirmar tentativa perigosa?"
            AppLanguage.FRENCH -> "Confirmer l’essai de démarrage dangereux ?"
            AppLanguage.ENGLISH -> "Confirm dangerous startup attempt?"
        }
        "danger_dialog_body" -> when (language) {
            AppLanguage.CHINESE -> "Hermes 将仅在这一次尝试中忽略 RAM 预检。模型仍需有效且可读；其他安全检查保持启用。设备可能严重变慢、应用可能崩溃，未保存的数据可能丢失。"
            AppLanguage.SPANISH -> "Hermes ignorará la comprobación previa de RAM solo en este intento. El modelo debe seguir siendo válido y legible; las demás comprobaciones siguen activas. El dispositivo puede ralentizarse, la app puede fallar y perder datos no guardados."
            AppLanguage.GERMAN -> "Hermes ignoriert die RAM-Vorprüfung nur für diesen Versuch. Das Modell muss gültig und lesbar bleiben; andere Sicherheitsprüfungen bleiben aktiv. Das Gerät kann stark langsamer werden, die App abstürzen und ungespeicherte Daten verloren gehen."
            AppLanguage.PORTUGUESE -> "O Hermes ignorará a pré-verificação de RAM apenas nesta tentativa. O modelo ainda deve ser válido e legível; as demais verificações continuam ativas. O aparelho pode ficar muito lento, o app pode falhar e dados não salvos podem ser perdidos."
            AppLanguage.FRENCH -> "Hermes ignorera le précontrôle RAM uniquement pour cet essai. Le modèle doit rester valide et lisible ; les autres contrôles restent actifs. L’appareil peut beaucoup ralentir, l’app peut planter et des données non enregistrées peuvent être perdues."
            AppLanguage.ENGLISH -> "Hermes will ignore the RAM preflight for this attempt only. The model must still be valid and readable; every other safety check stays enabled. The device may slow severely, the app may crash, and unsaved data may be lost."
        }
        "cancel" -> when (language) {
            AppLanguage.CHINESE -> "取消"
            AppLanguage.SPANISH -> "Cancelar"
            AppLanguage.GERMAN -> "Abbrechen"
            AppLanguage.PORTUGUESE -> "Cancelar"
            AppLanguage.FRENCH -> "Annuler"
            AppLanguage.ENGLISH -> "Cancel"
        }
        "confirm" -> when (language) {
            AppLanguage.CHINESE -> "仅尝试这一次"
            AppLanguage.SPANISH -> "Intentar solo esta vez"
            AppLanguage.GERMAN -> "Nur dieses Mal versuchen"
            AppLanguage.PORTUGUESE -> "Tentar só desta vez"
            AppLanguage.FRENCH -> "Essayer cette fois seulement"
            AppLanguage.ENGLISH -> "Try this time only"
        }
        "danger_starting" -> when (language) {
            AppLanguage.CHINESE -> "正在进行危险的单次 llama.cpp 启动尝试…"
            AppLanguage.SPANISH -> "Iniciando el intento peligroso y único de llama.cpp…"
            AppLanguage.GERMAN -> "Gefährlicher einmaliger llama.cpp-Startversuch läuft…"
            AppLanguage.PORTUGUESE -> "Iniciando a tentativa perigosa e única do llama.cpp…"
            AppLanguage.FRENCH -> "Démarrage de l’essai unique et dangereux de llama.cpp…"
            AppLanguage.ENGLISH -> "Starting the dangerous one-shot llama.cpp attempt…"
        }
        "danger_ready" -> when (language) {
            AppLanguage.CHINESE -> "危险的单次尝试已启动 llama.cpp；内存覆盖现已失效。"
            AppLanguage.SPANISH -> "El intento peligroso inició llama.cpp; la excepción de RAM ya caducó."
            AppLanguage.GERMAN -> "Der gefährliche Versuch hat llama.cpp gestartet; die RAM-Umgehung ist nun abgelaufen."
            AppLanguage.PORTUGUESE -> "A tentativa perigosa iniciou o llama.cpp; a exceção de RAM já expirou."
            AppLanguage.FRENCH -> "L’essai dangereux a démarré llama.cpp ; le contournement RAM est maintenant expiré."
            AppLanguage.ENGLISH -> "The dangerous one-shot attempt started llama.cpp; the RAM override has now expired."
        }
        "danger_failed" -> when (language) {
            AppLanguage.CHINESE -> "危险的单次 llama.cpp 尝试失败；内存覆盖未保存。"
            AppLanguage.SPANISH -> "El intento peligroso de llama.cpp falló; la excepción de RAM no se guardó."
            AppLanguage.GERMAN -> "Der gefährliche llama.cpp-Versuch ist fehlgeschlagen; die RAM-Umgehung wurde nicht gespeichert."
            AppLanguage.PORTUGUESE -> "A tentativa perigosa do llama.cpp falhou; a exceção de RAM não foi salva."
            AppLanguage.FRENCH -> "L’essai dangereux de llama.cpp a échoué ; le contournement RAM n’a pas été enregistré."
            AppLanguage.ENGLISH -> "The dangerous one-shot llama.cpp attempt failed; the RAM override was not saved."
        }
        else -> key
    }
}
