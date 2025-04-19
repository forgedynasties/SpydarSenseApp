package com.example.spydarsense.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spydarsense.ui.theme.rememberThemeState

@Composable
fun ThemeToggle(modifier: Modifier = Modifier) {
    val isDarkTheme = rememberThemeState()
    val emoji = if (isDarkTheme.value) "🌙" else "☀️"
    
    // Animation for the toggle
    val animatedScale = remember { Animatable(1f) }
    
    LaunchedEffect(isDarkTheme.value) {
        animatedScale.animateTo(
            targetValue = 1.2f,
            animationSpec = tween(100)
        )
        animatedScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(100)
        )
    }
    
    Box(
        modifier = modifier
            .clickable {
                isDarkTheme.value = !isDarkTheme.value
            }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 24.sp,
            modifier = Modifier.scale(animatedScale.value)
        )
    }
}
