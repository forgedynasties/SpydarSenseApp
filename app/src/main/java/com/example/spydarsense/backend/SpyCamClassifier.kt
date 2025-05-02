package com.example.spydarsense.backend

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.min

/**
 * SpyCamClassifier analyzes CSI and bitrate data to detect if a device is likely a spy camera.
 * Uses TensorFlow Lite models for classification based on neural network predictions.
 */
class SpyCamClassifier(private var context: Context? = null) {
    
    // TFLite interpreters
    private var csiInterpreter: Any? = null
    private var brInterpreter: Any? = null
    
    // Flag to indicate if models are loaded
    private var modelsLoaded = false
    
    // Flag to indicate if TFLite is available on this device
    private var tfliteAvailable = false
    
    // Sequence length for input to the models
    private val sequenceLength = 30
    
    // Window parameters for sliding window analysis
    private val windowSizeSeconds = 3.0
    private val strideSeconds = 0.5  // Changed from 1.0 to 0.1 for finer-grained analysis
    
    // Input and output buffer sizes
    private val inputSize = sequenceLength * 4 // 4 bytes per float
    private val outputSize = 1 * 4 // 1 float output
    
    // Fallback parameters for traditional threshold-based detection
    // Used if TFLite models are not available
    private val fallbackCorrelationThreshold = 2
    
    /**
     * Classification result data class 
     */
    data class ClassificationResult(
        val isSpyCam: Boolean,
        val confidence: Float,
        val detectionPoints: List<Double>,
        val csiChangePoints: List<Double>,
        val bitrateChangePoints: List<Double>,
        val message: String,
        val modelUsed: String = "N/A", // Indicates which model was used for classification
        val windowResults: List<WindowResult> = emptyList() // Added window-by-window results
    )
    
    /**
     * Data class representing an individual window classification result
     */
    data class WindowResult(
        val startTime: Double,
        val endTime: Double,
        val csiConfidence: Float,
        val bitrateConfidence: Float,
        val combinedConfidence: Float,
        val csiClass: Int,     // 1 if csiConfidence >= 0.5, else 0
        val bitrateClass: Int, // 1 if bitrateConfidence >= 0.5, else 0
        val windowClass: Int   // csiClass AND bitrateClass (1 only if both are 1)
    )
    
    init {
        // Check if TensorFlow Lite is available
        tfliteAvailable = isTFLiteAvailable()
        
        // Load TFLite models if context is provided and TFLite is available
        context?.let {
            if (tfliteAvailable) {
                initializeWithContext(it)
            } else {
                Log.d(TAG, "TensorFlow Lite is not available, will use fallback detection")
            }
        } ?: run {
            Log.d(TAG, "No context provided, will use fallback threshold-based detection until initialized")
        }
    }
    
    /**
     * Check if TensorFlow Lite is available on this device
     */
    private fun isTFLiteAvailable(): Boolean {
        return try {
            // First check if the TensorFlow Lite class is available
            val tfLiteClass = Class.forName("org.tensorflow.lite.Interpreter")
            
            // Now try to check if the native functionality is available by calling a static method
            try {
                val tfLiteBaseClass = Class.forName("org.tensorflow.lite.TensorFlowLite")
                val nativeInitMethod = tfLiteBaseClass.getMethod("init")
                nativeInitMethod.invoke(null)
                
                Log.d(TAG, "TensorFlow Lite is available and native libraries loaded")
                true
            } catch (e: Exception) {
                // The class exists but native libraries aren't available
                Log.d(TAG, "TensorFlow Lite classes found but native libraries not available: ${e.message}")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "TensorFlow Lite is not available: ${e.message}")
            false
        }
    }
    
    /**
     * Initialize the classifier with a context
     * This allows the classifier to be created without a context and initialized later
     * Useful when working with Jetpack Compose where Context is only available in @Composable functions
     */
    fun initializeWithContext(newContext: Context) {
        if (modelsLoaded) {
            // Already initialized, skip
            return
        }
        
        this.context = newContext
        
        // Update TFLite availability check to ensure we're not using stale information
        tfliteAvailable = isTFLiteAvailable()
        
        // Skip TFLite initialization if it's not available
        if (!tfliteAvailable) {
            Log.d(TAG, "Skipping TFLite initialization as it's not available")
            return
        }
        
        try {
            // Try to load the models but catch any exceptions
            safeLoadModels(newContext.assets)
            modelsLoaded = csiInterpreter != null && brInterpreter != null
            
            if (modelsLoaded) {
                Log.d(TAG, "TFLite models loaded successfully")
            } else {
                Log.d(TAG, "Failed to load TFLite models, will use fallback")
                // Explicitly set TFLite as unavailable if model loading failed
                tfliteAvailable = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TFLite models: ${e.message}")
            e.printStackTrace()
            modelsLoaded = false
            // Explicitly set TFLite as unavailable if an exception occurred
            tfliteAvailable = false
        }
    }
    
    /**
     * Safely load TFLite models from assets with error handling
     */
    private fun safeLoadModels(assetManager: AssetManager) {
        try {
            Log.d(TAG, "Available files in assets: ${assetManager.list("")?.joinToString()}")
            
            // Try accessing the model files first to verify they exist
            try {
                val csiModelFile = assetManager.openFd("csi_model.tflite")
                csiModelFile.close()
                
                val brModelFile = assetManager.openFd("br_model.tflite")
                brModelFile.close()
                
                Log.d(TAG, "Successfully verified model files exist in assets")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to access model files: ${e.message}")
                throw e // Re-throw to abort the loading process
            }
            
            // Load CSI model
            val csiModelBuffer = loadModelFile(assetManager, "csi_model.tflite")
            
            // Create TFLite interpreter using reflection
            val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
            val optionsClass = Class.forName("org.tensorflow.lite.Interpreter\$Options")
            val optionsConstructor = optionsClass.getConstructor()
            val options = optionsConstructor.newInstance()
            
            // Create the interpreter with ByteBuffer only (simpler constructor)
            val constructor = interpreterClass.getConstructor(ByteBuffer::class.java)
            try {
                csiInterpreter = constructor.newInstance(csiModelBuffer)
                Log.d(TAG, "CSI model loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize CSI model interpreter: ${e.message}")
                throw e // Re-throw to abort the loading process
            }
            
            // Load BR model
            val brModelBuffer = loadModelFile(assetManager, "br_model.tflite")
            try {
                brInterpreter = constructor.newInstance(brModelBuffer)
                Log.d(TAG, "BR model loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize BR model interpreter: ${e.message}")
                throw e // Re-throw to abort the loading process
            }
            
            modelsLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models: ${e.message}")
            e.printStackTrace()
            
            // Clean up any partially loaded resources
            csiInterpreter = null
            brInterpreter = null
            modelsLoaded = false
            
            // Re-throw to signal failure to the caller
            throw e
        }
    }
    
    /**
     * Load a TFLite model file from assets
     */
    private fun loadModelFile(assetManager: AssetManager, modelPath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * Analyze aligned features to determine if the device is a spy camera
     * @param features List of aligned CSI and bitrate features
     * @return ClassificationResult with determination and detected points
     */
    fun analyze(features: List<AlignedFeature>): ClassificationResult {
        if (features.size < 5) {
            return ClassificationResult(
                isSpyCam = false,
                confidence = 0f,
                detectionPoints = emptyList(),
                csiChangePoints = emptyList(),
                bitrateChangePoints = emptyList(),
                message = "Insufficient data for analysis",
                modelUsed = "N/A"
            )
        }
        
        Log.d(TAG, "Analyzing ${features.size} features for spy camera detection")
        
        // If TFLite models are loaded, try to use them for classification
        if (tfliteAvailable && modelsLoaded && csiInterpreter != null && brInterpreter != null) {
            try {
                return analyzeWithTFLite(features)
            } catch (e: Exception) {
                Log.e(TAG, "TFLite analysis failed, falling back to threshold-based: ${e.message}")
                e.printStackTrace()
                // Fall back to traditional analysis if TFLite fails
            }
        }
        
        // Fallback to traditional threshold-based detection
        Log.d(TAG, "Using fallback threshold-based detection")
        return analyzeWithThresholds(features)
    }
    
    /**
     * Analyze features using TFLite models
     */
    private fun analyzeWithTFLite(features: List<AlignedFeature>): ClassificationResult {
        if (!tfliteAvailable || csiInterpreter == null || brInterpreter == null) {
            throw IllegalStateException("TFLite not available or models not loaded")
        }
        
        try {
            // Need to use reflection to access TFLite since we're avoiding direct imports
            val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
            val runMethod = interpreterClass.getMethod("run", Any::class.java, Any::class.java)
            
            // Create windows of features for analysis
            val windows = createSlidingWindows(features)
            Log.d(TAG, "Created ${windows.size} sliding windows for analysis")
            
            if (windows.isEmpty()) {
                Log.d(TAG, "No valid windows could be created, falling back to threshold-based")
                return analyzeWithThresholds(features)
            }
            
            // Store predictions for each window
            val windowPredictions = mutableListOf<Float>()
            val windowTimestamps = mutableListOf<Double>()
            
            // Process each window
            for (window in windows) {
                // Prepare input data for this window
                val csiSequence = prepareSequence(window.map { it.csiFeature }, true)
                val brSequence = prepareSequence(window.map { it.bitrateFeature.toFloat() }, false)
                
                // Run inference on CSI model
                val csiResult = safeRunInference(csiInterpreter!!, csiSequence, runMethod)
                
                // Run inference on Bitrate model
                val brResult = safeRunInference(brInterpreter!!, brSequence, runMethod)
                
                // Calculate combined confidence score for this window
                val combinedConfidence = (csiResult * 0.6f) + (brResult * 0.4f)
                windowPredictions.add(combinedConfidence)
                
                // Store the timestamp of the end of this window for detection point
                windowTimestamps.add(window.last().timestamp)
            }
            
            Log.d(TAG, "Window predictions: $windowPredictions")
            
            // Calculate the final confidence as the maximum of window predictions
            // This helps catch even brief spy camera activity
            val finalConfidence = windowPredictions.maxOrNull() ?: 0f
            
            // Calculate average confidence for more robust detection
            val avgConfidence = if (windowPredictions.isNotEmpty()) 
                windowPredictions.sum() / windowPredictions.size else 0f
                
            // Count high confidence windows (>0.65)
            val highConfidenceCount = windowPredictions.count { it > 0.65f }
            
            // Final classification logic
            val isSpyCam = finalConfidence > 0.65f || 
                          (avgConfidence > 0.5f && highConfidenceCount >= 2)
            
            // Find indices of high confidence windows for detection points
            val detectionPointIndices = windowPredictions.mapIndexedNotNull { index, confidence -> 
                if (confidence > 0.65f) index else null 
            }
            val detectionPoints = detectionPointIndices.map { windowTimestamps[it] }
            
            // For visualization, still detect change points with traditional method
            val csiDeltas = calculateCsiDeltas(features)
            val bitrateDeltas = calculateBitrateDeltas(features)
            
            val csiThreshold = determineCsiThreshold(csiDeltas)
            val bitrateThreshold = determineBitrateThreshold(bitrateDeltas)
            
            val csiChangePoints = findChangePoints(features, csiDeltas, csiThreshold)
            val bitrateChangePoints = findChangePoints(features, bitrateDeltas, bitrateThreshold)
            
            // Generate appropriate message based on model confidence
            val message = when {
                highConfidenceCount >= 3 -> 
                    "High confidence spy camera detection: Multiple time windows show spy camera activity"
                highConfidenceCount >= 2 || finalConfidence > 0.8f -> 
                    "Medium-high confidence spy camera detection: Several time windows indicate spy camera activity"
                highConfidenceCount == 1 || finalConfidence > 0.65f -> 
                    "Medium confidence spy camera detection: At least one time window shows strong spy camera indicators"
                avgConfidence > 0.5f -> 
                    "Low confidence spy camera detection: Weak indicators across multiple time windows"
                else -> 
                    "Not a spy camera: No significant spy camera behavior detected in any time window"
            }
            
            return ClassificationResult(
                isSpyCam = isSpyCam,
                confidence = finalConfidence,
                detectionPoints = detectionPoints,
                csiChangePoints = csiChangePoints.map { features[it.toInt()].timestamp },
                bitrateChangePoints = bitrateChangePoints.map { features[it.toInt()].timestamp },
                message = message,
                modelUsed = "TFLite Neural Network (Sliding Window)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during TFLite inference: ${e.message}")
            e.printStackTrace()
            
            // Fall back to threshold-based detection if TFLite fails
            Log.d(TAG, "Falling back to threshold-based detection")
            return analyzeWithThresholds(features)
        }
    }
    
    /**
     * Create sliding windows of features for analysis
     * Each window is 3 seconds of data, with a stride of 0.1 second
     */
    private fun createSlidingWindows(features: List<AlignedFeature>): List<List<AlignedFeature>> {
        if (features.size < 3) return emptyList()
        
        val windows = mutableListOf<List<AlignedFeature>>()
        
        // Get the start and end timestamps
        val startTime = features.first().timestamp
        val endTime = features.last().timestamp
        
        // Make sure we have enough data
        val totalDuration = endTime - startTime
        if (totalDuration < windowSizeSeconds) {
            // Just return a single window with all data if we don't have enough for sliding windows
            return listOf(features)
        }
        
        Log.d(TAG, "Creating sliding windows - total duration: ${totalDuration}s, window size: ${windowSizeSeconds}s, stride: ${strideSeconds}s")
        Log.d(TAG, "Expected number of windows: ${((totalDuration - windowSizeSeconds) / strideSeconds + 1).toInt()}")
        
        // Create windows with sliding approach
        var windowStart = startTime
        var windowCount = 0
        while (windowStart + windowSizeSeconds <= endTime) {
            val windowEnd = windowStart + windowSizeSeconds
            
            // Find features within this window
            val windowFeatures = features.filter { 
                it.timestamp >= windowStart && it.timestamp <= windowEnd 
            }
            
            // Only add window if it has enough data points
            if (windowFeatures.size >= 3) {
                windows.add(windowFeatures)
                windowCount++
                Log.d(TAG, "Window #$windowCount: ${windowFeatures.size} features, start=${windowStart.format(2)}s, end=${windowEnd.format(2)}s")
            } else {
                Log.d(TAG, "SKIPPED Window at ${windowStart.format(2)}s-${windowEnd.format(2)}s: insufficient features (${windowFeatures.size})")
            }
            
            // Check if there's enough time left for another stride
            if (endTime - windowEnd < strideSeconds) {
                Log.d(TAG, "Stopping window creation: time left (${(endTime - windowEnd).format(2)}s) is less than stride (${strideSeconds}s)")
                break;
            }
            
            // Slide the window by the stride amount
            windowStart += strideSeconds
        }
        
        Log.d(TAG, "Created ${windows.size} sliding windows with ${windowSizeSeconds}s size and ${strideSeconds}s stride")
        return windows
    }

    // Helper extension function to format double with fixed decimal places
    private fun Double.format(decimals: Int) = String.format("%.${decimals}f", this)
    
    /**
     * Safely run inference using reflection to avoid direct TFLite dependencies
     */
    private fun safeRunInference(interpreter: Any, inputData: FloatArray, runMethod: java.lang.reflect.Method): Float {
        // Prepare input buffer
        val inputBuffer = ByteBuffer.allocateDirect(inputSize)
            .order(ByteOrder.nativeOrder())
        
        // Fill input buffer
        for (value in inputData) {
            inputBuffer.putFloat(value)
        }
        inputBuffer.rewind()
        
        // Prepare output buffer
        val outputBuffer = ByteBuffer.allocateDirect(outputSize)
            .order(ByteOrder.nativeOrder())
        
        try {
            // Run inference using reflection
            runMethod.invoke(interpreter, inputBuffer, outputBuffer)
            
            // Extract result
            outputBuffer.rewind()
            return outputBuffer.float
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}")
            e.printStackTrace()
            // Return default value on error
            return 0.5f
        }
    }
    
    /**
     * Prepare a sequence for TFLite model inference
     * Normalizes length to sequenceLength and scales values appropriately
     */
    private fun prepareSequence(data: List<Float>, isCsi: Boolean): FloatArray {
        val result = FloatArray(sequenceLength)
        
        // If data is shorter than required, pad with zeros at the end
        // If data is longer, take the most recent values
        val startIdx = maxOf(0, data.size - sequenceLength)
        val usableLength = minOf(data.size, sequenceLength)
        
        // Initialize with zeros
        result.fill(0f)
        
        // Copy available data
        for (i in 0 until usableLength) {
            val value = data[startIdx + i]
            
            // Scale the data differently based on whether it's CSI or Bitrate
            result[i] = if (isCsi) {
                // CSI scaling - normalize to range suitable for the model
                val scaledValue = value / 1000f  // assuming CSI values are typically 0-100
                // Log scaling for debugging
                if (i == 0 || i == usableLength-1) {
                    Log.d(TAG, "CSI scaling: original=${value}, scaled=${scaledValue}")
                }
                scaledValue
            } else {
                // Bitrate scaling - normalize to range suitable for the model
                val scaledValue = value / 1e3f  // assuming bitrate is in Kbps (1e3 to 1e7)
                // Log scaling for debugging
                if (i == 0 || i == usableLength-1) {
                    Log.d(TAG, "Bitrate scaling: original=${value}, scaled=${scaledValue}")
                }
                scaledValue
            }
        }
        
        Log.d(TAG, "Prepared sequence of length $usableLength (padded to $sequenceLength): ${result.take(min(5, usableLength))}...")
        
        return result
    }
    
    /**
     * Traditional threshold-based detection method (fallback if TFLite not available)
     */
    private fun analyzeWithThresholds(features: List<AlignedFeature>): ClassificationResult {
        // Step 1: Calculate deltas (changes between consecutive measurements)
        val csiDeltas = calculateCsiDeltas(features)
        val bitrateDeltas = calculateBitrateDeltas(features)
        
        // Step 2: Find significant change points using thresholds
        val csiThreshold = determineCsiThreshold(csiDeltas)
        val bitrateThreshold = determineBitrateThreshold(bitrateDeltas)
        
        Log.d(TAG, "Using thresholds - CSI: $csiThreshold, Bitrate: $bitrateThreshold")
        
        val csiChangePoints = findChangePoints(features, csiDeltas, csiThreshold)
        val bitrateChangePoints = findChangePoints(features, bitrateDeltas, bitrateThreshold)
        
        Log.d(TAG, "Found ${csiChangePoints.size} CSI change points and ${bitrateChangePoints.size} bitrate change points")
        
        // Step 3: Find correlated changes (changes that happen close in time)
        val correlatedChangePoints = findCorrelatedChangePoints(
            csiChangePoints,
            bitrateChangePoints
        )
        
        Log.d(TAG, "Found ${correlatedChangePoints.size} correlated change points")
        
        // Step 4: Make classification decision based on number of correlated changes
        val isSpyCam = correlatedChangePoints.size >= fallbackCorrelationThreshold
        
        // Calculate confidence based on number of correlated changes
        val confidence = if (correlatedChangePoints.isEmpty()) {
            0f
        } else {
            // Cap confidence at 80% for threshold-based detection
            minOf(0.8f, correlatedChangePoints.size / 10f)
        }
        
        // Generate appropriate message
        val message = when {
            correlatedChangePoints.size >= 5 -> 
                "High confidence spy camera detection: Multiple synchronized signal changes detected"
            correlatedChangePoints.size >= 3 -> 
                "Medium confidence spy camera detection: Several synchronized signal changes detected"
            correlatedChangePoints.size >= fallbackCorrelationThreshold -> 
                "Low confidence spy camera detection: A few synchronized signal changes detected"
            correlatedChangePoints.size == 1 -> 
                "Inconclusive: One synchronized change detected - insufficient for classification"
            csiChangePoints.size >= 3 && bitrateChangePoints.isEmpty() -> 
                "Not a spy camera: CSI changes detected but no corresponding bitrate changes"
            bitrateChangePoints.size >= 3 && csiChangePoints.isEmpty() -> 
                "Not a spy camera: Bitrate changes detected but no corresponding CSI changes"
            else -> 
                "Not a spy camera: No significant correlated changes detected"
        }
        
        return ClassificationResult(
            isSpyCam = isSpyCam,
            confidence = confidence,
            detectionPoints = correlatedChangePoints.map { features[it.toInt()].timestamp },
            csiChangePoints = csiChangePoints.map { features[it.toInt()].timestamp },
            bitrateChangePoints = bitrateChangePoints.map { features[it.toInt()].timestamp },
            message = message,
            modelUsed = "Threshold-based"
        )
    }
    
    /**
     * Calculate deltas for CSI features (changes between consecutive measurements)
     */
    private fun calculateCsiDeltas(features: List<AlignedFeature>): List<Float> {
        val deltas = mutableListOf<Float>()
        for (i in 1 until features.size) {
            val delta = abs(features[i].csiFeature - features[i-1].csiFeature)
            deltas.add(delta)
        }
        return deltas
    }
    
    /**
     * Calculate deltas for bitrate features (changes between consecutive measurements)
     */
    private fun calculateBitrateDeltas(features: List<AlignedFeature>): List<Int> {
        val deltas = mutableListOf<Int>()
        for (i in 1 until features.size) {
            val delta = abs(features[i].bitrateFeature - features[i-1].bitrateFeature)
            deltas.add(delta)
        }
        return deltas
    }
    
    /**
     * Determine threshold for significant CSI changes
     * Uses a percentile-based approach to adapt to the data
     */
    private fun determineCsiThreshold(deltas: List<Float>): Float {
        if (deltas.isEmpty()) return 0.5f
        
        // Sort deltas and take the 75th percentile as the threshold
        val sortedDeltas = deltas.sorted()
        val percentileIndex = (sortedDeltas.size * 0.75).toInt().coerceIn(0, sortedDeltas.size - 1)
        val percentileThreshold = sortedDeltas[percentileIndex]
        
        // Use at least 0.5 as minimum threshold to avoid noise
        return maxOf(0.5f, percentileThreshold)
    }
    
    /**
     * Determine threshold for significant bitrate changes
     * Uses a percentile-based approach to adapt to the data
     */
    private fun determineBitrateThreshold(deltas: List<Int>): Int {
        if (deltas.isEmpty()) return 100
        
        // Sort deltas and take the 75th percentile as the threshold
        val sortedDeltas = deltas.sorted()
        val percentileIndex = (sortedDeltas.size * 0.75).toInt().coerceIn(0, sortedDeltas.size - 1)
        val percentileThreshold = sortedDeltas[percentileIndex]
        
        // Use at least 100 Kbps as minimum threshold to avoid noise
        return maxOf(100, percentileThreshold)
    }
    
    /**
     * Find indices where deltas exceed the threshold
     */
    private fun findChangePoints(
        features: List<AlignedFeature>,
        deltas: List<Float>,
        threshold: Float
    ): List<Int> {
        val changePoints = mutableListOf<Int>()
        for (i in deltas.indices) {
            if (deltas[i] > threshold) {
                // Store index of the feature AFTER the change
                changePoints.add(i + 1)
            }
        }
        return changePoints
    }
    
    /**
     * Find indices where deltas exceed the threshold
     */
    private fun findChangePoints(
        features: List<AlignedFeature>,
        deltas: List<Int>,
        threshold: Int
    ): List<Int> {
        val changePoints = mutableListOf<Int>()
        for (i in deltas.indices) {
            if (deltas[i] > threshold) {
                // Store index of the feature AFTER the change
                changePoints.add(i + 1)
            }
        }
        return changePoints
    }
    
    /**
     * Find timestamps where changes in CSI and bitrate occur close together
     */
    private fun findCorrelatedChangePoints(
        csiChangePoints: List<Int>,
        bitrateChangePoints: List<Int>
    ): List<Double> {
        val correlatedPoints = mutableListOf<Int>()
        
        // Consider points correlated if they're within 2 positions of each other
        val maxDistance = 2
        
        for (csiPoint in csiChangePoints) {
            for (bitratePoint in bitrateChangePoints) {
                if (abs(csiPoint - bitratePoint) <= maxDistance) {
                    // Use the later point as the correlation point
                    correlatedPoints.add(maxOf(csiPoint, bitratePoint))
                    break
                }
            }
        }
        
        // Return sorted and distinct list
        return correlatedPoints.sorted().distinct().map { it.toDouble() }
    }
    
    /**
     * Analyze features with detailed window-by-window results
     */
    fun analyzeDetailed(features: List<AlignedFeature>): ClassificationResult {
        if (features.size < 5) {
            return ClassificationResult(
                isSpyCam = false,
                confidence = 0f,
                detectionPoints = emptyList(),
                csiChangePoints = emptyList(),
                bitrateChangePoints = emptyList(),
                message = "Insufficient data for analysis",
                modelUsed = "N/A"
            )
        }
        
        Log.d(TAG, "Performing detailed analysis on ${features.size} features")
        
        // If TFLite models are loaded, try to use them for classification
        if (tfliteAvailable && modelsLoaded && csiInterpreter != null && brInterpreter != null) {
            try {
                // Create windows of features for analysis
                val windows = createSlidingWindows(features)
                Log.d(TAG, "Created ${windows.size} sliding windows for detailed analysis")
                
                if (windows.isEmpty()) {
                    Log.d(TAG, "No valid windows could be created, falling back to threshold-based")
                    return analyzeWithThresholds(features)
                }
                
                // Get interpreter class through reflection
                val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
                val runMethod = interpreterClass.getMethod("run", Any::class.java, Any::class.java)
                
                // Process each window and store detailed results
                val windowResults = mutableListOf<WindowResult>()
                val windowPredictions = mutableListOf<Float>()
                val windowTimestamps = mutableListOf<Double>()
                
                // Calculate window class counts for majority voting
                var spyCameraWindowCount = 0
                var normalWindowCount = 0
                
                // Add logging for window classifications
                Log.d(TAG, "====== WINDOW-BY-WINDOW CLASSIFICATION RESULTS ======")
                Log.d(TAG, "Window # | Start-End | CSI Class | BR Class | Window Class | CSI Conf | BR Conf | Combined")
                
                for ((windowIndex, window) in windows.withIndex()) {
                    // Get window start and end times
                    val startTime = window.first().timestamp
                    val endTime = window.last().timestamp
                    
                    // Prepare input data for this window
                    val csiSequence = prepareSequence(window.map { it.csiFeature }, true)
                    val brSequence = prepareSequence(window.map { it.bitrateFeature.toFloat() }, false)
                    
                    // Run inference on CSI model
                    val csiResult = safeRunInference(csiInterpreter!!, csiSequence, runMethod)
                    
                    // Run inference on Bitrate model
                    val brResult = safeRunInference(brInterpreter!!, brSequence, runMethod)
                    
                    // Create class values (0 or 1)
                    val csiClass = if (csiResult >= 0.5f) 1 else 0
                    val bitrateClass = if (brResult >= 0.5f) 1 else 0
                    
                    // Logical AND for the window class (both must be 1 for window to be classified as spy camera)
                    val windowClass = if (csiClass == 1 && bitrateClass == 1) 1 else 0
                    
                    // Update window counts
                    if (windowClass == 1) {
                        spyCameraWindowCount++
                    } else {
                        normalWindowCount++
                    }
                    
                    // Calculate combined confidence
                    val combinedConfidence = (csiResult * 0.6f) + (brResult * 0.4f)
                    windowPredictions.add(combinedConfidence)
                    windowTimestamps.add(endTime)
                    
                    // Store detailed window result
                    windowResults.add(
                        WindowResult(
                            startTime = startTime,
                            endTime = endTime,
                            csiConfidence = csiResult,
                            bitrateConfidence = brResult,
                            combinedConfidence = combinedConfidence,
                            csiClass = csiClass,
                            bitrateClass = bitrateClass,
                            windowClass = windowClass
                        )
                    )
                    
                    // Log detailed window classification results
                    Log.d(TAG, String.format("%-8d | %5.1f-%5.1f | %-9d | %-7d | %-11d | %7.3f | %6.3f | %7.3f",
                        windowIndex + 1,
                        startTime,
                        endTime,
                        csiClass,
                        bitrateClass,
                        windowClass,
                        csiResult,
                        brResult,
                        combinedConfidence
                    ))
                }
                
                // Log summary of window classifications
                Log.d(TAG, "====== WINDOW CLASSIFICATION SUMMARY ======")
                Log.d(TAG, "Total windows analyzed: ${windows.size}")
                Log.d(TAG, "Spy camera windows: $spyCameraWindowCount")
                Log.d(TAG, "Normal windows: $normalWindowCount")
                Log.d(TAG, "Spy camera ratio: ${(spyCameraWindowCount.toFloat() / windows.size).toDouble().format(3)}")
                // Calculate final confidence based on all windows
                val finalConfidence = windowPredictions.maxOrNull() ?: 0f
                
                // Determine final classification by majority voting
                val isSpyCam = spyCameraWindowCount > normalWindowCount
                
                // Find detection points from high confidence windows
                val detectionPointIndices = windowPredictions.mapIndexedNotNull { index, confidence -> 
                    if (confidence > 0.65f) index else null 
                }
                val detectionPoints = detectionPointIndices.map { windowTimestamps[it] }
                
                // For visualization, still detect change points with traditional method
                val csiDeltas = calculateCsiDeltas(features)
                val bitrateDeltas = calculateBitrateDeltas(features)
                
                val csiThreshold = determineCsiThreshold(csiDeltas)
                val bitrateThreshold = determineBitrateThreshold(bitrateDeltas)
                
                val csiChangePoints = findChangePoints(features, csiDeltas, csiThreshold)
                val bitrateChangePoints = findChangePoints(features, bitrateDeltas, bitrateThreshold)
                
                // Generate appropriate message based on window counts
                val message = when {
                    spyCameraWindowCount >= 3 -> 
                        "High confidence spy camera detection: ${spyCameraWindowCount} of ${windows.size} windows show spy camera activity"
                    spyCameraWindowCount >= 2 -> 
                        "Medium confidence spy camera detection: ${spyCameraWindowCount} of ${windows.size} windows show spy camera activity"
                    spyCameraWindowCount == 1 -> 
                        "Low confidence spy camera detection: Only 1 of ${windows.size} windows shows spy camera activity"
                    else -> 
                        "Not a spy camera: 0 of ${windows.size} windows show spy camera activity"
                }
                
                return ClassificationResult(
                    isSpyCam = isSpyCam,
                    confidence = finalConfidence,
                    detectionPoints = detectionPoints,
                    csiChangePoints = csiChangePoints.map { features[it.toInt()].timestamp },
                    bitrateChangePoints = bitrateChangePoints.map { features[it.toInt()].timestamp },
                    message = message,
                    modelUsed = "TFLite Neural Network (Window-by-Window)",
                    windowResults = windowResults
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during detailed TFLite inference: ${e.message}")
                e.printStackTrace()
                
                // Fall back to threshold-based detection if TFLite fails
                Log.d(TAG, "Falling back to threshold-based detection")
                return analyzeWithThresholds(features)
            }
        } else {
            // Use threshold-based detection if TFLite is not available
            Log.d(TAG, "TFLite not available, using threshold-based detection")
            return analyzeWithThresholds(features)
        }
    }
    
    companion object {
        private const val TAG = "SpyCamClassifier"
    }
}
