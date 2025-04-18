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

    // Indices of CSI amplitudes to keep
    private val csiIndicesToKeep = intArrayOf(4, 8, 13, 18, 22, 27, 34, 38, 43, 48, 52, 58)

    private val pcaExtractor = PCAFeatureExtractor()

    // New state flows for PCA features
    private val _csiPcaFeatures = MutableStateFlow<PCAFeatureExtractor.PCAFeatures?>(null)
    val csiPcaFeatures: StateFlow<PCAFeatureExtractor.PCAFeatures?> = _csiPcaFeatures

    private val _bitratePcaFeatures = MutableStateFlow<PCAFeatureExtractor.PCAFeatures?>(null)
    val bitratePcaFeatures: StateFlow<PCAFeatureExtractor.PCAFeatures?> = _bitratePcaFeatures

    // Timeline data structures
    private val _csiTimeline = MutableStateFlow<Map<Double, List<Float>>>(emptyMap())
    val csiTimeline: StateFlow<Map<Double, List<Float>>> = _csiTimeline
    
    private val _bitrateTimeline = MutableStateFlow<Map<Double, Int>>(emptyMap())
    val bitrateTimeline: StateFlow<Map<Double, Int>> = _bitrateTimeline
    
    // Timestamp of first sample for relative timing
    private var firstTimestamp: Long? = null

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
        
        // Add direct monitoring of processing trigger from TcpdumpManager
        CoroutineScope(Dispatchers.IO).launch {
            tcpdumpManager.processingTrigger.collect { timestamp ->
                if (timestamp > 0) {
                    log("Processing trigger received at $timestamp")
                    processContinuousData()
                }
            }
        }
    }
    
    // Add method to process continuous data streams
    private fun processContinuousData() {
        val csiFile = tcpdumpManager.getCurrentCsiFile()
        val brFile = tcpdumpManager.getCurrentBrFile()
        
        log("Starting continuous data processing cycle")
        
        if (csiFile != null) {
            log("Processing continuous CSI data from $csiFile")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val samples = PcapProcessor.processPcapCSI(csiFile)
                    if (samples.isNotEmpty()) {
                        log("Processed ${samples.size} new CSI samples")
                        synchronized(csiSamplesBuffer) {
                            csiSamplesBuffer.addAll(samples)
                            updateCsiStats()
                        }
                    } else {
                        log("No new CSI samples found in file: $csiFile")
                    }
                } catch (e: Exception) {
                    log("Error processing CSI file $csiFile: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        
        if (brFile != null) {
            log("Processing continuous bitrate data from $brFile")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val samples = PcapProcessor.processPcapBitrate(brFile)
                    if (samples.isNotEmpty()) {
                        log("Processed ${samples.size} new bitrate samples")
                        synchronized(bitrateSamplesBuffer) {
                            bitrateSamplesBuffer.addAll(samples)
                            updateBitrateStats()
                        }
                    } else {
                        log("No new bitrate samples found in file: $brFile")
                    }
                } catch (e: Exception) {
                    log("Error processing bitrate file $brFile: ${e.message}")
                    e.printStackTrace()
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
            return // Keep the previous stats if buffer is empty
        }
        
        log("Updating CSI stats with ${csiSamplesBuffer.size} samples")
        val amplitudesList = mutableListOf<Float>()
        val sampleAmplitudesList = mutableListOf<List<Float>>()
        val timestampedSamples = mutableListOf<Pair<Double, List<Float>>>()
        
        try {
            csiSamplesBuffer.forEach { sample ->
                // Calculate timestamp in seconds
                val timestamp = sample.tsSec.toDouble() + (sample.tsUsec.toDouble() / 1_000_000.0)
                
                // Track first timestamp for relative timing
                if (firstTimestamp == null) {
                    firstTimestamp = sample.tsSec * 1_000_000L + sample.tsUsec
                }
                
                // Calculate relative timestamp
                val relativeTimestamp = (timestamp - (firstTimestamp!! / 1_000_000.0))
                
                val complexSamples = PcapProcessor.unpack(sample.csi, "default", fftshift = true)
                val fullAmplitudes = complexSamples.map { 
                    kotlin.math.sqrt(it.re * it.re + it.im * it.im) 
                }
                
                // Only keep the specified indices - fixed to avoid mapNotNull
                val filteredAmplitudes = mutableListOf<Float>()
                for (index in csiIndicesToKeep) {
                    if (index >= 0 && index < fullAmplitudes.size) {
                        filteredAmplitudes.add(fullAmplitudes[index])
                    }
                }
                
                // Add to aggregate list
                amplitudesList.addAll(filteredAmplitudes)
                
                // Add as an individual sample
                sampleAmplitudesList.add(filteredAmplitudes)
                
                // Add to timestamped list for timeline
                timestampedSamples.add(Pair(relativeTimestamp, filteredAmplitudes))
                
                log("Filtered CSI amplitudes to ${filteredAmplitudes.size} values from ${fullAmplitudes.size}")
            }
            
            val avgAmplitude = if (amplitudesList.isNotEmpty()) amplitudesList.sum() / amplitudesList.size.toFloat() else 0f
            val minAmplitude = amplitudesList.minOfOrNull { it } ?: 0f
            val maxAmplitude = amplitudesList.maxOfOrNull { it } ?: 0f
            
            _csiStats.value = CSIStats(
                sampleCount = csiSamplesBuffer.size,
                avgAmplitude = avgAmplitude,
                minAmplitude = minAmplitude,
                maxAmplitude = maxAmplitude,
                sampleAmplitudes = sampleAmplitudesList
            )
            
            // Extract PCA features
            CoroutineScope(Dispatchers.IO).launch {
                val pcaFeatures = pcaExtractor.extractCSIFeatures(sampleAmplitudesList)
                _csiPcaFeatures.value = pcaFeatures
                log("Updated CSI PCA features: ${pcaFeatures.values.size} values")
            }
            
            // Generate timeline
            CoroutineScope(Dispatchers.IO).launch {
                val normalizedTimeline = pcaExtractor.normalizeCSITimeline(timestampedSamples)
                _csiTimeline.value = normalizedTimeline
                log("Updated CSI timeline with ${normalizedTimeline.size} time points")
            }
            
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
            log("Bitrate buffer is empty, cannot update stats")
            return // Keep the previous stats if buffer is empty
        }
        
        val bitrateValues = bitrateSamplesBuffer.map { it.bitrate }
        val avgBitrate = bitrateValues.average().toFloat()
        val minBitrate = bitrateValues.minOrNull() ?: 0
        val maxBitrate = bitrateValues.maxOrNull() ?: 0
        
        _bitrateStats.value = BitrateStats(
            sampleCount = bitrateSamplesBuffer.size,
            avgBitrate = avgBitrate,
            minBitrate = minBitrate,
            maxBitrate = maxBitrate,
            bitrateValues = bitrateValues
        )
        
        // Create timestamped samples for timeline
        val timestampedSamples = bitrateSamplesBuffer.map { sample ->
            Pair(sample.timestamp.toDouble(), sample.bitrate)
        }
        
        // Extract PCA features
        CoroutineScope(Dispatchers.IO).launch {
            val pcaFeatures = pcaExtractor.extractBitrateFeatures(bitrateValues)
            _bitratePcaFeatures.value = pcaFeatures
            log("Updated bitrate PCA features: ${pcaFeatures.values.size} values")
        }
        
        // Generate timeline
        CoroutineScope(Dispatchers.IO).launch {
            val normalizedTimeline = pcaExtractor.normalizeBitrateTimeline(timestampedSamples)
            _bitrateTimeline.value = normalizedTimeline
            log("Updated bitrate timeline with ${normalizedTimeline.size} time points")
        }
        
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
            firstTimestamp = null
            _csiTimeline.value = emptyMap()
            updateCsiStats()
        }
        synchronized(bitrateSamplesBuffer) {
            bitrateSamplesBuffer.clear()
            _bitrateTimeline.value = emptyMap()
            updateBitrateStats()
        }
        
        // Clear tracked file positions
        PcapProcessor.clearTrackedPositions()
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
        val maxAmplitude: Float,
        val sampleAmplitudes: List<List<Float>> = emptyList() // List of sample amplitudes
    )

    data class BitrateStats(
        val sampleCount: Int,
        val avgBitrate: Float,
        val minBitrate: Int,
        val maxBitrate: Int,
        val bitrateValues: List<Int> = emptyList() // List of bitrate values
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