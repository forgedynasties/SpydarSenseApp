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
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Display AP information
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "SSID: $essid", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "MAC Address: $mac", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Signal Strength: $pwr dBm", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Channel: $ch", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // Sample Count Summary Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
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

            // Controls Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Start/Stop Button
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCollecting) Color.Red else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isCollecting) "Stop Collection" else "Start Collection")
                }

                // Clear Data Button
                Button(
                    onClick = {
                        detector.clearBuffers()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Clear Data")
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp)) // Add extra space at the bottom for safe area
        }
    }
}

@Composable
fun CSISampleCard(amplitudes: List<Float>) {
    val df = remember { DecimalFormat("#.##") }
    
    Card(
        modifier = Modifier
            .width(160.dp)
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp)
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

@Composable
fun BitrateSampleCard(bitrate: Int) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp)
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
@Composable
fun PCAFeatureCard(value: Float, type: String) {
    val df = remember { DecimalFormat("#.####") }
    
    Card(
        modifier = Modifier
            .width(120.dp)
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp)
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