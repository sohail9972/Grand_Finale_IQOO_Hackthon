package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translation_entries WHERE sourceHash = :hash")
    suspend fun getByHash(hash: String): com.sohailsquad.animalhealthbridge.data.db.entity.TranslationCache?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: com.sohailsquad.animalhealthbridge.data.db.entity.TranslationCache)
    @Query("DELETE FROM translation_entries WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
