package com.sohailsquad.animalhealthbridge.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ClinicalMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "ALTER TABLE animal_profiles ADD COLUMN name TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE cases ADD COLUMN additional_notes TEXT NOT NULL DEFAULT 'not stated'",
            "ALTER TABLE cases ADD COLUMN source_lang TEXT NOT NULL DEFAULT 'hi'",
            "ALTER TABLE cases ADD COLUMN status TEXT NOT NULL DEFAULT 'DRAFT'",
            "ALTER TABLE cases ADD COLUMN draft_key TEXT",
            "ALTER TABLE cases ADD COLUMN processing_complete INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE cases ADD COLUMN translation_fallback INTEGER NOT NULL DEFAULT 0",
            "CREATE UNIQUE INDEX index_cases_draft_key ON cases(draft_key)",
            "CREATE TABLE translation_entries (sourceHash TEXT NOT NULL PRIMARY KEY, sourceText TEXT NOT NULL, sourceLang TEXT NOT NULL, translatedText TEXT NOT NULL, createdAt INTEGER NOT NULL)",
            "CREATE TABLE treatments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, caseId INTEGER NOT NULL, medicine TEXT NOT NULL, dosage TEXT NOT NULL, frequency TEXT NOT NULL, duration TEXT NOT NULL, route TEXT NOT NULL, withdrawalPeriodDays INTEGER NOT NULL, specialInstructions TEXT NOT NULL, followUpDate INTEGER, vetName TEXT NOT NULL, vetRegistrationNumber TEXT NOT NULL, approvedAt INTEGER NOT NULL, vetNotes TEXT NOT NULL, FOREIGN KEY(caseId) REFERENCES cases(id) ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX index_treatments_caseId ON treatments(caseId)",
            "CREATE TABLE vet_queries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, caseId INTEGER NOT NULL, vetId TEXT NOT NULL, vetName TEXT NOT NULL, questionTextEnglish TEXT NOT NULL, questionTextNative TEXT NOT NULL, nativeLanguage TEXT NOT NULL, queryType TEXT NOT NULL, status TEXT NOT NULL, answerText TEXT NOT NULL, answerMediaUris TEXT NOT NULL, askedAt INTEGER NOT NULL, answeredAt INTEGER, translationFallback INTEGER NOT NULL, FOREIGN KEY(caseId) REFERENCES cases(id) ON DELETE CASCADE)",
            "CREATE INDEX index_vet_queries_caseId ON vet_queries(caseId)",
            "CREATE TABLE vaccinations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, animalId INTEGER NOT NULL, vaccineName TEXT NOT NULL, date INTEGER NOT NULL, nextDueDate INTEGER, vetName TEXT NOT NULL, FOREIGN KEY(animalId) REFERENCES animal_profiles(id) ON DELETE CASCADE)",
            "CREATE INDEX index_vaccinations_animalId ON vaccinations(animalId)",
            "CREATE TABLE triage_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, caseId INTEGER NOT NULL, matchedRuleId TEXT NOT NULL, extractedFieldsJson TEXT NOT NULL, triageLevel TEXT NOT NULL, timestamp INTEGER NOT NULL, FOREIGN KEY(caseId) REFERENCES cases(id) ON DELETE CASCADE)",
            "CREATE INDEX index_triage_logs_caseId ON triage_logs(caseId)",
            "CREATE TABLE evidence (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, caseId INTEGER NOT NULL, uri TEXT NOT NULL, mimeType TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(caseId) REFERENCES cases(id) ON DELETE CASCADE)",
            "CREATE INDEX index_evidence_caseId ON evidence(caseId)",
            "CREATE UNIQUE INDEX index_evidence_uri ON evidence(uri)",
        ).forEach(db::execSQL)
    }
}
