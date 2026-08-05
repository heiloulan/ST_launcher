package com.wlav.stlauncher.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidStorageSpaceProviderInstrumentedTest {
    @Test
    fun noBackupDirectoryReportsUsableStorage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val space = AndroidStorageSpaceProvider().query(context.noBackupFilesDir.toPath())

        assertTrue(space.totalBytes > 0)
        assertTrue(space.usableBytes > 0)
        assertTrue(space.usableBytes <= space.totalBytes)
    }
}
