package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*
import com.sohailsquad.animalhealthbridge.data.db.entity.Evidence
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(evidence: Evidence): Long
    @Query("SELECT * FROM evidence WHERE caseId = :caseId ORDER BY createdAt")
    fun observeForCase(caseId: Long): Flow<List<Evidence>>
    @Query("SELECT COUNT(*) FROM evidence WHERE caseId = :caseId")
    suspend fun countForCase(caseId: Long): Int
    @Query("SELECT * FROM evidence WHERE caseId = :caseId ORDER BY createdAt")
    suspend fun getForCase(caseId: Long): List<Evidence>
}
