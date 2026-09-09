package com.mobilefork.hermesagent.device

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import org.json.JSONObject

/** App-data storage and device RAM are different measurements with different authorities. */
internal object AndroidResourceSnapshot {
    fun memorySummary(context: Context): JSONObject {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        val storage = StatFs(context.filesDir.absolutePath)
        val available = storage.availableBytes
        val total = storage.totalBytes
        return JSONObject()
            .put("measurement_available", manager != null)
            .put("memory_scope", "whole_device_not_hermes_heap")
            .put("memory_source", "ActivityManager.MemoryInfo")
            .put("available_bytes", memory.availMem)
            .put("available_label", Formatter.formatFileSize(context, memory.availMem))
            .put("total_bytes", memory.totalMem)
            .put("total_label", Formatter.formatFileSize(context, memory.totalMem))
            .put("low_memory", memory.lowMemory)
            .put("threshold_bytes", memory.threshold)
            .put("threshold_label", Formatter.formatFileSize(context, memory.threshold))
            .put("app_data_storage_path", context.filesDir.absolutePath)
            .put("app_data_storage_source", "StatFs.availableBytes")
            .put("app_data_free_bytes", available)
            .put("app_data_total_bytes", total)
            .put("app_data_free_label", Formatter.formatFileSize(context, available))
            .put("app_data_total_label", Formatter.formatFileSize(context, total))
    }
}
