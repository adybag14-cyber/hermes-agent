package com.mobilefork.hermesagent.backend

import android.content.Context
import java.io.File

/** The Play GGUF engine is the source-built static e30664a engine, never a downloaded executable. */
internal object PlayPackagedLlamaLaunch {
    const val PACKAGED_LIBRARY = "libhermes_android_llama_server_experimental.so"

    fun executable(context: Context): File = File(context.applicationInfo.nativeLibraryDir, PACKAGED_LIBRARY)

    fun workingDirectory(context: Context): File = File(context.filesDir, "hermes-local-runtime").also {
        check(it.isDirectory || it.mkdirs()) { "Could not prepare private local-inference directory" }
    }

    fun start(context: Context, modelPath: String, port: Int, contextSize: Int,
              launchConfig: LlamaCppLaunchConfig, apiKey: String): Process {
        com.mobilefork.hermesagent.play.PlayForegroundLifetime.requireForeground()
        val binary = executable(context)
        check(binary.isFile && binary.canExecute()) { "The Play-packaged GGUF engine is missing" }
        // Unreviewed CLI flags can load external modules or expose additional server features.
        require(launchConfig.additionalArguments.isEmpty()) {
            "Clear additional llama.cpp arguments for the Play-packaged engine; cache and Flash Attention controls remain available"
        }
        val arguments = listOf(binary.absolutePath, "--model", modelPath, "--host", "127.0.0.1",
            "--port", port.toString(), "--api-key", apiKey) +
            LlamaCppServerController.launchArgumentTokensForModel(
                modelPath, contextSizeOverride = contextSize, launchConfig = launchConfig)
        return ProcessBuilder(arguments).directory(workingDirectory(context)).redirectErrorStream(true)
            .apply {
                // Do not inherit a previous full-edition shell's dynamic-loader overrides.
                environment().remove("LD_PRELOAD")
                environment().remove("LD_LIBRARY_PATH")
            }.start()
    }
}
