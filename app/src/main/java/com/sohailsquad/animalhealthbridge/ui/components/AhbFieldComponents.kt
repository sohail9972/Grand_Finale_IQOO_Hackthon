package com.sohailsquad.animalhealthbridge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile

@Composable
fun AhbStatusBadge(text: String, tint: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = tint.copy(alpha = 0.14f), contentColor = tint) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let { Icon(it, null, Modifier.size(16.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun AhbSectionHeading(title: String, subtitle: String? = null, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { Icon(it, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun AhbPatientHeader(animal: AnimalProfile, onEdit: (() -> Unit)? = null) {
    AhbCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (animal.photoUri != null) {
                EvidencePreview(animal.photoUri, "image/jpeg", Modifier.size(64.dp), "Animal photo")
            } else {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Icon(Icons.Default.Pets, null, Modifier.padding(14.dp).size(32.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(animal.name.ifBlank { animal.tagNumber }, style = MaterialTheme.typography.titleMedium)
                Text("#${animal.tagNumber}", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium)
                Text(listOf(animal.species, animal.age).filter { it.isNotBlank() }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(animal.ownerName.ifBlank { "Owner not recorded" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        onEdit?.let { TextButton(onClick = it) { Text("Edit animal details") } }
    }
}

@Composable
fun AhbEmptyState(title: String, detail: String) {
    AhbCard {
        Icon(Icons.Default.Pets, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
