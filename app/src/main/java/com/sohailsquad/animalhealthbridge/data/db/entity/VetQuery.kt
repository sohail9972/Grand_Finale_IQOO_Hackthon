package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "vet_queries", foreignKeys = [ForeignKey(entity = Case::class,
    parentColumns = ["id"], childColumns = ["caseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("caseId")])
data class VetQuery(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val vetId: String,
    val vetName: String,
    val questionTextEnglish: String,
    val questionTextNative: String,
    val nativeLanguage: String,
    val queryType: String,
    val status: String = "PENDING",
    val answerText: String = "",
    val answerMediaUris: String = "[]",
    val askedAt: Long = System.currentTimeMillis(),
    val answeredAt: Long? = null,
    val translationFallback: Boolean = false,
)
