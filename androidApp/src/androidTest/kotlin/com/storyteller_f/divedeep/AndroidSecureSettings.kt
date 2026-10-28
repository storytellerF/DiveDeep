package com.storyteller_f.divedeep

import android.database.Cursor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry

/** Null means a successful query found no row; provider failures always propagate. */
internal class AndroidSecureSettings(private val query: (String) -> Cursor? = ::querySetting) {
    fun read(name: String): String? = checkNotNull(query(name)) { "Secure settings query returned no cursor" }.use {
        if (!it.moveToFirst()) return@use null
        val value = checkNotNull(it.getString(it.getColumnIndexOrThrow(Settings.NameValueTable.VALUE))) {
            "Secure setting row has no value"
        }
        check(!it.moveToNext()) { "Secure settings query returned multiple rows" }
        value
    }

    private companion object {
        fun querySetting(name: String): Cursor? {
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            // Settings.getString and ContentResolver.query can turn provider failures into null.
            // Calling the client directly preserves RemoteException; a null cursor is rejected above.
            return checkNotNull(resolver.acquireContentProviderClient(Settings.Secure.CONTENT_URI)) {
                "Secure settings provider unavailable"
            }.use { provider ->
                provider.query(
                    Settings.Secure.CONTENT_URI,
                    arrayOf(Settings.NameValueTable.VALUE),
                    "name=?",
                    arrayOf(name),
                    null,
                )
            }
        }
    }
}
