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
import kotlinx.coroutines.delay

/**
 * SpyCameraDetector serves as the main driver class for backend operations.
 * It orchestrates all backend components and provides a clean interface for the UI.
 */
class SpyCameraDetector private constructor(private val etherSrc: String) {
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    @SuppressLint("SdCardPath")
    private val outputDir = "/sdcard/Download/Lab"
    private val shellExecutor = ShellExecutor()

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
    val captureCompleted: StateFlow<Boolean> = tcpdumpManager.captureCompleted

    // Add processing state
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    // Configuration
    private val _loggingEnabled = MutableStateFlow(false)
    val loggingEnabled: StateFlow<Boolean> = _loggingEnabled

    // Indices of CSI amplitudes to keep
    private val csiIndicesToKeep = intArrayOf(4, 8, 13, 18, 22, 27, 34, 38, 43, 48, 52, 58)

    private val pcaExtractor = PCAFeatureExtractor()
    private val featureProcessor = FeatureProcessor()

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
    
    // New state flow for aligned features
    private val _alignedFeatures = MutableStateFlow<List<AlignedFeature>>(emptyList())
    val alignedFeatures: StateFlow<List<AlignedFeature>> = _alignedFeatures
    
    // Timestamp of first sample for relative timing
    private var firstTimestamp: Long? = null

    // Expose the processing trigger for UI updates
    val processingTrigger: StateFlow<Long> = tcpdumpManager.processingTrigger

    // Add processing stage tracking
    private val _processingStage = MutableStateFlow("Ready")
    val processingStage: StateFlow<String> = _processingStage
    
    private val _processingProgress = MutableStateFlow(0f)
    val processingProgress: StateFlow<Float> = _processingProgress

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
                    _isProcessing.value = true
                    
                    // Process the completed capture files
                    processCompletedCaptures()
                    
                    _isProcessing.value = false
                }
            }
        }
        
        // Add monitoring of capture completion
        CoroutineScope(Dispatchers.IO).launch {
            tcpdumpManager.captureCompleted.collect { completed ->
                if (completed) {
                    log("Capture completed, ready for processing")
                }
            }
        }
    }
    
    /**
     * Start collecting CSI and bitrate data for the specified device
     */
    suspend fun startDetection(macAddress: String, channel: Int, captureSeconds: Int = 10) {
        log("Starting detection for MAC: $macAddress on channel: $channel for $captureSeconds seconds")
        
        // Update processing stage
        _processingStage.value = "Initializing detection..."
        _processingProgress.value = 0f
        
        // Clear any existing data before starting new detection
        clearBuffers()
        
        // Set up CSI collection with nexutil
        _processingStage.value = "Configuring CSI parameters..."
        _processingProgress.value = 0.1f
        
        val csiParam = csiCollector.makeCSIParams(macAddress, channel)
        if (csiParam.isEmpty()) {
            log("Failed to generate CSI parameters, aborting detection")
            _processingStage.value = "Failed to configure CSI"
            return
        }
        
        // Configure nexutil for CSI collection
        _processingStage.value = "Setting up network monitor..."
        _processingProgress.value = 0.2f
        
        val nexutilCommand = "nexutil -Iwlan0 -s500 -b -l34 -v$csiParam"
        log("Configuring CSI with command: $nexutilCommand")
        
        shellExecutor.execute(nexutilCommand) { output, exitCode ->
            if (exitCode == 0) {
                log("nexutil configured successfully: $output")
                
                // Update processing stage
                _processingStage.value = "Starting data capture (${captureSeconds}s)..."
                _processingProgress.value = 0.3f
                
                // Start captures with the specified duration
                tcpdumpManager.startCaptures(captureSeconds)
                log("Started ${captureSeconds}-second captures")
                
                // Start a countdown on the processing stage
                CoroutineScope(Dispatchers.Main).launch {
                    for (i in captureSeconds downTo 1) {
                        _processingStage.value = "Capturing data... ${i}s remaining"
                        _processingProgress.value = 0.3f + (0.4f * (captureSeconds - i) / captureSeconds)
                        delay(1000)
                    }
                }
            } else {
                log("nexutil configuration failed: $output")
                _processingStage.value = "Failed to configure network monitor"
            }
        }
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

    // New method to process completed capture files
    private fun processCompletedCaptures() {
        val csiFile = tcpdumpManager.getCurrentCsiFile()
        val brFile = tcpdumpManager.getCurrentBrFile()
        
        _processingStage.value = "Processing captured data..."
        _processingProgress.value = 0.7f
        
        log("Processing completed captures from CSI: $csiFile and Bitrate: $brFile")
        
        if (csiFile != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val samples = PcapProcessor.processPcapCSI(csiFile)
                    if (samples.isNotEmpty()) {
                        log("Processed ${samples.size} CSI samples from completed capture")
                        _processingStage.value = "Processing CSI samples..."
                        _processingProgress.value = 0.75f
                        
                        synchronized(csiSamplesBuffer) {
                            csiSamplesBuffer.clear() // Clear old data
                            csiSamplesBuffer.addAll(samples)
                            updateCsiStats()
                        }
                    } else {
                        log("No CSI samples found in completed capture file: $csiFile")
                    }
                } catch (e: Exception) {
                    log("Error processing completed CSI file $csiFile: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        
        if (brFile != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    _processingStage.value = "Processing bitrate data..."
                    _processingProgress.value = 0.8f
                    
                    val samples = PcapProcessor.processPcapBitrate(brFile)
                    if (samples.isNotEmpty()) {
                        log("Processed ${samples.size} bitrate samples from completed capture")
                        synchronized(bitrateSamplesBuffer) {
                            bitrateSamplesBuffer.clear() // Clear old data
                            bitrateSamplesBuffer.addAll(samples)
                            updateBitrateStats()
                        }
                    } else {
                        log("No bitrate samples found in completed capture file: $brFile")
                    }
                } catch (e: Exception) {
                    log("Error processing completed bitrate file $brFile: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
    
    // Add method to process and align features
    private fun processAndAlignFeatures() {
        CoroutineScope(Dispatchers.IO).launch {
            _processingStage.value = "Aligning features..."
            _processingProgress.value = 0.85f
            
            log("Processing and aligning features...")
            val features = featureProcessor.processFeatures(
                csiTimeline = _csiTimeline.value,
                bitrateTimeline = _bitrateTimeline.value
            )
            _alignedFeatures.value = features
            
            _processingStage.value = "Generating visualizations..."
            _processingProgress.value = 0.95f
            
            delay(500) // Small delay to ensure UI updates
            
            _processingStage.value = "Analysis complete"
            _processingProgress.value = 1.0f
            
            log("Processed and aligned ${features.size} features")
        }
    }

    fun enableLogging(enabled: Boolean) {
        _loggingEnabled.value = enabled
        // Update all components that use logging
        Log.d("SpyCameraDetector", "Logging ${if (enabled) "enabled" else "disabled"}")
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
                _processingStage.value = "Extracting CSI features..."
                _processingProgress.value = 0.9f
                
                val pcaFeatures = pcaExtractor.extractCSIFeatures(sampleAmplitudesList)
                _csiPcaFeatures.value = pcaFeatures
                log("Updated CSI PCA features: ${pcaFeatures.values.size} values")
            }
            
            // Generate timeline
            CoroutineScope(Dispatchers.IO).launch {
                val normalizedTimeline = pcaExtractor.normalizeCSITimeline(timestampedSamples)
                _csiTimeline.value = normalizedTimeline
                log("Updated CSI timeline with ${normalizedTimeline.size} time points")
                
                // Process and align features after updating timeline
                processAndAlignFeatures()
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
            
            // Process and align features after updating timeline
            processAndAlignFeatures()
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
        log("Clearing all data buffers and states")
        
        // Stop any active captures first and clear data in TcpdumpManager
        tcpdumpManager.clearData()
        
        // Clear processed directory tracking
        processedCsiDirs.clear()
        processedBrDirs.clear()
        
        // Clear data buffers
        synchronized(csiSamplesBuffer) {
            csiSamplesBuffer.clear()
        }
        synchronized(bitrateSamplesBuffer) {
            bitrateSamplesBuffer.clear()
        }
        
        // Reset the first timestamp
        firstTimestamp = null
        
        // Reset all state flows to empty values
        _csiTimeline.value = emptyMap()
        _bitrateTimeline.value = emptyMap()
        _alignedFeatures.value = emptyList()  // Clear aligned features too
        _csiStats.value = null
        _bitrateStats.value = null
        _csiPcaFeatures.value = null
        _bitratePcaFeatures.value = null
        _isProcessing.value = false
        
        // Clear tracked file positions in PcapProcessor
        PcapProcessor.clearTrackedPositions()
        
        log("All data buffers and states have been cleared")
    }
    
    /**
     * Force a complete reset of the detector, including killing all processes
     * This is used when switching between devices to ensure a clean slate
     */
    fun forceReset() {
        log("Forcing complete detector reset")

        // First stop any active captures
        stopDetection()

        // Use shellExecutor to forcefully kill any lingering processes
        shellExecutor.execute("pkill tcpdump") { output, exitCode ->
            log("Killed tcpdump processes: $output (exit code: $exitCode)")
        }

        shellExecutor.execute("pkill nexutil") { output, exitCode ->
            log("Killed nexutil processes: $output (exit code: $exitCode)")
        }

        // Clear all buffers and state
        clearBuffers()

        // Reset capture files in TcpdumpManager
        tcpdumpManager.resetCaptureFiles()

        log("Detector has been completely reset")
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

        fun getInstance(etherSrc: String = "00:00:00:00:00:00"): SpyCameraDetector {
            return synchronized(this) {
                // If we have an existing instance but the MAC changed, completely replace it
                if (instance != null && instance!!.etherSrc != etherSrc) {
                    Log.d(TAG, "MAC address changed from ${instance!!.etherSrc} to $etherSrc, creating new detector instance")
                    // Force reset the old instance first to clean up resources
                    instance!!.forceReset()
                    // Explicitly set instance to null to ensure complete replacement
                    instance = null
                    // Create a completely new instance with the new MAC address
                    instance = SpyCameraDetector(etherSrc)
                    Log.d(TAG, "New detector instance created with MAC: $etherSrc")
                } else if (instance == null) {
                    Log.d(TAG, "Creating first detector instance with MAC: $etherSrc")
                    instance = SpyCameraDetector(etherSrc)
                }
                instance!!
            }
        }
    }
}