package com.example.spydarsense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spydarsense.backend.SpyCameraDetector
import com.example.spydarsense.ui.theme.SpydarSenseTheme
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import kotlin.math.min
import androidx.compose.ui.text.rememberTextMeasurer
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.delay
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.draw.scale
import com.example.spydarsense.components.ThemeToggle
import com.example.spydarsense.ui.theme.rememberThemeState

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
    val latestCsiDir = csiDirs.lastOrNull() ?: "None"
    val latestBrDir = brDirs.lastOrNull() ?: "None"
    
    // Collect statistics
    val csiStats by detector.csiStats.collectAsState()
    val bitrateStats by detector.bitrateStats.collectAsState()
    
    // Collect PCA features
    val csiPcaFeatures by detector.csiPcaFeatures.collectAsState()
    val bitratePcaFeatures by detector.bitratePcaFeatures.collectAsState()
    
    // Collect timeline data
    val csiTimeline by detector.csiTimeline.collectAsState()
    val bitrateTimeline by detector.bitrateTimeline.collectAsState()

    // Force UI refresh periodically
    val refreshCounter = remember { mutableStateOf(0) }
    
    // Periodically increment counter to force recomposition
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000) // Update every second
            refreshCounter.value += 1
            Log.d("DetectSpyCamScreen", "Forcing UI refresh")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App bar with theme toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detection",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                ThemeToggle()
            }

            // Target network info card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.elevatedCardElevation(
                    defaultElevation = 2.dp
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = essid,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "MAC Address",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = mac,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Channel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "$ch",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Signal Strength",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "$pwr dBm",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        // Controls
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
            }
            
            // Stats summary
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.elevatedCardElevation(
                    defaultElevation = 2.dp
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Collection Summary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        IconButton(onClick = { showExtraStats = !showExtraStats }) {
                            Icon(
                                imageVector = if (showExtraStats) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (showExtraStats) "Show less" else "Show more",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Divider()
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        StatColumn(
                            title = "CSI Samples",
                            value = "${csiStats?.sampleCount ?: 0}",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        StatColumn(
                            title = "Bitrate Samples",
                            value = "${bitrateStats?.sampleCount ?: 0}",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    
                    if (isCollecting) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { detector.clearBuffers() },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Clear Data")
                        }
                    }
                }
            }
            
            // Detailed stats (collapsible)
            AnimatedVisibility(
                visible = showExtraStats,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // CSI Timeline Card
                    if (!csiTimeline.isEmpty()) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "CSI Timeline",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Timeline visualization
                                TimelineVisualization(
                                    csiTimeline = csiTimeline,
                                    bitrateTimeline = bitrateTimeline,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                    
                    // PCA Features Card (if available)
                    if (csiPcaFeatures != null || bitratePcaFeatures != null) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "PCA Features",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                // CSI Features
                                if (csiPcaFeatures?.values?.isNotEmpty() == true) {
                                    Text(
                                        text = "CSI Features",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(vertical = 8.dp)
                                    ) {
                                        items(csiPcaFeatures?.values.orEmpty()) { value ->
                                            PCAFeatureChip(value, "CSI")
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Bitrate Features
                                if (bitratePcaFeatures?.values?.isNotEmpty() == true) {
                                    Text(
                                        text = "Bitrate Features",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(vertical = 8.dp)
                                    ) {
                                        items(bitratePcaFeatures?.values.orEmpty()) { value ->
                                            PCAFeatureChip(value, "Bitrate")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Detailed sample stats (only show if there are samples)
                    if ((csiStats?.sampleCount ?: 0) > 0 || (bitrateStats?.sampleCount ?: 0) > 0) {
                        var selectedTab by remember { mutableStateOf(0) }
                        
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "Detailed Statistics",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                TabRow(
                                    selectedTabIndex = selectedTab,
                                    indicator = { tabPositions ->
                                        TabRowDefaults.Indicator(
                                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                            height = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                ) {
                                    Tab(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0 },
                                        text = { Text("CSI") }
                                    )
                                    Tab(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        text = { Text("Bitrate") }
                                    )
                                }
                                
                                when (selectedTab) {
                                    0 -> {
                                        // CSI Stats
                                        if (csiStats == null) {
                                            EmptyDataView("No CSI data available")
                                        } else {
                                            Column(
                                                modifier = Modifier.padding(vertical = 12.dp)
                                            ) {
                                                StatRow("Samples", "${csiStats?.sampleCount}")
                                                StatRow("Avg Amplitude", df.format(csiStats?.avgAmplitude))
                                                StatRow("Min Amplitude", df.format(csiStats?.minAmplitude))
                                                StatRow("Max Amplitude", df.format(csiStats?.maxAmplitude))
                                            }
                                        }
                                    }
                                    1 -> {
                                        // Bitrate Stats
                                        if (bitrateStats == null) {
                                            EmptyDataView("No bitrate data available")
                                        } else {
                                            Column(
                                                modifier = Modifier.padding(vertical = 12.dp)
                                            ) {
                                                StatRow("Samples", "${bitrateStats?.sampleCount}")
                                                StatRow("Avg Bitrate", "${df.format(bitrateStats?.avgBitrate)} bytes")
                                                StatRow("Min Bitrate", "${bitrateStats?.minBitrate} bytes")
                                                StatRow("Max Bitrate", "${bitrateStats?.maxBitrate} bytes")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun StatColumn(title: String, value: String, tint: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = tint
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
fun EmptyDataView(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun PCAFeatureChip(value: Float, type: String) {
    val df = remember { DecimalFormat("#.####") }
    
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = df.format(value),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Extension function to format doubles consistently
fun Double.format(digits: Int): String = "%.${digits}f".format(this)

@Composable
fun TimelineVisualization(
    csiTimeline: Map<Double, List<Float>>,
    bitrateTimeline: Map<Double, Int>,
    modifier: Modifier = Modifier
) {
    val df = remember { DecimalFormat("#.#") }
    val textMeasurer = rememberTextMeasurer()
    
    // Combine time points from both timelines
    val allTimePoints = remember(csiTimeline, bitrateTimeline) {
        (csiTimeline.keys + bitrateTimeline.keys).toSortedSet().toList()
    }
    
    if (allTimePoints.isEmpty()) {
        Box(modifier = modifier) {
            Text(
                text = "No data points to display",
                modifier = Modifier.align(Alignment.Center),
                color = Color.Gray
            )
        }
        return
    }
    
    // Find max values for scaling
    val maxCsiAmplitude = remember(csiTimeline) {
        csiTimeline.values.flatMap { it }.maxOfOrNull { it } ?: 0f
    }
    
    val maxBitrate = remember(bitrateTimeline) {
        bitrateTimeline.values.maxOfOrNull { it } ?: 0
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val graphHeight = height * 0.8f
        val startY = height * 0.1f
        
        // Time range
        val startTime = allTimePoints.first()
        val endTime = allTimePoints.last()
        val timeRange = endTime - startTime
        
        // Draw time axis
        drawLine(
            color = Color.Gray,
            start = Offset(0f, height - 20),
            end = Offset(width, height - 20),
            strokeWidth = 1f
        )
        
        // Draw time markers
        val numMarkers = min(10, allTimePoints.size)
        for (i in 0..numMarkers) {
            val x = (i * width) / numMarkers
            val time = startTime + (i * timeRange) / numMarkers
            
            // Draw tick mark
            drawLine(
                color = Color.Gray,
                start = Offset(x, height - 20),
                end = Offset(x, height - 15),
                strokeWidth = 1f
            )
            
            // Draw time label using the correct drawText overload
            drawText(
                textMeasurer = textMeasurer,
                text = df.format(time),
                topLeft = Offset(x - 10, height - 15),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 10.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            )
        }
        
        // Draw CSI data points (in blue)
        csiTimeline.forEach { (time, amplitudes) ->
            if (amplitudes.isNotEmpty()) {
                // Use average amplitude for visualization
                val avgAmplitude = amplitudes.average().toFloat()
                val normalizedAmplitude = if (maxCsiAmplitude > 0) avgAmplitude / maxCsiAmplitude else 0f
                
                val x = ((time - startTime) / timeRange * width).toFloat()
                val y = startY + (1 - normalizedAmplitude) * graphHeight
                
                // Draw a blue point
                drawCircle(
                    color = Color.Blue,
                    radius = 3f,
                    center = Offset(x, y)
                )
            }
        }
        
        // Draw Bitrate data points (in red)
        bitrateTimeline.forEach { (time, bitrate) -> 
            val normalizedBitrate = if (maxBitrate > 0) bitrate.toFloat() / maxBitrate else 0f
            
            val x = ((time - startTime) / timeRange * width).toFloat()
            val y = startY + (1 - normalizedBitrate) * graphHeight
            
            // Draw a red point
            drawCircle(
                color = Color.Red,
                radius = 3f,
                center = Offset(x, y)
            )
        }
        
        // Draw legend
        val legendY = 15f
        
        // CSI legend
        drawCircle(
            color = Color.Blue,
            radius = 3f,
            center = Offset(width * 0.25f, legendY)
        )
        
        // Draw legend text using the correct drawText overload
        drawText(
            textMeasurer = textMeasurer,
            text = "CSI",
            topLeft = Offset(width * 0.25f + 10, legendY - 5),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                color = Color.Blue
            )
        )
        
        // Bitrate legend
        drawCircle(
            color = Color.Red,
            radius = 3f,
            center = Offset(width * 0.75f, legendY)
        )
        
        // Draw legend text using the correct drawText overload
        drawText(
            textMeasurer = textMeasurer,
            text = "Bitrate",
            topLeft = Offset(width * 0.75f + 10, legendY - 5),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                color = Color.Red
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CSISampleCard(amplitudes: List<Float>) {
    val df = remember { DecimalFormat("#.##") }
    
    Card(
        modifier = Modifier
            .width(160.dp)
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "CSI Sample",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            
            amplitudes.forEachIndexed { index, amplitude ->
                Text(
                    text = "${index}: ${df.format(amplitude)}",
                    fontSize = 12.sp,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BitrateSampleCard(bitrate: Int) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bitrate",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$bitrate bytes",
                fontSize = 12.sp
            )
        }
    }
}

// Add a new composable for displaying PCA feature cards
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PCAFeatureCard(value: Float, type: String) {
    val df = remember { DecimalFormat("#.####") }
    
    Card(
        modifier = Modifier
            .width(120.dp)
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$type Mean",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = df.format(value),
                fontSize = 12.sp
            )
        }
    }
}

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