package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val connectorColor = MaterialTheme.colorScheme.outlineVariant
    OfflineContent(AhbDestination.HealthRecord, onBack, error, busy, actions = {
        profile?.let { animal ->
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2) {
            AhbSecondaryButton("Export Record", enabled = !busy, loading = busy,
                icon = Icons.Default.FileDownload, modifier = Modifier.weight(1f).widthIn(min = 140.dp), onClick = {
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
            })
                AhbPrimaryButton("Start New Case", onClick = { onStartNewCase(animalId) },
                    icon = Icons.Default.Add, modifier = Modifier.weight(1f).widthIn(min = 140.dp))
            }
        }
    }) {
        if (animalId == 0L) {
            AhbSectionHeading("Animal health records", "Select an animal stored on this phone", Icons.Default.Timeline)
            if (animals.isEmpty()) AhbEmptyState("No animals saved yet.", "Identify an animal to begin its local health record.")
            animals.forEach { animal ->
                AhbSelectableCard(onClick = { onOpenAnimal(animal.id) },
                    title = animal.name.ifBlank { animal.tagNumber }, subtitle = "#${animal.tagNumber} · ${animal.species}") {
                    Text(animal.ownerName.ifBlank { "Owner not recorded" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else profile?.let { animal ->
            AhbSectionHeading("Longitudinal health record", "Cases, treatments and vaccinations · Local only", Icons.Default.Timeline)
            AhbPatientHeader(animal, onEdit = {
                animalName = animal.name
                animalAge = animal.age
                ownerName = animal.ownerName
                editProfile = !editProfile
            })
            if (editProfile) {
                AhbCard(title = "Animal details") {
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
            }
            withdrawal.forEach { approved ->
                AhbCard(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f)) {
                    AhbSectionHeading("Active withdrawal period", icon = Icons.Default.Warning)
                    Text(approved.medicine, style = MaterialTheme.typography.titleMedium)
                    AhbStatusBadge("Until ${displayDate(approved.approvedAt + approved.withdrawalPeriodDays * 86_400_000L)}",
                        tint = MaterialTheme.colorScheme.error)
                    Text("Follow the veterinarian's milk/meat withdrawal instructions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Cases", "Treatments", "Vaccinations", "Withdrawals").forEach { option ->
                    val count = timeline.count { option == "All" || it.kind == option }
                    FilterChip(filter == option, onClick = { filter = option }, label = { Text("$option ($count)") },
                        shape = MaterialTheme.shapes.large, modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                }
            }
            val visible = timeline.filter { filter == "All" || it.kind == filter }.sortedByDescending { it.date }
            if (visible.isEmpty()) AhbEmptyState("No history yet", "Saved ${if (filter == "All") "records" else filter.lowercase()} will appear here.")
            visible.forEach { entry ->
                val date = Instant.ofEpochMilli(entry.date).atZone(ZoneId.systemDefault())
                Row(Modifier.fillMaxWidth().drawBehind {
                    drawLine(connectorColor, Offset(28.dp.toPx(), 0f), Offset(28.dp.toPx(), size.height), 2.dp.toPx())
                }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.width(56.dp), shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge)
                            Text(date.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())).uppercase(),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            Text(date.year.toString(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                AhbCard(Modifier.weight(1f)) {
                AhbStatusBadge(entry.kind)
                entry.triage?.let { AhbTriageBadge(com.sohailsquad.animalhealthbridge.triage.resolveTriageLevel(it)) }
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(entry.detail, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entry.caseId?.let { id ->
                    TextButton(onClick = { onOpenCase(animalId, id) }) { Text("Open case") }
                }
                }
                }
            }
            TextButton(onClick = { addVaccination = !addVaccination }) { Text("Record vaccination") }
            if (addVaccination) {
                AhbCard(title = "Vaccination entry", subtitle = "Record a vaccine already administered") {
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
            }
        }
    }
    if (profilePhoto) CaptureDialog(videoMode = false, onCaptured = { uri, mime ->
        val validated = EvidenceStore(context).validate(uri, mime)
        val updated = requireNotNull(profile).copy(photoUri = validated)
        database.animalDao().update(updated)
        profile = updated
    }, onDismiss = { profilePhoto = false })
}
