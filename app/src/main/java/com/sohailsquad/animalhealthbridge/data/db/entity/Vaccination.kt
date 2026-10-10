package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "vaccinations", foreignKeys = [ForeignKey(entity = AnimalProfile::class,
    parentColumns = ["id"], childColumns = ["animalId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("animalId")])
data class Vaccination(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val animalId: Long,
    val vaccineName: String,
    val date: Long,
    val nextDueDate: Long?,
    val vetName: String,
)
