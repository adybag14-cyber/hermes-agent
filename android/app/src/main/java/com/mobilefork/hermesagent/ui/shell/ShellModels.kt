package com.mobilefork.hermesagent.ui.shell

import androidx.annotation.DrawableRes
import com.mobilefork.hermesagent.R
import com.mobilefork.hermesagent.ui.i18n.HermesStrings

enum class AppSection(
    @DrawableRes val iconRes: Int,
) {
    Hermes(iconRes = R.drawable.ic_nav_hermes),
    // label = "Accounts"
    Accounts(iconRes = R.drawable.ic_nav_accounts),
    // label = "Provider Portal"
    NousPortal(iconRes = R.drawable.ic_nav_portal),
    Device(iconRes = R.drawable.ic_nav_device),
    Kanban(iconRes = R.drawable.ic_nav_kanban),
    Terminal(iconRes = R.drawable.ic_nav_device),
    Settings(iconRes = R.drawable.ic_nav_settings);

    fun label(strings: HermesStrings): String {
        return when (this) {
            Hermes -> strings.sectionHermes
            Accounts -> strings.sectionAccounts
            NousPortal -> strings.sectionPortal
            Device -> strings.sectionDevice
            Kanban -> "Kanban"
            Terminal -> when (strings.language) {
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.CHINESE -> "终端"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.SPANISH -> "Terminal"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.GERMAN -> "Terminal"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.FRENCH -> "Terminal"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.PORTUGUESE -> "Terminal"
                else -> "Terminal"
            }
            Settings -> strings.sectionSettings
        }
    }

    fun navigationLabel(strings: HermesStrings): String {
        return when (this) {
            Device -> when (strings.language) {
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.SPANISH -> "Equipo"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.PORTUGUESE -> "Aparelho"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.FRENCH -> "Appareil"
                else -> label(strings)
            }
            Kanban -> when (strings.language) {
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.CHINESE -> "看板"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.SPANISH -> "Kanban"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.GERMAN -> "Kanban"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.FRENCH -> "Kanban"
                else -> "Kanban"
            }
            Terminal -> label(strings)
            else -> label(strings)
        }
    }

    fun title(strings: HermesStrings): String {
        return when (this) {
            Hermes -> strings.sectionHermes
            Accounts -> strings.sectionAccounts
            NousPortal -> strings.portalTitle
            Device -> strings.sectionDevice
            Kanban -> navigationLabel(strings)
            Terminal -> navigationLabel(strings)
            Settings -> strings.sectionSettings
        }
    }

    fun subtitle(strings: HermesStrings): String {
        return when (this) {
            Hermes -> strings.subtitleHermes
            Accounts -> strings.subtitleAccounts
            NousPortal -> strings.subtitlePortal
            Device -> strings.subtitleDevice
            Kanban -> when (strings.language) {
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.CHINESE -> "共享任务板与人工处置"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.SPANISH -> "Tablero compartido y control humano de tareas"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.GERMAN -> "Gemeinsames Aufgabenboard und manuelle Steuerung"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.PORTUGUESE -> "Quadro compartilhado e controle humano de tarefas"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.FRENCH -> "Tableau partagé et contrôle humain des tâches"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.ENGLISH -> "Shared task board and human task control"
            }
            Terminal -> when (strings.language) {
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.CHINESE -> "手动运行 PRoot Linux 命令"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.SPANISH -> "Ejecuta comandos PRoot Linux manualmente"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.GERMAN -> "PRoot-Linux-Befehle manuell ausführen"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.FRENCH -> "Exécuter manuellement des commandes PRoot Linux"
                com.mobilefork.hermesagent.ui.i18n.AppLanguage.PORTUGUESE -> "Execute comandos PRoot Linux manualmente"
                else -> "Run PRoot Linux commands manually"
            }
            Settings -> strings.subtitleSettings
        }
    }
}

data class ShellActionItem(
    val label: String,
    val description: String = "",
    @DrawableRes val iconRes: Int,
    val onClick: () -> Unit,
)
