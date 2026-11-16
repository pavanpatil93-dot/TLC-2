package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.FileStorageManager
import com.example.model.AttachmentType
import com.example.model.PropertyItem

@Composable
fun PropertyMediaLibrary(property: PropertyItem, context: Context) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Photos", "Video Walkthroughs", "Floor Plans", "Brochures & Documents")
    Column(Modifier.fillMaxWidth()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            tabs.forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> Text("${property.images.size} photo(s) available", style = MaterialTheme.typography.bodySmall)
            1 -> {
                val videos = property.attachments.filter { it.type == AttachmentType.VIDEO }
                val links = listOfNotNull(property.videoUrl).filter { it.isNotBlank() } + videos.map { it.localUriOrPath }
                if (links.isEmpty()) Text("No video walkthrough added", style = MaterialTheme.typography.bodySmall)
                links.distinct().forEach { link -> MediaOpenRow("▶ Video walkthrough", link, context) }
            }
            2 -> {
                val plans = listOfNotNull(property.floorPlanUrl).filter { it.isNotBlank() } + property.attachments.filter { it.type == AttachmentType.FLOOR_PLAN }.map { it.localUriOrPath }
                if (plans.isEmpty()) Text("No floor plan added", style = MaterialTheme.typography.bodySmall)
                plans.distinct().forEach { link -> MediaOpenRow("Floor plan", link, context) }
            }
            else -> {
                val docs = listOfNotNull(property.brochureUrl).filter { it.isNotBlank() } + property.attachments.filter { it.type == AttachmentType.PDF_BROCHURE || it.type == AttachmentType.DOCUMENT }.map { it.localUriOrPath }
                if (docs.isEmpty()) Text("No brochure or compliance document added", style = MaterialTheme.typography.bodySmall)
                docs.distinct().forEach { link -> MediaOpenRow("Brochure / compliance document", link, context) }
            }
        }
    }
}

@Composable
private fun MediaOpenRow(title: String, link: String, context: Context) {
    TextButton(onClick = {
        val uri = if (link.startsWith("http://") || link.startsWith("https://")) Uri.parse(link) else FileStorageManager.getShareableUri(context, link)
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$title  ·  Open", modifier = Modifier.fillMaxWidth()) }
}
