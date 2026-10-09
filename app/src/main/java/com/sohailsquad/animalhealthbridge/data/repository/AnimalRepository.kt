package com.sohailsquad.animalhealthbridge.data.repository

import android.util.Log
import com.sohailsquad.animalhealthbridge.data.db.dao.AnimalDao
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile

/**
 * The single place that decides what happens to animal data.
 *
 * Screens never touch [AnimalDao] directly. Keeping that rule means the
 * "scan the same tag twice and you get the same animal" policy lives in one
 * function instead of being re-implemented (and eventually forgotten) on every
 * screen that can identify an animal.
 *
 * @param dao the animals table.
 */
class AnimalRepository(private val dao: AnimalDao) {

    /**
     * Finds an animal by tag.
     *
     * @return the stored profile, or null when this tag is new.
     */
    suspend fun findByTag(tag: String): AnimalProfile? {
        val normalised = normalise(tag)
        return dao.getByTagNumber(normalised)
    }

    /**
     * Resolves a scanned or typed tag to an animal, creating it if needed.
     *
     * This is the function every identification path funnels through, so NFC,
     * QR and manual entry cannot drift apart in behaviour. It is idempotent:
     * calling it twice with the same tag returns the same row rather than
     * inserting a duplicate.
     *
     * @param tag the tag number or scanned UID.
     * @param species species name; Phase 1 passes "cattle".
     * @param age free-text age, or blank when unknown.
     * @param owner owner name, or blank when unknown.
     * @return the existing or newly created profile.
     */
    suspend fun createOrGet(
        tag: String,
        species: String,
        age: String,
        owner: String,
    ): AnimalProfile {
        val normalised = normalise(tag)

        // Fast path: the common case on a farm is re-scanning a tag that is
        // already in the database.
        dao.getByTagNumber(normalised)?.let { existing ->
            Log.i(TAG, "Existing animal resolved for tag=$normalised id=${existing.id}")
            return existing
        }

        val id = dao.insert(
            AnimalProfile(
                tagNumber = normalised,
                species = species,
                age = age,
                ownerName = owner,
            ),
        )
        Log.i(TAG, "Created animal for tag=$normalised id=$id")

        // Re-read rather than trusting the constructor: Room fills in the
        // generated id and any column defaults, so the returned row is the
        // authoritative copy of what is actually stored.
        return dao.getByTagNumber(normalised)
            ?: AnimalProfile(id = id, tagNumber = normalised, species = species, age = age, ownerName = owner)
    }

    /** The five most recently added animals. */
    suspend fun getRecent(): List<AnimalProfile> = dao.getRecent()

    /**
     * True when the tag is already known.
     *
     * Exposed separately from [findByTag] because callers sometimes only need
     * the yes/no answer, and this avoids materialising the whole row.
     */
    suspend fun exists(tag: String): Boolean = dao.countByTag(normalise(tag)) > 0

    /**
     * Canonical form of a tag.
     *
     * Trims whitespace and upper-cases, so a QR code containing " in-mh-0042 ",
     * a manually typed "IN-MH-0042" and an NFC UID all compare consistently and
     * cannot create near-duplicate animals.
     */
    private fun normalise(tag: String): String = tag.trim().uppercase()

    private companion object {
        const val TAG = "AhbAnimal"
    }
}