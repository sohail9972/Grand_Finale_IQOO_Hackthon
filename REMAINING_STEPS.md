```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/UserPreferences.kt
package com.sohailsquad.animalhealthbridge.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userSettings by preferencesDataStore("user_settings")

class UserPreferences(context: Context) {
    private val store = context.applicationContext.userSettings
    private val languageKey = stringPreferencesKey("language")
    val language = store.data.catch { failure ->
        if (failure is CancellationException) throw failure
        Log.w("AhbAsr", "Preferences unavailable; using Hindi", failure)
        emit(androidx.datastore.preferences.core.emptyPreferences())
    }.map { it[languageKey] ?: "hi" }

    suspend fun selectedLanguage(): String = language.first()
    suspend fun setLanguage(code: String) {
        require(code.matches(Regex("[a-z]{2,3}")))
        store.edit { it[languageKey] = code }
        Log.i("AhbAsr", "Input language saved: $code")
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/entity/TranslationCache.kt
package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_entries")
data class TranslationCache(
    @PrimaryKey val sourceHash: String,
    val sourceText: String,
    val sourceLang: String,
    val translatedText: String,
    val createdAt: Long = System.currentTimeMillis(),
)
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/dao/TranslationDao.kt
package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translation_entries WHERE sourceHash = :hash")
    suspend fun getByHash(hash: String): com.sohailsquad.animalhealthbridge.data.db.entity.TranslationCache?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: com.sohailsquad.animalhealthbridge.data.db.entity.TranslationCache)
    @Query("DELETE FROM translation_entries WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/entity/Treatment.kt
package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "treatments", foreignKeys = [ForeignKey(entity = Case::class,
    parentColumns = ["id"], childColumns = ["caseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["caseId"], unique = true)])
data class Treatment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val medicine: String,
    val dosage: String,
    val frequency: String,
    val duration: String,
    val route: String,
    val withdrawalPeriodDays: Int,
    val specialInstructions: String,
    val followUpDate: Long?,
    val vetName: String,
    val vetRegistrationNumber: String,
    val approvedAt: Long = System.currentTimeMillis(),
    val vetNotes: String = "",
)
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/dao/TreatmentDao.kt
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
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/entity/VetQuery.kt
package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "vet_queries", foreignKeys = [ForeignKey(entity = Case::class,
    parentColumns = ["id"], childColumns = ["caseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("caseId")])
data class VetQuery(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val vetId: String,
    val vetName: String,
    val questionTextEnglish: String,
    val questionTextNative: String,
    val nativeLanguage: String,
    val queryType: String,
    val status: String = "PENDING",
    val answerText: String = "",
    val answerMediaUris: String = "[]",
    val askedAt: Long = System.currentTimeMillis(),
    val answeredAt: Long? = null,
    val translationFallback: Boolean = false,
)
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/dao/VetQueryDao.kt
package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*
import com.sohailsquad.animalhealthbridge.data.db.entity.VetQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface VetQueryDao {
    @Insert suspend fun insert(query: VetQuery): Long
    @Query("SELECT * FROM vet_queries WHERE caseId = :caseId ORDER BY askedAt")
    fun observeForCase(caseId: Long): Flow<List<VetQuery>>
    @Query("SELECT * FROM vet_queries WHERE id = :queryId")
    suspend fun getById(queryId: Long): VetQuery?
    @Query("SELECT COUNT(*) FROM vet_queries WHERE caseId = :caseId AND status = 'PENDING'")
    suspend fun pendingCount(caseId: Long): Int
    @Query("UPDATE vet_queries SET answerText = :text, answerMediaUris = :media, status = 'ANSWERED', answeredAt = :now WHERE id = :queryId AND status = 'PENDING'")
    suspend fun answer(queryId: Long, text: String, media: String, now: Long): Int
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/entity/Vaccination.kt
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
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/dao/VaccinationDao.kt
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
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/entity/TriageLog.kt
package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "triage_logs", foreignKeys = [ForeignKey(entity = Case::class,
    parentColumns = ["id"], childColumns = ["caseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("caseId")])
data class TriageLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val matchedRuleId: String,
    val extractedFieldsJson: String,
    val triageLevel: String,
    val timestamp: Long = System.currentTimeMillis(),
)
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/dao/TriageLogDao.kt
package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*
import com.sohailsquad.animalhealthbridge.data.db.entity.TriageLog

@Dao
interface TriageLogDao {
    @Insert suspend fun insert(log: TriageLog)
    @Query("SELECT * FROM triage_logs WHERE caseId = :caseId ORDER BY timestamp")
    suspend fun getForCase(caseId: Long): List<TriageLog>
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/entity/Evidence.kt
package com.sohailsquad.animalhealthbridge.data.db.entity

import androidx.room.*

@Entity(tableName = "evidence", foreignKeys = [ForeignKey(entity = Case::class,
    parentColumns = ["id"], childColumns = ["caseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("caseId"), Index(value = ["uri"], unique = true)])
data class Evidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val uri: String,
    val mimeType: String,
    val createdAt: Long = System.currentTimeMillis(),
)
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/dao/EvidenceDao.kt
package com.sohailsquad.animalhealthbridge.data.db.dao

import androidx.room.*
import com.sohailsquad.animalhealthbridge.data.db.entity.Evidence
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(evidence: Evidence): Long
    @Query("SELECT * FROM evidence WHERE caseId = :caseId ORDER BY createdAt")
    fun observeForCase(caseId: Long): Flow<List<Evidence>>
    @Query("SELECT COUNT(*) FROM evidence WHERE caseId = :caseId")
    suspend fun countForCase(caseId: Long): Int
    @Query("SELECT * FROM evidence WHERE caseId = :caseId ORDER BY createdAt")
    suspend fun getForCase(caseId: Long): List<Evidence>
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/triage/TriageRules.kt
package com.sohailsquad.animalhealthbridge.triage

import kotlinx.serialization.Serializable

@Serializable
enum class TriageLevel { EMERGENCY_NOW, SEE_VET_TODAY, MONITOR }

@Serializable
data class TriageRule(
    val id: String,
    val field: String,
    val operator: String = "default",
    val values: List<String> = emptyList(),
    val action: TriageLevel,
)
@Serializable
data class TriageRulesFile(val rules: List<TriageRule>)
data class TriageResult(val level: TriageLevel, val matchedRuleId: String, val reason: String)
fun resolveTriageLevel(value: String): TriageLevel = TriageLevel.entries.firstOrNull { it.name == value }
    ?: TriageLevel.SEE_VET_TODAY
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/triage/TriageEngine.kt
package com.sohailsquad.animalhealthbridge.triage

import android.content.Context
import android.util.Log
import com.sohailsquad.animalhealthbridge.ml.llm.StructuredCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TriageEngine(context: Context) {
    private val rules = try {
        context.assets.open("triage_rules.json").bufferedReader().use {
            Json.decodeFromString<TriageRulesFile>(it.readText()).rules.also { loaded ->
                require(loaded.isNotEmpty() && loaded.map { rule -> rule.id }.distinct().size == loaded.size)
                require(loaded.last().field == "default" && loaded.last().action == TriageLevel.MONITOR)
                require(loaded.any { rule -> rule.field == "confidence" && rule.values.contains("low") &&
                    rule.action != TriageLevel.MONITOR })
                loaded.forEach { rule ->
                    require(rule.field in setOf("red_flags", "severity_indicators", "duration", "confidence", "default"))
                    require(rule.operator in setOf("is_not_empty", "contains_any", "equals", "default"))
                }
            }
        }
    } catch (failure: Throwable) {
        Log.e("AhbTriage", "Rules unavailable; conservative fallback", failure)
        emptyList()
    }

    fun evaluate(case: StructuredCase): TriageResult {
        val matching = rules.filter { rule ->
            val fields = when (rule.field) {
                "red_flags" -> case.redFlags
                "severity_indicators" -> case.severityIndicators
                "duration" -> listOf(case.duration)
                "confidence" -> listOf(case.confidence)
                else -> emptyList()
            }
            when (rule.operator) {
                "is_not_empty" -> fields.any { it.isNotBlank() && it != StructuredCase.NOT_STATED }
                "contains_any" -> fields.any { value -> rule.values.any { value.contains(it, ignoreCase = true) } }
                "equals" -> fields.any { value -> rule.values.any { value.equals(it, ignoreCase = true) } }
                else -> rule.field == "default"
            }
        }
        val result = when {
            case.redFlags.any { it.isNotBlank() && it != StructuredCase.NOT_STATED } ->
                TriageResult(TriageLevel.EMERGENCY_NOW, "R1", "Red flags reported")
            matching.any { it.action == TriageLevel.EMERGENCY_NOW } -> matching.first { it.action == TriageLevel.EMERGENCY_NOW }.let {
                TriageResult(it.action, it.id, "Emergency indicators reported")
            }
            matching.isNotEmpty() -> matching.first().let { TriageResult(it.action, it.id, "Matched ${it.field}") }
            else -> TriageResult(TriageLevel.EMERGENCY_NOW, "RULES_UNAVAILABLE", "Unable to assess safely; seek urgent veterinary help")
        }
        Log.i("AhbTriage", "rule=${result.matchedRuleId} level=${result.level} fields=${Json.encodeToString(case)}")
        return result
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/ClinicalMigration.kt
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
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/repository/CaseRepository.kt
package com.sohailsquad.animalhealthbridge.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.*
import com.sohailsquad.animalhealthbridge.ml.llm.StructuredCase
import com.sohailsquad.animalhealthbridge.triage.TriageResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CaseRepository(context: Context, val database: AppDatabase = AppDatabase.get(context)) {
    private val cases = database.caseDao()
    suspend fun get(caseId: Long): Case = requireNotNull(cases.getById(caseId)) { "Case not found on this phone." }

    suspend fun saveDraft(animalId: Long, text: String, language: String, key: String): Long =
        database.withTransaction {
            requireNotNull(database.animalDao().getById(animalId)) { "Identify the animal first." }
            val existing = cases.getByDraftKey(key)
            if (existing != null) {
                if (existing.status == "DRAFT" && existing.originalTranscript != text) {
                    cases.update(existing.copy(originalTranscript = text, sourceLang = language, processingComplete = false))
                }
                return@withTransaction existing.id
            }
            val id = cases.insert(Case(animalId = animalId, chiefComplaint = text, duration = StructuredCase.NOT_STATED,
                affectedArea = StructuredCase.NOT_STATED, confidence = "low", triageLevel = "SEE_VET_TODAY",
                triageRuleId = "PENDING", originalTranscript = text, englishTranscript = "",
                draftKey = key, sourceLang = language, processingComplete = false, structuredBy = Case.STRUCTURED_BY_HUMAN))
            Log.i("AhbRecord", "Draft saved case=$id animal=$animalId")
            id
        }

    suspend fun saveStructured(caseId: Long, structured: StructuredCase, english: String,
        triage: TriageResult, modelFallback: Boolean, translationFallback: Boolean): Case =
        database.withTransaction {
            val previous = get(caseId)
            require(previous.status == "DRAFT") { "A submitted case cannot be overwritten." }
            val updated = previous.copy(chiefComplaint = structured.chiefComplaint, duration = structured.duration,
                affectedArea = structured.affectedArea, severityIndicatorsJson = Json.encodeToString(structured.severityIndicators),
                redFlagsJson = Json.encodeToString(structured.redFlags), additionalNotes = structured.additionalNotes,
                confidence = structured.confidence, englishTranscript = english, triageLevel = triage.level.name,
                triageRuleId = triage.matchedRuleId, processingComplete = true,
                translationFallback = translationFallback,
                structuredBy = if (modelFallback) Case.STRUCTURED_BY_FALLBACK else Case.STRUCTURED_BY_MODEL)
            cases.update(updated)
            database.triageLogDao().insert(TriageLog(caseId = caseId, matchedRuleId = triage.matchedRuleId,
                extractedFieldsJson = Json.encodeToString(structured), triageLevel = triage.level.name))
            Log.i("AhbRecord", "Structured case saved case=$caseId rule=${triage.matchedRuleId}")
            updated
        }

    suspend fun requestReview(caseId: Long) = database.withTransaction {
        val case = get(caseId)
        require(case.processingComplete) { "Wait for case preparation to finish." }
        if (case.status == "DRAFT") cases.update(case.copy(status = "REVIEW_REQUESTED"))
        Log.i("AhbVet", "Local review requested case=$caseId")
    }

    suspend fun addEvidence(caseId: Long, uri: String, mime: String) = database.withTransaction {
        require(get(caseId).status != "APPROVED") { "An approved case is read-only." }
        database.evidenceDao().insert(Evidence(caseId = caseId, uri = uri, mimeType = mime))
        Log.i("AhbCamera", "Evidence attached case=$caseId type=$mime")
    }

    suspend fun ask(query: VetQuery) = database.withTransaction {
        require(query.vetName.isNotBlank() && query.vetId.isNotBlank() && query.questionTextEnglish.isNotBlank())
        require(query.queryType in setOf("PHOTO", "VIDEO", "YES_NO", "TEXT"))
        require(get(query.caseId).processingComplete && database.treatmentDao().getForCase(query.caseId) == null) {
            "Questions cannot be added to an approved or incomplete case."
        }
        database.vetQueryDao().insert(query)
        val case = get(query.caseId)
        database.caseDao().update(case.copy(status = "AWAITING_INFORMATION"))
        Log.i("AhbVet", "Question saved case=${query.caseId} type=${query.queryType}")
    }

    suspend fun answer(caseId: Long, queryId: Long, text: String, media: List<String>) = database.withTransaction {
        val query = requireNotNull(database.vetQueryDao().getById(queryId))
        require(query.caseId == caseId && query.status == "PENDING")
        require(text.isNotBlank() || media.isNotEmpty()) { "Answer in text or attach evidence." }
        require(media.size <= 5)
        val known = database.evidenceDao().getForCase(caseId).map { it.uri }
        require(media.all { it in known }) { "Attachment does not belong to this case." }
        database.vetQueryDao().answer(queryId, text.trim(), Json.encodeToString(media), System.currentTimeMillis())
        if (database.vetQueryDao().pendingCount(caseId) == 0) {
            database.caseDao().update(get(caseId).copy(status = "REVIEW_REQUESTED"))
        }
        Log.i("AhbVet", "Question answered case=$caseId query=$queryId")
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/repository/TreatmentRepository.kt
package com.sohailsquad.animalhealthbridge.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.Treatment

class TreatmentRepository(context: Context, private val database: AppDatabase = AppDatabase.get(context)) {
    suspend fun approve(treatment: Treatment): Long = database.withTransaction {
        require(listOf(treatment.medicine, treatment.dosage, treatment.frequency, treatment.duration,
            treatment.route, treatment.vetName, treatment.vetRegistrationNumber).all { it.isNotBlank() }) {
            "Enter every treatment field and the veterinarian's name and registration number."
        }
        require(treatment.withdrawalPeriodDays in 0..3650) { "Enter a withdrawal period between 0 and 3650 days." }
        require(treatment.followUpDate == null || treatment.followUpDate >=
            java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) {
            "Follow-up date cannot be in the past."
        }
        require(database.vetQueryDao().pendingCount(treatment.caseId) == 0) { "Answer pending questions before approval." }
        val case = requireNotNull(database.caseDao().getById(treatment.caseId))
        require(case.processingComplete && case.status != "DRAFT") { "Request review before approving." }
        require(database.treatmentDao().getForCase(case.id) == null) { "Treatment already approved." }
        val id = database.treatmentDao().insert(treatment)
        database.caseDao().update(case.copy(status = "APPROVED"))
        Log.i("AhbVet", "Treatment approved locally case=${case.id} treatment=$id")
        id
    }
    suspend fun getForCase(caseId: Long): Treatment? = database.treatmentDao().getForCase(caseId)
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ml/asr/AsrConfig.kt
package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import java.io.File

object AsrConfig {
    const val SAMPLE_RATE = 16000
    const val MAX_SECONDS = 30
    fun directory(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/indicconformer")
    fun model(context: Context) = File(directory(context), "model.onnx")
    fun tokens(context: Context) = File(directory(context), "tokens.txt")
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ml/asr/AsrEngine.kt
package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.k2fsa.sherpa.onnx.*
import com.sohailsquad.animalhealthbridge.data.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AsrEngine(context: Context) {
    private val context = context.applicationContext
    @Volatile private var listening = false
    @Volatile private var recorder: AudioRecord? = null
    private val mutableStatus = MutableStateFlow("Ready")
    val status = mutableStatus.asStateFlow()
    fun isAvailable(): Boolean = runCatching {
        listOf(AsrConfig.model(context), AsrConfig.tokens(context)).all {
            it.isFile && it.canRead() && it.length() > 0
        }
    }.onFailure { Log.w("AhbAsr", "Speech model storage unavailable", it) }.getOrDefault(false)

    suspend fun startListening(onPartial: (String) -> Unit, onFinal: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            if (listening) return@withContext
            if (!isAvailable()) {
                mutableStatus.value = "Voice input is not set up on this phone yet. Type the symptoms below - it works exactly the same."
                Log.i("AhbAsr", "Missing ASR files in ${AsrConfig.directory(context)}")
                return@withContext
            }
            listening = true
            var recognizer: OfflineRecognizer? = null
            try {
                mutableStatus.value = "Loading speech model..."
                val language = UserPreferences(context).selectedLanguage()
                OrtSession.SessionOptions().use { options ->
                    OrtEnvironment.getEnvironment().createSession(AsrConfig.model(context).path, options).use { probe ->
                        val metadata = probe.metadata.customMetadata
                        require(metadata["model_type"] == "nemo_ctc") { "A sherpa-compatible NeMo CTC export is required." }
                        require((metadata["language"] ?: "hi") == language) { "This speech model does not support $language." }
                    }
                }
                Log.i("AhbAsr", "Resolved input language=$language; backend=offline nemo_ctc")
                recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = AsrConfig.SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig(
                        model = AsrConfig.model(context).path), tokens = AsrConfig.tokens(context).path,
                        numThreads = 2, provider = "cpu", modelType = "nemo_ctc")))
                val minimum = AudioRecord.getMinBufferSize(AsrConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minimum > 0) { "Microphone unavailable." }
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AsrConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
                recorder = audio
                require(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable." }
                audio.startRecording()
                mutableStatus.value = "Listening"
                val samples = FloatArray(AsrConfig.SAMPLE_RATE * AsrConfig.MAX_SECONDS)
                val chunk = ShortArray(2048)
                var size = 0
                val asr = requireNotNull(recognizer)
                coroutineScope {
                    val partialJob = launch(Dispatchers.Default) {
                        while (isActive && listening) {
                            delay(3000)
                            if (!listening) break
                            val snapshot = synchronized(samples) { samples.copyOf(size) }
                            if (snapshot.isNotEmpty()) {
                                val partial = decode(asr, snapshot)
                                withContext(Dispatchers.Main.immediate) { onPartial(partial) }
                            }
                        }
                    }
                    try {
                        while (listening && currentCoroutineContext().isActive && size < samples.size) {
                            val count = audio.read(chunk, 0, minOf(chunk.size, samples.size - size))
                            if (count <= 0) {
                                if (listening) error("Microphone read failed ($count).")
                                break
                            }
                            synchronized(samples) {
                                repeat(count) { offset -> samples[size + offset] = chunk[offset] / 32768f }
                                size += count
                            }
                        }
                    } finally {
                        listening = false
                        runCatching { audio.stop() }
                        mutableStatus.value = "Transcribing..."
                        withContext(NonCancellable) { partialJob.cancelAndJoin() }
                    }
                }
                currentCoroutineContext().ensureActive()
                val final = if (size > 0) decode(recognizer, samples.copyOf(size)) else ""
                withContext(Dispatchers.Main.immediate) { onFinal(final) }
                mutableStatus.value = if (final.isBlank()) "No speech recognized. Try again or type instead." else "Finished"
                Log.i("AhbAsr", "Capture finished samples=$size characters=${final.length}")
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                mutableStatus.value = "Voice input unavailable. Type the symptoms instead."
                Log.w("AhbAsr", "Speech capture unavailable", failure)
            } finally {
                listening = false
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recorder = null
                runCatching { recognizer?.release() }
            }
        }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, AsrConfig.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }

    fun stopListening() {
        listening = false
        runCatching { recorder?.stop() }.onFailure { Log.w("AhbAsr", "Microphone stop failed", it) }
        Log.i("AhbAsr", "Stop requested")
    }
    fun close() = stopListening()
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ml/translation/TranslatorConfig.kt
package com.sohailsquad.animalhealthbridge.ml.translation

import android.content.Context
import java.io.File

object TranslatorConfig {
    val languageTags = mapOf("hi" to "hin_Deva", "kn" to "kan_Knda", "ta" to "tam_Taml",
        "te" to "tel_Telu", "ml" to "mal_Mlym", "mr" to "mar_Deva", "bn" to "ben_Beng",
        "gu" to "guj_Gujr", "pa" to "pan_Guru", "or" to "ory_Orya", "as" to "asm_Beng",
        "ur" to "urd_Arab", "en" to "eng_Latn")
    fun directory(context: Context, source: String, target: String = "en"): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models/indictrans2/$source-$target")
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ml/translation/Translator.kt
package com.sohailsquad.animalhealthbridge.ml.translation

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.TranslationCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest

class Translator(context: Context) {
    private val context = context.applicationContext
    private val lock = Mutex()
    data class TranslationResult(val text: String, val isFallback: Boolean, val source: String)
    suspend fun translate(text: String, sourceLang: String): String = translateResult(text, sourceLang).text
    suspend fun translateHindiToEnglish(text: String, cache: TranslationCacheDao? = null): TranslationResult =
        translateResult(text, "hi")

    suspend fun translateResult(text: String, sourceLang: String, targetLang: String = "en"): TranslationResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank() || sourceLang == targetLang) return@withContext TranslationResult(text, false, "identity")
            try {
                lock.withLock {
                    require(sourceLang in TranslatorConfig.languageTags && targetLang in TranslatorConfig.languageTags) {
                        "Unsupported translation language pair."
                    }
                    val dao = AppDatabase.get(context).translationDao()
                    val hash = MessageDigest.getInstance("SHA-256").digest(
                        "$sourceLang\u0000$targetLang\u0000$text".toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    try {
                        dao.getByHash(hash)?.takeIf { it.sourceText == text && it.sourceLang == sourceLang }?.let {
                            Log.i("AhbTranslate", "Cache hit $sourceLang->$targetLang")
                            return@withLock TranslationResult(it.translatedText, false, "cache")
                        }
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        Log.w("AhbTranslate", "Cache read failed", failure)
                    }
                    val directory = TranslatorConfig.directory(context, sourceLang, targetLang)
                    val required = listOf("encoder.onnx", "decoder.onnx", "tokenizer.json", "config.json")
                    require(required.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }) {
                        "Model files missing at $directory"
                    }
                    val translated = decode(directory, text, sourceLang, targetLang)
                    require(translated.isNotBlank())
                    try { dao.insert(TranslationCache(hash, text, sourceLang, translated)) }
                    catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        Log.w("AhbTranslate", "Cache write failed", failure)
                    }
                    Log.i("AhbTranslate", "Translated $sourceLang->$targetLang")
                    TranslationResult(translated, false, "model")
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                Log.w("AhbTranslate", "Translation unavailable $sourceLang->$targetLang; preserving original", failure)
                TranslationResult(text, true, "unavailable")
            }
        }

    private fun decode(directory: File, text: String, source: String, target: String): String {
        val tokenizer = UnigramTokenizer(JSONObject(File(directory, "tokenizer.json").readText()))
        val config = JSONObject(File(directory, "config.json").readText())
        val sourceTag = requireNotNull(TranslatorConfig.languageTags[source])
        val targetTag = requireNotNull(TranslatorConfig.languageTags[target])
        val input = longArrayOf(tokenizer.languageId(sourceTag), tokenizer.languageId(targetTag)) +
            tokenizer.encode(text) + config.getLong("eos_token_id")
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(File(directory, "encoder.onnx").path, options).use { encoder ->
                env.createSession(File(directory, "decoder.onnx").path, options).use { decoder ->
                    require(encoder.inputNames == setOf("input_ids", "attention_mask"))
                    require(decoder.inputNames.all { it in setOf("input_ids", "encoder_hidden_states", "encoder_attention_mask") })
                    require("encoder_hidden_states" in decoder.inputNames)
                    ids(env, input).use { sourceIds ->
                        ids(env, LongArray(input.size) { 1 }).use { mask ->
                            encoder.run(mapOf("input_ids" to sourceIds, "attention_mask" to mask)).use { encoded ->
                                val hidden = encoded.get(0) as OnnxTensor
                                val output = mutableListOf(config.getLong("decoder_start_token_id"))
                                var finished = false
                                repeat(256) {
                                    if (!finished) {
                                        ids(env, output.toLongArray()).use { prefix ->
                                            val feeds = mutableMapOf("input_ids" to prefix, "encoder_hidden_states" to hidden)
                                            if ("encoder_attention_mask" in decoder.inputNames) feeds["encoder_attention_mask"] = mask
                                            decoder.run(feeds).use { decoded ->
                                                val logits = decoded.get(0) as OnnxTensor
                                                val width = logits.info.shape.last().toInt()
                                                val buffer = logits.floatBuffer
                                                val offset = buffer.remaining() - width
                                                val best = (0 until width).maxBy { buffer.get(offset + it) }.toLong()
                                                if (best == config.getLong("eos_token_id")) finished = true else output += best
                                            }
                                        }
                                    }
                                }
                                require(finished) { "Decoder did not finish." }
                                return tokenizer.decode(output.drop(1))
                            }
                        }
                    }
                }
            }
        }
    }
    private fun ids(env: OrtEnvironment, ids: LongArray) =
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
    fun close() = Unit
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ml/translation/UnigramTokenizer.kt
package com.sohailsquad.animalhealthbridge.ml.translation

import org.json.JSONObject
import java.text.Normalizer

internal class UnigramTokenizer(json: JSONObject) {
    private val model = json.getJSONObject("model").also { require(it.getString("type") == "Unigram") }
    private val vocabulary = model.getJSONArray("vocab")
    private val pieces = (0 until vocabulary.length()).associate { index ->
        vocabulary.getJSONArray(index).let { it.getString(0) to (index.toLong() to it.getDouble(1)) }
    }
    private val addedTokens = json.optJSONArray("added_tokens")?.let { added ->
        (0 until added.length()).associate { index -> added.getJSONObject(index).let {
            it.getString("content") to it.getLong("id")
        } }
    }.orEmpty()
    private val normalization = if (json.optJSONObject("normalizer")?.optString("type") == "NFC")
        Normalizer.Form.NFC else Normalizer.Form.NFKC
    init {
        require(!model.optBoolean("byte_fallback", false)) { "Byte-fallback tokenizer is not supported." }
        val normalizer = json.optJSONObject("normalizer")
        require(normalizer == null || normalizer.optString("type") in setOf("NFKC", "NFC")) {
            "Install an export with an NFKC/NFC Unigram tokenizer."
        }
        val preTokenizer = json.optJSONObject("pre_tokenizer")
        require(preTokenizer == null || preTokenizer.optString("type") == "Metaspace")
    }

    fun encode(text: String): LongArray {
        val normalized = "▁" + Normalizer.normalize(text.trim(), normalization).replace(Regex("\\s+"), "▁")
        val scores = DoubleArray(normalized.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(scores.size) { -1 }
        val tokens = LongArray(scores.size)
        scores[0] = 0.0
        val maximum = pieces.keys.maxOf { it.length }
        for (start in normalized.indices) {
            if (!scores[start].isFinite()) continue
            for (end in start + 1..minOf(normalized.length, start + maximum)) {
                val piece = pieces[normalized.substring(start, end)] ?: continue
                val score = scores[start] + piece.second
                if (score > scores[end]) {
                    scores[end] = score
                    previous[end] = start
                    tokens[end] = piece.first
                }
            }
        }
        require(previous.last() >= 0) { "Tokenizer cannot encode the input without losing characters." }
        val result = mutableListOf<Long>()
        var position = normalized.length
        while (position > 0) {
            result += tokens[position]
            position = previous[position]
            require(position >= 0)
        }
        return result.asReversed().toLongArray()
    }

    fun languageId(tag: String): Long = requireNotNull(addedTokens[tag] ?: pieces[tag]?.first) {
        "Tokenizer is missing language token $tag."
    }
    fun decode(tokens: List<Long>): String = tokens.joinToString("") { id ->
        if (id in addedTokens.values) "" else vocabulary.getJSONArray(id.toInt()).getString(0)
    }.replace("▁", " ").trim()
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/camera/SensorMonitor.kt
package com.sohailsquad.animalhealthbridge.camera

import android.content.Context
import android.hardware.*
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class SensorMonitor(context: Context) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val mutableGyro = MutableStateFlow(0f)
    private val mutableLight = MutableStateFlow(0f)
    private val mutableProximity = MutableStateFlow(0f)
    private val mutableAvailability = MutableStateFlow(emptySet<Int>())
    val gyroscopeMagnitude = mutableGyro.asStateFlow()
    val lightLevel = mutableLight.asStateFlow()
    val proximity = mutableProximity.asStateFlow()
    val availableSensors = mutableAvailability.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        mutableAvailability.value = listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY)
            .filter { type ->
                try {
                    manager?.getDefaultSensor(type)?.let {
                        manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    } == true
                } catch (failure: Throwable) {
                    Log.w("AhbSensor", "Sensor unavailable type=$type", failure)
                    false
                }
            }.toSet()
        Log.i("AhbSensor", "Monitoring started sensors=${mutableAvailability.value}")
    }

    fun stop() {
        runCatching { manager?.unregisterListener(this) }.onFailure { Log.w("AhbSensor", "Stop failed", it) }
        mutableAvailability.value = emptySet()
        started = false
        Log.i("AhbSensor", "Monitoring stopped")
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> mutableGyro.value = sqrt(event.values.take(3).sumOf { (it * it).toDouble() }).toFloat()
            Sensor.TYPE_LIGHT -> mutableLight.value = event.values[0]
            Sensor.TYPE_PROXIMITY -> mutableProximity.value = event.values[0]
        }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/camera/CaptureCoach.kt
package com.sohailsquad.animalhealthbridge.camera

import android.hardware.Sensor
import android.os.SystemClock

class CaptureCoach(private val sensors: SensorMonitor) {
    private var shakingSince: Long? = null
    fun checkSteadiness(): String {
        if (Sensor.TYPE_GYROSCOPE !in sensors.availableSensors.value) return "UNAVAILABLE"
        if (sensors.gyroscopeMagnitude.value <= 0.5f) {
            shakingSince = null
            return "GOOD"
        }
        val now = SystemClock.elapsedRealtime()
        if (shakingSince == null) shakingSince = now
        return if (now - requireNotNull(shakingSince) >= 1000) "HOLD_STEADY" else "GOOD"
    }
    fun checkLighting(): String = when {
        Sensor.TYPE_LIGHT !in sensors.availableSensors.value -> "UNAVAILABLE"
        sensors.lightLevel.value < 50f -> "TOO_DARK"
        else -> "GOOD"
    }
    fun checkProximity(): String = when {
        Sensor.TYPE_PROXIMITY !in sensors.availableSensors.value -> "UNAVAILABLE"
        sensors.proximity.value < 1f -> "COVERED"
        else -> "OK"
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/camera/CameraCapture.kt
package com.sohailsquad.animalhealthbridge.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

class CameraCapture(context: Context) {
    private val context = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var image: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var paused = false

    suspend fun bind(owner: LifecycleOwner, view: PreviewView): Boolean = withContext(Dispatchers.Main.immediate) {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
                future.addListener({
                    runCatching { future.get() }.fold(
                        { if (continuation.isActive) continuation.resume(it) },
                        { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) })
                }, executor)
            }
            require(cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
            val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val photo = ImageCapture.Builder().build()
            val recorder = Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))).build()
            val movie = VideoCapture.withOutput(recorder)
            val videoAvailable = try {
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, photo, movie)
                true
            } catch (failure: Throwable) {
                Log.w("AhbCamera", "Video unavailable; trying photo-only capture", failure)
                cameraProvider.unbind(cameraPreview, photo, movie)
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, photo)
                false
            }
            provider = cameraProvider
            preview = cameraPreview
            image = photo
            video = if (videoAvailable) movie else null
            Log.i("AhbCamera", "Photo/video camera bound")
            true
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            Log.w("AhbCamera", "Camera unavailable; use text", failure)
            false
        }
    }

    private fun output(extension: String): File {
        val directory = File(context.filesDir, "evidence")
        require(directory.isDirectory || directory.mkdirs())
        return File(directory, UUID.randomUUID().toString() + extension)
    }

    suspend fun capturePhoto(): Uri? = withContext(Dispatchers.Main.immediate) {
        val camera = image ?: return@withContext null
        var file: File? = null
        try {
            val destination = output(".jpg")
            file = destination
            suspendCancellableCoroutine { continuation ->
                camera.takePicture(ImageCapture.OutputFileOptions.Builder(destination).build(), executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            if (continuation.isActive) {
                                Log.i("AhbCamera", "Photo captured")
                                continuation.resume(Uri.fromFile(destination))
                            } else destination.delete()
                        }
                        override fun onError(exception: ImageCaptureException) {
                            destination.delete()
                            Log.w("AhbCamera", "Photo failed", exception)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            file?.delete()
            Log.w("AhbCamera", "Photo unavailable", failure)
            null
        }
    }

    suspend fun recordVideo(maxDurationSec: Int): Uri? = withContext(Dispatchers.Main.immediate) {
        val camera = video ?: return@withContext null
        if (recording != null) return@withContext null
        var file: File? = null
        try {
            val destination = output(".mp4")
            file = destination
            suspendCancellableCoroutine { continuation ->
                val timeout = scope.launch {
                    delay(maxDurationSec.coerceIn(1, 30) * 1000L)
                    recording?.stop()
                }
                recording = camera.output.prepareRecording(context, FileOutputOptions.Builder(destination)
                    .setFileSizeLimit(50L * 1024 * 1024).build()).start(executor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        timeout.cancel()
                        recording?.close()
                        recording = null
                        paused = false
                        val result = if (!event.hasError() && destination.length() > 0) Uri.fromFile(destination) else null
                        if (result == null || !continuation.isActive) destination.delete()
                        if (continuation.isActive) continuation.resume(result)
                        Log.i("AhbCamera", "Video finalized error=${event.error}")
                    }
                }
                continuation.invokeOnCancellation {
                    executor.execute { runCatching { recording?.stop() } }
                }
                Log.i("AhbCamera", "Silent video started")
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            file?.delete()
            Log.w("AhbCamera", "Video unavailable", failure)
            null
        }
    }

    fun setCovered(covered: Boolean) {
        runCatching {
            if (recording != null && covered != paused) {
                if (covered) recording?.pause() else recording?.resume()
                paused = covered
                Log.i("AhbCamera", "Proximity pause=$covered")
            }
        }.onFailure { Log.w("AhbCamera", "Pause failed", it) }
    }
    fun close() {
        runCatching { recording?.stop() }
        scope.cancel()
        runCatching {
            listOfNotNull(preview, image, video).takeIf { it.isNotEmpty() }?.let { provider?.unbind(*it.toTypedArray()) }
        }.onFailure { Log.w("AhbCamera", "Camera release failed", it) }
        image = null
        video = null
        Log.i("AhbCamera", "Camera closed")
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/camera/EvidenceStore.kt
package com.sohailsquad.animalhealthbridge.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EvidenceStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "evidence")
    suspend fun validate(uri: Uri, mime: String): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "file")
        val file = File(requireNotNull(uri.path)).canonicalFile
        require(file.parentFile == root.canonicalFile && file.isFile && file.length() in 1..(50L * 1024 * 1024)) {
            "Attachment is missing or exceeds 50 MB."
        }
        when (mime) {
            "image/jpeg" -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                require(options.outWidth > 0 && options.outHeight > 0 && options.outMimeType == "image/jpeg") {
                    "Photo is unreadable."
                }
            }
            "video/mp4" -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    require(duration != null && duration in 1..31_000) { "Video must be at most 30 seconds." }
                    require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
                } finally { retriever.release() }
            }
            else -> error("Unsupported attachment type.")
        }
        uri.toString()
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/components/OfflineContent.kt
package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun OfflineContent(destination: AhbDestination, onBack: () -> Unit, error: String? = null,
    busy: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    AhbScreen(destination, onBack, safetyBanner = true) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .imePadding().padding(LocalAhbDimens.current.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap)) {
            if (busy) CircularProgressIndicator()
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            content()
        }
    }
}
@Composable
fun InputField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) })
}
@Composable
fun ClinicalField(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gapTight)) {
        Icon(Icons.Default.Description, contentDescription = null)
        Text("$label: $value")
    }
}
fun parseDate(value: String): Long? = value.trim().takeIf { it.isNotEmpty() }?.let {
    LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
fun displayDate(value: Long): String =
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/components/EvidencePreview.kt
package com.sohailsquad.animalhealthbridge.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EvidencePreview(uri: String, mime: String) {
    var play by remember(uri) { mutableStateOf(false) }
    if (mime.startsWith("image/")) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(Uri.parse(uri).path,
                    BitmapFactory.Options().apply { inSampleSize = 8 }) }
                    .onFailure { Log.w("AhbCamera", "Preview unavailable", it) }.getOrNull()
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), "Case photo", Modifier.fillMaxWidth().height(140.dp)) }
            ?: Text("Photo saved; preview unavailable.")
    } else {
        TextButton(onClick = { play = true }) { Text("Preview video") }
    }
    if (play) {
        val context = LocalContext.current
        var playbackError by remember(uri) { mutableStateOf<String?>(null) }
        val player = remember(uri) {
            runCatching { ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError = "Video saved; this phone cannot preview it."
                        Log.w("AhbCamera", "Video playback failed", error)
                    }
                })
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            } }.onFailure { Log.w("AhbCamera", "Video preview unavailable", it) }.getOrNull()
        }
        DisposableEffect(player) { onDispose { player?.release() } }
        Dialog(onDismissRequest = { play = false }) {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    playbackError?.let { Text(it) }
                    if (player != null) AndroidView(
                        factory = { PlayerView(it).apply { this.player = player } },
                        modifier = Modifier.fillMaxWidth().height(240.dp))
                    else Text("Video saved; preview unavailable.")
                    TextButton(onClick = { play = false }) { Text("Close") }
                }
            }
        }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/camera/CaptureDialog.kt
package com.sohailsquad.animalhealthbridge.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable
fun CaptureDialog(videoMode: Boolean, onCaptured: suspend (Uri, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val capture = remember { CameraCapture(context) }
    val sensors = remember { SensorMonitor(context) }
    val coach = remember { CaptureCoach(sensors) }
    val view = remember { PreviewView(context) }
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var available by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var guidance by remember { mutableStateOf("") }
    val proximity by sensors.proximity.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) error = "Camera permission denied. Answer in text instead."
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sensors.start()
                capture.setCovered(false)
            }
            if (event == Lifecycle.Event.ON_STOP) {
                sensors.stop()
                capture.setCovered(true)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) sensors.start()
        onDispose { lifecycle.lifecycle.removeObserver(observer); sensors.stop(); capture.close() }
    }
    LaunchedEffect(granted) {
        if (granted) {
            available = capture.bind(lifecycle, view)
            if (!available) error = "Camera unavailable. Answer in text instead."
        }
    }
    LaunchedEffect(proximity) { capture.setCovered(coach.checkProximity() == "COVERED") }
    LaunchedEffect(Unit) {
        while (isActive) {
            guidance = "Steadiness: ${coach.checkSteadiness()} | Light: ${coach.checkLighting()} | Proximity: ${coach.checkProximity()}"
            delay(250)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (videoMode) "Capture video (30 seconds, no audio)" else "Capture photo")
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(240.dp))
                Text(guidance)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!granted) Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                if (busy) {
                    CircularProgressIndicator()
                    Text("Capturing...")
                }
                Button(enabled = available && !busy, onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        var uri: Uri? = null
                        try {
                            require(coach.checkProximity() != "COVERED") { "Uncover the phone before capture." }
                            uri = if (videoMode) capture.recordVideo(30) else capture.capturePhoto()
                            val captured = requireNotNull(uri) { "Capture failed. Try again or answer in text." }
                            onCaptured(captured, if (videoMode) "video/mp4" else "image/jpeg")
                            onDismiss()
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Throwable) {
                            error = failure.message ?: "Capture failed. Answer in text instead."
                            Log.w("AhbCamera", "Capture/attachment failed", failure)
                            uri?.path?.let { java.io.File(it).delete() }
                        } finally { busy = false }
                    }
                }) { Text(if (videoMode) "Record video" else "Take photo") }
                TextButton(onClick = onDismiss) { Text("Cancel / use text") }
            }
        }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/screens/CaseReviewViewModel.kt
package com.sohailsquad.animalhealthbridge.ui.screens

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.ml.llm.LlmEngine
import com.sohailsquad.animalhealthbridge.ml.llm.StructuredCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaseReviewState(
    val stage: String = "Loading model...",
    val record: Case? = null,
    val error: String? = null,
    val modelAvailable: Boolean = false,
)
class CaseReviewViewModel(context: Context, private val caseId: Long) : ViewModel() {
    private val application = context.applicationContext.app()
    private val mutableState = MutableStateFlow(CaseReviewState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    init { prepare() }

    fun prepare() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutableState.value = CaseReviewState()
            try {
                val existing = application.caseRepository.get(caseId)
                val available = application.llmEngine.load()
                mutableState.value = mutableState.value.copy(modelAvailable = available)
                if (existing.processingComplete) {
                    mutableState.value = mutableState.value.copy(stage = "Ready", record = existing)
                    return@launch
                }
                require(existing.originalTranscript.isNotBlank()) { "Enter symptoms before reviewing." }
                mutableState.value = mutableState.value.copy(stage = "Translating...")
                val translation = application.translator.translateResult(existing.originalTranscript, existing.sourceLang)
                mutableState.value = mutableState.value.copy(stage = "Structuring...")
                val result = application.llmEngine.structure(translation.text)
                val fallback = result !is LlmEngine.Result.Success || result.usedFallback
                var structured = when (result) {
                    is LlmEngine.Result.Success -> result.case
                    is LlmEngine.Result.Escalated -> result.case.copy(confidence = "low", additionalNotes = result.reason)
                }
                if (translation.isFallback) structured = structured.copy(confidence = "low")
                mutableState.value = mutableState.value.copy(stage = "Evaluating...")
                val triage = withContext(Dispatchers.Default) { application.triageEngine.evaluate(structured) }
                val saved = application.caseRepository.saveStructured(caseId, structured,
                    if (translation.isFallback) "" else translation.text, triage,
                    fallback, translation.isFallback)
                mutableState.value = mutableState.value.copy(stage = "Ready", record = saved)
                Log.i("AhbRecord", "Case review ready case=$caseId")
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                Log.e("AhbRecord", "Case preparation failed", failure)
                mutableState.value = mutableState.value.copy(stage = "Error",
                    error = failure.message ?: "Could not prepare this case. The draft is saved; retry.")
            }
        }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/screens/VoiceIntakeScreen.kt
package com.sohailsquad.animalhealthbridge.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.ml.asr.AsrEngine
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@Composable
@OptIn(kotlinx.coroutines.FlowPreview::class)
fun VoiceIntakeScreen(animalId: Long, onTranscriptReady: (Long, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.app()
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember { AsrEngine(context) }
    val status by engine.status.collectAsState()
    val language by application.preferences.language.collectAsState(initial = "hi")
    var transcript by rememberSaveable { mutableStateOf("") }
    val draftKey = rememberSaveable { UUID.randomUUID().toString() }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(!engine.isAvailable()) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveLock = remember { Mutex() }
    suspend fun saveCurrentDraft(): Long = saveLock.withLock {
        application.caseRepository.saveDraft(animalId, transcript.trim(),
            application.preferences.selectedLanguage(), draftKey)
    }
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(1f, 1.12f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    fun listen() {
        scope.launch {
            recording = true
            error = null
            try {
                engine.startListening({ if (!typing) transcript = it }, {
                    if (!typing) transcript = it
                    recording = false
                    if (!typing && it.isNotBlank()) scope.launch {
                        busy = true
                        try {
                            val id = saveCurrentDraft()
                            onTranscriptReady(id, transcript.trim())
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Throwable) {
                            error = "Could not save the transcript. Press Continue to retry."
                            Log.w("AhbRecord", "Final transcript save failed", failure)
                        } finally { busy = false }
                    }
                })
                if (transcript.isBlank()) typing = true
            } finally { recording = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) listen() else {
            typing = true
            error = "Microphone permission denied. Type the symptoms instead."
            Log.i("AhbAsr", "Microphone permission denied")
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) engine.stopListening()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); engine.close() }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { transcript }.debounce(600).distinctUntilChanged().collect { value ->
            if (value.isNotBlank()) try {
                saveCurrentDraft()
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                error = "Draft could not be saved. Retry before leaving this screen."
                Log.w("AhbRecord", "Draft autosave failed", failure)
            }
        }
    }
    OfflineContent(AhbDestination.VoiceIntake, onBack, error, busy) {
        AssistChip(onClick = {}, label = { Text("Input language: $language") })
        Text(status)
        if (!engine.isAvailable()) Text("Voice input is not set up on this phone yet. Type the symptoms below - it works exactly the same.")
        if (!typing && engine.isAvailable()) {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !recording && !busy, shape = CircleShape,
                modifier = Modifier.size(LocalAhbDimens.current.micButton).scale(if (recording) pulse else 1f)) {
                Icon(Icons.Default.Mic, "Speak symptoms")
            }
        }
        if (recording) Button(onClick = { engine.stopListening() }) { Text("Stop") }
        TextButton(onClick = { engine.stopListening(); typing = true }) { Text("Type Instead") }
        if (typing) InputField("Symptoms, in your own words", transcript, { transcript = it })
        else Text(transcript.ifBlank { "Describe what you observe." })
        Button(enabled = transcript.isNotBlank() && !recording && !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    val id = saveCurrentDraft()
                    onTranscriptReady(id, transcript.trim())
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Throwable) {
                    error = failure.message ?: "Could not save the draft. Retry."
                    Log.e("AhbRecord", "Continue failed", failure)
                } finally { busy = false }
            }
        }) { Text("Continue") }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/screens/CaseReviewScreen.kt
package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.camera.*
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json

@Composable
fun CaseReviewScreen(caseId: Long, onShareWithVet: (Long) -> Unit,
    onOpenQueries: (Long, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.app()
    val scope = rememberCoroutineScope()
    val factory = remember(caseId) { object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return modelClass.cast(CaseReviewViewModel(context, caseId))!!
        }
    } }
    val model: CaseReviewViewModel = viewModel(key = "review-$caseId", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var originalExpanded by remember { mutableStateOf(false) }
    var captureMode by remember { mutableStateOf<String?>(null) }
    val evidenceFlow = remember(caseId) { application.database.evidenceDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Attachments could not be loaded."
        emit(emptyList())
    } }
    val evidence by evidenceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    OfflineContent(AhbDestination.CaseReview, onBack, error ?: state.error, state.stage !in setOf("Ready", "Error")) {
        if (state.stage !in setOf("Ready", "Error")) Text(state.stage)
        Text(if (state.modelAvailable) "On-device model loaded" else "Model not loaded")
        if (state.stage == "Error") Button(onClick = { model.prepare() }) { Text("Retry preparation") }
        state.record?.let { case ->
            AhbTriageBadge(com.sohailsquad.animalhealthbridge.triage.resolveTriageLevel(case.triageLevel))
            if (case.structuredBy == Case.STRUCTURED_BY_FALLBACK) Text("Fallback used")
            if (case.translationFallback) Text("Translation unavailable. Original text preserved; contact a veterinarian.")
            Text("Local case #${case.id} · ${case.status}")
            ClinicalField("Main complaint", case.chiefComplaint)
            ClinicalField("Duration", case.duration)
            ClinicalField("Affected area", case.affectedArea)
            ClinicalField("Severity", case.severityIndicatorsJson)
            ClinicalField("Red flags", case.redFlagsJson)
            ClinicalField("Confidence", case.confidence)
            ClinicalField("Notes", case.additionalNotes)
            TextButton(onClick = { originalExpanded = !originalExpanded }) { Text("Original Transcript") }
            if (originalExpanded) Text(case.originalTranscript)
            evidence.forEach { EvidencePreview(it.uri, it.mimeType) }
            if (case.status != "APPROVED" && evidence.size < 5) {
                TextButton(onClick = { captureMode = "PHOTO" }) { Text("Attach photo") }
                TextButton(onClick = { captureMode = "VIDEO" }) { Text("Attach video") }
            }
            Button(enabled = !sharing, onClick = {
                scope.launch {
                    sharing = true
                    try {
                        application.caseRepository.requestReview(caseId)
                        onShareWithVet(caseId)
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Could not request review. Retry."
                        Log.e("AhbVet", "Review request failed", failure)
                    } finally { sharing = false }
                }
            }) { Text("Share with Vet (local review)") }
            TextButton(onClick = { onOpenQueries(caseId, "OWNER") }) { Text("Answer vet questions") }
        }
    }
    captureMode?.let { mode ->
        CaptureDialog(mode == "VIDEO", onCaptured = { uri, mime ->
            val validated = EvidenceStore(context).validate(uri, mime)
            application.caseRepository.addEvidence(caseId, validated, mime)
        }, onDismiss = { captureMode = null })
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/RecordExporter.kt
package com.sohailsquad.animalhealthbridge.data

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

class RecordExporter(context: Context) {
    private val context = context.applicationContext
    suspend fun createPdf(lines: List<String>, name: String): Uri? = withContext(Dispatchers.IO) {
        var file: File? = null
        try {
            require(name.matches(Regex("[a-zA-Z0-9_-]+\\.pdf")))
            val directory = File(context.cacheDir, "exports")
            require(directory.isDirectory || directory.mkdirs())
            file = File(directory, name)
            val document = PdfDocument()
            try {
                val paint = Paint().apply { textSize = 12f; isAntiAlias = true }
                var number = 1
                var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                var baseline = 40f
                for (line in lines.flatMap { it.lines() }) {
                    var remaining = line
                    do {
                        if (baseline > 800f) {
                            document.finishPage(page)
                            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
                            baseline = 40f
                        }
                        val count = paint.breakText(remaining, true, 515f, null).coerceAtLeast(1).coerceAtMost(remaining.length)
                        page.canvas.drawText(remaining.take(count), 40f, baseline, paint)
                        remaining = remaining.drop(count)
                        baseline += 18f
                    } while (remaining.isNotEmpty())
                }
                document.finishPage(page)
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            Log.i("AhbRecord", "PDF created: $name")
            FileProvider.getUriForFile(context, context.packageName + ".files", file)
        } catch (failure: CancellationException) { file?.delete(); throw failure }
        catch (failure: Throwable) {
            file?.delete()
            Log.w("AhbRecord", "PDF export unavailable", failure)
            null
        }
    }
    fun share(uri: Uri): Boolean = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("Record", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share local record").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i("AhbRecord", "User-initiated PDF share")
        true
    } catch (failure: Throwable) {
        Log.w("AhbRecord", "No sharing application available", failure)
        false
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/screens/VetApprovalScreen.kt
package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.data.db.entity.Treatment
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

@Composable
fun VetApprovalScreen(caseId: Long, onRequestMoreInfo: (Long, String) -> Unit,
    onTreatmentApproved: (Long) -> Unit, onBack: () -> Unit) {
    val application = LocalContext.current.app()
    val scope = rememberCoroutineScope()
    var case by remember(caseId) { mutableStateOf<Case?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var approved by remember { mutableStateOf(false) }
    var fields by rememberSaveable(caseId) { mutableStateOf(List(12) { "" }) }
    val labels = listOf("Vet name", "Registration number", "Vet notes", "Medicine", "Dosage",
        "Frequency", "Duration", "Route", "Withdrawal period (days; enter 0 if none)",
        "Special instructions", "Follow-up date (YYYY-MM-DD; optional)", "Clinic / contact (optional)")
    val queriesFlow = remember(caseId) { application.database.vetQueryDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Questions could not be loaded; approval will recheck them."
        emit(emptyList())
    } }
    val queries by queriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val evidenceFlow = remember(caseId) { application.database.evidenceDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Evidence could not be loaded; do not approve until reviewed."
        emit(emptyList())
    } }
    val evidence by evidenceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(caseId) {
        try {
            case = application.caseRepository.get(caseId)
            approved = application.treatmentRepository.getForCase(caseId) != null
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            error = failure.message ?: "Could not load this case."
            Log.e("AhbVet", "Review load failed", failure)
        } finally { busy = false }
    }
    OfflineContent(AhbDestination.VetApproval, onBack, error, busy) {
        Text("Local veterinarian review. Credentials entered here are not independently verified.")
        case?.let {
            AhbTriageBadge(com.sohailsquad.animalhealthbridge.triage.resolveTriageLevel(it.triageLevel))
            ClinicalField("Case #${it.id}", it.chiefComplaint)
            ClinicalField("Duration", it.duration)
            ClinicalField("Affected area", it.affectedArea)
            ClinicalField("Severity", it.severityIndicatorsJson)
            ClinicalField("Red flags", it.redFlagsJson)
            ClinicalField("Confidence", it.confidence)
            ClinicalField("Observations", it.additionalNotes)
            Text(it.englishTranscript.ifBlank { it.originalTranscript })
            if (it.translationFallback) Text("Translation unavailable; original language preserved.")
        }
        evidence.forEach { EvidencePreview(it.uri, it.mimeType) }
        val pending = queries.count { it.status == "PENDING" }
        Text("Pending questions: $pending")
        queries.filter { it.status == "ANSWERED" }.forEach {
            Text("Q: ${it.questionTextEnglish}\nA: ${it.answerText}\nMedia: ${it.answerMediaUris}")
        }
        if (approved) {
            Text("Treatment already approved and saved.")
            Button(onClick = { onTreatmentApproved(caseId) }) { Text("View prescription") }
        } else {
            labels.forEachIndexed { index, label ->
                InputField(label, fields[index], { value -> fields = fields.toMutableList().also { it[index] = value } })
            }
            TextButton(enabled = case != null && !busy, onClick = { onRequestMoreInfo(caseId, "VET") }) {
                Text("Request More Info")
            }
            Button(enabled = case != null && pending == 0 && !busy &&
                listOf(0, 1, 3, 4, 5, 6, 7, 8).all { fields[it].isNotBlank() }, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val withdrawal = requireNotNull(fields[8].trim().toIntOrNull()) { "Enter withdrawal days as a whole number." }
                        val treatment = Treatment(caseId = caseId, medicine = fields[3].trim(), dosage = fields[4].trim(),
                            frequency = fields[5].trim(), duration = fields[6].trim(), route = fields[7].trim(),
                            withdrawalPeriodDays = withdrawal, specialInstructions = fields[9].trim(),
                            followUpDate = parseDate(fields[10]), vetName = fields[0].trim(),
                            vetRegistrationNumber = fields[1].trim(),
                            vetNotes = listOf(fields[2], fields[11]).filter { it.isNotBlank() }.joinToString("\n"))
                        application.treatmentRepository.approve(treatment)
                        approved = true
                        onTreatmentApproved(caseId)
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Approval failed. Check the fields and retry."
                        Log.e("AhbVet", "Approval failed", failure)
                    } finally { busy = false }
                }
            }) { Text("Approve Treatment") }
        }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/screens/PrescriptionScreen.kt
package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.RecordExporter
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.data.db.entity.Treatment
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import kotlinx.coroutines.*

@Composable
fun PrescriptionScreen(caseId: Long, onSaveToRecord: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.app()
    val scope = rememberCoroutineScope()
    var case by remember { mutableStateOf<Case?>(null) }
    var treatment by remember { mutableStateOf<Treatment?>(null) }
    var animalTag by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(caseId) {
        try {
            case = application.caseRepository.get(caseId)
            treatment = application.treatmentRepository.getForCase(caseId)
            requireNotNull(treatment) { "No approved treatment. A veterinarian must approve this case first." }
            animalTag = application.database.animalDao().getById(requireNotNull(case).animalId)?.tagNumber.orEmpty()
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            error = failure.message ?: "Prescription unavailable."
            Log.w("AhbVet", "Prescription load failed", failure)
        } finally { busy = false }
    }
    OfflineContent(AhbDestination.Prescription, onBack, error, busy) {
        treatment?.let { approved ->
            val lines = listOf("Animal Health Bridge — approved local treatment",
                "Animal: $animalTag | Case: $caseId", "Issued: ${displayDate(approved.approvedAt)}",
                "Vet: ${approved.vetName}", "Registration: ${approved.vetRegistrationNumber}",
                "Complaint: ${case?.chiefComplaint.orEmpty()}", "Vet notes: ${approved.vetNotes}",
                "Medicine: ${approved.medicine}", "Dose: ${approved.dosage}", "Frequency: ${approved.frequency}",
                "Duration: ${approved.duration}", "Route: ${approved.route}",
                "Withdrawal: ${approved.withdrawalPeriodDays} days", "Instructions: ${approved.specialInstructions}",
                "Follow-up: ${approved.followUpDate?.let(::displayDate) ?: "Not specified"}",
                "Credentials are user-entered, not independently verified. This document is not digitally signed.")
            lines.forEach { Text(it) }
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val exporter = RecordExporter(context)
                        val uri = requireNotNull(exporter.createPdf(lines, "prescription-$caseId.pdf")) { "PDF could not be created. Retry." }
                        require(exporter.share(uri)) { "No app can share this PDF." }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) { error = failure.message ?: "Sharing failed." }
                    finally { busy = false }
                }
            }) { Text("Share PDF") }
            TextButton(onClick = { case?.let { onSaveToRecord(it.animalId) } }) { Text("Saved to Record · Open history") }
        }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/screens/VetQueryScreen.kt
package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.camera.*
import com.sohailsquad.animalhealthbridge.data.db.entity.VetQuery
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json

@Composable
fun VetQueryScreen(caseId: Long, viewMode: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.app()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var question by rememberSaveable { mutableStateOf("") }
    var vetName by rememberSaveable { mutableStateOf("") }
    var vetId by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("TEXT") }
    var answers by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var media by remember { mutableStateOf<Map<Long, List<String>>>(emptyMap()) }
    var captureQuery by remember { mutableStateOf<VetQuery?>(null) }
    val flow = remember(caseId) { application.database.vetQueryDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Questions could not be loaded."
        Log.w("AhbVet", "Query load failed", it)
        emit(emptyList())
    } }
    val queries by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val evidenceFlow = remember(caseId) { application.database.evidenceDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Saved attachments could not be loaded. Answer in text instead."
        emit(emptyList())
    } }
    val savedEvidence by evidenceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    OfflineContent(AhbDestination.VetQuery, onBack, error, busy) {
        Text("Local ${if (viewMode == "VET") "vet" else "owner"} view; no remote delivery or verified role.")
        if (viewMode == "VET") {
            InputField("Vet name", vetName, { vetName = it })
            InputField("Registration number", vetId, { vetId = it })
            InputField("Question in English", question, { question = it })
            listOf("PHOTO", "VIDEO", "YES_NO", "TEXT").forEach { option ->
                FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option) })
            }
            Button(enabled = !busy && question.isNotBlank() && vetName.isNotBlank() && vetId.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val case = application.caseRepository.get(caseId)
                        val translated = application.translator.translateResult(question.trim(), "en", case.sourceLang)
                        application.caseRepository.ask(VetQuery(caseId = caseId, vetId = vetId.trim(), vetName = vetName.trim(),
                            questionTextEnglish = question.trim(), questionTextNative = translated.text,
                            nativeLanguage = case.sourceLang, queryType = type, translationFallback = translated.isFallback))
                        question = ""
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Question could not be saved. Retry."
                        Log.e("AhbVet", "Question save failed", failure)
                    } finally { busy = false }
                }
            }) { Text("Save question locally") }
        }
        if (queries.isEmpty()) Text("No questions yet.")
        queries.forEach { query ->
            HorizontalDivider()
            Text(if (viewMode == "OWNER") query.questionTextNative else query.questionTextEnglish)
            if (query.translationFallback) Text("Translation unavailable; original English shown.")
            Text("${query.queryType} · ${query.status}")
            if (query.status == "ANSWERED") {
                Text(query.answerText)
                val uris = runCatching { Json.decodeFromString<List<String>>(query.answerMediaUris) }.getOrDefault(emptyList())
                uris.forEach { EvidencePreview(it, if (it.endsWith(".mp4")) "video/mp4" else "image/jpeg") }
            } else if (viewMode != "VET") {
                val value = answers[query.id].orEmpty()
                InputField("Answer (text is always available)", value, { answers = answers + (query.id to it) })
                if (query.queryType == "YES_NO") listOf("Yes", "No", "Not sure").forEach { response ->
                    TextButton(onClick = { answers = answers + (query.id to response) }) { Text(response) }
                }
                if (query.queryType in setOf("PHOTO", "VIDEO")) {
                    TextButton(enabled = media[query.id].orEmpty().size < 5 && !busy,
                        onClick = { captureQuery = query }) { Text("Capture ${query.queryType.lowercase()}") }
                    savedEvidence.filter { item ->
                        if (query.queryType == "PHOTO") item.mimeType.startsWith("image/") else item.mimeType.startsWith("video/")
                    }.forEach { item ->
                        val selected = item.uri in media[query.id].orEmpty()
                        Row {
                            Checkbox(checked = selected, enabled = selected || media[query.id].orEmpty().size < 5,
                                onCheckedChange = { checked ->
                                    val current = media[query.id].orEmpty()
                                    media = media + (query.id to if (checked) (current + item.uri).distinct() else current - item.uri)
                                })
                            Text("Use saved attachment #${item.id}")
                        }
                    }
                }
                media[query.id].orEmpty().forEach { EvidencePreview(it, if (it.endsWith(".mp4")) "video/mp4" else "image/jpeg") }
            }
        }
        if (viewMode != "VET") {
            val pending = queries.filter { it.status == "PENDING" }
            Button(enabled = !busy && pending.isNotEmpty() && pending.all {
                answers[it.id].orEmpty().isNotBlank() || media[it.id].orEmpty().isNotEmpty()
            }, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        application.database.withTransaction {
                            pending.forEach { application.caseRepository.answer(caseId, it.id,
                                answers[it.id].orEmpty(), media[it.id].orEmpty()) }
                        }
                        answers = emptyMap()
                        media = emptyMap()
                        Log.i("AhbVet", "All responses saved locally case=$caseId")
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Responses could not be saved. Retry."
                        Log.e("AhbVet", "Response save failed", failure)
                    } finally { busy = false }
                }
            }) { Text("Send All Responses (save locally)") }
        }
    }
    captureQuery?.let { query ->
        CaptureDialog(query.queryType == "VIDEO", onCaptured = { uri, mime ->
            val validated = EvidenceStore(context).validate(uri, mime)
            application.caseRepository.addEvidence(caseId, validated, mime)
            media = media + (query.id to (media[query.id].orEmpty() + validated))
        }, onDismiss = { captureQuery = null })
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/screens/HealthRecordScreen.kt
package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.RecordExporter
import com.sohailsquad.animalhealthbridge.camera.CaptureDialog
import com.sohailsquad.animalhealthbridge.camera.EvidenceStore
import com.sohailsquad.animalhealthbridge.data.db.entity.*
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

private fun <T> Flow<List<T>>.recordFallback(onError: (String) -> Unit) = catch { failure ->
    if (failure is CancellationException) throw failure
    onError("Some records could not be loaded.")
    Log.w("AhbRecord", "History load failed", failure)
    emit(emptyList())
}
private data class TimelineEntry(val date: Long, val kind: String, val title: String,
    val detail: String, val caseId: Long? = null, val triage: String? = null)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HealthRecordScreen(animalId: Long, onOpenAnimal: (Long) -> Unit,
    onOpenCase: (Long, Long) -> Unit, onStartNewCase: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.app()
    val database = application.database
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var profile by remember(animalId) { mutableStateOf<AnimalProfile?>(null) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var addVaccination by remember { mutableStateOf(false) }
    var editProfile by remember { mutableStateOf(false) }
    var profilePhoto by remember { mutableStateOf(false) }
    var animalName by rememberSaveable(animalId) { mutableStateOf("") }
    var animalAge by rememberSaveable(animalId) { mutableStateOf("") }
    var ownerName by rememberSaveable(animalId) { mutableStateOf("") }
    var vaccineName by rememberSaveable { mutableStateOf("") }
    var vaccineDate by rememberSaveable { mutableStateOf(displayDate(System.currentTimeMillis())) }
    var dueDate by rememberSaveable { mutableStateOf("") }
    var vetName by rememberSaveable { mutableStateOf("") }
    val animals by remember { database.animalDao().observeAll().recordFallback { error = it } }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val cases by remember(animalId) { database.caseDao().observeForAnimal(animalId).recordFallback { error = it } }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val treatments by remember(animalId) { database.treatmentDao().observeForAnimal(animalId).recordFallback { error = it } }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val vaccinations by remember(animalId) { database.vaccinationDao().observeForAnimal(animalId).recordFallback { error = it } }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(animalId) {
        if (animalId > 0) try { profile = requireNotNull(database.animalDao().getById(animalId)) { "Animal not found." } }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) { error = failure.message ?: "Animal unavailable." }
        Log.i("AhbRecord", "History opened animal=$animalId")
    }
    val withdrawal = treatments.filter { it.withdrawalPeriodDays > 0 &&
        it.approvedAt + it.withdrawalPeriodDays * 86_400_000L > System.currentTimeMillis() }
    val timeline = cases.map { TimelineEntry(it.createdAt, "Cases", it.chiefComplaint.ifBlank { "Draft case" },
        "Case #${it.id} · ${it.status}\n${it.additionalNotes}", it.id, it.triageLevel) } +
        treatments.map { TimelineEntry(it.approvedAt, "Treatments", it.medicine,
            "${it.dosage}, ${it.frequency}, ${it.duration}\nVet: ${it.vetName}\n${it.vetNotes}\nFollow-up: ${it.followUpDate?.let(::displayDate) ?: "Not specified"}") } +
        vaccinations.map { TimelineEntry(it.date, "Vaccinations", it.vaccineName,
            "Vet: ${it.vetName}\nNext due: ${it.nextDueDate?.let(::displayDate) ?: "Not specified"}") } +
        treatments.filter { it.withdrawalPeriodDays > 0 }.map { TimelineEntry(it.approvedAt, "Withdrawals",
            "Withdrawal: ${it.medicine}", "Until ${displayDate(it.approvedAt + it.withdrawalPeriodDays * 86_400_000L)}") }
    val connectorColor = MaterialTheme.colorScheme.outline
    OfflineContent(AhbDestination.HealthRecord, onBack, error, busy) {
        if (animalId == 0L) {
            Text("Select an animal")
            if (animals.isEmpty()) Text("No animals saved yet.")
            animals.forEach { animal ->
                TextButton(onClick = { onOpenAnimal(animal.id) }) { Text(animal.tagNumber) }
            }
        } else profile?.let { animal ->
            Text("Animal: ${animal.tagNumber}")
            Text("Name: ${animal.name.ifBlank { "Not recorded" }}")
            Text("Species: ${animal.species} · Age: ${animal.age.ifBlank { "Not recorded" }}")
            Text("Owner: ${animal.ownerName.ifBlank { "Not recorded" }}")
            animal.photoUri?.let { EvidencePreview(it, "image/jpeg") }
            TextButton(onClick = {
                animalName = animal.name
                animalAge = animal.age
                ownerName = animal.ownerName
                editProfile = !editProfile
            }) { Text("Edit animal details") }
            if (editProfile) {
                InputField("Animal name (optional)", animalName, { animalName = it })
                InputField("Age (optional)", animalAge, { animalAge = it })
                InputField("Owner name (optional)", ownerName, { ownerName = it })
                TextButton(onClick = { profilePhoto = true }) { Text("Capture animal photo") }
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val updated = animal.copy(name = animalName.trim(), age = animalAge.trim(), ownerName = ownerName.trim())
                            database.animalDao().update(updated)
                            profile = updated
                            editProfile = false
                            Log.i("AhbRecord", "Animal details updated animal=$animalId")
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Throwable) { error = "Animal details could not be saved. Retry." }
                        finally { busy = false }
                    }
                }) { Text("Save animal details") }
            }
            withdrawal.forEach { Text("Active withdrawal: ${it.medicine}, until ${displayDate(it.approvedAt + it.withdrawalPeriodDays * 86_400_000L)}") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Cases", "Treatments", "Vaccinations", "Withdrawals").forEach { option ->
                    FilterChip(filter == option, onClick = { filter = option }, label = { Text(option) })
                }
            }
            val visible = timeline.filter { filter == "All" || it.kind == filter }.sortedByDescending { it.date }
            if (visible.isEmpty()) Text("No history yet")
            visible.forEach { entry ->
                Column(Modifier.fillMaxWidth().drawBehind {
                    drawLine(connectorColor, Offset(4.dp.toPx(), 0f), Offset(4.dp.toPx(), size.height), 2.dp.toPx())
                }.padding(start = 20.dp)) {
                Text("● ${displayDate(entry.date)} · ${entry.kind}")
                entry.triage?.let { AhbTriageBadge(com.sohailsquad.animalhealthbridge.triage.resolveTriageLevel(it)) }
                Text(entry.title)
                Text(entry.detail)
                entry.caseId?.let { id ->
                    TextButton(onClick = { onOpenCase(animalId, id) }) { Text("Open case") }
                }
                }
            }
            TextButton(onClick = { addVaccination = !addVaccination }) { Text("Record vaccination") }
            if (addVaccination) {
                InputField("Vaccine name", vaccineName, { vaccineName = it })
                InputField("Given date (YYYY-MM-DD)", vaccineDate, { vaccineDate = it })
                InputField("Next due date (YYYY-MM-DD; optional)", dueDate, { dueDate = it })
                InputField("Veterinarian name", vetName, { vetName = it })
                Button(enabled = !busy && vaccineName.isNotBlank() && vetName.isNotBlank(), onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val date = requireNotNull(parseDate(vaccineDate))
                            val next = parseDate(dueDate)
                            require(date <= System.currentTimeMillis()) { "Given date cannot be in the future." }
                            require(next == null || next >= date) { "Next due date must follow the given date." }
                            database.vaccinationDao().insert(Vaccination(animalId = animalId, vaccineName = vaccineName.trim(),
                                date = date, nextDueDate = next, vetName = vetName.trim()))
                            addVaccination = false
                            vaccineName = ""
                            Log.i("AhbRecord", "Vaccination saved animal=$animalId")
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Throwable) { error = failure.message ?: "Vaccination could not be saved." }
                        finally { busy = false }
                    }
                }) { Text("Save vaccination") }
            }
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val lines = mutableListOf("Animal Health Bridge — local health record", "Animal: ${animal.tagNumber}",
                            "Name: ${animal.name}", "Species: ${animal.species}", "Owner: ${animal.ownerName}", "Age: ${animal.age}")
                        cases.forEach { episode ->
                            lines += listOf("Case #${episode.id}: ${displayDate(episode.createdAt)} (${episode.status})",
                                "Complaint: ${episode.chiefComplaint}", "Duration: ${episode.duration}", "Area: ${episode.affectedArea}",
                                "Severity: ${episode.severityIndicatorsJson}", "Red flags: ${episode.redFlagsJson}",
                                "Triage: ${episode.triageLevel} (${episode.triageRuleId})", "Confidence: ${episode.confidence}",
                                "Source: ${episode.structuredBy}; translation fallback: ${episode.translationFallback}",
                                "Original: ${episode.originalTranscript}", "English: ${episode.englishTranscript}", "Notes: ${episode.additionalNotes}")
                            val attachments = database.evidenceDao().getForCase(episode.id)
                            lines += "Attachments retained on this phone: ${attachments.size}; not embedded in this PDF."
                        }
                        treatments.forEach { approved ->
                            lines += listOf("Treatment for case #${approved.caseId}: ${displayDate(approved.approvedAt)}",
                                "Medicine: ${approved.medicine}; dosage: ${approved.dosage}; frequency: ${approved.frequency}",
                                "Duration: ${approved.duration}; route: ${approved.route}", "Instructions: ${approved.specialInstructions}",
                                "Vet: ${approved.vetName} (${approved.vetRegistrationNumber})", "Vet notes: ${approved.vetNotes}",
                                "Withdrawal: ${approved.withdrawalPeriodDays} days", "Follow-up: ${approved.followUpDate?.let(::displayDate) ?: "Not specified"}")
                        }
                        vaccinations.forEach { vaccine ->
                            lines += listOf("Vaccination: ${vaccine.vaccineName} on ${displayDate(vaccine.date)}",
                                "Vet: ${vaccine.vetName}; next due: ${vaccine.nextDueDate?.let(::displayDate) ?: "Not specified"}")
                        }
                        val exporter = RecordExporter(context)
                        val uri = requireNotNull(exporter.createPdf(lines, "record-$animalId.pdf")) { "PDF could not be created." }
                        require(exporter.share(uri)) { "No sharing application available." }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) { error = failure.message ?: "Export failed." }
                    finally { busy = false }
                }
            }) { Text("Export Record") }
            Button(onClick = { onStartNewCase(animalId) }) { Text("Start New Case") }
        }
    }
    if (profilePhoto) CaptureDialog(videoMode = false, onCaptured = { uri, mime ->
        val validated = EvidenceStore(context).validate(uri, mime)
        val updated = requireNotNull(profile).copy(photoUri = validated)
        database.animalDao().update(updated)
        profile = updated
    }, onDismiss = { profilePhoto = false })
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/MainActivity.kt
package com.sohailsquad.animalhealthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sohailsquad.animalhealthbridge.navigation.AhbNavHost
import com.sohailsquad.animalhealthbridge.ui.theme.AnimalHealthBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AnimalHealthBridgeTheme { AhbNavHost() } }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/navigation/AhbNavHost.kt
package com.sohailsquad.animalhealthbridge.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.ui.screens.*
import kotlinx.coroutines.*

object NavArgs {
    const val ANIMAL_ID = "animalId"
    const val CASE_ID = "caseId"
    const val TRANSCRIPT = "transcript"
}
fun caseReviewRoute(animalId: Long, caseId: Long, transcript: String = "") =
    "case_review/$animalId?caseId=$caseId&transcript=" + Uri.encode(transcript)

private fun NavBackStackEntry.identifier(key: String) = arguments?.getLong(key) ?: 0L
private fun longArgument(name: String) = navArgument(name) { type = NavType.LongType; defaultValue = 0L }

@Composable
fun AhbNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val application = LocalContext.current.app()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val back: () -> Unit = { if (!nav.popBackStack()) nav.navigate("animal_id") }
    fun navigate(route: String) {
        try {
            nav.navigate(route) { launchSingleTop = true }
            Log.i("AhbRecord", "Navigation: ${route.substringBefore('?')}")
        } catch (failure: Throwable) {
            error = "This screen could not be opened. Go back and retry."
            Log.e("AhbRecord", "Navigation failed", failure)
        }
    }
    NavHost(navController = nav, startDestination = "language_select", modifier = modifier) {
        composable("language_select") {
            LanguageSelectScreen(onContinue = { language ->
                scope.launch {
                    try {
                        application.preferences.setLanguage(language)
                        navigate("animal_id")
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = "Language could not be saved. Retry."
                        Log.e("AhbAsr", "Language save failed", failure)
                    }
                }
            })
        }
        composable("animal_id") {
            AnimalIdScreen(onAnimalResolved = { id ->
                val animalId = id.toLongOrNull()
                if (animalId != null && animalId > 0) navigate("voice_intake/$animalId")
                else error = "Animal could not be identified."
            }, onOpenHistory = { navigate("health_record/0") }, onBack = back)
        }
        composable("voice_intake/{animalId}", arguments = listOf(longArgument("animalId"))) { entry ->
            val animalId = entry.identifier("animalId")
            VoiceIntakeScreen(animalId, onTranscriptReady = { caseId, text ->
                navigate(caseReviewRoute(animalId, caseId, text))
            }, onBack = back)
        }
        composable("case_review/{animalId}?caseId={caseId}&transcript={transcript}",
            arguments = listOf(longArgument("animalId"), longArgument("caseId"),
                navArgument("transcript") { type = NavType.StringType; defaultValue = "" })) { entry ->
            CaseReviewScreen(entry.identifier("caseId"),
                onShareWithVet = { navigate("vet_approval/$it") },
                onOpenQueries = { caseId, mode -> navigate("vet_query/$caseId/$mode") }, onBack = back)
        }
        composable("vet_approval/{caseId}", arguments = listOf(longArgument("caseId"))) { entry ->
            VetApprovalScreen(entry.identifier("caseId"),
                onRequestMoreInfo = { caseId, mode -> navigate("vet_query/$caseId/$mode") },
                onTreatmentApproved = { navigate("prescription/$it") }, onBack = back)
        }
        composable("prescription/{caseId}", arguments = listOf(longArgument("caseId"))) { entry ->
            PrescriptionScreen(entry.identifier("caseId"), onSaveToRecord = { navigate("health_record/$it") }, onBack = back)
        }
        composable("vet_query/{caseId}/{viewMode}", arguments = listOf(longArgument("caseId"),
            navArgument("viewMode") { type = NavType.StringType; defaultValue = "OWNER" })) { entry ->
            VetQueryScreen(entry.identifier("caseId"), entry.arguments?.getString("viewMode") ?: "OWNER", back)
        }
        composable("health_record/{animalId}", arguments = listOf(longArgument("animalId"))) { entry ->
            HealthRecordScreen(entry.identifier("animalId"), onOpenAnimal = { navigate("health_record/$it") },
                onOpenCase = { animalId, caseId -> navigate(caseReviewRoute(animalId, caseId)) },
                onStartNewCase = { navigate("voice_intake/$it") }, onBack = back)
        }
    }
    error?.let {
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Action unavailable") },
            text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/AnimalHealthBridgeApp.kt
package com.sohailsquad.animalhealthbridge

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.repository.AnimalRepository
import com.sohailsquad.animalhealthbridge.ml.llm.LlmEngine
import com.sohailsquad.animalhealthbridge.ml.translation.Translator

/**
 * Application subclass and the app's manual dependency graph.
 *
 * Manual DI (rather than Hilt) is a deliberate choice from the tech stack: with
 * a 35-hour budget, a graph a judge can read top-to-bottom is worth more than
 * an annotation processor's worth of build time.
 *
 * Everything is lazy. Nothing is constructed until a screen asks for it, which
 * matters now that the graph includes multi-hundred-megabyte model engines:
 * opening the app must not load Gemma just to show the language picker.
 */
class AnimalHealthBridgeApp : Application() {

    /**
     * The Room database.
     *
     * `by lazy` + [AppDatabase.get] means the first screen that needs data pays
     * the construction cost, and the singleton guarantees one shared connection.
     */
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val preferences by lazy { com.sohailsquad.animalhealthbridge.data.UserPreferences(this) }
    val caseRepository by lazy { com.sohailsquad.animalhealthbridge.data.repository.CaseRepository(this) }
    val treatmentRepository by lazy { com.sohailsquad.animalhealthbridge.data.repository.TreatmentRepository(this) }
    val triageEngine by lazy { com.sohailsquad.animalhealthbridge.triage.TriageEngine(this) }

    /** The single animal repository for the process. */
    val animalRepository: AnimalRepository by lazy {
        AnimalRepository(database.animalDao())
    }

    /**
     * Hindi to English translation.
     *
     * Lazy and process-wide: the ONNX sessions are expensive to build and are
     * thread-safe to reuse, so they are shared rather than rebuilt per screen.
     */
    val translator: Translator by lazy { Translator(this) }

    /**
     * Case structuring.
     *
     * Also lazy and process-wide. Gemma loads on first use, which is why the
     * case-review screen shows a "Structuring..." state rather than blocking
     * app start.
     */
    val llmEngine: LlmEngine by lazy { LlmEngine(this) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Animal Health Bridge starting - offline-first, on-device only")
    }

    private companion object {
        const val TAG = "AnimalHealthBridge"
    }
}

/**
 * Finds the [AnimalHealthBridgeApp] behind any context.
 *
 * Compose hands us the Activity context, and an Activity's `baseContext` is a
 * framework `ContextImpl` rather than the Application - so walking the wrapper
 * chain alone never reaches the Application and would throw. Asking for
 * [Context.getApplicationContext] first is the reliable route, with the wrapper
 * walk kept as a fallback for unusual wrapped contexts.
 *
 * The cast is safe because the manifest declares this class as the application
 * (`android:name=".AnimalHealthBridgeApp"`), but a misconfigured manifest still
 * reports clearly rather than surfacing a ClassCastException from a screen.
 */
fun Context.app(): AnimalHealthBridgeApp {
    (applicationContext as? AnimalHealthBridgeApp)?.let { return it }

    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is AnimalHealthBridgeApp) return context
        context = context.baseContext
    }

    error("AnimalHealthBridgeApp is not installed; check AndroidManifest android:name")
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/AppDatabase.kt
package com.sohailsquad.animalhealthbridge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sohailsquad.animalhealthbridge.data.db.dao.AnimalDao
import com.sohailsquad.animalhealthbridge.data.db.dao.CaseDao
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.ml.translation.TranslationCacheDao
import com.sohailsquad.animalhealthbridge.ml.translation.TranslationCacheEntry

/**
 * The app's Room database.
 *
 * Version history:
 *   1 - AnimalProfile only (Step 2).
 *   2 - Case, and the translation cache (Steps 4-5).
 *
 * ---------------------------------------------------------------------------
 * NO DESTRUCTIVE MIGRATION
 * ---------------------------------------------------------------------------
 * There is deliberately no `fallbackToDestructiveMigration()`. A version bump
 * with no migration throws instead of wiping the database - which is the
 * correct trade for a table of clinical records. Version 2 is reached by
 * [MIGRATION_1_2] below, which only ADDS tables, so no existing animal is
 * touched.
 */
@Database(
    entities = [
        AnimalProfile::class,
        Case::class,
        TranslationCacheEntry::class,
        com.sohailsquad.animalhealthbridge.data.db.entity.TranslationCache::class,
        com.sohailsquad.animalhealthbridge.data.db.entity.Treatment::class,
        com.sohailsquad.animalhealthbridge.data.db.entity.VetQuery::class,
        com.sohailsquad.animalhealthbridge.data.db.entity.Vaccination::class,
        com.sohailsquad.animalhealthbridge.data.db.entity.TriageLog::class,
        com.sohailsquad.animalhealthbridge.data.db.entity.Evidence::class,
    ],
    version = 3,
    // Schemas are not exported yet; flip to true once migrations need diffing.
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    /** Animals. */
    abstract fun animalDao(): AnimalDao

    /** Clinical cases. */
    abstract fun caseDao(): CaseDao

    /** Translation cache. */
    abstract fun translationCacheDao(): TranslationCacheDao
    abstract fun translationDao(): com.sohailsquad.animalhealthbridge.data.db.dao.TranslationDao
    abstract fun treatmentDao(): com.sohailsquad.animalhealthbridge.data.db.dao.TreatmentDao
    abstract fun vetQueryDao(): com.sohailsquad.animalhealthbridge.data.db.dao.VetQueryDao
    abstract fun vaccinationDao(): com.sohailsquad.animalhealthbridge.data.db.dao.VaccinationDao
    abstract fun triageLogDao(): com.sohailsquad.animalhealthbridge.data.db.dao.TriageLogDao
    abstract fun evidenceDao(): com.sohailsquad.animalhealthbridge.data.db.dao.EvidenceDao

    companion object {

        /** File name on disk. */
        private const val DB_NAME = "animal_health_bridge.db"

        /**
         * Adds the cases and translation_cache tables.
         *
         * Purely additive: no existing column is altered and no row is
         * rewritten, so an animal captured in Step 2 keeps its history intact.
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cases` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `animal_id` INTEGER NOT NULL,
                        `chief_complaint` TEXT NOT NULL,
                        `duration` TEXT NOT NULL,
                        `affected_area` TEXT NOT NULL,
                        `severity_indicators_json` TEXT NOT NULL,
                        `red_flags_json` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `triage_level` TEXT NOT NULL,
                        `triage_rule_id` TEXT NOT NULL,
                        `original_transcript` TEXT NOT NULL,
                        `english_transcript` TEXT NOT NULL,
                        `photo_uri` TEXT,
                        `structured_by` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        FOREIGN KEY(`animal_id`) REFERENCES `animal_profiles`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cases_animal_id_created_at` " +
                        "ON `cases` (`animal_id`, `created_at`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `translation_cache` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `source_text` TEXT NOT NULL,
                        `target_text` TEXT NOT NULL,
                        `source_lang` TEXT NOT NULL,
                        `target_lang` TEXT NOT NULL,
                        `is_model_output` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_translation_cache_source_text_source_lang_target_lang` " +
                        "ON `translation_cache` (`source_text`, `source_lang`, `target_lang`)",
                )
            }
        }

        /**
         * Cached instance.
         *
         * `@Volatile` so a write from one thread is immediately visible to the
         * others; without it a second thread could observe a half-published
         * instance and open a second connection.
         */
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the singleton database, building it on first use.
         *
         * Double-checked locking: the fast path avoids the lock once the
         * instance exists, which matters because every screen calls this.
         *
         * @param context any context; the application context is retained so the
         *   database cannot outlive an Activity.
         */
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, ClinicalMigration)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/dao/AnimalDao.kt
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
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/dao/CaseDao.kt
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
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/entity/AnimalProfile.kt
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
    @ColumnInfo(defaultValue = "''")
    val name: String = "",
)
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/data/db/entity/Case.kt
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
        Index(value = ["draft_key"], unique = true),
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
    @ColumnInfo(name = "additional_notes", defaultValue = "'not stated'")
    val additionalNotes: String = "not stated",
    @ColumnInfo(name = "source_lang", defaultValue = "'hi'")
    val sourceLang: String = "hi",
    @ColumnInfo(defaultValue = "'DRAFT'")
    val status: String = "DRAFT",
    @ColumnInfo(name = "draft_key")
    val draftKey: String? = null,
    @ColumnInfo(name = "processing_complete", defaultValue = "1")
    val processingComplete: Boolean = true,
    @ColumnInfo(name = "translation_fallback", defaultValue = "0")
    val translationFallback: Boolean = false,
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
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/components/AhbTriageBadge.kt
package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors

/**
 * The three urgency levels the deterministic rules engine can assign.
 *
 * Declared here - not in the rules-engine package - because the enum is first
 * and foremost a presentation concern: it decides colour and icon. Step 6 will
 * add the rule engine that produces these values; keeping the shared vocabulary
 * in one place avoids a cyclic dependency between engine and UI.
 *
 * Ordering is meaningful: `ordinal` runs from most to least urgent, which lets
 * callers compare severity with `<` / `>` instead of a lookup table.
 */
typealias TriageLevel = com.sohailsquad.animalhealthbridge.triage.TriageLevel

/**
 * Colour + icon + label for a [TriageLevel], resolved from the theme.
 *
 * Kept as a private extension so the mapping lives next to the badge that uses
 * it, rather than being duplicated on every screen that shows urgency.
 */
private data class TriageStyle(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
)

@Composable
private fun styleFor(level: TriageLevel): TriageStyle {
    val extras = LocalAhbColors.current
    return when (level) {
        TriageLevel.EMERGENCY_NOW -> TriageStyle(
            container = extras.emergency,
            content = extras.onEmergency,
            icon = Icons.Filled.Error,
        )
        TriageLevel.SEE_VET_TODAY -> TriageStyle(
            container = extras.warning,
            content = extras.onEmergency, // dark text reads better on amber too
            icon = Icons.Filled.Warning,
        )
        TriageLevel.MONITOR -> TriageStyle(
            container = extras.success,
            content = extras.onAccent,
            icon = Icons.Filled.CheckCircle,
        )
    }
}

/**
 * Human-readable label for a triage level.
 *
 * The strings are deliberately spelled out rather than derived from the enum
 * name so the wording can change without touching the data model.
 */
fun TriageLevel.displayLabel(): String = when (this) {
    TriageLevel.EMERGENCY_NOW -> "EMERGENCY NOW"
    TriageLevel.SEE_VET_TODAY -> "SEE VET TODAY"
    TriageLevel.MONITOR -> "MONITOR"
}

/**
 * Pill-shaped urgency badge.
 *
 * Colour is never the only signal: the badge always pairs a colour with a
 * distinct icon and a text label, so it stays readable for colour-blind users
 * and in bright sunlight on a cheap screen.
 *
 * @param level the urgency to display.
 * @param modifier layout modifier.
 */
@Composable
fun AhbTriageBadge(
    level: TriageLevel,
    modifier: Modifier = Modifier,
) {
    val style = styleFor(level)
    Row(
        modifier = modifier
            .background(
                color = style.container,
                shape = RoundedCornerShape(LocalAhbDimens.current.radiusChip),
            )
            .padding(horizontal = LocalAhbDimens.current.gap, vertical = LocalAhbDimens.current.gapTight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gapTight),
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = null, // the label below carries the meaning
            tint = style.content,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = level.displayLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = style.content,
        )
    }
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/java/com/sohailsquad/animalhealthbridge/ui/screens/AnimalIdScreen.kt
package com.sohailsquad.animalhealthbridge.ui.screens

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.repository.AnimalRepository
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.nfc.NfcReader
import com.sohailsquad.animalhealthbridge.nfc.QrScannerDialog
import com.sohailsquad.animalhealthbridge.ui.components.AhbCard
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButtonWide
import com.sohailsquad.animalhealthbridge.ui.components.AhbScreen
import com.sohailsquad.animalhealthbridge.ui.components.AhbSecondaryButton
import com.sohailsquad.animalhealthbridge.ui.components.AhbStepBar
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase-1 species. Dairy cattle only for now; the database column is free text
 * so later species need no migration. Declared once so the NFC, QR and manual
 * paths cannot disagree about what they create.
 */
private const val PHASE_ONE_SPECIES = "cattle"

/**
 * Screen 2 - identify the animal.
 *
 * Three identification paths, presented together in fallback order:
 *
 *   1. NFC scan of the ear tag - fastest, and the only path that cannot be
 *      mis-keyed;
 *   2. QR / barcode scan - works when the tag's chip has failed;
 *   3. manual typing - works when the camera and the radio both let you down.
 *
 * All three converge on [AnimalRepository.createOrGet], so scanning the same tag
 * twice always resolves to the same animal regardless of which path was used.
 * Every path logs under "AhbAnimal".
 *
 * @param onAnimalResolved called with the stable Room animal id as a string.
 * @param onOpenHistory "View History" shortcut into screen 7.
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@Composable
fun AnimalIdScreen(
    onAnimalResolved: (animalId: String) -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repository = context.app().animalRepository

    var tagNumber by rememberSaveable { mutableStateOf("") }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var showEnableNfcDialog by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    // True while a scan or database write is in flight, so buttons show a
    // spinner and a second tap cannot start a duplicate resolve.
    var isResolving by rememberSaveable { mutableStateOf(false) }
    // True once the owner has explicitly pressed "Start NFC scan". Reader mode
    // is only auto-re-armed after a pause/resume if this is set, so opening the
    // screen alone never starts listening.
    var nfcArmed by rememberSaveable { mutableStateOf(false) }

    val trimmedTag = tagNumber.trim()
    val canContinue = trimmedTag.isNotEmpty() && !isResolving

    // The NFC reader is bound to the Activity, so remember it against that key
    // instead of rebuilding it on every recomposition.
    val nfcReader = remember(activity) {
        activity?.let { host ->
            NfcReader(activity = host) { uid ->
                Log.i(TAG, "NFC tag read uid=$uid")
                resolveTag(
                    rawTag = uid,
                    source = "nfc",
                    repository = repository,
                    scope = scope,
                    setResolving = { isResolving = it },
                    onResolved = onAnimalResolved,
                    onError = { errorMessage = it },
                )
            }
        }
    }

    // rememberUpdatedState so the lifecycle observer below always sees the
    // current value of nfcArmed without having to be torn down and rebuilt
    // every time the owner presses Scan.
    val armedState = rememberUpdatedState(nfcArmed)

    // Arm on resume, disarm on pause. Leaving reader mode on would drain the
    // battery and keep the phone reacting to tags while the owner is elsewhere.
    DisposableEffect(lifecycleOwner, nfcReader) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    if (armedState.value) nfcReader?.start()
                Lifecycle.Event.ON_PAUSE ->
                    nfcReader?.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            nfcReader?.stop()
        }
    }

    // Result contract for "please turn NFC on". Android gives apps no way to
    // toggle the radio, so the correct behaviour is to deep-link the setting.
    val nfcSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        // The result code carries no meaning for Settings, so re-check the
        // adapter instead of trusting it.
        if (nfcReader?.isEnabled() == true) {
            Log.i(TAG, "NFC enabled by user; starting reader")
            nfcArmed = true
            nfcReader.start()
        } else {
            Log.w(TAG, "NFC still disabled after settings round trip")
            errorMessage = "NFC is still off. You can scan a QR code or type the tag number."
        }
    }

    AhbScreen(
        destination = AhbDestination.AnimalId,
        onBack = onBack,
        modifier = modifier,
        bottomBar = {
            AhbStepBar(
                current = AhbDestination.AnimalId,
                onStepClick = { /* Backward jumps land with the health record. */ },
            )
        },
    ) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(LocalAhbDimens.current.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap),
        ) {
            // --- Primary path: NFC ------------------------------------------
            AhbCard(
                title = "Scan ear tag",
                subtitle = "Hold the phone against the tag until it beeps.",
            ) {
                Icon(
                    imageVector = Icons.Filled.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(40.dp),
                )
                AhbSecondaryButton(
                    text = if (nfcArmed && !isResolving) "Listening for tag..." else "Start NFC scan",
                    onClick = {
                        val reader = nfcReader
                        when {
                            // No hardware: say so plainly and point at the
                            // fallbacks rather than leaving a dead button.
                            reader == null || !reader.isAvailable() -> {
                                Log.w(TAG, "NFC scan requested but device has no NFC")
                                errorMessage = "This phone has no NFC reader. " +
                                    "Scan a QR code or type the tag number instead."
                            }

                            // Hardware present, radio off.
                            !reader.isEnabled() -> {
                                Log.i(TAG, "NFC scan requested but NFC is disabled")
                                showEnableNfcDialog = true
                            }

                            else -> {
                                Log.i(TAG, "NFC scan armed")
                                nfcArmed = true
                                reader.start()
                            }
                        }
                    },
                    icon = Icons.Filled.Nfc,
                    modifier = Modifier.fillMaxWidth(),
                    loading = isResolving,
                    enabled = !isResolving,
                )
            }

            // --- Fallback 1: QR ---------------------------------------------
            AhbCard(
                title = "Scan QR code",
                subtitle = "Use this if the tag cannot be read.",
            ) {
                AhbSecondaryButton(
                    text = "Open QR scanner",
                    onClick = {
                        Log.i(TAG, "QR scanner opened")
                        // Stop listening for NFC while the camera owns the
                        // screen; otherwise a tag on the desk would resolve
                        // first and the scan would appear to do nothing.
                        nfcArmed = false
                        nfcReader?.stop()
                        showQrScanner = true
                    },
                    icon = Icons.Filled.QrCodeScanner,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isResolving,
                )
            }

            // --- Fallback 2: manual entry -----------------------------------
            AhbCard(
                title = "Type the tag number",
                subtitle = "Always available, even with no camera and no NFC.",
            ) {
                OutlinedTextField(
                    value = tagNumber,
                    onValueChange = { tagNumber = it },
                    label = { Text("Tag number") },
                    singleLine = true,
                    enabled = !isResolving,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        // Ear-tag numbers are alphanumeric and conventionally
                        // upper-case, e.g. "IN-MH-0042".
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            AhbPrimaryButtonWide(
                text = "Continue",
                onClick = {
                    resolveTag(
                        rawTag = trimmedTag,
                        source = "manual",
                        repository = repository,
                        scope = scope,
                        setResolving = { isResolving = it },
                        onResolved = onAnimalResolved,
                        onError = { errorMessage = it },
                    )
                },
                enabled = canContinue,
                loading = isResolving,
            )

            AhbSecondaryButton(
                text = "View history",
                onClick = onOpenHistory,
                icon = Icons.Filled.Timeline,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isResolving,
            )

            Text(
                text = "Animals you scan are saved on this phone and stay " +
                    "available offline.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalAhbColors.current.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // --- QR scanner, shown as a full-screen dialog --------------------------
    if (showQrScanner) {
        QrScannerDialog(
            onResult = { scanned ->
                // Close before resolving so the camera is released while the
                // database write happens.
                showQrScanner = false
                Log.i(TAG, "QR scan returned value=$scanned")
                resolveTag(
                    rawTag = scanned,
                    source = "qr",
                    repository = repository,
                    scope = scope,
                    setResolving = { isResolving = it },
                    onResolved = onAnimalResolved,
                    onError = { errorMessage = it },
                )
            },
            onCancel = {
                Log.i(TAG, "QR scanner cancelled by user")
                showQrScanner = false
            },
        )
    }

    // --- "Turn NFC on" explanation -----------------------------------------
    if (showEnableNfcDialog) {
        AlertDialog(
            onDismissRequest = { showEnableNfcDialog = false },
            title = { Text("NFC is turned off") },
            text = {
                Text(
                    "Open Settings and switch NFC on to scan the ear tag. " +
                        "You can also scan a QR code or type the tag number.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableNfcDialog = false
                        // Android has no public API to toggle NFC, so handing
                        // the user the setting is the correct behaviour.
                        nfcSettingsLauncher.launch(Intent(Settings.ACTION_NFC_SETTINGS))
                    },
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showEnableNfcDialog = false }) {
                    Text("Not now")
                }
            },
        )
    }

    // --- Error surface ------------------------------------------------------
    // Every failure path (no hardware, radio off, empty scan, storage error)
    // lands here rather than throwing, per "no feature may crash the app".
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Could not identify animal") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("OK") }
            },
        )
    }
}

/**
 * Resolves a raw tag to a stored animal, then hands the tag to the caller.
 *
 * Shared by all three identification paths so their behaviour cannot diverge.
 * The database call is `suspend`, so Room performs it off the main thread while
 * the coroutine keeps the state writes on the main dispatcher.
 *
 * @param rawTag the scanned or typed value; normalised inside the repository.
 * @param source short label for the log line: "nfc", "qr" or "manual".
 * @param onResolved called with the resolved tag on success.
 * @param onError called with a human-readable message on failure.
 */
private fun resolveTag(
    rawTag: String,
    source: String,
    repository: AnimalRepository,
    scope: CoroutineScope,
    setResolving: (Boolean) -> Unit,
    onResolved: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val tag = rawTag.trim()

    // An empty read happens when a tag is tapped too briefly. Treat it as a
    // normal, recoverable outcome rather than an exception.
    if (tag.isEmpty()) {
        Log.w(TAG, "Empty tag from source=$source; ignoring")
        onError("That tag could not be read. Try again, or type the tag number.")
        return
    }

    setResolving(true)
    scope.launch {
        try {
            val profile = repository.createOrGet(
                tag = tag,
                species = PHASE_ONE_SPECIES,
                age = "",
                owner = "",
            )
            Log.i(TAG, "Resolved source=$source tag=${profile.tagNumber} id=${profile.id}")
            onResolved(profile.id.toString())
        } catch (t: Throwable) {
            // Catch Throwable, not Exception: a corrupt database file can
            // surface as an Error, and the owner must still get a usable screen.
            Log.e(TAG, "Failed to resolve tag from source=$source", t)
            onError("Could not save this animal on the phone. Please try again.")
        } finally {
            setResolving(false)
        }
    }
}

/** Logcat tag; filter with: adb logcat -s AhbAnimal */
private const val TAG = "AhbAnimal"
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/build.gradle.kts
// ---------------------------------------------------------------------------
// :app module - Animal Health Bridge
// ---------------------------------------------------------------------------
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // The Compose compiler is a Kotlin plugin as of Kotlin 2.0, not a separate
    // androidx compiler artifact.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // KSP generates the Room DAO/Entity boilerplate.
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sohailsquad.animalhealthbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sohailsquad.animalhealthbridge"
        // 27, not 26: the GenieX AAR declares <uses-sdk minSdkVersion="27" /> in
        // its own manifest, so the whole app must be 27+ to link against it.
        minSdk = 27          // Android 8.1
        targetSdk = 35       // Android 15; the spec targets Android 13+ behaviour
        versionCode = 1
        versionName = "0.1.0-step1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            // Keep debug builds fast; no minification while iterating.
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
        jniLibs {
            useLegacyPackaging = true

            // The sherpa-onnx "static link" AAR is meant to bundle ONNX Runtime
            // internally, and does so correctly for arm64/armeabi-v7a/x86_64.
            // For 32-bit x86 it ALSO ships a libonnxruntime.so, which collides
            // with the one from onnxruntime-android and fails the merge.
            //
            // Our target hardware is arm64-v8a, so the 32-bit x86 copy is dead
            // weight: dropping it removes the collision and shrinks the APK.
            // x86_64 (the emulator ABI) is left untouched.
            excludes += "lib/x86/libonnxruntime.so"
        }
    }

    // Model payloads are memory-mapped at runtime, so they must not be
    // compressed inside the APK.
    androidResources {
        noCompress += setOf("task", "onnx", "bin", "ort", "tflite")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ---------------------------------------------------------------------------
// KSP / Room configuration.
//
// This block must sit at the top level of the module script (it is a KSP
// extension, not an `android {}` property). The exported schema directory is
// checked into version control so Step 2+ migrations can be diffed.
// ---------------------------------------------------------------------------
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    // --- AndroidX core -----------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    // --- Lifecycle ---------------------------------------------------------
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // --- Compose (versions come from the BOM) ------------------------------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Persistence -------------------------------------------------------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // --- Coroutines + serialization ---------------------------------------
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // --- On-device AI ------------------------------------------------------
    // Offline Hindi ASR (sherpa-onnx + a streaming zipformer transducer).
    // The "static" variant links ONNX Runtime internally, so it does not clash
    // with the onnxruntime-android artifact used for translation below.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    // MediaPipe LLM Inference (Gemma-3 1B) - wired up in Step 5.
    implementation(libs.mediapipe.tasks.genai)
    // ONNX Runtime for IndicTrans2 (Step 4) and IndicConformer ASR (Step 3).
    implementation(libs.onnxruntime.android)
    // Qualcomm GenieX: Qwen3 inference on the Snapdragon NPU (QAIRT/Hexagon).
    // Replaces the MediaPipe Gemma-3 path in LlmEngine.
    implementation(libs.geniex.android)

    // --- Camera / media ----------------------------------------------------
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation("androidx.camera:camera-video:1.4.0")
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.accompanist.permissions)
    // Bundled ML Kit barcode model: runs fully on-device, so the QR scanner
    // works in airplane mode.
    implementation(libs.mlkit.barcode.scanning)

    // --- Test --------------------------------------------------------------
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
```

// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/AndroidManifest.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!--
      =====================================================================
      PERMISSIONS
      =====================================================================
      Animal Health Bridge is offline-first: every AI step (ASR, translation,
      LLM, triage) runs on-device. INTERNET is deliberately ABSENT, so the OS
      itself prevents an accidental cloud call - a privacy guarantee and a
      judgeable artifact.

      The two removals below exist because transitive dependencies inject
      permissions we never asked for and never use:

        INTERNET            <- com.google.android.datatransport
                               (telemetry pulled in by MediaPipe tasks-genai)
        ACCESS_NETWORK_STATE <- androidx.media3-common (ExoPlayer)

      Both dependencies stay on the classpath (MediaPipe is the documented
      rollback path for the LLM), but their network permissions are stripped
      from the merged manifest so airplane mode remains a hard guarantee
      rather than a convention. tools:node="remove" wins over a library's
      own <uses-permission>, regardless of merge order.
    -->
    <uses-permission
        android:name="android.permission.INTERNET"
        tools:node="remove" />
    <uses-permission
        android:name="android.permission.ACCESS_NETWORK_STATE"
        tools:node="remove" />

    <!-- Voice intake: the owner speaks symptoms freely in their language. -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- Evidence capture: photos and 30s videos answering vet queries. -->
    <uses-permission android:name="android.permission.CAMERA" />

    <!-- Tag identification: NFC scan of the animal tag. -->
    <uses-permission android:name="android.permission.NFC" />

    <!--
      Media read access. Android 13 (API 33) replaced READ_EXTERNAL_STORAGE with
      per-type permissions; the legacy permission is capped at API 32 so older
      devices keep working without requesting a permission that no longer
      exists on modern devices.
    -->
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <!-- Dose reminders and withdrawal-period alerts. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!--
      Android 12+ throttles motion sensors to 200 Hz unless the app opts in.
      Gait-steadiness analysis benefits from the full rate, so we declare it.
    -->
    <uses-permission
        android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS"
        tools:ignore="ProtectedPermissions" />

    <!--
      =====================================================================
      HARDWARE FEATURES
      =====================================================================
      Everything is required="false". The app degrades gracefully: no NFC ->
      QR -> manual tag entry; no camera -> capture is skipped; no sensors ->
      quality checks are skipped. Declaring them optional keeps the app
      installable on low-end and emulated devices.
    -->
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
    <uses-feature android:name="android.hardware.microphone" android:required="false" />
    <uses-feature android:name="android.hardware.nfc" android:required="false" />
    <uses-feature android:name="android.hardware.sensor.accelerometer" android:required="false" />
    <uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />
    <uses-feature android:name="android.hardware.sensor.light" android:required="false" />
    <uses-feature android:name="android.hardware.sensor.proximity" android:required="false" />

    <!--
      Android 11+ package visibility: without this the offline SpeechRecognizer
      fallback cannot see the on-device recognition service.
    -->
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>

    <application
        android:name=".AnimalHealthBridgeApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AnimalHealthBridge"
        android:enableOnBackInvokedCallback="true"
        tools:targetApi="34">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="adjustResize"
            android:theme="@style/Theme.AnimalHealthBridge"
            tools:ignore="DiscouragedApi,LockedOrientationActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!--
              NFC tag dispatch. A tag tapped while the app is open is routed
              straight here, which is faster than the reader-mode polling path.
            -->
            <intent-filter>
                <action android:name="android.nfc.action.TECH_DISCOVERED" />
            </intent-filter>
            <meta-data
                android:name="android.nfc.action.TECH_DISCOVERED"
                android:resource="@xml/nfc_tech_filter" />
        </activity>
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/res/xml/file_paths.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path name="exports" path="exports/" />
</paths>
```

// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/main/assets/triage_rules.json
```json
{
  "rules": [
    {
      "id": "R1",
      "field": "red_flags",
      "operator": "is_not_empty",
      "action": "EMERGENCY_NOW"
    },
    {
      "id": "R2",
      "field": "severity_indicators",
      "operator": "contains_any",
      "values": [
        "cannot stand",
        "bleeding heavily",
        "difficulty breathing",
        "bloated",
        "unconscious",
        "seizure",
        "extreme pain"
      ],
      "action": "EMERGENCY_NOW"
    },
    {
      "id": "R3",
      "field": "duration",
      "operator": "contains_any",
      "values": [
        "week",
        "weeks",
        "many days",
        "long time",
        "month"
      ],
      "action": "SEE_VET_TODAY"
    },
    {
      "id": "R4",
      "field": "severity_indicators",
      "operator": "contains_any",
      "values": [
        "swelling",
        "fever",
        "not eating",
        "limping",
        "wound"
      ],
      "action": "SEE_VET_TODAY"
    },
    {
      "id": "R5",
      "field": "confidence",
      "operator": "equals",
      "values": [
        "low"
      ],
      "action": "SEE_VET_TODAY"
    },
    {
      "id": "R6",
      "field": "default",
      "action": "MONITOR"
    }
  ]
}
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/androidTest/java/com/sohailsquad/animalhealthbridge/OfflineWorkflowTest.kt
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
```

```properties
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/gradle.properties
# ---------------------------------------------------------------------------
# Gradle / JVM settings for 8 GB RAM development machine.
# Goal: complete builds without freezing the OS.
# Trade speed for stability — slower builds, no swap thrashing.
# ---------------------------------------------------------------------------

# Sequential builds — one task at a time to cap peak memory
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.configuration-cache=false
org.gradle.daemon=false

# Gradle JVM: 1 GB heap + 384 MB metaspace
# This leaves room for Android Studio + Windows + Kotlin compiler
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8

# Run Kotlin compiler inside the Gradle JVM instead of a separate process
# This is the biggest memory saving on small machines
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=true
kotlin.daemon.jvmargs=-Xmx768m

# AndroidX + non-transitive R classes
android.useAndroidX=true
android.nonTransitiveRClass=true
android.enableJetifier=false

# Disable parallel tooling sync (Gradle 9.4+)
org.gradle.tooling.parallel=false
android.injected.androidTest.leaveApksInstalledAfterRun=true
```

```kotlin
// FILE: D:/ProjectsForResume/Grand_Finale_Pitch/app/src/androidTest/java/com/sohailsquad/animalhealthbridge/ml/llm/RestoreLocalModelDeviceTest.kt
package com.sohailsquad.animalhealthbridge.ml.llm

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RestoreLocalModelDeviceTest {
    @Test
    fun restoreFromDeviceBackup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restoreLocalModel") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val source = "/data/local/tmp/geniex/qwen3_1_7b"
        fun shellText(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }

        val names = shellText("ls -1 $source").lines()
        assertTrue(names.containsAll(listOf("config.json", "tokenizer.json")))
        assertTrue(names.all { it.matches(Regex("[a-zA-Z0-9._-]+")) && it != "." && it != ".." })
        val external = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val destination = File(external, "geniex/qwen3_1_7b")
        assertTrue(destination.mkdirs() || destination.isDirectory)
        for (name in names) {
            val expectedSize = shellText("stat -c %s $source/$name").toLong()
            val target = File(destination, name)
            if (target.exists()) {
                assertEquals("Refusing to overwrite incomplete $name", expectedSize, target.length())
                assertTrue("Unreadable restored file $name", target.isFile && target.canRead())
                continue
            }
            assertTrue("Cannot create app-owned $name", target.createNewFile())
            shellText("cp $source/$name ${target.absolutePath}")
            val copied = target.length()
            assertEquals(name, expectedSize, copied)
            assertTrue("Unreadable restored file $name", target.isFile && target.canRead())
            Log.i("AhbModelTest", "Restored $name ($copied bytes) into app-owned storage")
        }
    }
}
```

All remaining steps generated. Build with: .\gradlew.bat :app:assembleDebug
