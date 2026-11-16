package com.example.ui.screens

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.model.*
import com.example.data.UserSettingsManager
import com.example.inventory.InventoryQuality
import java.time.LocalDate

@Composable
internal fun Build02DateField(label: String, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    Row {
        OutlinedButton(onClick = {
            val d = InventoryQuality.date(value) ?: LocalDate.now()
            DatePickerDialog(context, { _, y, m, day -> onChange(LocalDate.of(y, m+1, day).toString()) }, d.year, d.monthValue-1, d.dayOfMonth).show()
        }) { Text("$label: ${InventoryQuality.shown(value)}") }
        if(value.isNotBlank()) TextButton(onClick = { onChange("") }) { Text("Clear") }
    }
}

@Composable
internal fun AvailabilityPicker(status: AvailabilityStatus, onChange: (AvailabilityStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Status: ${status.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AvailabilityStatus.lifecycleStates.forEach { state ->
                DropdownMenuItem(text = { Text(state.label) }, onClick = { onChange(state); open = false })
            }
        }
    }
}

@Composable
internal fun InventoryQualityPanel(p: PropertyItem, all: List<PropertyItem>, showPrivate: Boolean,
    resolve: ((String, String, String) -> Boolean)?, confirmPrice: (() -> Boolean)?) {
    val context = LocalContext.current
    val settings = remember { UserSettingsManager(context) }
    val days by settings.verificationValidityDays.collectAsState()
    var candidate by remember(p.id) { mutableStateOf<PropertyItem?>(null) }
    val suspects = all.filter { InventoryQuality.suspected(p, it) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Data completeness: ${InventoryQuality.completeness(p)}%")
        Text(InventoryQuality.missing(p).joinToString(" · ") { "$it: Not Mentioned" })
        Text("Availability check: ${getVerificationAgeState(p, days).label} · $days-day freshness")
        Row { listOf(7,15,30).forEach { d ->
            TextButton(onClick = { settings.updateVerificationValidityDays(d) }) { Text("$d days") }
        } }
        Text("Verified by: ${InventoryQuality.shown(p.verifiedBy)}")
        Text("Owner confirmation: ${InventoryQuality.shown(p.ownerConfirmationStatus)}")
        Text("Price confirmed: ${InventoryQuality.shown(p.lastPriceConfirmationDate)}")
        Text("Lease expiry: ${InventoryQuality.shown(p.leaseExpiryDate)}")
        Text("Upcoming vacancy: ${InventoryQuality.shown(p.upcomingVacancyDate)}")
        if (showPrivate && confirmPrice != null) TextButton(onClick = {
            Toast.makeText(context, if(confirmPrice()) "Price confirmation saved" else "Save failed; please retry", Toast.LENGTH_SHORT).show()
        }) { Text("I confirmed the price today") }
        if(showPrivate) suspects.forEach { other ->
            TextButton(onClick = { candidate = other }) { Text("Review duplicate: ${other.propertyCode} · ${p.duplicateDecisions[other.id] ?: "Not reviewed"}") }
        }
    }
    candidate?.let { other ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text("Compare possible duplicate") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row { Text(p.propertyCode, Modifier.weight(1f)); Text(other.propertyCode, Modifier.weight(1f)) }
                    val left = listOf(p.propertyCode,p.projectName,p.unitNumber,p.location,p.floor,p.carpetAreaSqFt.toString(),p.builtUpAreaSqFt?.toString(),p.ownerContact,p.tab.code)
                    val right = listOf(other.propertyCode,other.projectName,other.unitNumber,other.location,other.floor,other.carpetAreaSqFt.toString(),other.builtUpAreaSqFt?.toString(),other.ownerContact,other.tab.code)
                    InventoryQuality.factors(p,other).entries.forEachIndexed { i, factor ->
                        Text("${factor.key} · ${if(factor.value) "Matches" else "Different / missing"}")
                        Row { Text(InventoryQuality.shown(left[i]), Modifier.weight(1f)); Text(InventoryQuality.shown(right[i]), Modifier.weight(1f)) }
                        HorizontalDivider()
                    }
                    Text("Linking retains both records and all contacts. No automatic merge or deletion.")
                }
            }, confirmButton = {
                Column { listOf("Keep Separate", "Link as Same Unit", "Review Later").forEach { decision ->
                    TextButton(enabled = resolve != null, onClick = {
                        if(resolve?.invoke(p.id,other.id,decision)==true) candidate=null
                        else Toast.makeText(context,"Could not save decision; retry",Toast.LENGTH_SHORT).show()
                    }) { Text(decision) }
                } }
            })
    }
}
