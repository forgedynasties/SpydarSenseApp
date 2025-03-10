package com.example.spydarsense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.spydarsense.backend.CSIBitrateCollector
import com.example.spydarsense.ui.theme.SpydarSenseTheme
import kotlinx.coroutines.launch


@Composable
fun DetectSpyCamScreen(essid: String, mac: String, pwr: Int, ch: Int) {

    val csiCollector = remember { CSIBitrateCollector() }
    var isCollecting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val latestCsiDir by csiCollector.tcpdumpManager.csiDirs.collectAsState(emptyList())
    val latestBrDir by csiCollector.tcpdumpManager.brDirs.collectAsState(emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
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

            // Display latest CSI and bitrate directories
            Text(
                text = "Latest CSI Directory: ${latestCsiDir.lastOrNull() ?: "None"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Latest Bitrate Directory: ${latestBrDir.lastOrNull() ?: "None"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Stop/Start button
            Button(
                onClick = {
                    if (isCollecting) {
                        csiCollector.tcpdumpManager.stopCaptures()
                    } else {
                        coroutineScope.launch {
                            csiCollector.collectCSIBitrate(mac, ch)
                        }
                    }
                    isCollecting = !isCollecting
                }
            ) {
                Text(if (isCollecting) "Stop Collection" else "Start Collection")
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