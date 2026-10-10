package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.Modifier
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
            AhbStatusBadge("Approved locally · ${displayDate(approved.approvedAt)}")
            AhbCard(title = "Animal #$animalTag", subtitle = "Case #$caseId") {
                ClinicalField("Veterinarian", approved.vetName)
                ClinicalField("Registration", approved.vetRegistrationNumber)
                ClinicalField("Complaint", case?.chiefComplaint.orEmpty())
                ClinicalField("Clinical notes", approved.vetNotes)
            }
            AhbCard {
                AhbSectionHeading("Treatment prescription", "As entered and approved by the reviewer", Icons.Default.Medication)
                ClinicalField("Medicine", approved.medicine)
                ClinicalField("Dosage", approved.dosage)
                ClinicalField("Frequency", approved.frequency)
                ClinicalField("Duration", approved.duration)
                ClinicalField("Route", approved.route)
                ClinicalField("Withdrawal period", "${approved.withdrawalPeriodDays} days")
                ClinicalField("Special instructions", approved.specialInstructions)
                ClinicalField("Follow-up", approved.followUpDate?.let(::displayDate) ?: "Not specified")
            }
            AhbCard {
                Text("Credentials are user-entered, not independently verified. This document is not digitally signed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AhbPrimaryButtonWide("Share PDF", enabled = !busy, loading = busy, icon = Icons.Default.Share, onClick = {
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
            })
            AhbSecondaryButton("Saved to Record · Open history", modifier = Modifier.fillMaxWidth(),
                onClick = { case?.let { onSaveToRecord(it.animalId) } })
        }
    }
}
