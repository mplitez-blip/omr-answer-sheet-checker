package com.omr.checker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testVersionComparison_NewerVersion() {
        assertTrue(AppUpdateManager.compareVersions("v1.0.1", "1.0.0") > 0)
        assertTrue(AppUpdateManager.compareVersions("v1.1.0", "1.0.9") > 0)
        assertTrue(AppUpdateManager.compareVersions("v2.0.0", "1.99.99") > 0)
    }

    @Test
    fun testVersionComparison_SameVersion() {
        assertEquals(0, AppUpdateManager.compareVersions("v1.0.0", "1.0.0"))
        assertEquals(0, AppUpdateManager.compareVersions("1.0.0", "1.0.0"))
        assertEquals(0, AppUpdateManager.compareVersions("V1.0.0", "v1.0.0"))
    }

    @Test
    fun testVersionComparison_OlderVersion() {
        assertTrue(AppUpdateManager.compareVersions("v1.0.0", "1.0.1") < 0)
        assertTrue(AppUpdateManager.compareVersions("v0.9.9", "1.0.0") < 0)
    }
}
