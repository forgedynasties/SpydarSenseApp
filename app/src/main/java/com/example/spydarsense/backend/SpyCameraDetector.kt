package com.example.spydarsense.backend

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.example.spydarsense.data.CSISample

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

    // Buffers to store processed data
    private val csiSamplesBuffer = mutableListOf<CSISample>()
    private val bitrateSamplesBuffer = mutableListOf<BitrateSample>()

    // Stats for UI display
    private val _csiStats = MutableStateFlow<CSIStats?>(null)
    val csiStats: StateFlow<CSIStats?> = _csiStats

    private val _bitrateStats = MutableStateFlow<BitrateStats?>(null)
    val bitrateStats: StateFlow<BitrateStats?> = _bitrateStats

    // Tracking processed directories
    private val processedCsiDirs = mutableSetOf<String>()
    private val processedBrDirs = mutableSetOf<String>()

    // Expose necessary state flows for UI
    val csiDirs: StateFlow<List<String>> = tcpdumpManager.csiDirs
    val brDirs: StateFlow<List<String>> = tcpdumpManager.brDirs
    val isCapturing: StateFlow<Boolean> = tcpdumpManager.isRunning

    // Configuration
    private val _loggingEnabled = MutableStateFlow(false)
    val loggingEnabled: StateFlow<Boolean> = _loggingEnabled

    init {
        // Monitor for new directories and process them
        CoroutineScope(Dispatchers.IO).launch {
            tcpdumpManager.csiDirs.collect { dirs ->
                dirs.forEach { dir ->
                    if (dir !in processedCsiDirs) {
                        processedCsiDirs.add(dir)
                        processNewCsiDir(dir)
                    }
                }
            }
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            tcpdumpManager.brDirs.collect { dirs ->
                dirs.forEach { dir ->
                    if (dir !in processedBrDirs) {
                        processedBrDirs.add(dir)
                        processNewBrDir(dir)
                    }
                }
            }
        }
    }

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
     * Process CSI directory and update stats
     */
    private fun processNewCsiDir(dir: String) {
        log("Processing new CSI directory: $dir")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val samples = PcapProcessor.processPcapCSI(dir)
                if (samples.isNotEmpty()) {
                    log("Processed ${samples.size} CSI samples")
                    synchronized(csiSamplesBuffer) {
                        csiSamplesBuffer.addAll(samples)
                        updateCsiStats()
                    }
                } else {
                    log("No CSI samples found in directory: $dir")
                }
            } catch (e: Exception) {
                log("Error processing CSI directory $dir: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Process bitrate directory and update stats
     */
    private fun processNewBrDir(dir: String) {
        log("Processing new bitrate directory: $dir")
        CoroutineScope(Dispatchers.IO).launch {
            val samples = PcapProcessor.processPcapBitrate(dir)
            synchronized(bitrateSamplesBuffer) {
                bitrateSamplesBuffer.addAll(samples)
                updateBitrateStats()
            }
        }
    }

    /**
     * Update CSI statistics based on current buffer
     */
    private fun updateCsiStats() {
        if (csiSamplesBuffer.isEmpty()) {
            log("CSI buffer is empty, cannot update stats")
            _csiStats.value = null
            return
        }
        
        log("Updating CSI stats with ${csiSamplesBuffer.size} samples")
        val amplitudesList = mutableListOf<Float>()
        
        try {
            csiSamplesBuffer.forEach { sample ->
                val complexSamples = PcapProcessor.unpack(sample.csi, "default", fftshift = true)
                val amplitudes = complexSamples.map { 
                    kotlin.math.sqrt(it.re * it.re + it.im * it.im) 
                }
                amplitudesList.addAll(amplitudes)
            }
            
            val avgAmplitude = amplitudesList.average().toFloat()
            val minAmplitude = amplitudesList.minOrNull() ?: 0f
            val maxAmplitude = amplitudesList.maxOrNull() ?: 0f
            
            _csiStats.value = CSIStats(
                sampleCount = csiSamplesBuffer.size,
                avgAmplitude = avgAmplitude,
                minAmplitude = minAmplitude,
                maxAmplitude = maxAmplitude
            )
            
            log("Updated CSI stats: ${_csiStats.value}")
        } catch (e: Exception) {
            log("Error updating CSI stats: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Update bitrate statistics based on current buffer
     */
    private fun updateBitrateStats() {
        if (bitrateSamplesBuffer.isEmpty()) {
            _bitrateStats.value = null
            return
        }
        
        val bitrateValues = bitrateSamplesBuffer.map { it.bitrate }
        val avgBitrate = bitrateValues.average().toFloat()
        val minBitrate = bitrateValues.minOrNull() ?: 0
        val maxBitrate = bitrateValues.maxOrNull() ?: 0
        
        _bitrateStats.value = BitrateStats(
            sampleCount = bitrateSamplesBuffer.size,
            avgBitrate = avgBitrate,
            minBitrate = minBitrate,
            maxBitrate = maxBitrate
        )
        
        log("Updated bitrate stats: ${_bitrateStats.value}")
    }

    /**
     * Process and analyze the last capture to detect spy cameras
     */
    suspend fun analyzeCaptures(): Boolean {
        log("Analyzing captures for spy camera detection")
        
        if (csiSamplesBuffer.isEmpty() || bitrateSamplesBuffer.isEmpty()) {
            log("No data available for analysis")
            return false
        }
        
        // For now, just log the stats - actual analysis logic would go here
        log("CSI samples: ${csiSamplesBuffer.size}")
        log("Bitrate samples: ${bitrateSamplesBuffer.size}")
        log("CSI stats: ${_csiStats.value}")
        log("Bitrate stats: ${_bitrateStats.value}")
        
        return true
    }

    /**
     * Clear the data buffers
     */
    fun clearBuffers() {
        log("Clearing data buffers")
        synchronized(csiSamplesBuffer) {
            csiSamplesBuffer.clear()
            updateCsiStats()
        }
        synchronized(bitrateSamplesBuffer) {
            bitrateSamplesBuffer.clear()
            updateBitrateStats()
        }
    }

    private fun log(message: String) {
        if (_loggingEnabled.value) {
            Log.d(TAG, message)
        }
    }

    /**
     * Data classes to represent statistics
     */
    data class CSIStats(
        val sampleCount: Int,
        val avgAmplitude: Float,
        val minAmplitude: Float, 
        val maxAmplitude: Float
    )

    data class BitrateStats(
        val sampleCount: Int,
        val avgBitrate: Float,
        val minBitrate: Int,
        val maxBitrate: Int
    )

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