package com.wizlabs.pdfmaster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rename several scans with one pattern. [docs] are numbered in the order they are listed;
 * [others] are the names that stay as they are (the new names keep clear of them).
 */
@Composable
fun BatchRenameDialog(docs: List<Doc>, others: Set<String>, onDismiss: () -> Unit, onApply: (Map<String, String>) -> Unit) {
    var pattern by remember { mutableStateOf("${RenamePattern.NAME}_${RenamePattern.N}") }
    val dates = remember(docs) { SimpleDateFormat("yyyy-MM-dd", Locale.US).let { f -> docs.map { f.format(Date(it.created)) } } }
    val names = remember(pattern, docs) { RenamePattern.plan(pattern, docs.map { it.name }, dates, others) }
    val ok = pattern.isNotBlank() && docs.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${docs.size} scan${if (docs.size == 1) "" else "s"}") },
        text = {
            Column {
                OutlinedTextField(pattern, { pattern = it.take(80) }, label = { Text("Name pattern") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizChip("Old name", false, { pattern += RenamePattern.NAME })
                    WizChip("Date", false, { pattern += RenamePattern.DATE })
                    WizChip("Number", false, { pattern += RenamePattern.N })
                }
                Spacer(Modifier.height(12.dp))
                Text("PREVIEW", style = MaterialTheme.typography.labelMedium, color = WizMuted)
                names.take(4).forEachIndexed { i, n ->
                    Text("${docs[i].name}  →  $n", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (names.size > 4) Text("… and ${names.size - 4} more", style = MaterialTheme.typography.bodySmall, color = WizMuted)
                Spacer(Modifier.height(6.dp))
                Text("Names that are already taken get (2), (3) … added.", style = MaterialTheme.typography.bodySmall, color = WizMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(docs.indices.associate { docs[it].id to names[it] }) }, enabled = ok) {
                Text("Rename", color = if (ok) WizYellow else WizMuted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = WizMuted) } },
        containerColor = WizPanel
    )
}
