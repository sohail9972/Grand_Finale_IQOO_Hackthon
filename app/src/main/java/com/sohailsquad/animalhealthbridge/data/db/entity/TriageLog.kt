package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "triage_logs", foreignKeys = [ForeignKey(entity = Case::class,
    parentColumns = ["id"], childColumns = ["caseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("caseId")])
data class TriageLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val matchedRuleId: String,
    val extractedFieldsJson: String,
    val triageLevel: String,
    val timestamp: Long = System.currentTimeMillis(),
)
