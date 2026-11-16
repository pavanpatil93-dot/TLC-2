package com.example.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage

// Non-destructive correction for older compressed photos whose EXIF was lost.
fun rotatePropertyPhoto(context: Context, path: String) {
    val prefs = context.getSharedPreferences("property_photo_orientation", Context.MODE_PRIVATE)
    prefs.edit().putInt(path, (prefs.getInt(path, 0) + 90) % 360).apply()
}

@Composable
fun PropertyPhoto(model: String, contentDescription: String?, modifier: Modifier = Modifier,
                  contentScale: ContentScale = ContentScale.Fit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("property_photo_orientation", Context.MODE_PRIVATE) }
    var rotation by remember(model) { mutableIntStateOf(prefs.getInt(model, 0)) }
    DisposableEffect(prefs, model) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == model) rotation = prefs.getInt(model, 0)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val imageModifier = if (rotation % 180 == 90) Modifier.requiredSize(maxHeight, maxWidth)
            else Modifier.fillMaxSize()
        AsyncImage(model = model, contentDescription = contentDescription,
            contentScale = contentScale, modifier = imageModifier.graphicsLayer { rotationZ = rotation.toFloat() })
    }
}

