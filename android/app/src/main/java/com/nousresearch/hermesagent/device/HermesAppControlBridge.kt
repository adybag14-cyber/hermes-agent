package com.nousresearch.hermesagent.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import org.json.JSONObject

object HermesAppControlBridge {
    fun launchPackage(context: Context, packageName: String): JSONObject {
        val appContext = context.applicationContext
        val trimmedPackageName = packageName.trim()
        if (trimmedPackageName.isBlank()) {
            return errorJson(
                exitCode = 64,
                packageName = trimmedPackageName,
                message = "launch_app requires a package_name argument",
            )
        }
        if (trimmedPackageName.indexOf('\u0000') >= 0) {
            return errorJson(
                exitCode = 64,
                packageName = trimmedPackageName,
                message = "launch_app package_name must not contain NUL bytes",
            )
        }

        val intent = appContext.packageManager.getLaunchIntentForPackage(trimmedPackageName)
            ?: return errorJson(
                exitCode = 1,
                packageName = trimmedPackageName,
                message = "$trimmedPackageName is not installed or does not expose a launcher activity",
            )

        return runCatching {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DeviceStateWriter.write(appContext)
            JSONObject()
                .put("success", true)
                .put("exit_code", 0)
                .put("action", "launch_app")
                .put("package_name", trimmedPackageName)
                .put("message", "Opened $trimmedPackageName")
        }.getOrElse { error ->
            val message = when (error) {
                is ActivityNotFoundException -> "$trimmedPackageName does not expose a launchable activity"
                else -> error.message ?: error.javaClass.simpleName
            }
            errorJson(
                exitCode = 1,
                packageName = trimmedPackageName,
                message = message,
            )
        }
    }

    private fun errorJson(exitCode: Int, packageName: String, message: String): JSONObject {
        return JSONObject()
            .put("success", false)
            .put("exit_code", exitCode)
            .put("action", "launch_app")
            .put("package_name", packageName)
            .put("error", message)
    }
}
