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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.spydarsense.components.*
import kotlinx.coroutines.delay
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectSpyCamScreen(sessionId: String, stationMac: String, apMac: String, pwr: Int, ch: Int) {
    val detector = remember { SpyCameraDetector.getInstance(stationMac) }
    var isCollecting by remember { mutableStateOf(false) }
    var showExtraStats by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val df = remember { DecimalFormat("#.##") }
    val scrollState = rememberScrollState()

    // Clear previous detection data when this screen is first shown
    LaunchedEffect(sessionId) {
        detector.clearBuffers()
        Log.d("DetectSpyCamScreen", "New detection session started: $sessionId")
    }

    // Handle screen lifecycle - stop collecting when screen is disposed
    DisposableEffect(key1 = true) {
        onDispose {
            // Stop any ongoing detection when navigating away
            if (isCollecting) {
                detector.stopDetection()
                isCollecting = false
            }
        }
    }

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
    
    // Periodically increment counter to force recomposition - only when collecting AND screen is active
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
                // Target device info card
                AppElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Target Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        // Device information
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Station MAC:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stationMac,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Associated AP:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = apMac,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            InfoChip(label = "Channel", value = "$ch")
                            InfoChip(label = "Signal", value = "$pwr dBm")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
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
                                        // Now using station MAC address for detection
                                        detector.startDetection(stationMac, ch)
                                    }
                                }
                                isCollecting = !isCollecting
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(buttonScale.value),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCollecting) Color.Red else MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isCollecting) "Stop Detection" else "Start Detection")
                        }
                    }
                }
                
                // Stats summary card
                AppElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Detection Statistics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        // CSI Data
                        Text(
                            text = "CSI Data",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatsInfoItem(
                                label = "Samples",
                                value = "${csiStats?.sampleCount ?: 0}"
                            )
                            StatsInfoItem(
                                label = "Avg Amplitude",
                                value = df.format(csiStats?.avgAmplitude ?: 0f)
                            )
                            StatsInfoItem(
                                label = "Max Amplitude",
                                value = df.format(csiStats?.maxAmplitude ?: 0f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Bitrate Data
                        Text(
                            text = "Bitrate Data",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatsInfoItem(
                                label = "Samples",
                                value = "${bitrateStats?.sampleCount ?: 0}"
                            )
                            StatsInfoItem(
                                label = "Avg Bitrate",
                                value = "${df.format(bitrateStats?.avgBitrate ?: 0f)} Mbps"
                            )
                            StatsInfoItem(
                                label = "Max Bitrate",
                                value = "${bitrateStats?.maxBitrate ?: 0} Mbps"
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Remove progress indicator and replace with analysis status
                        val hasData = (csiStats?.sampleCount ?: 0) > 0 || (bitrateStats?.sampleCount ?: 0) > 0
                        
                        if (isCollecting) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (hasData) "Collecting data..." else "Waiting for data...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (hasData) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Analysis complete",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        // Action button
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
                
                // Data visualization section
                AppElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Signal Patterns",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        // CSI Timeline Visualization
                        if (csiTimeline.isNotEmpty()) {
                            TimelineChart(
                                title = "CSI Amplitude over Time",
                                data = csiTimeline.toList().sortedBy { it.first }.take(50),
                                height = 150.dp
                            )
                        } else {
                            EmptyDataIndicator(text = "No CSI timeline data yet")
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Bitrate Timeline Visualization
                        if (bitrateTimeline.isNotEmpty()) {
                            BitrateTimelineChart(
                                title = "Bitrate over Time",
                                data = bitrateTimeline.toList().sortedBy { it.first }.take(50),
                                height = 150.dp
                            )
                        } else {
                            EmptyDataIndicator(text = "No bitrate timeline data yet")
                        }
                    }
                }
                
                // Detailed stats section (collapsible)
                TextButton(
                    onClick = { showExtraStats = !showExtraStats },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showExtraStats) "Hide Advanced Analysis" else "Show Advanced Analysis")
                }
                
                if (showExtraStats) {
                    AppElevatedCard {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Advanced Analysis",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            Divider(modifier = Modifier.padding(vertical = 4.dp))
                            
                            // PCA Features visualization if available
                            if (csiPcaFeatures != null && csiPcaFeatures?.values?.isNotEmpty() == true) {
                                Text(
                                    text = "CSI Pattern Analysis",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                // Add your PCA visualization here
                                Text(
                                    text = "Pattern strength: ${df.format(csiPcaFeatures?.values?.average() ?: 0f)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                EmptyDataIndicator(text = "No pattern analysis data yet")
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun InfoChip(label: String, value: String) {
    Card(
        modifier = Modifier.padding(end = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label: ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun StatsInfoItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun EmptyDataIndicator(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun TimelineChart(
    title: String,
    data: List<Pair<Double, List<Float>>>,
    height: Dp
) {
    // Extract the color from MaterialTheme before entering the Canvas scope
    val primaryColor = MaterialTheme.colorScheme.primary
    
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(vertical = 8.dp)
        ) {
            if (data.isEmpty()) {
                // Use direct drawing APIs instead of composable functions
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                
                drawContext.canvas.nativeCanvas.drawText(
                    "Insufficient data for visualization",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
                return@Canvas
            }
            
            val width = size.width
            val canvasHeight = size.height
            
            // Flatten the data for visualization
            val flatData = data.flatMap { (time, values) -> 
                values.map { value -> Pair(time, value) }
            }
            
            // Find min/max values
            val timeRange = if (flatData.size > 1) {
                flatData.maxOf { it.first } - flatData.minOf { it.first }
            } else {
                1.0 // Default to avoid division by zero
            }
            
            val maxValue = if (flatData.isNotEmpty()) {
                flatData.maxOf { it.second }
            } else {
                1.0f // Default to avoid division by zero
            }
            
            // Draw the timeline
            if (timeRange > 0 && maxValue > 0 && flatData.size > 1) {
                val minTime = flatData.minOf { it.first }
                
                flatData.forEachIndexed { index, (time, value) ->
                    if (index > 0) {
                        val prev = flatData[index - 1]
                        
                        val x1 = ((prev.first - minTime) / timeRange * width).toFloat()
                        val y1 = (canvasHeight - (prev.second / maxValue * canvasHeight * 0.8f) - canvasHeight * 0.1f).toFloat()
                        
                        val x2 = ((time - minTime) / timeRange * width).toFloat()
                        val y2 = (canvasHeight - (value / maxValue * canvasHeight * 0.8f) - canvasHeight * 0.1f).toFloat()
                        
                        drawLine(
                            color = primaryColor, // Use the captured color
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            } else {
                // Draw text using the native canvas API
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "Insufficient data for visualization",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
            }
        }
    }
}

@Composable
fun BitrateTimelineChart(
    title: String,
    data: List<Pair<Double, Int>>,
    height: Dp
) {
    // Extract the color from MaterialTheme before entering the Canvas scope
    val secondaryColor = MaterialTheme.colorScheme.secondary
    
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(vertical = 8.dp)
        ) {
            if (data.isEmpty()) {
                // Draw text using the native canvas API
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "Insufficient data for visualization",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
                return@Canvas
            }
            
            val width = size.width
            val canvasHeight = size.height
            
            // Find min/max values
            val timeRange = if (data.size > 1) {
                data.maxOf { it.first } - data.minOf { it.first }
            } else {
                1.0 // Default to avoid division by zero
            }
            
            val maxValue = if (data.isNotEmpty()) {
                data.maxOf { it.second }.toFloat()
            } else {
                1.0f // Default to avoid division by zero
            }
            
            // Draw the timeline
            if (timeRange > 0 && maxValue > 0 && data.size > 1) {
                val minTime = data.minOf { it.first }
                
                data.forEachIndexed { index, (time, value) ->
                    if (index > 0) {
                        val prev = data[index - 1]
                        
                        val x1 = ((prev.first - minTime) / timeRange * width).toFloat()
                        val y1 = (canvasHeight - (prev.second / maxValue * canvasHeight * 0.8f) - canvasHeight * 0.1f).toFloat()
                        
                        val x2 = ((time - minTime) / timeRange * width).toFloat()
                        val y2 = (canvasHeight - (value / maxValue * canvasHeight * 0.8f) - canvasHeight * 0.1f).toFloat()
                        
                        drawLine(
                            color = secondaryColor, // Use the captured color
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            } else {
                // Draw text using the native canvas API
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "Insufficient data for visualization",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "DetectSpyCamScreen Preview")
@Composable
fun PreviewDetectSpyCamScreen() {
    SpydarSenseTheme(darkTheme = true) {
        DetectSpyCamScreen(
            sessionId = "session_123",
            stationMac = "84:36:71:10:A4:64",
            apMac = "AA:BB:CC:DD:EE:FF",
            pwr = -60,
            ch = 6
        )
    }
}