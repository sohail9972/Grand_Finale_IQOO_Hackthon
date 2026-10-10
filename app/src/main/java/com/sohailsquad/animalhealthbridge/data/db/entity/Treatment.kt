package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "treatments", foreignKeys = [ForeignKey(entity = Case::class,
    parentColumns = ["id"], childColumns = ["caseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["caseId"], unique = true)])
data class Treatment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val medicine: String,
    val dosage: String,
    val frequency: String,
    val duration: String,
    val route: String,
    val withdrawalPeriodDays: Int,
    val specialInstructions: String,
    val followUpDate: Long?,
    val vetName: String,
    val vetRegistrationNumber: String,
    val approvedAt: Long = System.currentTimeMillis(),
    val vetNotes: String = "",
)
