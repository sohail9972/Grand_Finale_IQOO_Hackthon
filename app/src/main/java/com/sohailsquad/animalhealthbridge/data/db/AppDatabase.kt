package com.sohailsquad.animalhealthbridge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sohailsquad.animalhealthbridge.data.db.dao.AnimalDao
import com.sohailsquad.animalhealthbridge.data.db.dao.CaseDao
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.ml.translation.TranslationCacheDao
import com.sohailsquad.animalhealthbridge.ml.translation.TranslationCacheEntry

/**
 * The app's Room database.
 *
 * Version history:
 *   1 - AnimalProfile only (Step 2).
 *   2 - Case, and the translation cache (Steps 4-5).
 *
 * ---------------------------------------------------------------------------
 * NO DESTRUCTIVE MIGRATION
 * ---------------------------------------------------------------------------
 * There is deliberately no `fallbackToDestructiveMigration()`. A version bump
 * with no migration throws instead of wiping the database - which is the
 * correct trade for a table of clinical records. Version 2 is reached by
 * [MIGRATION_1_2] below, which only ADDS tables, so no existing animal is
 * touched.
 */
@Database(
    entities = [
        AnimalProfile::class,
        Case::class,
        TranslationCacheEntry::class,
    ],
    version = 2,
    // Schemas are not exported yet; flip to true once migrations need diffing.
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    /** Animals. */
    abstract fun animalDao(): AnimalDao

    /** Clinical cases. */
    abstract fun caseDao(): CaseDao

    /** Translation cache. */
    abstract fun translationCacheDao(): TranslationCacheDao

    companion object {

        /** File name on disk. */
        private const val DB_NAME = "animal_health_bridge.db"

        /**
         * Adds the cases and translation_cache tables.
         *
         * Purely additive: no existing column is altered and no row is
         * rewritten, so an animal captured in Step 2 keeps its history intact.
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cases` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `animal_id` INTEGER NOT NULL,
                        `chief_complaint` TEXT NOT NULL,
                        `duration` TEXT NOT NULL,
                        `affected_area` TEXT NOT NULL,
                        `severity_indicators_json` TEXT NOT NULL,
                        `red_flags_json` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `triage_level` TEXT NOT NULL,
                        `triage_rule_id` TEXT NOT NULL,
                        `original_transcript` TEXT NOT NULL,
                        `english_transcript` TEXT NOT NULL,
                        `photo_uri` TEXT,
                        `structured_by` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        FOREIGN KEY(`animal_id`) REFERENCES `animal_profiles`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cases_animal_id_created_at` " +
                        "ON `cases` (`animal_id`, `created_at`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `translation_cache` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `source_text` TEXT NOT NULL,
                        `target_text` TEXT NOT NULL,
                        `source_lang` TEXT NOT NULL,
                        `target_lang` TEXT NOT NULL,
                        `is_model_output` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_translation_cache_source_text_source_lang_target_lang` " +
                        "ON `translation_cache` (`source_text`, `source_lang`, `target_lang`)",
                )
            }
        }

        /**
         * Cached instance.
         *
         * `@Volatile` so a write from one thread is immediately visible to the
         * others; without it a second thread could observe a half-published
         * instance and open a second connection.
         */
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the singleton database, building it on first use.
         *
         * Double-checked locking: the fast path avoids the lock once the
         * instance exists, which matters because every screen calls this.
         *
         * @param context any context; the application context is retained so the
         *   database cannot outlive an Activity.
         */
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}