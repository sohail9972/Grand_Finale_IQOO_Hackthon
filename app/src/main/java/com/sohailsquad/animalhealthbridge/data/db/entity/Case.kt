package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One clinical episode for one animal.
 *
 * A Case is created when the owner finishes describing symptoms, and it then
 * accumulates: the triage level, the vet's review, and eventually a Treatment.
 * It is the spine of the longitudinal health record.
 *
 * ---------------------------------------------------------------------------
 * SAFETY BOUNDARY
 * ---------------------------------------------------------------------------
 * Note what is NOT here: no diagnosis column, no condition column, no
 * medicine, no dosage. Those live on [Treatment], which requires a vet's name
 * and registration number to exist at all. Keeping the two apart in the schema
 * is what makes "the app cannot prescribe" a structural fact rather than a
 * convention.
 *
 * ---------------------------------------------------------------------------
 * WHY THE TRANSCRIPTS ARE STORED IN FULL
 * ---------------------------------------------------------------------------
 * Both [originalTranscript] and [englishTranscript] are kept verbatim. If a
 * triage decision is ever questioned, the raw words the owner used are the
 * evidence - a summarised version would be worthless for that purpose. The
 * English copy is what the vet reads; the original is what the owner actually
 * said, and it is never discarded.
 *
 * @param id surrogate key.
 * @param animalId owning animal; cascades on delete so removing an animal
 *   cannot leave orphaned clinical records.
 * @param chiefComplaint main symptom, extracted.
 * @param duration how long, or the not-stated marker.
 * @param affectedArea body part, or the not-stated marker.
 * @param severityIndicatorsJson JSON array of severity clues.
 * @param redFlagsJson JSON array of emergency indicators. Non-empty here means
 *   the triage engine will always return EMERGENCY_NOW.
 * @param confidence model confidence: high, medium or low. "low" escalates
 *   urgency.
 * @param triageLevel the urgency the rules engine assigned, stored as the enum
 *   name so it survives enum reordering.
 * @param triageRuleId which rule fired, for auditing the decision.
 * @param originalTranscript what the owner said, in their own language.
 * @param englishTranscript the translated text the model was given.
 * @param photoUri optional photo captured during intake.
 * @param structuredBy records which path produced the fields: the model, the
 *   keyword fallback, or a human. Needed because a fallback-derived case must
 *   never be presented with the same authority as a model-derived one.
 * @param createdAt epoch millis.
 */
@Entity(
    tableName = "cases",
    foreignKeys = [
        ForeignKey(
            entity = AnimalProfile::class,
            parentColumns = ["id"],
            childColumns = ["animal_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Every health-record query filters by animal and orders by recency,
        // so a composite index serves both.
        Index(value = ["animal_id", "created_at"]),
    ],
)
data class Case(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "animal_id")
    val animalId: Long,

    @ColumnInfo(name = "chief_complaint")
    val chiefComplaint: String,

    val duration: String,

    @ColumnInfo(name = "affected_area")
    val affectedArea: String,

    /** JSON array of severity clues, stored as text. */
    @ColumnInfo(name = "severity_indicators_json")
    val severityIndicatorsJson: String = "[]",

    /** JSON array of emergency indicators. */
    @ColumnInfo(name = "red_flags_json")
    val redFlagsJson: String = "[]",

    val confidence: String,

    /**
     * Triage outcome, as the enum's `name`.
     *
     * Stored as text rather than an ordinal so reordering the enum cannot
     * silently reclassify historical cases.
     */
    @ColumnInfo(name = "triage_level")
    val triageLevel: String,

    /**
     * The rule that produced [triageLevel], e.g. "R2".
     *
     * Spec rule 5 requires every triage decision to be logged with its matched
     * rule; keeping it on the case makes the record self-explaining.
     */
    @ColumnInfo(name = "triage_rule_id")
    val triageRuleId: String,

    @ColumnInfo(name = "original_transcript")
    val originalTranscript: String,

    @ColumnInfo(name = "english_transcript")
    val englishTranscript: String,

    @ColumnInfo(name = "photo_uri")
    val photoUri: String? = null,

    /** "model", "fallback" or "human". */
    @ColumnInfo(name = "structured_by")
    val structuredBy: String = STRUCTURED_BY_MODEL,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Fields were extracted by the on-device LLM. */
        const val STRUCTURED_BY_MODEL = "model"

        /** Fields came from the deterministic keyword fallback. */
        const val STRUCTURED_BY_FALLBACK = "fallback"

        /** Fields were entered or corrected by a person. */
        const val STRUCTURED_BY_HUMAN = "human"
    }
}