package com.example.spydarsense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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
            SpydarSenseTheme(darkTheme = true) {
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
        AP("Access Point 1", "AC:6C:90:22:8F:37", "WPA2", "CCMP", "PSK", -50, 100, 50, 0, 6),
        AP("Access Point 2", "00:1A:2B:3C:4D:5F", "WPA2", "CCMP", "PSK", -60, 80, 40, 0, 11),
        AP("Access Point 3", "00:1A:2B:3C:4D:60", "WPA2", "CCMP", "PSK", -70, 60, 30, 0, 1),
        AP("Access Point 4", "00:1A:2B:3C:4D:61", "WPA2", "CCMP", "PSK", -65, 70, 35, 0, 2),
        AP("Access Point 5", "00:1A:2B:3C:4D:62", "WPA2", "CCMP", "PSK", -75, 50, 25, 0, 3),
        AP("Access Point 6", "00:1A:2B:3C:4D:63", "WPA2", "CCMP", "PSK", -80, 40, 20, 0, 4)
    )

    // State to track the number of APs to display
    val displayedAPsCount = remember { mutableStateOf(3) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spydar Sense") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary, // Use primary color from theme
                    titleContentColor = MaterialTheme.colorScheme.onPrimary // Use onPrimary color from theme
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Display the number of APs scanned
            Text(
                text = "Total APs Scanned: ${allScannedAPs.size}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Display the list of APs
            LazyColumn {
                items(allScannedAPs.take(displayedAPsCount.value)) { ap ->
                    APCard(ap, navController)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Add a "Load More" button if there are more APs to display
                if (displayedAPsCount.value < allScannedAPs.size) {
                    item {
                        Button(
                            onClick = {
                                // Increase the number of APs to display by 3
                                displayedAPsCount.value += 3
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 16.dp)
                        ) {
                            Text("Load More")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun APCard(ap: AP, navController: NavController) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
                navController.navigate("detectSpyCam/${ap.essid}/${ap.mac}/${ap.pwr}/${ap.ch}")
            }
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(text = "SSID: ${ap.essid}", style = MaterialTheme.typography.bodyLarge)
            Text(text = "MAC Address: ${ap.mac}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Channel: ${ap.ch}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Signal Strength: ${ap.pwr} dBm", style = MaterialTheme.typography.bodyMedium)
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