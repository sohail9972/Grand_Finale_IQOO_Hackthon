package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*
import com.sohailsquad.animalhealthbridge.data.db.entity.TriageLog

@Dao
interface TriageLogDao {
    @Insert suspend fun insert(log: TriageLog)
    @Query("SELECT * FROM triage_logs WHERE caseId = :caseId ORDER BY timestamp, id")
    suspend fun getByCaseId(caseId: Long): List<TriageLog>

    @Query("SELECT COUNT(*) FROM triage_logs WHERE triageLevel = :level")
    suspend fun countByLevel(level: String): Int

    suspend fun getForCase(caseId: Long): List<TriageLog> = getByCaseId(caseId)
}
