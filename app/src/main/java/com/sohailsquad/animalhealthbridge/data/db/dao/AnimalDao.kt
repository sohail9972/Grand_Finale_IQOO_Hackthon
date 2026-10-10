package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile

/**
 * Data access for [AnimalProfile].
 *
 * Every method is `suspend`, so Room moves the query off the main thread for us.
 * Nothing here does I/O on the caller's thread, which is what keeps the UI
 * responsive while the app is fully offline.
 */
@Dao
interface AnimalDao {

    /**
     * Inserts a profile without replacing an animal with the same tag number.
     *
     * A conflict leaves the existing identity and its clinical history intact.
     *
     * @return the inserted row id, or -1 if the tag already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: AnimalProfile): Long

    /**
     * Looks up an animal by its tag.
     *
     * @return the matching profile, or null when the tag has never been seen.
     */
    @Query("SELECT * FROM animal_profiles WHERE tag_number = :tag LIMIT 1")
    suspend fun getByTagNumber(tag: String): AnimalProfile?

    /**
     * The five most recently created animals, for the "recent" list.
     *
     * Ordering by creation time rather than tag number means the list reflects
     * what the owner actually worked on last, not alphabetical accident.
     */
    @Query("SELECT * FROM animal_profiles ORDER BY created_at DESC LIMIT 5")
    suspend fun getRecent(): List<AnimalProfile>

    /**
     * Existence check used before inserting.
     *
     * A COUNT rather than a SELECT so the query never materialises a row, and
     * so the caller does not have to distinguish "not found" from "found but
     * null". Note this counts on the unique tag_number index.
     *
     * @return 1 when the tag exists, 0 otherwise.
     */
    @Query("SELECT COUNT(*) FROM animal_profiles WHERE tag_number = :tag")
    suspend fun countByTag(tag: String): Int
    @Query("SELECT * FROM animal_profiles WHERE id = :animalId")
    suspend fun getById(animalId: Long): AnimalProfile?
    @Query("SELECT * FROM animal_profiles ORDER BY created_at DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<AnimalProfile>>
    @androidx.room.Update
    suspend fun update(profile: AnimalProfile)
}
