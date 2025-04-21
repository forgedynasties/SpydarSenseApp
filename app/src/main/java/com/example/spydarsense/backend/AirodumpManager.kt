package com.example.spydarsense.backend

import android.util.Log
import com.example.spydarsense.data.AP
import com.example.spydarsense.data.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AirodumpManager {
    private val shellExecutor = ShellExecutor()
    private var airodumpJob: Job? = null
    private var parserJob: Job? = null
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning
    
    private val _accessPoints = MutableStateFlow<List<AP>>(emptyList())
    val accessPoints: StateFlow<List<AP>> = _accessPoints
    
    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing
    
    private val outputDir = "/sdcard"
    private val outputFileName = "airodump_testfile"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    
    companion object {
        private var instance: AirodumpManager? = null
        
        fun getInstance(): AirodumpManager {
            if (instance == null) {
                instance = AirodumpManager()
            }
            return instance!!
        }
    }
    
    fun startScan() {
        if (_isScanning.value) {
            Log.d("AirodumpManager", "Scan already running, ignoring start request")
            return
        }
        
        _isScanning.value = true
        _isRefreshing.value = true
        
        // Start airodump-ng
        val command = "LD_PRELOAD=libfakeioctl.so airodump-ng --write-interval 3 --band bg --output-format csv -w $outputDir/$outputFileName wlan0"
        
        airodumpJob = CoroutineScope(Dispatchers.IO).launch {
            shellExecutor.execute(command) { output, exitCode -> 
                if (exitCode != 0) {
                    Log.e("AirodumpManager", "airodump-ng failed: $output")
                    _isScanning.value = false
                }
            }
        }
        
        // Start parsing the CSV file periodically
        parserJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _isScanning.value) {
                parseCSV()
                _isRefreshing.value = false
                delay(3000) // Wait for 3 seconds before parsing again
            }
        }
    }
    
    fun stopScan() {
        if (!_isScanning.value) {
            return
        }
        
        _isScanning.value = false
        shellExecutor.stop()
        airodumpJob?.cancel()
        parserJob?.cancel()
        
        // Clean up temporary files
        cleanupFiles()
    }
    
    private fun cleanupFiles() {
        try {
            // List all files with the base name and delete them
            val baseFile = File(outputDir)
            if (baseFile.exists() && baseFile.isDirectory) {
                baseFile.listFiles { file -> 
                    file.name.startsWith(outputFileName)
                }?.forEach { file -> 
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("AirodumpManager", "Error cleaning up files: ${e.message}")
        }
    }
    
    fun forceRefresh() {
        _isRefreshing.value = true
        CoroutineScope(Dispatchers.IO).launch {
            parseCSV()
            _isRefreshing.value = false
        }
    }
    
    private fun parseCSV() {
        try {
            // Find the most recent CSV file
            val csvFile = findMostRecentCSVFile()
            if (csvFile == null) {
                Log.d("AirodumpManager", "No CSV file found")
                return
            }
            
            Log.d("AirodumpManager", "Parsing CSV file: ${csvFile.absolutePath}")
            
            val lines = csvFile.readLines()
            if (lines.isEmpty()) {
                Log.d("AirodumpManager", "CSV file is empty")
                return
            }
            
            // Parse APs and Stations
            val accessPoints = mutableListOf<AP>()
            val stations = mutableListOf<Station>()
            
            var currentSection = ""
            var headers: List<String> = emptyList()
            
            for (line in lines) {
                val trimmedLine = line.trim()
                
                // Skip empty lines
                if (trimmedLine.isEmpty()) continue
                
                // Detect section by header
                if (trimmedLine.startsWith("BSSID")) {
                    currentSection = "AP"
                    headers = trimmedLine.split(",").map { it.trim() }
                    continue
                } else if (trimmedLine.startsWith("Station MAC")) {
                    currentSection = "Station"
                    headers = trimmedLine.split(",").map { it.trim() }
                    continue
                }
                
                // Parse data based on section
                when (currentSection) {
                    "AP" -> {
                        val ap = parseAPLine(trimmedLine)
                        if (ap != null) {
                            accessPoints.add(ap)
                        }
                    }
                    "Station" -> {
                        val station = parseStationLine(trimmedLine)
                        if (station != null) {
                            stations.add(station)
                        }
                    }
                }
            }
            
            // Update flows with new data
            _accessPoints.value = accessPoints
            _stations.value = stations
            
            Log.d("AirodumpManager", "Parsed ${accessPoints.size} APs and ${stations.size} stations")
            
        } catch (e: Exception) {
            Log.e("AirodumpManager", "Error parsing CSV: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun findMostRecentCSVFile(): File? {
        try {
            val baseFile = File(outputDir)
            if (!baseFile.exists() || !baseFile.isDirectory) {
                return null
            }
            
            return baseFile.listFiles { file -> 
                file.name.startsWith(outputFileName) && file.name.endsWith(".csv")
            }?.maxByOrNull { file -> file.lastModified() }
        } catch (e: Exception) {
            Log.e("AirodumpManager", "Error finding CSV file: ${e.message}")
            return null
        }
    }
    
    private fun parseAPLine(line: String): AP? {
        try {
            val parts = line.split(",").map { it.trim() }
            if (parts.size < 14) return null
            
            val bssid = parts[0]
            val channel = parts[3].toIntOrNull() ?: 0
            val privacy = if (parts.size > 5) parts[5] else "Unknown"
            val cipher = if (parts.size > 6) parts[6] else "Unknown"
            val auth = if (parts.size > 7) parts[7] else "Unknown"
            val power = parts[8].toIntOrNull() ?: -80
            val beacons = if (parts.size > 9) parts[9].toIntOrNull() ?: 0 else 0
            val ivs = if (parts.size > 10) parts[10].toIntOrNull() ?: 0 else 0
            val essid = if (parts.size > 13) parts[13] else null
            
            // Skip entries with empty BSSIDs
            if (bssid.isEmpty()) return null
            
            return AP(
                essid = essid,
                mac = bssid,
                ch = channel,
                pwr = power,
                enc = privacy,
                cipher = cipher,
                auth = auth,
                beacons = beacons,
                data = 0, // Data not directly available in CSV
                ivs = ivs
            )
        } catch (e: Exception) {
            Log.e("AirodumpManager", "Error parsing AP line: $line")
            return null
        }
    }
    
    private fun parseStationLine(line: String): Station? {
        try {
            val parts = line.split(",").map { it.trim() }
            if (parts.size < 6) return null
            
            val mac = parts[0]
            val power = parts[3].toIntOrNull() ?: -80
            val bssid = parts[5]
            val probedEssids = if (parts.size > 6) parts.subList(6, parts.size).joinToString(",") else ""
            
            // Skip entries with empty MACs
            if (mac.isEmpty()) return null
            
            return Station(mac, bssid, power, probedEssids)
        } catch (e: Exception) {
            Log.e("AirodumpManager", "Error parsing Station line: $line")
            return null
        }
    }
}
