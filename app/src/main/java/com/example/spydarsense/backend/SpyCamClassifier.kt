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
        val modelUsed: String = "N/A" // Indicates which model was used for classification
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
            
            // Prepare input data - normalize sequences to the right length and scale
            val csiSequence = prepareSequence(features.map { it.csiFeature }, true)
            val brSequence = prepareSequence(features.map { it.bitrateFeature.toFloat() }, false)
            
            // Run inference on CSI model
            val csiResult = safeRunInference(csiInterpreter!!, csiSequence, runMethod)
            
            // Run inference on Bitrate model
            val brResult = safeRunInference(brInterpreter!!, brSequence, runMethod)
            
            Log.d(TAG, "TFLite inference results - CSI: $csiResult, BR: $brResult")
            
            // Calculate combined confidence score
            // Average of both models, weighted slightly toward CSI model (60/40 split)
            val combinedConfidence = (csiResult * 0.6f) + (brResult * 0.4f)
            
            // Determine if it's a spy camera based on confidence threshold
            val isSpyCam = combinedConfidence > 0.65f
            
            // For visualization purposes, still detect change points with traditional method
            val csiDeltas = calculateCsiDeltas(features)
            val bitrateDeltas = calculateBitrateDeltas(features)
            
            val csiThreshold = determineCsiThreshold(csiDeltas)
            val bitrateThreshold = determineBitrateThreshold(bitrateDeltas)
            
            val csiChangePoints = findChangePoints(features, csiDeltas, csiThreshold)
            val bitrateChangePoints = findChangePoints(features, bitrateDeltas, bitrateThreshold)
            
            val correlatedChangePoints = findCorrelatedChangePoints(
                csiChangePoints, 
                bitrateChangePoints
            )
            
            // Generate appropriate message based on model confidence
            val message = when {
                combinedConfidence > 0.85f -> 
                    "High confidence spy camera detection: Neural network analysis indicates spy camera activity"
                combinedConfidence > 0.75f -> 
                    "Medium confidence spy camera detection: Neural network analysis suggests spy camera activity"
                combinedConfidence > 0.65f -> 
                    "Low confidence spy camera detection: Neural network analysis indicates possible spy camera"
                combinedConfidence > 0.5f -> 
                    "Inconclusive: Some spy camera characteristics detected but confidence is low"
                else -> 
                    "Not a spy camera: Neural network analysis shows normal device behavior"
            }
            
            return ClassificationResult(
                isSpyCam = isSpyCam,
                confidence = combinedConfidence,
                detectionPoints = correlatedChangePoints.map { features[it.toInt()].timestamp },
                csiChangePoints = csiChangePoints.map { features[it.toInt()].timestamp },
                bitrateChangePoints = bitrateChangePoints.map { features[it.toInt()].timestamp },
                message = message,
                modelUsed = "TFLite Neural Network"
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
                value / 100f  // assuming CSI values are typically 0-100
            } else {
                // Bitrate scaling - normalize to range suitable for the model
                value / 1e4f  // assuming bitrate is in Kbps (1e3 to 1e7)
            }
        }
        
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
    
    companion object {
        private const val TAG = "SpyCamClassifier"
    }
}
