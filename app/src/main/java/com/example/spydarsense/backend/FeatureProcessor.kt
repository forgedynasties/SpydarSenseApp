package com.example.spydarsense.backend

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data class representing aligned features at a specific timestamp
 */
data class AlignedFeature(
    val timestamp: Double,
    val csiFeature: Float,
    val bitrateFeature: Int
)

class FeatureProcessor {

    /**
     * Processes raw data to create aligned features at uniform timestamps
     * starting from 0 with 0.1 increments
     */
    suspend fun processFeatures(
        csiTimeline: Map<Double, List<Float>>,
        bitrateTimeline: Map<Double, Int>
    ): List<AlignedFeature> = withContext(Dispatchers.Default) {
        if (csiTimeline.isEmpty() && bitrateTimeline.isEmpty()) {
            return@withContext emptyList()
        }

        // Find start and end timestamps
        val allTimestamps = (csiTimeline.keys + bitrateTimeline.keys).toSet()
        if (allTimestamps.isEmpty()) {
            return@withContext emptyList()
        }

        val startTime = allTimestamps.minOrNull() ?: 0.0
        val endTime = allTimestamps.maxOrNull() ?: 0.0

        // Create uniform timeline with 0.1 increments starting from 0
        val timelineStep = 0.1
        val uniformTimeline = generateSequence(0.0) { it + timelineStep }
            .takeWhile { it <= endTime - startTime }
            .toList()

        // Process CSI data - create map with shifted timestamps
        val shiftedCsiMap = csiTimeline.mapKeys { (key, _) -> 
            (key - startTime).let { if (it < 0) 0.0 else it }
        }
        
        // Process CSI data - forward fill and backward fill
        val filledCsiMap = fillMissingCSI(uniformTimeline, shiftedCsiMap)
        
        // Calculate average of 12 values for each timestamp (mean of CSI amplitudes)
        val averagedCsiMap = filledCsiMap.mapValues { (_, values) -> 
            values.average().toFloat()
        }

        // Process bitrate data - create map with shifted timestamps and fill missing with 0
        val shiftedBitrateMap = bitrateTimeline.mapKeys { (key, _) -> 
            (key - startTime).let { if (it < 0) 0.0 else it }
        }
        val filledBitrateMap = fillMissingBitrate(uniformTimeline, shiftedBitrateMap)
        
        // Apply median filter to bitrate data
        val filteredBitrateMap = applyMedianFilter(filledBitrateMap, windowSize = 3, stride = 1)

        // Combine data into aligned features
        return@withContext uniformTimeline.map { time ->
            AlignedFeature(
                timestamp = time,
                csiFeature = averagedCsiMap[time] ?: 0f,
                bitrateFeature = filteredBitrateMap[time] ?: 0
            )
        }
    }

    /**
     * Fills missing CSI values using forward-fill and backward-fill
     */
    private fun fillMissingCSI(
        timeline: List<Double>,
        csiMap: Map<Double, List<Float>>
    ): Map<Double, List<Float>> {
        if (csiMap.isEmpty() || timeline.isEmpty()) {
            return emptyMap()
        }

        // Get the size of CSI feature vectors (should be 12)
        val featureSize = csiMap.values.firstOrNull()?.size ?: 12
        val defaultValue = List(featureSize) { 0f }

        // Initialize result map with all timeline points
        val result = timeline.associateWith { defaultValue }.toMutableMap()

        // Copy existing values
        csiMap.forEach { (time, values) ->
            // Find the closest timestamp in the timeline
            val closestTime = timeline.minByOrNull { Math.abs(it - time) } ?: return@forEach
            if (Math.abs(closestTime - time) < 0.05) { // Within 0.05 seconds
                result[closestTime] = values
            }
        }

        // Forward fill
        var lastValues = defaultValue
        for (time in timeline.sorted()) {
            if (result[time] == defaultValue && lastValues != defaultValue) {
                result[time] = lastValues
            } else if (result[time] != defaultValue) {
                lastValues = result[time]!!
            }
        }

        // Backward fill (only if we still have missing values)
        lastValues = defaultValue
        for (time in timeline.sortedDescending()) {
            if (result[time] == defaultValue && lastValues != defaultValue) {
                result[time] = lastValues
            } else if (result[time] != defaultValue) {
                lastValues = result[time]!!
            }
        }

        return result
    }

    /**
     * Fills missing bitrate values with 0
     */
    private fun fillMissingBitrate(
        timeline: List<Double>,
        bitrateMap: Map<Double, Int>
    ): Map<Double, Int> {
        // Initialize with all zeros
        val result = timeline.associateWith { 0 }.toMutableMap()
        
        // Copy existing values
        bitrateMap.forEach { (time, value) ->
            // Find the closest timestamp in the timeline
            val closestTime = timeline.minByOrNull { Math.abs(it - time) } ?: return@forEach
            if (Math.abs(closestTime - time) < 0.05) { // Within 0.05 seconds
                result[closestTime] = value
            }
        }
        
        return result
    }

    /**
     * Applies a median filter to bitrate data
     */
    private fun applyMedianFilter(
        bitrateMap: Map<Double, Int>,
        windowSize: Int,
        stride: Int
    ): Map<Double, Int> {
        if (bitrateMap.isEmpty() || windowSize <= 1) {
            return bitrateMap
        }

        val sortedTimestamps = bitrateMap.keys.sorted()
        if (sortedTimestamps.size < windowSize) {
            return bitrateMap
        }

        val result = mutableMapOf<Double, Int>()
        
        // Apply median filter with sliding window
        var i = 0
        while (i <= sortedTimestamps.size - windowSize) {
            val windowTimestamps = sortedTimestamps.subList(i, i + windowSize)
            val centerTimestamp = windowTimestamps[windowSize / 2]
            
            // Get values in the window
            val windowValues = windowTimestamps.mapNotNull { bitrateMap[it] }
            
            // Calculate median
            val median = if (windowValues.isNotEmpty()) {
                windowValues.sorted()[windowValues.size / 2]
            } else {
                0
            }
            
            result[centerTimestamp] = median
            
            i += stride
        }
        
        // Fill in any missing values in the result using the original data
        sortedTimestamps.forEach { time ->
            if (!result.containsKey(time)) {
                result[time] = bitrateMap[time] ?: 0
            }
        }
        
        return result
    }
}
