package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.model.PropertyItem
import com.example.social.SocialLinks
import com.example.social.SocialPlatform

@Composable
fun SocialMediaHubButton(initialCommercial: Boolean = true) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("Company social pages • Instagram / Facebook / LinkedIn") }
    if (show) SocialMediaHub(initialCommercial) { show = false }
}

@Composable
private fun SocialMediaHub(initialCommercial: Boolean, onDismiss: () -> Unit) {
    var commercial by remember { mutableStateOf(initialCommercial) }
    var edit by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = if (commercial) "brand:ascend" else "brand:aurea"
    val context = LocalContext.current
    val links = remember(scope, revision) { SocialLinks.load(context, scope) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Company social pages") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = commercial, onClick = { commercial = true }, label = { Text("Ascend Commercial") })
                FilterChip(selected = !commercial, onClick = { commercial = false }, label = { Text("Aurea Living") })
            }
            SocialPlatform.values().forEach { platform ->
                val url = links[platform].orEmpty()
                OutlinedButton(enabled = url.isNotBlank() && SocialLinks.validUrl(platform, url), onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { error = "No app or browser could open this page." }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (url.isBlank()) "${platform.label} • Add page URL" else "Open ${platform.label}") }
            }
            Text("Pages open in the installed social app or browser. These shortcuts do not connect your inbox or analytics.", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { edit = true }) { Text("Set page links") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (edit) SocialLinkEditor(if (commercial) "Ascend Commercial Solutions" else "Aurea Living", scope,
        onDismiss = { edit = false }, onSaved = { edit = false; revision++ })
}

@Composable
fun PropertySocialLinks(property: PropertyItem) {
    val context = LocalContext.current
    val scope = "property:${property.id}"
    var revision by remember(property.id) { mutableIntStateOf(0) }
    var edit by remember(property.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val links = remember(scope, revision) { SocialLinks.load(context, scope) }
    val published = links.filter { it.value.isNotBlank() && SocialLinks.validUrl(it.key, it.value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Published property videos & posts", style = MaterialTheme.typography.titleMedium)
        if (published.isEmpty()) Text("No published links added for this property.", style = MaterialTheme.typography.bodySmall)
        published.forEach { (platform, url) ->
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { error = "Unable to open the post. Check the link and internet connection." }
            }, modifier = Modifier.fillMaxWidth()) { Text("Watch / open on ${platform.label}") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = property.id.isNotBlank(), onClick = { edit = true }) { Text("Add / edit published links") }
            if (published.isNotEmpty()) TextButton(onClick = {
                val text = buildString {
                    appendLine("${property.propertyCode} • ${property.projectName}")
                    appendLine(property.location)
                    published.forEach { (platform, url) -> appendLine("${platform.label}: $url") }
                }
                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Share property videos")) }.onFailure { error = "No sharing app available." }
            }) { Text("Share video links") }
        }
        SocialMediaHubButton(initialCommercial = property.tab.isCommercial)
        Text("Only link posts that belong to this property. Links are saved on this device; post availability is checked when opened.", style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
    if (edit) SocialLinkEditor("${property.propertyCode} • Published links", scope,
        onDismiss = { edit = false }, onSaved = { edit = false; revision++ })
}

@Composable
private fun SocialLinkEditor(title: String, scope: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var links by remember(scope) { mutableStateOf(SocialLinks.load(context, scope)) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste the exact public page, reel, video or post URL. Leave blank to remove a saved link.", style = MaterialTheme.typography.bodySmall)
            SocialPlatform.values().forEach { platform ->
                OutlinedTextField(value = links[platform].orEmpty(), onValueChange = { value -> links = links + (platform to value) },
                    label = { Text("${platform.label} URL") }, isError = !SocialLinks.validUrl(platform, links[platform].orEmpty()), modifier = Modifier.fillMaxWidth())
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(onClick = {
            runCatching { check(SocialLinks.save(context, scope, links)) { "Could not save links. Please retry." } }
                .onSuccess { onSaved() }.onFailure { error = it.message ?: "Unable to save links." }
        }) { Text("Save links") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

