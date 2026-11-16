package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.model.Brand
import com.example.ui.theme.BorderGrey
import com.example.ui.theme.TlcThemeController

@Composable
fun BrandLogoView(
    brand: Brand?,
    customLogoUri: String?,
    size: Dp = 38.dp,
    modifier: Modifier = Modifier
) {
    val isDark = TlcThemeController.isDark
    val containerColor = if (isDark) Color(0xFF1E2328) else Color(0xFFFFFFFF)
    val logoTint = if (isDark) Color.White else Color(0xFF111827)
    val cornerRadius = (size.value * 0.25f).coerceIn(8f, 16f).dp
    val logoDescription = when (brand) {
        Brand.COMMERCIAL -> "The Leasing Company Commercial Logo"
        Brand.RESIDENTIAL -> "The Leasing Company Residential Logo"
        null -> "The Leasing Company Logo"
    }

    if (!customLogoUri.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(customLogoUri)
                .crossfade(true)
                .build(),
            contentDescription = logoDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .border(1.dp, BorderGrey, RoundedCornerShape(cornerRadius))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(containerColor)
                .border(1.dp, BorderGrey, RoundedCornerShape(cornerRadius))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_tlc_logo),
                contentDescription = "The Leasing Company Logo",
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(logoTint),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
