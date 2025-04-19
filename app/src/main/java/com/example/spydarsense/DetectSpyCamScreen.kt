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
import kotlinx.coroutines.delay
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
                .padding(16.dp)
                .verticalScroll(scrollState), // Make the screen scrollable
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top app bar with theme toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detection Results",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                ThemeToggle()
            }

            // Display AP information with improved styling
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Network Information",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(text = "SSID: $essid", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "MAC Address: $mac", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Signal Strength: $pwr dBm", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Channel: $ch", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // Sample Count Summary Card with animation
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sample Counts",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        
                        // Toggle button for showing/hiding extra stats
                        IconButton(
                            onClick = { showExtraStats = !showExtraStats }
                        ) {
                            Icon(
                                imageVector = if (showExtraStats) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (showExtraStats) "Hide details" else "Show details",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${csiStats?.sampleCount ?: 0}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "CSI Samples",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${bitrateStats?.sampleCount ?: 0}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Bitrate Samples",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            // Show detailed stats only if toggle is enabled
            if (showExtraStats) {
                Spacer(modifier = Modifier.height(8.dp))

                // Display CSI Statistics
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "CSI Statistics",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (csiStats == null) {
                            Text(text = "No CSI data available", color = Color.Gray)
                        } else {
                            Text(text = "Samples: ${csiStats?.sampleCount}")
                            Text(text = "Average Amplitude: ${df.format(csiStats?.avgAmplitude)}")
                            Text(text = "Min Amplitude: ${df.format(csiStats?.minAmplitude)}")
                            Text(text = "Max Amplitude: ${df.format(csiStats?.maxAmplitude)}")
                            
                            // Display individual CSI samples (in chunks)
                            if (csiStats?.sampleAmplitudes?.isNotEmpty() == true) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "Sample Amplitudes:", fontWeight = FontWeight.Medium)
                                
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    items(csiStats?.sampleAmplitudes.orEmpty()) { sample ->
                                        CSISampleCard(sample)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Display Bitrate Statistics
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Bitrate Statistics",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (bitrateStats == null) {
                            Text(text = "No bitrate data available", color = Color.Gray)
                        } else {
                            Text(text = "Samples: ${bitrateStats?.sampleCount}")
                            Text(text = "Average Bitrate: ${df.format(bitrateStats?.avgBitrate)} bytes")
                            Text(text = "Min Bitrate: ${bitrateStats?.minBitrate} bytes")
                            Text(text = "Max Bitrate: ${bitrateStats?.maxBitrate} bytes")
                            
                            // Display individual bitrate samples
                            if (bitrateStats?.bitrateValues?.isNotEmpty() == true) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "Bitrate Samples:", fontWeight = FontWeight.Medium)
                                
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    items(bitrateStats?.bitrateValues.orEmpty()) { bitrate ->
                                        BitrateSampleCard(bitrate)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Display latest CSI and bitrate directories
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Capture Directories",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Latest CSI Directory: ${latestCsiDir.substringAfterLast('/')}")
                        Text(text = "Latest Bitrate Directory: ${latestBrDir.substringAfterLast('/')}")
                        Text(text = "Total CSI Captures: ${csiDirs.size}")
                        Text(text = "Total Bitrate Captures: ${brDirs.size}")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Display Timeline
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Unified Timeline",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (csiTimeline.isEmpty() && bitrateTimeline.isEmpty()) {
                            Text(text = "No timeline data available yet", color = Color.Gray)
                        } else {
                            // Get the combined time points from both timelines
                            val allTimePoints = (csiTimeline.keys + bitrateTimeline.keys).toSortedSet()
                            
                            if (allTimePoints.isNotEmpty()) {
                                Text(
                                    text = "Timeline points: ${allTimePoints.size} (${allTimePoints.first().format(1)}s - ${allTimePoints.last().format(1)}s)",
                                    fontSize = 14.sp
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                // Draw the timeline visualization
                                TimelineVisualization(
                                    csiTimeline = csiTimeline,
                                    bitrateTimeline = bitrateTimeline,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp))
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // NEW: Display PCA Features
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "PCA Features",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // CSI PCA Features
                        Text(
                            text = "CSI PCA Features:",
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        )
                        if (csiPcaFeatures == null || csiPcaFeatures?.values.isNullOrEmpty()) {
                            Text(text = "No CSI PCA features available", color = Color.Gray)
                        } else {
                            Text(text = "Feature type: ${csiPcaFeatures?.featureType ?: "unknown"}")
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                items(csiPcaFeatures?.values.orEmpty()) { value ->
                                    PCAFeatureCard(value, "CSI")
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Bitrate PCA Features
                        Text(
                            text = "Bitrate PCA Features:",
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        )
                        if (bitratePcaFeatures == null || bitratePcaFeatures?.values.isNullOrEmpty()) {
                            Text(text = "No bitrate PCA features available", color = Color.Gray)
                        } else {
                            Text(text = "Feature type: ${bitratePcaFeatures?.featureType ?: "unknown"}")
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                items(bitratePcaFeatures?.values.orEmpty()) { value ->
                                    PCAFeatureCard(value, "Bitrate")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controls Row with improved styling
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                // Start/Stop Button with animation
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
                    Text(
                        if (isCollecting) "Stop Collection" else "Start Collection",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Clear Data Button
                Button(
                    onClick = {
                        detector.clearBuffers()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Clear Data",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp)) // Add extra space at the bottom for safe area
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