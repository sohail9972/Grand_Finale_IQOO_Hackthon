package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [Case].
 *
 * All methods are `suspend` except the flow-returning ones, which Room drives
 * on its own executor. Nothing here performs I/O on the caller's thread.
 */
@Dao
interface CaseDao {

    /**
     * Inserts a case.
     *
     * @return the new row id.
     */
    @Insert
    suspend fun insert(case: Case): Long

    /** Replaces an existing case row. */
    @Update
    suspend fun update(case: Case)

    /**
     * Loads one case.
     *
     * @return the case, or null when the id is unknown.
     */
    @Query("SELECT * FROM cases WHERE id = :caseId LIMIT 1")
    suspend fun getById(caseId: Long): Case?

    /**
     * Live view of one case, for a screen that must react to edits.
     *
     * A Flow rather than a one-shot query because the vet may amend the case
     * while the review screen is open.
     */
    @Query("SELECT * FROM cases WHERE id = :caseId LIMIT 1")
    fun observeById(caseId: Long): Flow<Case?>

    /**
     * Full history for an animal, newest first.
     *
     * Drives the health-record timeline.
     */
    @Query("SELECT * FROM cases WHERE animal_id = :animalId ORDER BY created_at DESC")
    suspend fun getForAnimal(animalId: Long): List<Case>

    /** Live version of [getForAnimal], for the timeline screen. */
    @Query("SELECT * FROM cases WHERE animal_id = :animalId ORDER BY created_at DESC")
    fun observeForAnimal(animalId: Long): Flow<List<Case>>

    /**
     * The animal's most recent case.
     *
     * Used to answer "is there an active case for this animal?" without loading
     * the whole history.
     */
    @Query("SELECT * FROM cases WHERE animal_id = :animalId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestForAnimal(animalId: Long): Case?

    /**
     * Cases at a given triage level, most urgent first.
     *
     * Ordering is by creation time rather than severity because the UI groups
     * by level already; within a group, recency is what matters.
     */
    @Query("SELECT * FROM cases WHERE triage_level = :triageLevel ORDER BY created_at DESC")
    suspend fun getByTriageLevel(triageLevel: String): List<Case>

    /**
     * Count of emergency cases, for the diagnostics readout.
     *
     * The spec names the under-triage rate as the primary safety metric; this is
     * the numerator the demo screen shows.
     */
    @Query("SELECT COUNT(*) FROM cases WHERE triage_level = :triageLevel")
    suspend fun countByTriageLevel(triageLevel: String): Int

    /** Total cases, for diagnostics. */
    @Query("SELECT COUNT(*) FROM cases")
    suspend fun count(): Int

    /**
     * Deletes every case for an animal.
     *
     * The foreign key already cascades from the animal, so this exists for the
     * "clear this animal's history" action, not for integrity.
     */
    @Query("DELETE FROM cases WHERE animal_id = :animalId")
    suspend fun deleteForAnimal(animalId: Long): Int
    @Query("SELECT * FROM cases WHERE draft_key = :key")
    suspend fun getByDraftKey(key: String): Case?
}
