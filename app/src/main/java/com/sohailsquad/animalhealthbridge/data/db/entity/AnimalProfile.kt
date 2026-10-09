package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single animal the owner manages.
 *
 * This row is the anchor of the whole app: every case, treatment, vaccination
 * and vet query hangs off an [id] from this table. It is created the first time
 * a tag is seen (by NFC, QR, or manual typing) and then lives forever, which is
 * what makes the "longitudinal health record" promise real.
 *
 * @param id auto-generated surrogate key. Never shown to the user - the owner
 *   identifies animals by [tagNumber].
 * @param tagNumber the ear-tag number or scanned tag UID. UNIQUE: scanning the
 *   same tag twice must resolve to the same animal rather than creating a
 *   duplicate profile.
 * @param species Phase 1 ships dairy cattle only, but the column is free text
 *   so buffalo, goats and poultry need no schema migration later.
 * @param age free-text age ("4 years", "18 months"). Deliberately not a number:
 *   farmers rarely know an exact date of birth, and forcing one would produce
 *   fake precision in the record.
 * @param ownerName the person who keeps the animal.
 * @param photoUri local file URI of the animal photo, or null when the camera
 *   was unavailable or the owner skipped it.
 * @param createdAt epoch millis. Also drives the "recent animals" ordering.
 */
@Entity(
    tableName = "animal_profiles",
    indices = [
        // Unique index, not just an index: it is the database-level guarantee
        // that one tag maps to exactly one animal, even if two code paths race
        // (e.g. an NFC read and a QR scan arriving together).
        Index(value = ["tag_number"], unique = true),
    ],
)
data class AnimalProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "tag_number")
    val tagNumber: String,

    val species: String,

    val age: String,

    @ColumnInfo(name = "owner_name")
    val ownerName: String,

    @ColumnInfo(name = "photo_uri")
    val photoUri: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)