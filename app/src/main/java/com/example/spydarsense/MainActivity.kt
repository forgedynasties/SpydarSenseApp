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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.spydarsense.components.ThemeToggle
import com.example.spydarsense.data.AP
import com.example.spydarsense.ui.theme.rememberThemeState

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
        enableEdgeToEdge()

        setContent {
            val isDarkTheme = rememberThemeState()
            
            SpydarSenseTheme(darkTheme = isDarkTheme.value) {
                val navController = rememberNavController()
                val setup = true // Set to true to show the setup screen

                NavHost(navController = navController, startDestination = if (setup) "setup" else "home") {
                    composable("setup") {
                        SetupScreen(navController)
                    }
                    composable("home") {
                        HomeScreen(navController)
                    }
                    composable("detectSpyCam/{essid}/{mac}/{pwr}/{ch}") { backStackEntry ->
                        val essid = backStackEntry.arguments?.getString("essid") ?: ""
                        val mac = backStackEntry.arguments?.getString("mac") ?: ""
                        val pwr = backStackEntry.arguments?.getString("pwr")?.toIntOrNull() ?: 0
                        val ch = backStackEntry.arguments?.getString("ch")?.toIntOrNull() ?: 0
                        DetectSpyCamScreen(essid, mac, pwr, ch)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    // Sample list of scanned access points (APs)
    val allScannedAPs = listOf(
        AP("Access Point 1", "AC:6C:90:22:8F:37", "WPA2", "CCMP", "PSK", -50, 100, 50, 0, 1),
        AP("Access Point 2", "00:1A:2B:3C:4D:5F", "WPA2", "CCMP", "PSK", -60, 80, 40, 0, 11),
        AP("Access Point 3", "00:1A:2B:3C:4D:60", "WPA2", "CCMP", "PSK", -70, 60, 30, 0, 1),
        AP("Access Point 4", "00:1A:2B:3C:4D:61", "WPA2", "CCMP", "PSK", -65, 70, 35, 0, 2),
        AP("Access Point 5", "00:1A:2B:3C:4D:62", "WPA2", "CCMP", "PSK", -75, 50, 25, 0, 3),
        AP("Access Point 6", "00:1A:2B:3C:4D:63", "WPA2", "CCMP", "PSK", -80, 40, 20, 0, 4)
    )
    
    // State to track the number of APs to display
    val displayedAPsCount = remember { mutableStateOf(3) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    // Calculate if we're showing all APs
    val isShowingAll = displayedAPsCount.value >= allScannedAPs.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Spydar Sense",
                        fontWeight = FontWeight.Medium
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    ThemeToggle()
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* Scan for new APs */ },
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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Summary card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 0.dp
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${allScannedAPs.size}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Networks Found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        
                        OutlinedButton(
                            onClick = { /* Filter options */ },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Filter")
                        }
                    }
                }
                
                // AP list title
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Available Networks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    TextButton(
                        onClick = { 
                            if (isShowingAll) {
                                displayedAPsCount.value = 3  // Reset to initial count
                            } else {
                                displayedAPsCount.value = allScannedAPs.size  // Show all
                            }
                        }
                    ) {
                        Text(if (isShowingAll) "Show Less" else "Show All")
                    }
                }

                // AP list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(allScannedAPs.take(displayedAPsCount.value)) { ap ->
                        APCard(ap, navController)
                    }
                }
            }
        }
    }
}

@Composable
fun APCard(ap: AP, navController: NavController) {
    // Calculate signal strength indicator (0-4) but we won't display the circle
    val signalStrength = when {
        ap.pwr >= -55 -> 4
        ap.pwr >= -65 -> 3
        ap.pwr >= -75 -> 2
        ap.pwr >= -85 -> 1
        else -> 0
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                navController.navigate("detectSpyCam/${ap.essid}/${ap.mac}/${ap.pwr}/${ap.ch}")
            },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp
        )
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