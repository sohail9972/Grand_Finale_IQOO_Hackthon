package com.sohailsquad.animalhealthbridge.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.Treatment

class TreatmentRepository(context: Context, private val database: AppDatabase = AppDatabase.get(context)) {
    suspend fun approve(treatment: Treatment): Long = database.withTransaction {
        require(listOf(treatment.medicine, treatment.dosage, treatment.frequency, treatment.duration,
            treatment.route, treatment.vetName, treatment.vetRegistrationNumber).all { it.isNotBlank() }) {
            "Enter every treatment field and the veterinarian's name and registration number."
        }
        require(treatment.withdrawalPeriodDays in 0..3650) { "Enter a withdrawal period between 0 and 3650 days." }
        require(treatment.followUpDate == null || treatment.followUpDate >=
            java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) {
            "Follow-up date cannot be in the past."
        }
        require(database.vetQueryDao().pendingCount(treatment.caseId) == 0) { "Answer pending questions before approval." }
        val case = requireNotNull(database.caseDao().getById(treatment.caseId))
        require(case.processingComplete && case.status != "DRAFT") { "Request review before approving." }
        require(database.treatmentDao().getForCase(case.id) == null) { "Treatment already approved." }
        val id = database.treatmentDao().insert(treatment)
        database.caseDao().update(case.copy(status = "APPROVED"))
        Log.i("AhbVet", "Treatment approved locally case=${case.id} treatment=$id")
        id
    }
    suspend fun getForCase(caseId: Long): Treatment? = database.treatmentDao().getForCase(caseId)
}
