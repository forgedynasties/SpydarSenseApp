package com.example.spydarsense.backend

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * SpyCameraDetector serves as the main driver class for backend operations.
 * It orchestrates all backend components and provides a clean interface for the UI.
 */
class SpyCameraDetector private constructor() {
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    @SuppressLint("SdCardPath")
    private val outputDir = "/sdcard/Download/Lab"
    private val shellExecutor = ShellExecutor()
    private val etherSrc = "AC:6C:90:22:8F:37" // Example MAC address, replace with actual

    private val tcpdumpManager = TcpdumpManager(outputDir, dateFormat, shellExecutor, etherSrc)
    private val csiCollector = CSIBitrateCollector(tcpdumpManager)

    // Expose necessary state flows for UI
    val csiDirs: StateFlow<List<String>> = tcpdumpManager.csiDirs
    val brDirs: StateFlow<List<String>> = tcpdumpManager.brDirs
    val isCapturing: StateFlow<Boolean> = tcpdumpManager.isRunning

    // Configuration
    private val _loggingEnabled = MutableStateFlow(false)
    val loggingEnabled: StateFlow<Boolean> = _loggingEnabled

    fun enableLogging(enabled: Boolean) {
        _loggingEnabled.value = enabled
        // Update all components that use logging
        Log.d("SpyCameraDetector", "Logging ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Start collecting CSI and bitrate data for the specified device
     */
    suspend fun startDetection(macAddress: String, channel: Int) {
        log("Starting detection for MAC: $macAddress on channel: $channel")
        csiCollector.collectCSIBitrate(macAddress, channel)
    }

    /**
     * Stop all ongoing captures
     */
    fun stopDetection() {
        log("Stopping all detection activities")
        tcpdumpManager.stopCaptures()
    }

    /**
     * Process and analyze the last capture to detect spy cameras
     */
    suspend fun analyzeCaptures(): Boolean {
        log("Analyzing captures for spy camera detection")
        val latestCsiDir = csiDirs.value.lastOrNull()
        val latestBrDir = brDirs.value.lastOrNull()

        if (latestCsiDir == null || latestBrDir == null) {
            log("No captures available for analysis")
            return false
        }

        // Here we would add the actual analysis logic
        // For now, returning a placeholder result
        return true
    }

    private fun log(message: String) {
        if (_loggingEnabled.value) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "SpyCameraDetector"

        // Singleton instance
        @Volatile
        private var instance: SpyCameraDetector? = null

        fun getInstance(): SpyCameraDetector {
            return instance ?: synchronized(this) {
                instance ?: SpyCameraDetector().also { instance = it }
            }
        }
    }
}