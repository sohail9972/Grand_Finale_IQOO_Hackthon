package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "evidence", foreignKeys = [ForeignKey(entity = Case::class,
    parentColumns = ["id"], childColumns = ["caseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("caseId"), Index(value = ["uri"], unique = true)])
data class Evidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val uri: String,
    val mimeType: String,
    val createdAt: Long = System.currentTimeMillis(),
)
