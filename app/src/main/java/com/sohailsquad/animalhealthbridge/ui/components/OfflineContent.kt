package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun OfflineContent(destination: AhbDestination, onBack: () -> Unit, error: String? = null,
    busy: Boolean = false, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    AhbScreen(destination, onBack, safetyBanner = true,
        bottomBar = { Column { actions(); AhbStepBar(current = destination) } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(LocalAhbDimens.current.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) AhbCard(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            content()
        }
    }
}
@Composable
fun InputField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) },
        shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
}
@Composable
fun ClinicalField(label: String, value: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value.ifBlank { "Not stated" }, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
fun parseDate(value: String): Long? = value.trim().takeIf { it.isNotEmpty() }?.let {
    LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
fun displayDate(value: Long): String =
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
