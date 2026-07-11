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
    Settings(iconRes = R.drawable.ic_nav_settings);

    fun label(strings: HermesStrings): String {
        return when (this) {
            Hermes -> strings.sectionHermes
            Accounts -> strings.sectionAccounts
            NousPortal -> strings.sectionPortal
            Device -> strings.sectionDevice
            Kanban -> "Kanban"
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
                else -> "Shared task board and human task control"
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
