package com.example.spydarsense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

@Composable
fun DetectSpyCamScreen(essid: String, mac: String, pwr: Int, ch: Int) {
    val detector = remember { SpyCameraDetector.getInstance() }
    var isCollecting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val df = remember { DecimalFormat("#.##") }

    // Get all directories and the latest for display
    val csiDirs by detector.csiDirs.collectAsState(emptyList())
    val brDirs by detector.brDirs.collectAsState(emptyList())
    val latestCsiDir = csiDirs.lastOrNull() ?: "None"
    val latestBrDir = brDirs.lastOrNull() ?: "None"
    
    // Collect statistics
    val csiStats by detector.csiStats.collectAsState()
    val bitrateStats by detector.bitrateStats.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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

            Spacer(modifier = Modifier.weight(1f))

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