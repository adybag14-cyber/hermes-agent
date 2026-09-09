package com.mobilefork.hermesagent.device

import android.os.StatFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidResourceSnapshotRobotest {
    @Test
    fun storageUsesTheAppDataMountAndAvailableRatherThanReservedFreeBlocks() {
        val context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir.absolutePath, 100, 70, 20)
        ShadowStatFs.registerStats("/system", 100, 0, 0)
        val expected = StatFs(context.filesDir.absolutePath)
        val report = AndroidResourceSnapshot.memorySummary(context)
        assertEquals(context.filesDir.absolutePath, report.getString("app_data_storage_path"))
        assertEquals(expected.availableBytes, report.getLong("app_data_free_bytes"))
        assertEquals(expected.totalBytes, report.getLong("app_data_total_bytes"))
        assertTrue(report.getLong("app_data_free_bytes") > 0)
        assertTrue(report.getLong("app_data_free_bytes") < expected.freeBytes)
        assertEquals("whole_device_not_hermes_heap", report.getString("memory_scope"))
    }
}
