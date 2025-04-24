package com.example.spydarsense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.spydarsense.ui.theme.SpydarSenseTheme
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.spydarsense.backend.SpyCameraDetector
import com.example.spydarsense.components.ThemeToggle
import com.example.spydarsense.data.AP
import com.example.spydarsense.data.Station
import com.example.spydarsense.repository.WifiScanRepository
import com.example.spydarsense.ui.theme.rememberThemeState
import com.example.spydarsense.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.spydarsense.backend.ShellExecutor

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Handle the permission result if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // Request READ_EXTERNAL_STORAGE permission if not already granted.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Request WRITE_EXTERNAL_STORAGE permission as well for airodump output
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        enableEdgeToEdge()

        setContent {
            val isDarkTheme = rememberThemeState()

            SpydarSenseTheme(darkTheme = isDarkTheme.value) {
                val navController = rememberNavController()
                // Always start with the home screen
                val setup = false 

                // Track current screen for lifecycle management
                var currentScreen by remember { mutableStateOf("") }
                
                // Listen for navigation changes to update current screen
                LaunchedEffect(navController) {
                    navController.currentBackStackEntryFlow.collect { entry ->
                        val previousScreen = currentScreen
                        val route = entry.destination.route ?: ""
                        currentScreen = route
                        
                        // Stop scanning when leaving the home screen
                        if (previousScreen == "home" && currentScreen != "home") {
                            WifiScanRepository.getInstance().stopScan()
                        }
                        
                        // If navigating to a detect spy cam screen, force reset detector
                        // This ensures a clean slate for each detection session
                        if (route.startsWith("detectSpyCam") && previousScreen.startsWith("detectSpyCam")) {
                            // Give time for previous session to fully clean up
                            delay(300)
                            // Reset detector for new session
                            SpyCameraDetector.getInstance().forceReset()
                        }
                    }
                }

                NavHost(navController = navController, startDestination = "home") {

                    composable("home") {
                        HomeScreen(navController)
                    }
                    // Updated route to include sessionId for isolation
                    composable("detectSpyCam/{sessionId}/{stationMac}/{apMac}/{pwr}/{ch}") { backStackEntry ->
                        val sessionId = backStackEntry.arguments?.getString("sessionId") ?: "${System.currentTimeMillis()}"
                        val stationMac = backStackEntry.arguments?.getString("stationMac") ?: ""
                        val apMac = backStackEntry.arguments?.getString("apMac") ?: ""
                        val pwr = backStackEntry.arguments?.getString("pwr")?.toIntOrNull() ?: 0
                        val ch = backStackEntry.arguments?.getString("ch")?.toIntOrNull() ?: 0
                        DetectSpyCamScreen(sessionId = sessionId, stationMac = stationMac, apMac = apMac, pwr = pwr, ch = ch)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop scanning when the app is destroyed
        WifiScanRepository.getInstance().stopScan()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    // Get the repository instance
    val repository = WifiScanRepository.getInstance()

    // Monitor mode state
    var isMonitorModeEnabled by remember { mutableStateOf(false) }
    var isToggling by remember { mutableStateOf(false) }
    var monitorModeOutput by remember { mutableStateOf("") }
    val shellExecutor = remember { ShellExecutor() }

    // Collect the APs from the repository with proper initialization
    val allScannedAPs = repository.allAccessPoints.collectAsState(initial = emptyList()).value
    val stations = repository.stations.collectAsState(initial = emptyList()).value
    val isScanning = repository.isScanning.collectAsState(initial = false).value
    val isRefreshing = repository.isRefreshing.collectAsState(initial = false).value

    // State to track the number of items to display
    val displayedAPsCount = remember { mutableStateOf(5) }
    val displayedStationsCount = remember { mutableStateOf(5) } // Add this state for stations

    // Start and stop scanning based on monitor mode state
    DisposableEffect(isMonitorModeEnabled) {
        // Only start scanning when monitor mode is enabled
        if (isMonitorModeEnabled) {
            repository.startScan()
        } else {
            repository.stopScan()
        }
        
        // Stop scanning when the HomeScreen is no longer active
        onDispose {
            repository.stopScan()
        }
    }

    // Create a map of MAC to AP for quick lookup of AP details
    val apMap = remember(allScannedAPs) {
        allScannedAPs.associateBy { AP.normalizeMac(it.mac) }
    }

    // Filter stations to only include associated stations
    val filteredStations = remember(stations, apMap) {
        stations.filter { station -> 
            // Only include associated stations
            station.bssid != "(not associated)" && 
            apMap.containsKey(AP.normalizeMac(station.bssid))
        }
    }

    // Function to toggle monitor mode
    val toggleMonitorMode = {
        isToggling = true
        if (isMonitorModeEnabled) {
            // Command to disable monitor mode
            shellExecutor.execute("nexutil -Iwlan0 -m0 && nexutil -m") { output, exitCode ->
                if (exitCode == 0 && output.contains("monitor: 0")) {
                    isMonitorModeEnabled = false
                    monitorModeOutput = "Monitor mode disabled"
                } else if (output.isNotEmpty()) {
                    monitorModeOutput = "Error: $output"
                }
                isToggling = false
            }
        } else {
            // Command to enable monitor mode
            shellExecutor.execute("ifconfig wlan0 up && nexutil -Iwlan0 -m1 && nexutil -m") { output, exitCode ->
                if (exitCode == 0 && output.contains("monitor: 1")) {
                    isMonitorModeEnabled = true
                    monitorModeOutput = "Monitor mode enabled"
                } else if (output.isNotEmpty()) {
                    monitorModeOutput = "Error: $output"
                }
                isToggling = false
            }
        }
    }

    // State for tracking which dropdown is expanded
    val savedDevicesExpanded = remember { mutableStateOf(false) }
    val scannedDevicesExpanded = remember { mutableStateOf(true) } // Default open
    val accessPointsExpanded = remember { mutableStateOf(true) } // Default open

    Scaffold(
        topBar = {
            AppTopBar(title = "Spydar Sense")
        },
        floatingActionButton = {
            if (isMonitorModeEnabled) {
                FloatingActionButton(
                    onClick = {
                        // Manually trigger a refresh
                        if (!isRefreshing) {
                            repository.forceRefreshScan()
                        }
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan for APs"
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Replace Column with LazyColumn to make the entire content scrollable
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // Monitor Mode Toggle Card
                item {
                    AppCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Monitor Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Status text
                            Text(
                                text = if (isMonitorModeEnabled) "Active" else "Inactive",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isMonitorModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Toggle button
                            Button(
                                onClick = { toggleMonitorMode() },
                                enabled = !isToggling,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isMonitorModeEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isToggling) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (isMonitorModeEnabled) "Disable Monitor Mode" else "Enable Monitor Mode")
                            }
                            
                            // Show the output/error message if any
                            if (monitorModeOutput.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = monitorModeOutput,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (monitorModeOutput.startsWith("Error")) 
                                        MaterialTheme.colorScheme.error 
                                    else 
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Hint message when monitor mode is disabled
                            if (!isMonitorModeEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "⚠️ Enable Monitor Mode to scan for networks and detect spy cameras",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Summary card - show only when monitor mode is enabled or with reduced opacity
                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isMonitorModeEnabled) 1f else 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Devices count
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (isMonitorModeEnabled) "${filteredStations.size}" else "0",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Devices",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }

                            // Show scanning indicator only when monitor mode is enabled
                            if (isMonitorModeEnabled && isRefreshing) {
                                Text(
                                    "Scanning...",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                // Scanned Devices (Stations) Section
                item {
                    ExpandableSection(
                        title = "Scanned Devices",
                        expanded = scannedDevicesExpanded.value && isMonitorModeEnabled,
                        onToggle = { if (isMonitorModeEnabled) scannedDevicesExpanded.value = !scannedDevicesExpanded.value },
                        count = if (isMonitorModeEnabled) filteredStations.size else 0
                    ) {
                        if (!isMonitorModeEnabled) {
                            Text(
                                text = "Monitor mode is disabled. Enable it to scan for devices.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else if (filteredStations.isEmpty()) {
                            Text(
                                text = "No devices detected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Only show limited number of stations initially
                                filteredStations.take(displayedStationsCount.value).forEach { station ->
                                    val associatedAP = apMap[AP.normalizeMac(station.bssid)]
                                    StationCard(
                                        station = station,
                                        apEssid = associatedAP?.essid ?: "Unknown",
                                        apChannel = associatedAP?.ch ?: 0,
                                        navController = navController
                                    )
                                }
                                
                                // Add Show More/Less buttons for stations
                                if (filteredStations.size > displayedStationsCount.value) {
                                    TextButton(
                                        onClick = {
                                            displayedStationsCount.value = filteredStations.size  // Show all
                                        },
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    ) {
                                        Text("Show More")
                                    }
                                } else if (displayedStationsCount.value > 5 && filteredStations.size > 5) {
                                    TextButton(
                                        onClick = {
                                            displayedStationsCount.value = 5  // Reset to initial count
                                        },
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    ) {
                                        Text("Show Less")
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Access Points Section
                item {
                    ExpandableSection(
                        title = "Access Points",
                        expanded = accessPointsExpanded.value && isMonitorModeEnabled,
                        onToggle = { if (isMonitorModeEnabled) accessPointsExpanded.value = !accessPointsExpanded.value },
                        count = if (isMonitorModeEnabled) allScannedAPs.size else 0
                    ) {
                        if (!isMonitorModeEnabled) {
                            Text(
                                text = "Monitor mode is disabled. Enable it to scan for networks.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else if (allScannedAPs.isEmpty()) {
                            Text(
                                "No networks found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                allScannedAPs.take(displayedAPsCount.value).forEach { ap ->
                                    APCard(ap, navController)
                                }
                                
                                if (allScannedAPs.size > displayedAPsCount.value) {
                                    TextButton(
                                        onClick = {
                                            displayedAPsCount.value = allScannedAPs.size  // Show all
                                        },
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    ) {
                                        Text("Show More")
                                    }
                                } else if (displayedAPsCount.value > 5 && allScannedAPs.size > 5) {
                                    TextButton(
                                        onClick = {
                                            displayedAPsCount.value = 5  // Reset to initial count
                                        },
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    ) {
                                        Text("Show Less")
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Add some padding at the bottom for better UX
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    count: Int,
    extraContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header section with toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$title ($count)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .rotate(if (expanded) 90f else 0f),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                extraContent?.invoke()
            }
            
            // Content section
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun StationCard(station: Station, apEssid: String, apChannel: Int, navController: NavController) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Generate a unique session ID for this detection session
                val sessionId = "${System.currentTimeMillis()}-${station.mac}"
                // Navigate to spy cam detector with station info
                navController.navigate("detectSpyCam/$sessionId/${station.mac}/${station.bssid}/${station.power}/${apChannel}")
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device details
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                Text(
                    text = station.mac,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Text(
                    text = "AP: ${if (apEssid != "null") apEssid else station.bssid}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CH $apChannel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${station.power} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // Arrow indicator
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun APCard(ap: AP, navController: NavController) {
    // Remove clickable modifier and navigation logic since APs shouldn't support detection
    AppCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Network details - removed the circular indicator
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                ap.essid?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = ap.mac,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CH ${ap.ch}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${ap.pwr} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // Arrow indicator
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewHomeScreen() {
    SpydarSenseTheme(darkTheme = true) { // Use dark theme for preview
        HomeScreen(navController = rememberNavController())
    }
}