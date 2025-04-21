package com.example.spydarsense.repository

import com.example.spydarsense.backend.AirodumpScanner
import com.example.spydarsense.data.AP
import com.example.spydarsense.data.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

class WifiScanRepository private constructor() {
    
    private val airodumpScanner = AirodumpScanner.getInstance()
    
    // Hard-coded APs that should always show
    private val _hardcodedAPs = MutableStateFlow<List<AP>>(
        listOf(
            AP(essid = "Demo Network 1", mac = "00:11:22:33:44:55", ch = 1, pwr = -50),
            AP(essid = "Demo Network 2", mac = "AA:BB:CC:DD:EE:FF", ch = 6, pwr = -65)
        )
    )
    
    // Combine hardcoded and scanned APs
    val allAccessPoints = combine(
        _hardcodedAPs,
        airodumpScanner.accessPoints
    ) { hardcoded, scanned ->
        // Combine and deduplicate (in case a scanned AP has the same MAC as a hardcoded one)
        val combined = hardcoded.toMutableList()
        combined.addAll(scanned)
        combined.distinctBy { AP.normalizeMac(it.mac) }
    }
    
    val stations = airodumpScanner.stations
    
    val isScanning = airodumpScanner.isScanning
    val isRefreshing = airodumpScanner.isRefreshing
    
    companion object {
        private var instance: WifiScanRepository? = null
        
        fun getInstance(): WifiScanRepository {
            if (instance == null) {
                instance = WifiScanRepository()
            }
            return instance!!
        }
    }
    
    fun startScan() {
        airodumpScanner.startScan()
    }
    
    fun stopScan() {
        airodumpScanner.stopScan()
    }
    
    fun forceRefreshScan() {
        airodumpScanner.forceRefresh()
    }
}
