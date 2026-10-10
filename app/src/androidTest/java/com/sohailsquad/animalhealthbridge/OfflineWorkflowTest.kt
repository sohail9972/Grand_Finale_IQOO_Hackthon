package com.sohailsquad.animalhealthbridge

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.content.ContextWrapper
import com.sohailsquad.animalhealthbridge.camera.*
import com.sohailsquad.animalhealthbridge.ml.asr.AsrEngine
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sohailsquad.animalhealthbridge.data.db.*
import com.sohailsquad.animalhealthbridge.data.db.entity.*
import com.sohailsquad.animalhealthbridge.data.repository.*
import com.sohailsquad.animalhealthbridge.ml.llm.StructuredCase
import com.sohailsquad.animalhealthbridge.ml.translation.Translator
import com.sohailsquad.animalhealthbridge.triage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun animal() = AnimalProfile(tagNumber = UUID.randomUUID().toString(), species = "cattle", age = "", ownerName = "")

    @Test fun identityConflictPreservesClinicalHistory() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val profile = animal()
            val animalId = database.animalDao().insert(profile)
            val repository = CaseRepository(context, database)
            val caseId = repository.saveDraft(animalId, "Observed swelling", "en", UUID.randomUUID().toString())
            assertEquals(-1L, database.animalDao().insert(profile))
            assertEquals(animalId, database.animalDao().getByTagNumber(profile.tagNumber)?.id)
            assertEquals(caseId, database.caseDao().getForAnimal(animalId).single().id)
        } finally { database.close() }
    }

    @Test fun draftsQueriesAndApprovalAreDurableAndGated() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val animalId = database.animalDao().insert(animal())
            val repository = CaseRepository(context, database)
            val key = UUID.randomUUID().toString()
            val caseId = repository.saveDraft(animalId, "Observed swelling", "en", key)
            assertEquals(caseId, repository.saveDraft(animalId, "Observed swelling", "en", key))
            val extracted = StructuredCase(chiefComplaint = "Observed swelling", confidence = "low")
            repository.saveStructured(caseId, extracted, "Observed swelling",
                TriageResult(TriageLevel.SEE_VET_TODAY, "R5", "Low confidence"), true, false)
            repository.requestReview(caseId)
            repository.ask(VetQuery(caseId = caseId, vetId = "TEST-ONLY", vetName = "Test reviewer",
                questionTextEnglish = "Can the animal stand?", questionTextNative = "Can the animal stand?",
                nativeLanguage = "en", queryType = "TEXT"))
            val queries = database.openHelper.readableDatabase.query("SELECT id FROM vet_queries")
            val queryId = queries.use { assertTrue(it.moveToFirst()); it.getLong(0) }
            val treatment = Treatment(caseId = caseId, medicine = "TEST ONLY", dosage = "TEST ONLY",
                frequency = "TEST ONLY", duration = "TEST ONLY", route = "TEST ONLY",
                withdrawalPeriodDays = 0, specialInstructions = "", followUpDate = null,
                vetName = "Test reviewer", vetRegistrationNumber = "TEST-ONLY")
            val treatments = TreatmentRepository(context, database)
            assertTrue(runCatching { treatments.approve(treatment) }.isFailure)
            repository.answer(caseId, queryId, "Yes", emptyList())
            treatments.approve(treatment)
            assertEquals("APPROVED", repository.get(caseId).status)
            assertNotNull(treatments.getForCase(caseId))
            assertTrue(runCatching { treatments.approve(treatment) }.isFailure)
            assertTrue(runCatching { repository.ask(VetQuery(caseId = caseId, vetId = "TEST",
                vetName = "Test", questionTextEnglish = "Further question", questionTextNative = "Further question",
                nativeLanguage = "en", queryType = "TEXT")) }.isFailure)
            assertEquals(1, database.triageLogDao().getForCase(caseId).size)
        } finally { database.close() }
    }

    @Test fun triageEmergencyOverridesOtherMatchesAndLowConfidenceEscalates() {
        val engine = TriageEngine(context)
        assertEquals(TriageLevel.EMERGENCY_NOW, engine.evaluate(StructuredCase(
            duration = "one week", severityIndicators = listOf("bloated"), confidence = "low")).level)
        assertEquals(TriageLevel.EMERGENCY_NOW, engine.evaluate(StructuredCase(redFlags = listOf("difficulty breathing"))).level)
        assertEquals(TriageLevel.SEE_VET_TODAY, engine.evaluate(StructuredCase(confidence = "low")).level)
        assertEquals(TriageLevel.MONITOR, engine.evaluate(StructuredCase(confidence = "high", duration = "today")).level)
    }

    @Test fun missingTranslationModelPreservesOriginal() = runBlocking {
        val input = UUID.randomUUID().toString()
        val result = Translator(context).translateResult(input, "zz")
        assertEquals(input, result.text)
        assertTrue(result.isFallback)
    }

    @Test fun missingSpeechSensorsAndUnboundCameraHaveFallbacks() = runBlocking {
        val fallbackContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): java.io.File = java.io.File(cacheDir, UUID.randomUUID().toString())
            override fun getSystemService(name: String): Any? =
                if (name == Context.SENSOR_SERVICE) null else super.getSystemService(name)
        }
        assertFalse(AsrEngine(fallbackContext).isAvailable())
        val sensors = SensorMonitor(fallbackContext)
        sensors.start()
        try {
            assertTrue(sensors.availableSensors.value.isEmpty())
            assertEquals("UNAVAILABLE", CaptureCoach(sensors).checkLighting())
        } finally { sensors.stop() }
        val camera = CameraCapture(fallbackContext)
        try {
            assertNull(camera.capturePhoto())
            assertNull(camera.recordVideo(30))
        } finally { camera.close() }
    }

    @Test fun applicationHasNoNetworkPermissions() {
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertFalse(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test fun localPdfExportProducesReadableDocument() = runBlocking {
        val exporter = com.sohailsquad.animalhealthbridge.data.RecordExporter(context)
        val name = "test-" + UUID.randomUUID() + ".pdf"
        try {
            val uri = requireNotNull(exporter.createPdf(listOf("TEST ONLY", "", "हिन्दी", "Long line ".repeat(300)), name))
            context.contentResolver.openInputStream(uri).use { input ->
                val header = ByteArray(4)
                assertEquals(4, requireNotNull(input).read(header))
                assertEquals("%PDF", header.toString(Charsets.US_ASCII))
            }
        } finally { java.io.File(context.cacheDir, "exports/$name").delete() }
    }

    @Test fun migrationFromVersionTwoPreservesAnimalAndCase() = runBlocking {
        val name = "migration-" + UUID.randomUUID() + ".db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            listOf(
                "CREATE TABLE animal_profiles (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tag_number TEXT NOT NULL, species TEXT NOT NULL, age TEXT NOT NULL, owner_name TEXT NOT NULL, photo_uri TEXT, created_at INTEGER NOT NULL)",
                "CREATE UNIQUE INDEX index_animal_profiles_tag_number ON animal_profiles(tag_number)",
                "CREATE TABLE cases (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, animal_id INTEGER NOT NULL, chief_complaint TEXT NOT NULL, duration TEXT NOT NULL, affected_area TEXT NOT NULL, severity_indicators_json TEXT NOT NULL, red_flags_json TEXT NOT NULL, confidence TEXT NOT NULL, triage_level TEXT NOT NULL, triage_rule_id TEXT NOT NULL, original_transcript TEXT NOT NULL, english_transcript TEXT NOT NULL, photo_uri TEXT, structured_by TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(animal_id) REFERENCES animal_profiles(id) ON DELETE CASCADE)",
                "CREATE INDEX index_cases_animal_id_created_at ON cases(animal_id, created_at)",
                "CREATE TABLE translation_cache (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, source_text TEXT NOT NULL, target_text TEXT NOT NULL, source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, is_model_output INTEGER NOT NULL, created_at INTEGER NOT NULL)",
                "CREATE UNIQUE INDEX index_translation_cache_source_text_source_lang_target_lang ON translation_cache(source_text, source_lang, target_lang)",
                "INSERT INTO animal_profiles VALUES(1, 'TEST-MIGRATION', 'cattle', '', '', NULL, 1)",
                "INSERT INTO cases VALUES(1, 1, 'TEST-MIGRATION', 'today', 'leg', '[]', '[]', 'low', 'SEE_VET_TODAY', 'R5', 'TEST-MIGRATION', 'TEST-MIGRATION', NULL, 'human', 1)",
            ).forEach(sqlite::execSQL)
            sqlite.version = 2
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(ClinicalMigration).build()
        try {
            assertEquals("TEST-MIGRATION", database.animalDao().getById(1)?.tagNumber)
            assertEquals("TEST-MIGRATION", database.caseDao().getById(1)?.chiefComplaint)
            assertEquals(0, database.vetQueryDao().pendingCount(1))
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
