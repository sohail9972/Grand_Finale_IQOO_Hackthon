package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*
import com.sohailsquad.animalhealthbridge.data.db.entity.Vaccination
import kotlinx.coroutines.flow.Flow

@Dao
interface VaccinationDao {
    @Insert suspend fun insert(vaccination: Vaccination): Long
    @Query("SELECT * FROM vaccinations WHERE animalId = :animalId ORDER BY date DESC")
    fun observeForAnimal(animalId: Long): Flow<List<Vaccination>>
}
