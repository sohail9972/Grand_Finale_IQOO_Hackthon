package com.sohailsquad.animalhealthbridge.ml.translation

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One cached translation.
 *
 * ---------------------------------------------------------------------------
 * WHY CACHE AT ALL, ON A DEVICE WITH NO NETWORK?
 * ---------------------------------------------------------------------------
 * IndicTrans2 is a large encoder-decoder; a single sentence takes seconds on a
 * mid-range phone. Farmers describe the same handful of complaints over and
 * over ("not eating since yesterday", "swelling on the leg"), and the vet's
 * follow-up questions repeat across cases. Caching turns the second and later
 * occurrences of a phrase into an instant lookup, which is the difference
 * between a demo that feels slow and one that feels instant.
 *
 * The cache is a pure local optimisation: it is never consulted for anything
 * except text the owner or vet has already produced on this device.
 */
@Entity(
    tableName = "translation_cache",
    indices = [
        // The lookup key is (source text + language pair), so a UNIQUE index on
        // that triple is both the correctness guarantee and the query index.
        Index(value = ["source_text", "source_lang", "target_lang"], unique = true),
    ],
)
data class TranslationCacheEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Original text, exactly as recognised or typed. */
    @ColumnInfo(name = "source_text")
    val sourceText: String,

    /** Translated text. */
    @ColumnInfo(name = "target_text")
    val targetText: String,

    /** Source language code, e.g. "hi". */
    @ColumnInfo(name = "source_lang")
    val sourceLang: String,

    /** Target language code, e.g. "en". */
    @ColumnInfo(name = "target_lang")
    val targetLang: String,

    /**
     * True when the translation came from the model rather than the
     * transliteration/keyword fallback.
     *
     * Kept so a later model upgrade can selectively invalidate only the
     * fallback-derived entries, which are the ones worth recomputing.
     */
    @ColumnInfo(name = "is_model_output")
    val isModelOutput: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Data access for the translation cache.
 *
 * All methods are `suspend` so Room runs them off the main thread.
 */
@Dao
interface TranslationCacheDao {

    /**
     * Inserts a translation, replacing any existing row for the same key.
     *
     * REPLACE is what makes re-translating a phrase idempotent instead of
     * tripping the unique index.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: TranslationCacheEntry): Long

    /**
     * Looks up a cached translation.
     *
     * @return the translated text, or null on a cache miss.
     */
    @Query(
        """
        SELECT target_text FROM translation_cache
        WHERE source_text = :sourceText
          AND source_lang = :sourceLang
          AND target_lang = :targetLang
        LIMIT 1
        """,
    )
    suspend fun get(
        sourceText: String,
        sourceLang: String,
        targetLang: String,
    ): String?

    /** Total entries, for the diagnostics screen. */
    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun count(): Int

    /**
     * Drops fallback-derived entries so they get recomputed once a real model
     * is installed.
     */
    @Query("DELETE FROM translation_cache WHERE is_model_output = 0")
    suspend fun clearFallbacks(): Int

    /** Wipes the cache entirely. */
    @Query("DELETE FROM translation_cache")
    suspend fun clearAll(): Int
}