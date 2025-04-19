package com.example.spydarsense.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            targetValue = 1.3f,
            animationSpec = tween(100)
        )
        animatedScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }
    
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .clickable {
                isDarkTheme.value = !isDarkTheme.value
            },
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        shape = CircleShape
    ) {
        Box(
            modifier = Modifier
                .padding(8.dp)
                .size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                fontSize = 20.sp,
                modifier = Modifier.scale(animatedScale.value)
            )
        }
    }
}
