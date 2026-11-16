package com.example.callintelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Opt-in call review form. Caller identity is never inferred from phone state.
 * Caller must review any external message before sending.
 */
@Composable
fun CallReviewScreen(
    onSave: (CallReviewDraft) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var callerType by rememberSaveable { mutableStateOf("UNKNOWN") }
    var division by rememberSaveable { mutableStateOf("PERSONAL") }
    var contactReference by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf("") }
    var propertyCode by rememberSaveable { mutableStateOf("") }
    var leadReference by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var actionNotes by rememberSaveable { mutableStateOf("") }
    var followUp by rememberSaveable { mutableStateOf("NONE") }
    var whatsappDraft by rememberSaveable { mutableStateOf("") }
    var consentToSave by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Review call", style = MaterialTheme.typography.headlineSmall)
        Text("Add details manually. TLC-1 has not accessed the call or recording.", style = MaterialTheme.typography.bodySmall)
        Text("Caller relationship", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(
            choices = listOf("UNKNOWN", "OWNER", "BROKER", "CLIENT", "TEAM", "FAMILY", "OTHER"),
            selected = callerType,
            onSelect = { callerType = it }
        )
        Text("Division", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("PERSONAL", "ASCEND", "AUREA"), division) { division = it }
        OutlinedTextField(contactReference, { contactReference = it }, label = { Text("Contact reference (optional)") }, modifier = Modifier.fillMaxWidth())
        if (division != "PERSONAL") {
            OutlinedTextField(projectId, { projectId = it }, label = { Text("Project ID (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(propertyCode, { propertyCode = it }, label = { Text("Property code (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(leadReference, { leadReference = it }, label = { Text("CRM lead reference (optional)") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(summary, { summary = it }, label = { Text("Discussion summary") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(actionNotes, { actionNotes = it }, label = { Text("Next action / follow-up notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("Follow-up", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("NONE", "TODAY", "TOMORROW", "THREE_DAYS"), followUp) { followUp = it }
        if (division != "PERSONAL") {
            OutlinedTextField(whatsappDraft, { whatsappDraft = it }, label = { Text("WhatsApp draft — never auto-sent") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
        Row {
            Checkbox(checked = consentToSave, onCheckedChange = { consentToSave = it })
            Text("Save this call review on this device", modifier = Modifier.padding(top = 12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                enabled = consentToSave,
                onClick = {
                    onSave(CallReviewDraft(
                        callerType = callerType,
                        division = division,
                        contactReference = contactReference.trim().ifBlank { null },
                        projectId = projectId.trim().takeIf { division != "PERSONAL" && it.isNotEmpty() },
                        propertyCode = propertyCode.trim().takeIf { division != "PERSONAL" && it.isNotEmpty() },
                        leadReference = leadReference.trim().takeIf { division != "PERSONAL" && it.isNotEmpty() },
                        summary = summary.trim(),
                        actionNotes = actionNotes.trim(),
                        followUp = followUp,
                        whatsappDraft = whatsappDraft.trim().takeIf { division != "PERSONAL" && it.isNotEmpty() }
                    ))
                }
            ) { Text("Save review") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(
    choices: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.replace('_', ' '),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.replace('_', ' ')) },
                    onClick = { onSelect(choice); expanded = false }
                )
            }
        }
    }
}

data class CallReviewDraft(
    val callerType: String,
    val division: String,
    val contactReference: String?,
    val projectId: String?,
    val propertyCode: String?,
    val leadReference: String?,
    val summary: String,
    val actionNotes: String,
    val followUp: String,
    val whatsappDraft: String?
)

