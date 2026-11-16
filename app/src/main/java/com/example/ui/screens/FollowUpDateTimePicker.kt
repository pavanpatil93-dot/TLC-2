package com.example.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
internal fun FollowUpDateTimePicker(date: String, time: String, onDateChange: (String) -> Unit, onTimeChange: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose date", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
            val calendar = Calendar.getInstance()
            runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(date) }.getOrNull()?.let { calendar.time = it }
            DatePickerDialog(context, { _, year, month, day ->
                onDateChange(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }) {
            Icon(Icons.Default.DateRange, null)
            Spacer(Modifier.width(10.dp))
            val readable = runCatching { SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!) }.getOrNull()
            Text(readable ?: "Choose date")
        }
        Text("Choose time", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
            val now = Calendar.getInstance()
            val parts = time.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: now.get(Calendar.HOUR_OF_DAY)
            val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: now.get(Calendar.MINUTE)
            TimePickerDialog(context, { _, h, m ->
                if (date.isBlank()) onDateChange(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
                onTimeChange(String.format(Locale.US, "%02d:%02d", h, m))
            }, hour, minute, DateFormat.is24HourFormat(context)).show()
        }) {
            Icon(Icons.Default.Schedule, null)
            Spacer(Modifier.width(10.dp))
            Text(time.ifBlank { "Open clock" })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Tomorrow" to 1, "In 3 Days" to 3).forEach { (label, days) ->
                OutlinedButton(onClick = {
                    val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }
                    onDateChange(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time))
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
        TextButton(onClick = { onDateChange(""); onTimeChange("") }) { Text("Clear follow-up") }
    }
}

