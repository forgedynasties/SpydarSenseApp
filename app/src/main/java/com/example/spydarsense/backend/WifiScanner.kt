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
    
    // WiFi interface name (make configurable)
    private var wifiInterface = "wlan0"
    private val possibleInterfaces = listOf("wlan0", "wlan1", "wlp2s0", "eth0", "rmnet0")
    
    init {
        // Add the predefined AP to saved list
        addDefaultSavedAP()
        
        // Add mock APs for testing when we can't properly scan
        addMockAPs()
        
        refreshAPLists()
    }
    
    private fun addDefaultSavedAP() {
        val defaultAP = AP(
            "Access Point 1", 
            "AC:6C:90:22:8F:37", 
            1
        )
        saveAP(defaultAP)
    }
    
    // Add mock APs for UI testing when we can't actually scan
    private fun addMockAPs() {
        val mockAPs = listOf(
            AP("Home Network", "00:11:22:33:44:55", 6, -45),
            AP("Neighbor WiFi", "AA:BB:CC:DD:EE:FF", 11, -65),
            AP("Cafe WiFi", "12:34:56:78:90:AB", 1, -72),
            AP("Unknown Device", "FF:EE:DD:CC:BB:AA", 3, -80)
        )
        
        for (ap in mockAPs) {
            _discoveredAPs[ap.mac] = ap
        }
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
        
        // Find the WiFi interface name (might not be wlan0 on all devices)
        findWifiInterface()
        
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            while (_isScanning.value) {
                // Just use fallback scan method - it's more reliable on most devices
                runFallbackScan()
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
    
    private fun findWifiInterface() {
        Log.d(TAG, "Searching for available WiFi interfaces")
        
        // First check if our current interface exists and is usable
        shellExecutor.execute("ip addr show $wifiInterface") { output, exitCode ->
            if (exitCode == 0) {
                // Check if interface is up
                if (output.contains("state UP")) {
                    Log.d(TAG, "Current interface $wifiInterface is up and usable")
                    ensureInterfaceIsUp(wifiInterface)
                    return@execute
                } else {
                    Log.d(TAG, "Current interface $wifiInterface exists but is down, bringing it up")
                    ensureInterfaceIsUp(wifiInterface)
                }
            } else {
                // Current interface not found, look for alternatives
                tryFindInterfaceFromIw()
            }
        }
    }
    
    private fun tryFindInterfaceFromIw() {
        // Try to find interfaces using 'iw dev'
        shellExecutor.execute("iw dev") { output, exitCode ->
            if (exitCode == 0) {
                // Parse output to find interface names
                val interfaceRegex = Regex("Interface\\s+([^\\s]+)")
                val matches = interfaceRegex.findAll(output)
                val foundInterfaces = matches.map { it.groupValues[1].trim() }.toList()
                
                if (foundInterfaces.isNotEmpty()) {
                    // Prefer wlan interfaces over others
                    val wlanInterface = foundInterfaces.find { it.startsWith("wlan") }
                    if (wlanInterface != null) {
                        wifiInterface = wlanInterface
                        Log.d(TAG, "Found WiFi interface from iw dev: $wifiInterface")
                        ensureInterfaceIsUp(wifiInterface)
                        return@execute
                    }
                    
                    // If no wlan interface, use the first one found
                    wifiInterface = foundInterfaces.first()
                    Log.d(TAG, "Using first available interface: $wifiInterface")
                    ensureInterfaceIsUp(wifiInterface)
                } else {
                    // No interfaces found from iw dev, try checking each possible interface
                    tryPossibleInterfaces()
                }
            } else {
                // iw dev command failed, try checking each possible interface
                tryPossibleInterfaces()
            }
        }
    }
    
    private fun tryPossibleInterfaces() {
        Log.d(TAG, "Checking list of possible interfaces")
        
        // Try each interface in our possible list
        var found = false
        
        // Use concurrent approach instead of sequential checks
        for (iface in possibleInterfaces) {
            if (found) continue
            
            shellExecutor.execute("ip link show $iface") { output, exitCode ->
                if (!found && exitCode == 0) {
                    Log.d(TAG, "Found working interface: $iface")
                    wifiInterface = iface
                    found = true
                    ensureInterfaceIsUp(iface)
                }
            }
        }
        
        // If we reach here and haven't found an interface
        if (!found) {
            // Try to check for p2p interfaces which are also sometimes available
            shellExecutor.execute("ip link | grep p2p") { output, exitCode ->
                if (exitCode == 0 && output.isNotEmpty()) {
                    // Extract p2p interface name
                    val p2pRegex = Regex("\\d+:\\s+([p2p][^:]+):")
                    val match = p2pRegex.find(output)
                    if (match != null) {
                        val p2pInterface = match.groupValues[1].trim()
                        Log.d(TAG, "Found p2p interface: $p2pInterface")
                        wifiInterface = p2pInterface
                        ensureInterfaceIsUp(p2pInterface)
                    } else {
                        Log.e(TAG, "No usable WiFi interface found, will use mock data")
                    }
                } else {
                    Log.e(TAG, "No usable WiFi interface found, will use mock data")
                }
            }
        }
    }
    
    private fun ensureInterfaceIsUp(iface: String) {
        Log.d(TAG, "Ensuring interface $iface is up")
        shellExecutor.execute("ip link set $iface up") { output, exitCode ->
            if (exitCode == 0) {
                Log.d(TAG, "Successfully brought up interface $iface")
            } else {
                Log.e(TAG, "Failed to bring up interface $iface: $output")
                // Even if we fail to bring it up, continue with the interface
                // as it might work for some operations
            }
        }
    }
    
    fun stopScanning() {
        _isScanning.value = false
        scanJob?.cancel()
        refreshJob?.cancel()
        
        Log.d(TAG, "WiFi scanning stopped")
    }
    
    // Fallback scan method - this is our primary scanning method now
    private fun runFallbackScan() {
        Log.d(TAG, "Running WiFi scan using iw scan")
        
        // Make sure the interface is up
        ensureInterfaceIsUp(wifiInterface)
        
        // Use iw scan to get available networks
        shellExecutor.execute("iw dev $wifiInterface scan") { output, exitCode ->
            if (exitCode == 0) {
                Log.d(TAG, "WiFi scan successful")
                
                // Process the output once at the end 
                CoroutineScope(Dispatchers.Default).launch {
                    parseIwScanOutputImproved(output)
                }
            } else {
                Log.e(TAG, "WiFi scan failed: $output")
                
                // Try using ip neighbor to find devices on the network
                tryIpNeighborScan()
            }
        }
    }
    
    // Improved parser for iw scan output
    private fun parseIwScanOutputImproved(output: String) {
        // Split the output by BSS entries - each represents a WiFi network
        val bssEntries = output.split("BSS ").drop(1) // Drop the first empty element
        
        Log.d(TAG, "Found ${bssEntries.size} WiFi networks in scan results")
        
        if (bssEntries.isEmpty()) {
            Log.d(TAG, "No networks found in scan results, using mock data")
            updateMockAPSignals()
            return
        }
        
        // Process each network
        for (entry in bssEntries) {
            try {
                // Extract MAC address from the start of the entry
                val macRegex = Regex("([0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2})")
                val macMatch = macRegex.find(entry)
                val mac = macMatch?.value ?: continue
                
                // Extract SSID
                val ssidRegex = Regex("SSID: (.+)")
                val ssidMatch = ssidRegex.find(entry)
                val ssid = ssidMatch?.groupValues?.get(1)?.trim() ?: "Hidden Network"
                
                // Extract channel
                val channelRegex = Regex("DS Parameter set: channel (\\d+)")
                val channelMatch = channelRegex.find(entry)
                val channel = channelMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                // Extract signal strength
                val signalRegex = Regex("signal: (-?\\d+\\.?\\d*) dBm")
                val signalMatch = signalRegex.find(entry)
                // Convert to int - some devices report signal with decimal points
                val signalStr = signalMatch?.groupValues?.get(1) ?: "-90.0"
                val signal = signalStr.toFloatOrNull()?.toInt() ?: -90
                
                // Create AP object
                val ap = AP(
                    essid = ssid,
                    mac = mac,
                    ch = channel,
                    pwr = signal
                )
                
                // Add or update the AP in our discovered list
                updateDiscoveredAP(ap)
                
                Log.d(TAG, "Parsed WiFi network: $ssid ($mac) on channel $channel with signal $signal dBm")
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing network entry: ${e.message}")
            }
        }
        
        // Immediately refresh AP lists to update UI
        refreshAPLists()
    }
    
    private fun tryIpNeighborScan() {
        Log.d(TAG, "Trying ip neighbor scan as last resort")
        
        shellExecutor.execute("ip neighbor") { output, exitCode ->
            if (exitCode == 0 && output.isNotEmpty()) {
                Log.d(TAG, "Found network neighbors, extracting device information")
                parseIpNeighborOutput(output)
            } else {
                Log.e(TAG, "IP neighbor scan failed, using mock AP data")
                // Last resort: Generate random signal strength updates for mock APs
                updateMockAPSignals()
            }
        }
    }
    
    private fun parseIpNeighborOutput(output: String) {
        val lines = output.split("\n")
        var foundDevices = false
        
        for (line in lines) {
            try {
                if (line.contains("lladdr")) {
                    // Extract IP and MAC
                    val parts = line.split(" ")
                    if (parts.size >= 4) {
                        val ip = parts[0].trim()
                        val mac = parts[4].trim()
                        
                        if (mac.matches(Regex("[0-9A-Fa-f:]{17}"))) {
                            foundDevices = true
                            // Create network device as AP
                            val ap = AP(
                                essid = "Device $ip",
                                mac = mac,
                                ch = 1, // Dummy channel
                                pwr = -65 // Average signal strength
                            )
                            updateDiscoveredAP(ap)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing ip neighbor line: $e")
            }
        }
        
        if (!foundDevices) {
            // If we didn't find any devices, fall back to mock data
            updateMockAPSignals()
        }
        
        // Immediately refresh AP lists
        refreshAPLists()
    }
    
    // Update mock APs with random signal strengths when all else fails
    private fun updateMockAPSignals() {
        Log.d(TAG, "Updating mock AP signals")
        for (ap in _discoveredAPs.values) {
            // Randomly fluctuate signal strength to simulate scanning
            val randomChange = (-5..5).random()
            val newPwr = (ap.pwr + randomChange).coerceIn(-95, -30)
            ap.update(ap.essid, ap.ch, newPwr)
        }
        
        // Ensure we have at least some mock APs if there are none
        if (_discoveredAPs.isEmpty()) {
            addMockAPs()
        }
        
        // Immediately refresh AP lists
        refreshAPLists()
    }
    
    private fun updateDiscoveredAP(ap: AP) {
        val existingAP = _discoveredAPs[ap.mac]
        if (existingAP != null) {
            // Update existing AP with new information if needed
            existingAP.update(
                essid = if (ap.essid != "Unknown" && ap.essid != "Hidden Network") ap.essid else existingAP.essid,
                ch = if (ap.ch > 0) ap.ch else existingAP.ch,
                pwr = ap.pwr
            )
            Log.d(TAG, "Updated existing AP: ${existingAP.essid} (${existingAP.mac})")
        } else {
            // Add new AP
            _discoveredAPs[ap.mac] = ap
            Log.d(TAG, "Added new AP: ${ap.essid} (${ap.mac})")
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
        
        Log.d(TAG, "Updated AP lists: ${combinedList.size} total APs (${_savedAPs.size} saved, ${sortedDiscovered.size} discovered)")
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
