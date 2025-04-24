package com.example.spydarsense.backend

import android.util.Log
import kotlin.math.abs

/**
 * SpyCamClassifier analyzes CSI and bitrate data to detect if a device is likely a spy camera.
 * The current implementation uses threshold-based detection to identify correlated sudden changes
 * in both CSI and bitrate signals, which are characteristic of spy cameras.
 */
class SpyCamClassifier {
    
    /**
     * Classification result data class 
     */
    data class ClassificationResult(
        val isSpyCam: Boolean,
        val confidence: Float,
        val detectionPoints: List<Double>,
        val csiChangePoints: List<Double>,
        val bitrateChangePoints: List<Double>,
        val message: String
    )
    
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
                message = "Insufficient data for analysis"
            )
        }
        
        Log.d(TAG, "Analyzing ${features.size} features for spy camera detection")
        
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
        val correlationThreshold = 2 // Minimum number of correlated changes to classify as spy cam
        val isSpyCam = correlatedChangePoints.size >= correlationThreshold
        
        // Calculate confidence based on number of correlated changes
        val confidence = if (correlatedChangePoints.isEmpty()) {
            0f
        } else {
            // Cap confidence at 90% (leaving room for ML improvements)
            minOf(0.9f, correlatedChangePoints.size / 10f)
        }
        
        // Generate appropriate message
        val message = when {
            correlatedChangePoints.size >= 5 -> 
                "High confidence spy camera detection: Multiple synchronized signal changes detected"
            correlatedChangePoints.size >= 3 -> 
                "Medium confidence spy camera detection: Several synchronized signal changes detected"
            correlatedChangePoints.size >= correlationThreshold -> 
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
            detectionPoints = correlatedChangePoints,
            csiChangePoints = csiChangePoints.map { features[it.toInt()].timestamp },
            bitrateChangePoints = bitrateChangePoints.map { features[it.toInt()].timestamp },
            message = message
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
