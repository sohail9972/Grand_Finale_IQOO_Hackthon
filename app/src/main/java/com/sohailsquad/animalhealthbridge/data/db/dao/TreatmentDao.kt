package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*
import com.sohailsquad.animalhealthbridge.data.db.entity.Treatment
import kotlinx.coroutines.flow.Flow

@Dao
interface TreatmentDao {
    @Insert suspend fun insert(treatment: Treatment): Long
    @Query("SELECT * FROM treatments WHERE caseId = :caseId")
    suspend fun getForCase(caseId: Long): Treatment?
    @Query("SELECT * FROM treatments WHERE caseId = :caseId")
    fun observeForCase(caseId: Long): Flow<Treatment?>
    @Query("SELECT treatments.* FROM treatments JOIN cases ON cases.id = treatments.caseId WHERE cases.animal_id = :animalId ORDER BY approvedAt DESC")
    fun observeForAnimal(animalId: Long): Flow<List<Treatment>>
}
