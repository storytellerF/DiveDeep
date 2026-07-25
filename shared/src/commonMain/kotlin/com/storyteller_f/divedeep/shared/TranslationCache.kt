package com.storyteller_f.divedeep.shared

import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.RoomDatabase.Builder
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Entity(tableName = "translation_cache")
data class TranslationCacheEntry(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "source_language")
    val sourceLanguage: String,
    @ColumnInfo(name = "target_language")
    val targetLanguage: String,
    @ColumnInfo(name = "source_text")
    val sourceText: String,
    @ColumnInfo(name = "translated_text")
    val translatedText: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Dao
interface TranslationCacheDao {
    @Query("SELECT * FROM translation_cache WHERE cache_key IN (:keys)")
    suspend fun findByKeys(keys: List<String>): List<TranslationCacheEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TranslationCacheEntry>)
}

@Database(entities = [TranslationCacheEntry::class], version = 1)
@ConstructedBy(TranslationCacheDatabaseConstructor::class)
abstract class TranslationCacheDatabase : RoomDatabase() {
    abstract fun translationCacheDao(): TranslationCacheDao
}

@Suppress("KotlinNoActualForExpect")
expect object TranslationCacheDatabaseConstructor : RoomDatabaseConstructor<TranslationCacheDatabase> {
    override fun initialize(): TranslationCacheDatabase
}

fun buildTranslationCacheDatabase(
    builder: Builder<TranslationCacheDatabase>,
): TranslationCacheDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

interface TranslationCache {
    suspend fun findTranslations(request: TranslationRequest): Map<String, String>
    suspend fun saveTranslations(request: TranslationRequest, translations: List<TranslationItem>)
}

class RoomTranslationCache(
    private val dao: TranslationCacheDao,
    private val nowProvider: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : TranslationCache {
    override suspend fun findTranslations(request: TranslationRequest): Map<String, String> {
        val keys = request.items.map { node ->
            translationCacheKey(request.sourceLanguage, request.targetLanguage, node.text)
        }.distinct()
        if (keys.isEmpty()) return emptyMap()
        return dao.findByKeys(keys).associate { entry ->
            entry.cacheKey to entry.translatedText
        }
    }

    override suspend fun saveTranslations(request: TranslationRequest, translations: List<TranslationItem>) {
        if (translations.isEmpty()) return
        val now = nowProvider()
        dao.insertAll(
            translations.map { item ->
                TranslationCacheEntry(
                    cacheKey = translationCacheKey(request.sourceLanguage, request.targetLanguage, item.sourceText),
                    sourceLanguage = request.sourceLanguage,
                    targetLanguage = request.targetLanguage,
                    sourceText = item.sourceText,
                    translatedText = item.translatedText,
                    updatedAt = now,
                )
            },
        )
    }
}

class CachingTranslationService(
    private val delegate: TranslationService,
    private val cache: TranslationCache,
    private val cacheEnabled: () -> Boolean = { true },
) : TranslationService {
    override suspend fun translate(request: TranslationRequest): List<TranslationItem> {
        if (!cacheEnabled() || request.items.isEmpty()) {
            return delegate.translate(request)
        }

        val cachedTranslations = cache.findTranslations(request)
        val missingNodes = request.items.filter { node ->
            translationCacheKey(request.sourceLanguage, request.targetLanguage, node.text) !in cachedTranslations
        }
        val fetchedTranslations = if (missingNodes.isEmpty()) {
            emptyList()
        } else {
            delegate.translate(request.copy(items = missingNodes)).also { translations ->
                cache.saveTranslations(request.copy(items = missingNodes), translations)
            }
        }
        val fetchedByKey = fetchedTranslations.associate { item ->
            translationCacheKey(request.sourceLanguage, request.targetLanguage, item.sourceText) to item.translatedText
        }

        return request.items.mapNotNull { node ->
            val cacheKey = translationCacheKey(request.sourceLanguage, request.targetLanguage, node.text)
            val translatedText = cachedTranslations[cacheKey] ?: fetchedByKey[cacheKey] ?: return@mapNotNull null
            TranslationItem(
                nodeId = node.id,
                sourceText = node.text,
                translatedText = translatedText,
                bounds = node.bounds,
            )
        }
    }
}

fun translationCacheKey(
    sourceLanguage: String,
    targetLanguage: String,
    sourceText: String,
): String =
    md5Hex("${sourceLanguage.length}:$sourceLanguage|${targetLanguage.length}:$targetLanguage|$sourceText")
