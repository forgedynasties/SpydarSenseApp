package com.example.spydarsense.backend

import android.util.Log
import com.example.spydarsense.data.AP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WifiScanner {
    private val shellExecutor = ShellExecutor()
    
    // Maps to store discovered APs by MAC address
    private val _discoveredAPs = ConcurrentHashMap<String, AP>()
    
    // Save list stores user's marked/saved APs
    private val _savedAPs = mutableListOf<AP>()
    
    // StateFlows to expose to UI
    private val _scannedAPsFlow = MutableStateFlow<List<AP>>(emptyList())
    val scannedAPs: StateFlow<List<AP>> = _scannedAPsFlow.asStateFlow()
    
    private val _savedAPsFlow = MutableStateFlow<List<AP>>(emptyList())
    val savedAPs: StateFlow<List<AP>> = _savedAPsFlow.asStateFlow()
    
    private val _allAPsFlow = MutableStateFlow<List<AP>>(emptyList())
    val allAPs: StateFlow<List<AP>> = _allAPsFlow.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning
    
    private var scanJob: Job? = null
    private var refreshJob: Job? = null
    
    // Default scan interval: 5 seconds
    private var scanInterval = 5000L
    
    init {
        // Add the predefined AP to saved list
        addDefaultSavedAP()
        refreshAPLists()
    }
    
    private fun addDefaultSavedAP() {
        val defaultAP = AP(
            "Access Point 1", 
            "AC:6C:90:22:8F:37", 
            "WPA2", 
            "CCMP", 
            "PSK", 
            -50, 
            100, 
            50, 
            0, 
            1
        )
        saveAP(defaultAP)
    }
    
    fun saveAP(ap: AP) {
        if (!_savedAPs.any { it.mac == ap.mac }) {
            _savedAPs.add(ap)
            refreshAPLists()
            Log.d(TAG, "AP saved: ${ap.essid} (${ap.mac})")
        }
    }
    
    fun removeSavedAP(mac: String) {
        _savedAPs.removeIf { it.mac == mac }
        refreshAPLists()
        Log.d(TAG, "Saved AP removed: $mac")
    }
    
    fun startScanning(intervalMs: Long = scanInterval) {
        if (_isScanning.value) {
            Log.d(TAG, "Scanner already running")
            return
        }
        
        scanInterval = intervalMs
        _isScanning.value = true
        
        // Put WiFi interface in monitor mode
        setupMonitorMode()
        
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            while (_isScanning.value) {
                runTcpdumpScan()
                delay(scanInterval)
            }
        }
        
        // Separate job to refresh AP lists every second
        refreshJob = CoroutineScope(Dispatchers.IO).launch {
            while (_isScanning.value) {
                refreshAPLists()
                delay(1000)
            }
        }
        
        Log.d(TAG, "WiFi scanning started with interval $intervalMs ms")
    }
    
    fun stopScanning() {
        _isScanning.value = false
        scanJob?.cancel()
        refreshJob?.cancel()
        
        // Put WiFi interface back in normal mode
        resetWifiInterface()
        
        Log.d(TAG, "WiFi scanning stopped")
    }
    
    private fun setupMonitorMode() {
        Log.d(TAG, "Setting up monitor mode")
        // This is a simplified approach - real implementation would require more setup
        // In a real implementation, you would use airmon-ng or similar tools
        shellExecutor.execute("ip link set wlan0 down") { _, _ -> }
        shellExecutor.execute("iw dev wlan0 set type monitor") { _, _ -> }
        shellExecutor.execute("ip link set wlan0 up") { _, _ -> }
    }
    
    private fun resetWifiInterface() {
        Log.d(TAG, "Resetting WiFi interface")
        shellExecutor.execute("ip link set wlan0 down") { _, _ -> }
        shellExecutor.execute("iw dev wlan0 set type managed") { _, _ -> }
        shellExecutor.execute("ip link set wlan0 up") { _, _ -> }
    }
    
    private fun runTcpdumpScan() {
        val command = "tcpdump -i wlan0 -e -s0 type mgt subtype beacon -vvv -c 100"
        Log.d(TAG, "Running tcpdump scan: $command")
        
        shellExecutor.execute(command) { output, exitCode ->
            if (exitCode == 0) {
                parseTcpdumpOutput(output)
            } else {
                Log.e(TAG, "tcpdump scan failed: $output")
            }
        }
    }
    
    private fun parseTcpdumpOutput(output: String) {
        // Basic parsing of tcpdump output to extract AP information
        // Real implementation would be more robust
        val lines = output.split("\n")
        for (line in lines) {
            try {
                // Look for lines with BSSID and SSID information
                if (line.contains("BSSID") && line.contains("SSID")) {
                    // Extract MAC address (BSSID)
                    val macRegex = Regex("BSSID:([0-9A-Fa-f:]{17})")
                    val macMatch = macRegex.find(line)
                    val mac = macMatch?.groupValues?.get(1)?.trim() ?: continue
                    
                    // Extract SSID
                    val ssidRegex = Regex("SSID\\s+(.+?)\\s*(?:\\(|$)")
                    val ssidMatch = ssidRegex.find(line)
                    val ssid = ssidMatch?.groupValues?.get(1)?.trim() ?: "Unknown"
                    
                    // Extract channel if present
                    val chRegex = Regex("CH(?::\\s*|=)(\\d+)")
                    val chMatch = chRegex.find(line)
                    val ch = chMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    
                    // Extract signal strength if present (very approximate)
                    val pwrRegex = Regex("(-\\d+)\\s*dBm")
                    val pwrMatch = pwrRegex.find(line)
                    val pwr = pwrMatch?.groupValues?.get(1)?.toIntOrNull() ?: -70
                    
                    // Create or update AP in our map
                    val ap = AP(
                        essid = ssid,
                        mac = mac,
                        auth = "Unknown", // We don't parse this from beacon
                        cipher = "Unknown", // We don't parse this from beacon
                        psk = "Unknown", // We don't parse this from beacon
                        pwr = pwr,
                        beacons = 1,
                        data = 0,
                        ch = ch,
                        mb = 0 // We don't parse this from beacon
                    )
                    
                    updateDiscoveredAP(ap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing tcpdump line: $e")
            }
        }
    }
    
    private fun updateDiscoveredAP(ap: AP) {
        val existingAP = _discoveredAPs[ap.mac]
        if (existingAP != null) {
            // Update existing AP with new information if needed
            val updatedAP = existingAP.copy(
                essid = if (ap.essid != "Unknown") ap.essid else existingAP.essid,
                pwr = ap.pwr, // Update signal strength
                beacons = existingAP.beacons + 1, // Increment beacon count
                ch = if (ap.ch > 0) ap.ch else existingAP.ch
            )
            _discoveredAPs[ap.mac] = updatedAP
        } else {
            // Add new AP
            _discoveredAPs[ap.mac] = ap
        }
    }
    
    private fun refreshAPLists() {
        // Sort discovered APs by signal strength
        val sortedDiscovered = _discoveredAPs.values.toList()
            .sortedByDescending { it.pwr }
        
        // Filter out stale APs (older than 60 seconds) in a real implementation
        
        _scannedAPsFlow.value = sortedDiscovered
        _savedAPsFlow.value = _savedAPs.toList()
        
        // Combine both lists for all APs, putting saved ones first
        val combinedList = mutableListOf<AP>()
        combinedList.addAll(_savedAPs)
        
        // Add discovered APs that aren't already saved
        for (ap in sortedDiscovered) {
            if (!_savedAPs.any { it.mac == ap.mac }) {
                combinedList.add(ap)
            }
        }
        
        _allAPsFlow.value = combinedList
    }
    
    companion object {
        private const val TAG = "WifiScanner"
        
        @Volatile
        private var instance: WifiScanner? = null
        
        fun getInstance(): WifiScanner {
            return instance ?: synchronized(this) {
                instance ?: WifiScanner().also { instance = it }
            }
        }
    }
}
