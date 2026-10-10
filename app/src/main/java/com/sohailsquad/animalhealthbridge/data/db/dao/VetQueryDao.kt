package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*
import com.sohailsquad.animalhealthbridge.data.db.entity.VetQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface VetQueryDao {
    @Insert suspend fun insert(query: VetQuery): Long
    @Query("SELECT * FROM vet_queries WHERE caseId = :caseId ORDER BY askedAt")
    fun observeForCase(caseId: Long): Flow<List<VetQuery>>
    @Query("SELECT * FROM vet_queries WHERE id = :queryId")
    suspend fun getById(queryId: Long): VetQuery?
    @Query("SELECT COUNT(*) FROM vet_queries WHERE caseId = :caseId AND status = 'PENDING'")
    suspend fun pendingCount(caseId: Long): Int
    @Query("UPDATE vet_queries SET answerText = :text, answerMediaUris = :media, status = 'ANSWERED', answeredAt = :now WHERE id = :queryId AND status = 'PENDING'")
    suspend fun answer(queryId: Long, text: String, media: String, now: Long): Int
}
