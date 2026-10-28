package com.storyteller_f.divedeep

import android.database.MatrixCursor
import android.os.RemoteException
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSecureSettingsTest {
    @Test
    fun deviceProviderReturnsExistingValue() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val expected = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        assertTrue(expected == "0" || expected == "1")
        assertEquals(expected, AndroidSecureSettings().read(Settings.Secure.ACCESSIBILITY_ENABLED))
    }

    @Test
    fun deviceProviderReturnsAbsentForUnknownKey() {
        assertNull(AndroidSecureSettings().read("divedeep_e2e_nonexistent_${java.util.UUID.randomUUID()}"))
    }

    @Test
    fun missingRowIsAbsent() {
        val cursor = cursor()
        assertNull(AndroidSecureSettings { cursor }.read(SERVICES))
        assertTrue(cursor.isClosed)
    }

    @Test
    fun existingValueIsPreserved() {
        val cursor = cursor().apply { addRow(arrayOf(FOREIGN_SERVICE)) }
        assertEquals(FOREIGN_SERVICE, AndroidSecureSettings { cursor }.read(SERVICES))
        assertTrue(cursor.isClosed)
    }

    @Test
    fun nullCursorPreventsEnableWrites() {
        val writes = mutableListOf<String>()
        val settings = settings(AndroidSecureSettings { null }, writes)
        assertThrows(IllegalStateException::class.java) { settings.enableService() }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun providerFailurePreventsEnableWrites() {
        val writes = mutableListOf<String>()
        val settings = settings(AndroidSecureSettings { throw RemoteException("Unavailable") }, writes)
        assertThrows(RemoteException::class.java) { settings.enableService() }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun nullRowValuePreventsEnableWrites() {
        val cursor = cursor().apply { addRow(arrayOf<Any?>(null)) }
        val writes = mutableListOf<String>()
        val settings = settings(AndroidSecureSettings { cursor }, writes)
        assertThrows(IllegalStateException::class.java) { settings.enableService() }
        assertTrue(writes.isEmpty())
        assertTrue(cursor.isClosed)
    }

    @Test
    fun enablePreservesForeignServices() {
        val values = mutableMapOf(SERVICES to FOREIGN_SERVICE)
        val settings = AndroidE2eSettings(
            readSetting = AndroidSecureSettings { name ->
                cursor().apply { values[name]?.let { addRow(arrayOf(it)) } }
            }::read,
            executeShell = { command ->
                val parts = command.split(' ')
                assertEquals(listOf("settings", "put", "secure"), parts.take(3))
                values[parts[3]] = parts[4]
                ""
            },
        )
        settings.enableService()
        assertEquals(listOf(FOREIGN_SERVICE, OWN_SERVICE), values[SERVICES]?.split(':'))
        assertEquals("1", values[Settings.Secure.ACCESSIBILITY_ENABLED])
    }

    @Test
    fun failedReadbackCannotConfirmDeletion() {
        val writes = mutableListOf<String>()
        val responses = ArrayDeque(listOf(cursor().apply { addRow(arrayOf(OWN_SERVICE)) }))
        val settings = settings(AndroidSecureSettings { responses.removeFirstOrNull() }, writes)
        assertThrows(IllegalStateException::class.java) { settings.disableService() }
        assertEquals(listOf("settings delete secure $SERVICES"), writes)
    }

    private fun settings(reader: AndroidSecureSettings, writes: MutableList<String>) = AndroidE2eSettings(
        readSetting = reader::read,
        executeShell = { writes.add(it); "" },
    )

    private fun cursor() = MatrixCursor(arrayOf(Settings.NameValueTable.VALUE))

    private companion object {
        const val SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        const val FOREIGN_SERVICE = "example.accessibility/.ReaderService"
        const val OWN_SERVICE = "com.storyteller_f.divedeep/com.storyteller_f.divedeep.DiveDeepAccessibilityService"
    }
}
