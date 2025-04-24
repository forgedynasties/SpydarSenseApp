package com.example.spydarsense.repository

import com.example.spydarsense.backend.AirodumpScanner
import com.example.spydarsense.data.AP
import com.example.spydarsense.data.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WifiScanRepository private constructor() {
    
    private val airodumpScanner = AirodumpScanner.getInstance()
    
    // Simply pass through the scanner's access points instead of combining with hardcoded ones
    val allAccessPoints = airodumpScanner.accessPoints
    
    val stations = airodumpScanner.stations
    
    val isScanning = airodumpScanner.isScanning
    val isRefreshing = airodumpScanner.isRefreshing
    
    // Add a state flow to track if scanning is active
    private val _scanActive = MutableStateFlow(false)
    val scanActive: StateFlow<Boolean> = _scanActive
    
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
        if (!_scanActive.value) {
            airodumpScanner.startScan()
            _scanActive.value = true
        }
    }
    
    fun stopScan() {
        if (_scanActive.value) {
            airodumpScanner.stopScan()
            _scanActive.value = false
        }
    }
    
    fun forceRefreshScan() {
        if (_scanActive.value) {
            airodumpScanner.forceRefresh()
        } else {
            // Start scan if not already scanning
            startScan()
        }
    }
}
