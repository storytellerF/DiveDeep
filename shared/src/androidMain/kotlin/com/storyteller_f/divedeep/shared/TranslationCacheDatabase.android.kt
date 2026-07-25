package com.storyteller_f.divedeep.shared

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun getTranslationCacheDatabaseBuilder(
    context: Context,
): RoomDatabase.Builder<TranslationCacheDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath("translation_cache.db")
    return Room.databaseBuilder<TranslationCacheDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    )
}
