package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sohailsquad.animalhealthbridge.ui.components.AhbCard
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButtonWide
import com.sohailsquad.animalhealthbridge.ui.components.AhbSecondaryButton
import com.sohailsquad.animalhealthbridge.ui.components.AhbStatusBadge
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BehaviourResultScreen(model: BehaviourViewModel, onStartVetCase: (Long, String) -> Unit,
    onDone: () -> Unit, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { model.analyze(model.description.value, model.duration.value, model.eating.value) }

    BehaviourContent("Behaviour Result", onBack) {
        when (val current = state) {
            BehaviourState.Idle, BehaviourState.Loading -> AhbCard(title = "Behaviour analysis") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Loading model and analysing behaviour...")
                }
            }
            is BehaviourState.Success -> {
                BehaviourUrgencyBadge(current.fields.urgency)
                if (current.usedFallback) AhbStatusBadge("Fallback used", tint = LocalAhbColors.current.warning)
                AhbCard(title = "Behaviour summary") { Text(current.fields.behaviour_summary) }
                AhbCard(title = "Possible causes", subtitle = "Possibilities only, not a diagnosis") {
                    if (current.fields.possible_causes.isEmpty()) Text(
                        "No model-derived causes available. Your observations are preserved.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    current.fields.possible_causes.forEachIndexed { index, cause -> Text("${index + 1}. $cause") }
                }
                AhbCard(title = "Advice") { Text(current.fields.advice) }
                Text("Inference: ${current.elapsedMs}ms · Confidence: ${current.fields.confidence}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is BehaviourState.Error -> AhbCard(title = "Analysis unavailable") {
                Text(current.msg, color = MaterialTheme.colorScheme.error)
                AhbSecondaryButton("Retry", onClick = {
                    scope.launch { model.analyze(model.description.value, model.duration.value, model.eating.value) }
                }, modifier = Modifier.fillMaxWidth())
            }
        }
        Text("First-pass analysis. Not a diagnosis. Consult a vet if persistent.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        AhbPrimaryButtonWide("Start Vet Case", loading = saving,
            enabled = state != BehaviourState.Loading && model.description.value.isNotBlank() &&
                model.duration.value.isNotBlank(), onClick = {
                scope.launch {
                    saving = true
                    error = null
                    try {
                        model.createVetCase().fold(
                            onSuccess = { (caseId, transcript) -> onStartVetCase(caseId, transcript) },
                            onFailure = { error = it.message ?: "Could not save the case. Please retry." },
                        )
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        Log.e("AhbBehaviour", "Vet case could not be opened", failure)
                        error = "Could not open the saved case. Please retry."
                    } finally { saving = false }
                }
            })
        AhbSecondaryButton("Done", onClick = onDone, enabled = !saving, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun BehaviourUrgencyBadge(urgency: String) {
    val colors = LocalAhbColors.current
    val (background, label, icon) = when (urgency) {
        "high" -> Triple(Color(0xFFB3261E), "High urgency — consult a vet urgently", Icons.Default.Warning)
        "medium" -> Triple(colors.warning, "Medium urgency", Icons.Default.Schedule)
        else -> Triple(colors.success, "Low urgency", Icons.Default.CheckCircle)
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = background, contentColor = if (urgency == "high") Color.White else Color(0xFF1C1B1F)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}
