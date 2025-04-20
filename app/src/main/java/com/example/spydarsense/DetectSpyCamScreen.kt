package com.example.spydarsense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.spydarsense.backend.SpyCameraDetector
import com.example.spydarsense.ui.theme.SpydarSenseTheme
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import com.example.spydarsense.components.*
import kotlinx.coroutines.delay
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectSpyCamScreen(essid: String, mac: String, pwr: Int, ch: Int) {
    val detector = remember { SpyCameraDetector.getInstance() }
    var isCollecting by remember { mutableStateOf(false) }
    var showExtraStats by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val df = remember { DecimalFormat("#.##") }
    val scrollState = rememberScrollState()

    // Get all directories and the latest for display
    val csiDirs by detector.csiDirs.collectAsState(emptyList())
    val brDirs by detector.brDirs.collectAsState(emptyList())
    
    // Collect statistics
    val csiStats by detector.csiStats.collectAsState()
    val bitrateStats by detector.bitrateStats.collectAsState()
    
    // Collect PCA features and timeline data
    val csiPcaFeatures by detector.csiPcaFeatures.collectAsState()
    val bitratePcaFeatures by detector.bitratePcaFeatures.collectAsState()
    val csiTimeline by detector.csiTimeline.collectAsState()
    val bitrateTimeline by detector.bitrateTimeline.collectAsState()

    // Force UI refresh periodically
    val refreshCounter = remember { mutableStateOf(0) }
    
    // Periodically increment counter to force recomposition - only when collecting
    LaunchedEffect(isCollecting) {
        if (isCollecting) {
            while (true) {
                delay(1000) // Update every second
                refreshCounter.value += 1
                Log.d("DetectSpyCamScreen", "Forcing UI refresh")
            }
        }
    }
    
    // Add debug logging for state updates with forced refreshes
    LaunchedEffect(csiStats, bitrateStats, csiTimeline, bitrateTimeline, refreshCounter.value) {
        Log.d("DetectSpyCamScreen", "Stats updated - CSI samples: ${csiStats?.sampleCount ?: 0}, " +
                "Bitrate samples: ${bitrateStats?.sampleCount ?: 0}, " +
                "Timeline points: CSI=${csiTimeline.size}, Bitrate=${bitrateTimeline.size}, " +
                "Refresh cycle: ${refreshCounter.value}")
    }

    // For progress indicator
    val progressValue = remember(csiStats, bitrateStats) {
        val csiCount = csiStats?.sampleCount ?: 0
        val brCount = bitrateStats?.sampleCount ?: 0
        val total = csiCount + brCount
        val progress = (total.toFloat() / 100f).coerceIn(0f, 1f)
        if (isCollecting && total == 0) 0.1f else progress
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            topBar = {
                AppTopBar(title = "Detection")
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Target network info card
                AppElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // ...existing target network info content...
                        
                        // Start/Stop Button
                        val buttonScale = animateFloatAsState(
                            targetValue = if (isCollecting) 1.05f else 1f,
                            label = "buttonScale"
                        )
                        
                        Button(
                            onClick = {
                                if (isCollecting) {
                                    detector.stopDetection()
                                } else {
                                    coroutineScope.launch {
                                        detector.startDetection(mac, ch)
                                    }
                                }
                                isCollecting = !isCollecting
                            },
                            modifier = Modifier.scale(buttonScale.value),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCollecting) Color.Red else MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isCollecting) "Stop" else "Start")
                        }
                    }
                }
                
                // Stats summary card with progress indicator
                AppElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ...existing stats summary content...
                        
                        if (isCollecting) {
                            AppProgressIndicator(progress = progressValue)
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            AppOutlinedButton(
                                text = "Clear Data",
                                onClick = { 
                                    detector.clearBuffers()
                                    if (isCollecting) {
                                        isCollecting = false
                                    }
                                }
                            )
                        }
                    }
                }
                
                // Detailed stats section (collapsible)
                CollapsibleSection(visible = showExtraStats) {
                    // ...existing collapsible content...
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ...existing helper composables can stay as is or be moved to CommonUI.kt...

@Preview(showBackground = true, name = "DetectSpyCamScreen Preview")
@Composable
fun PreviewDetectSpyCamScreen() {
    SpydarSenseTheme(darkTheme = true) {
        DetectSpyCamScreen(
            essid = "MyWiFiNetwork",
            mac = "84:36:71:10:A4:64",
            pwr = -60,
            ch = 6
        )
    }
}