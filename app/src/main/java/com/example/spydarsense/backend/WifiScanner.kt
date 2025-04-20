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
    
    // Add filtered APs flow
    private val _filteredAPsFlow = MutableStateFlow<List<AP>>(emptyList())
    val filteredAPs: StateFlow<List<AP>> = _filteredAPsFlow.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning
    
    // Add scanning status message
    private val _statusMessage = MutableStateFlow("Ready to scan")
    val statusMessage: StateFlow<String> = _statusMessage
    
    private var scanJob: Job? = null
    private var refreshJob: Job? = null
    
    // Default scan interval: 5 seconds
    private var scanInterval = 5000L
    
    // WiFi interface name (make configurable)
    private var wifiInterface = "wlan0"
    private val possibleInterfaces = listOf("wlan0", "wlan1", "wlp2s0", "eth0", "rmnet0")
    
    // Filter options
    private var minSignalStrength = -90
    private var channelFilter: Int? = null
    private var onlyShowSavedNetworks = false
    
    init {
        // Add the predefined AP to saved list
        addDefaultSavedAP()
        
        // Initialize empty AP lists
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
    
    fun saveAP(ap: AP) {
        if (!_savedAPs.any { it.mac == ap.mac }) {
            _savedAPs.add(ap)
            refreshAPLists()
        }
    }
    
    fun removeSavedAP(mac: String) {
        _savedAPs.removeIf { it.mac == mac }
        refreshAPLists()
    }
    
    // New method to explicitly trigger a fresh scan
    fun forceRefreshScan() {
        // Clear the discovered APs to start fresh
        _discoveredAPs.clear()
        _statusMessage.value = "Scanning networks..."
        
        CoroutineScope(Dispatchers.IO).launch {
            // First stop any ongoing scan
            if (_isScanning.value) {
                stopScanning()
                delay(500) // Wait for scanning to stop
            }
            
            // Start a new scan
            startScanning()
        }
    }
    
    // Set filter options
    fun setFilterOptions(minSignal: Int = -90, channel: Int? = null, onlySavedNetworks: Boolean = false) {
        minSignalStrength = minSignal
        channelFilter = channel
        onlyShowSavedNetworks = onlySavedNetworks
        
        // Apply the filter to the current data
        applyFilters()
    }
    
    // Apply current filters to the AP list
    private fun applyFilters() {
        val allAPs = if (onlyShowSavedNetworks) {
            _savedAPs.toList()
        } else {
            _allAPsFlow.value
        }
        
        // Apply filtering criteria
        val filtered = allAPs.filter { ap ->
            val passesSignal = ap.pwr >= minSignalStrength
            val passesChannel = channelFilter?.let { ap.ch == it } ?: true
            
            passesSignal && passesChannel
        }
        
        // Make sure the filtered list has no duplicates (should be redundant, but just to be safe)
        val uniqueFiltered = filtered.distinctBy { it.mac }
        
        _filteredAPsFlow.value = uniqueFiltered
    }
    
    fun startScanning(intervalMs: Long = scanInterval) {
        if (_isScanning.value) {
            return
        }
        
        scanInterval = intervalMs
        _isScanning.value = true
        _statusMessage.value = "Scanning for networks..."
        
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
    }
    
    private fun findWifiInterface() {
        // First check if our current interface exists and is usable
        shellExecutor.execute("ip addr show $wifiInterface") { output, exitCode ->
            if (exitCode == 0) {
                // Check if interface is up
                if (output.contains("state UP")) {
                    ensureInterfaceIsUp(wifiInterface)
                    return@execute
                } else {
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
                        ensureInterfaceIsUp(wifiInterface)
                        return@execute
                    }
                    
                    // If no wlan interface, use the first one found
                    wifiInterface = foundInterfaces.first()
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
        // Try each interface in our possible list
        var found = false
        
        // Use concurrent approach instead of sequential checks
        for (iface in possibleInterfaces) {
            if (found) continue
            
            shellExecutor.execute("ip link show $iface") { output, exitCode ->
                if (!found && exitCode == 0) {
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
        shellExecutor.execute("ip link set $iface up") { output, exitCode ->
            if (exitCode != 0) {
                Log.e(TAG, "Failed to bring up interface $iface: $output")
            }
        }
    }
    
    fun stopScanning() {
        _isScanning.value = false
        scanJob?.cancel()
        refreshJob?.cancel()
        
        _statusMessage.value = "Scanning stopped"
    }
    
    // Fallback scan method - this is our primary scanning method now
    private fun runFallbackScan() {
        // Make sure the interface is up
        ensureInterfaceIsUp(wifiInterface)
        
        // Use iw scan to get available networks
        shellExecutor.execute("iw dev $wifiInterface scan") { output, exitCode ->
            if (exitCode == 0) {
                _statusMessage.value = "Scan successful, parsing results..."
                
                // Process the output once at the end 
                CoroutineScope(Dispatchers.Default).launch {
                    parseIwScanOutputImproved(output)
                }
            } else {
                Log.e(TAG, "WiFi scan failed: $output")
                _statusMessage.value = "Scan failed. Check permissions."
                
                // Try using ip neighbor to find devices on the network
                tryIpNeighborScan()
            }
        }
    }
    
    // Improved parser for iw scan output
    private fun parseIwScanOutputImproved(output: String) {
        // Split the output by BSS entries - each represents a WiFi network
        val bssEntries = output.split("BSS ").drop(1) // Drop the first empty element
        
        if (bssEntries.isEmpty()) {
            _statusMessage.value = ""
            return
        }
        
        // Keep track of networks we've processed in this scan
        val processedMacs = mutableSetOf<String>()
        var parsedNetworks = 0
        var newNetworks = 0
        
        for (entry in bssEntries) {
            try {
                // Extract MAC address from the start of the entry
                val macRegex = Regex("([0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2})")
                val macMatch = macRegex.find(entry)
                val rawMac = macMatch?.value ?: continue
                
                // Normalize MAC to ensure consistent comparison
                val mac = normalizeMac(rawMac)
                
                // Skip if we've already processed this MAC in this scan
                if (mac in processedMacs) {
                    continue
                }
                processedMacs.add(mac)
                
                // Extract SSID - Fixed to handle multiline parsing better
                var ssid = "Unknown"
                if (entry.contains("SSID:")) {
                    val ssidLines = entry.lines().filter { it.contains("SSID:") }
                    if (ssidLines.isNotEmpty()) {
                        val ssidLine = ssidLines.first()
                        val ssidMatch = Regex("SSID:\\s*(.+?)\\s*$").find(ssidLine)
                        ssid = ssidMatch?.groupValues?.get(1)?.trim() ?: "Unknown"
                        
                        // Handle empty SSIDs as hidden networks
                        if (ssid.isEmpty() || ssid.isBlank()) {
                            ssid = "Hidden Network"
                        }
                    }
                }
                
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
                
                // Create AP object with normalized MAC
                val ap = AP(
                    essid = ssid,
                    mac = mac, // Use normalized MAC
                    ch = channel,
                    pwr = signal
                )
                
                // Get the count of networks before adding this one
                val networkCountBefore = _discoveredAPs.size
                
                // Add or update the AP in our discovered list
                updateDiscoveredAP(ap)
                parsedNetworks++
                
                // Check if this was a new network
                if (_discoveredAPs.size > networkCountBefore) {
                    newNetworks++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing network entry: ${e.message}")
            }
        }
        
        // Update status message to mention new networks if any were found
        if (newNetworks > 0) {
            _statusMessage.value = "Found $newNetworks new networks"
        } else {
            _statusMessage.value = "Scan complete, found $parsedNetworks networks"
        }
        
        // Immediately refresh AP lists to update UI
        refreshAPLists()
    }
    
    private fun tryIpNeighborScan() {
        shellExecutor.execute("ip neighbor") { output, exitCode ->
            if (exitCode == 0 && output.isNotEmpty()) {
                parseIpNeighborOutput(output)
            } else {
                Log.e(TAG, "IP neighbor scan failed")
                _statusMessage.value = "No networks found."
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
            // If we didn't find any devices
            _statusMessage.value = "No devices found on network"
        } else {
            _statusMessage.value = "Found ${_discoveredAPs.size} devices on network"
        }
        
        // Immediately refresh AP lists
        refreshAPLists()
    }
    
    private fun updateDiscoveredAP(ap: AP) {
        // Use normalized MAC for lookup
        val normalizedMac = normalizeMac(ap.mac)
        
        // Check if we already have this AP
        val existingAP = _discoveredAPs.entries.find { normalizeMac(it.key) == normalizedMac }?.value
        
        if (existingAP != null) {
            // Update existing AP with new information if needed - silently
            existingAP.update(
                essid = if (ap.essid != "Unknown" && ap.essid != "Hidden Network") ap.essid else existingAP.essid,
                ch = if (ap.ch > 0) ap.ch else existingAP.ch,
                pwr = ap.pwr
            )
            // No logging for updates to existing APs
        } else {
            // Add new AP and log only for new discoveries
            _discoveredAPs[normalizedMac] = ap
            Log.d(TAG, "⭐ NEW NETWORK DISCOVERED: ${ap.essid ?: "Unknown"} (${normalizedMac}) on channel ${ap.ch} with signal ${ap.pwr} dBm")
            
            // Update the status message to show the new network
            _statusMessage.value = "New network found: ${ap.essid ?: "Unknown"}"
        }
    }
    
    private fun refreshAPLists() {
        // Normalize all MAC addresses in the _discoveredAPs map to ensure no duplicates
        val normalizedDiscoveredAPs = mutableMapOf<String, AP>()
        _discoveredAPs.forEach { (mac, ap) ->
            val normalizedMac = normalizeMac(mac)
            // If there's a collision, keep the stronger signal
            val existingAP = normalizedDiscoveredAPs[normalizedMac]
            if (existingAP == null || existingAP.pwr < ap.pwr) {
                normalizedDiscoveredAPs[normalizedMac] = ap
            }
        }
        
        // Replace the discoveredAPs with the normalized version
        _discoveredAPs.clear()
        _discoveredAPs.putAll(normalizedDiscoveredAPs)
        
        // Sort discovered APs by signal strength
        val sortedDiscovered = _discoveredAPs.values.toList()
            .sortedByDescending { it.pwr }
        
        // Ensure saved APs list has no duplicates (by normalized MAC address)
        val uniqueSavedAPs = _savedAPs
            .groupBy { normalizeMac(it.mac) }
            .mapValues { it.value.first() } // Take the first AP from each group
            .values.toList()
        
        // Update saved APs flow with the unique list
        _savedAPsFlow.value = uniqueSavedAPs
        
        // Update scanned APs flow
        _scannedAPsFlow.value = sortedDiscovered
        
        // Create a combined list without duplicates
        val combinedMap = mutableMapOf<String, AP>()
        
        // First add all saved APs to the map (they take priority)
        uniqueSavedAPs.forEach { ap ->
            combinedMap[normalizeMac(ap.mac)] = ap
        }
        
        // Then add discovered APs that aren't already in the map
        sortedDiscovered.forEach { ap ->
            val normalizedMac = normalizeMac(ap.mac)
            if (!combinedMap.containsKey(normalizedMac)) {
                combinedMap[normalizedMac] = ap
            }
        }
        
        // Convert the map values to a list, ensuring no duplicates
        val combinedList = combinedMap.values.toList()
        
        // Update the all APs flow
        _allAPsFlow.value = combinedList
        
        // Apply filters to the updated list
        applyFilters()
    }
    
    // Helper function to normalize MAC addresses for consistent comparison
    private fun normalizeMac(mac: String): String {
        return mac.lowercase().trim().replace("-", ":")
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
