package com.mobilefork.hermesagent.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/** Request readable documents while accepting providers with unknown model MIME types. */
internal class LocalModelDocumentPicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}
