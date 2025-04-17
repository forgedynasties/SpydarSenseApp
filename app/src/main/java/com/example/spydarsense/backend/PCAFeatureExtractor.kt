package com.example.spydarsense.backend

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PCAFeatureExtractor provides Principal Component Analysis functionality
 * Currently, it only calculates mean values as a feature placeholder
 * More sophisticated PCA features will be implemented in the future
 */
class PCAFeatureExtractor {
    
    // For now, just extract mean as a simple feature (will be enhanced later)
    suspend fun extractCSIFeatures(csiData: List<List<Float>>): PCAFeatures {
        return withContext(Dispatchers.Default) {
            if (csiData.isEmpty()) {
                return@withContext PCAFeatures(emptyList())
            }
            
            try {
                // For now, just calculate mean per subcarrier position across all samples
                val numPositions = csiData.first().size
                val means = MutableList(numPositions) { 0f }
                
                // Sum values at each position
                csiData.forEach { sample ->
                    for (i in sample.indices) {
                        if (i < means.size) {
                            means[i] += sample[i]
                        }
                    }
                }
                
                // Calculate means
                for (i in means.indices) {
                    means[i] /= csiData.size
                }
                
                Log.d("PCAFeatureExtractor", "Extracted ${means.size} CSI feature means")
                return@withContext PCAFeatures(means)
            } catch (e: Exception) {
                Log.e("PCAFeatureExtractor", "Error extracting CSI features: ${e.message}")
                return@withContext PCAFeatures(emptyList())
            }
        }
    }
    
    suspend fun extractBitrateFeatures(bitrateData: List<Int>): PCAFeatures {
        return withContext(Dispatchers.Default) {
            if (bitrateData.isEmpty()) {
                return@withContext PCAFeatures(emptyList())
            }
            
            try {
                // For now, just calculate mean as our sole feature
                val mean = bitrateData.average().toFloat()
                
                Log.d("PCAFeatureExtractor", "Extracted bitrate feature mean: $mean")
                return@withContext PCAFeatures(listOf(mean))
            } catch (e: Exception) {
                Log.e("PCAFeatureExtractor", "Error extracting bitrate features: ${e.message}")
                return@withContext PCAFeatures(emptyList())
            }
        }
    }
    
    /**
     * Normalizes CSI samples by timestamp, averaging amplitudes for samples at the same time point
     */
    suspend fun normalizeCSITimeline(csiSamples: List<Pair<Double, List<Float>>>): Map<Double, List<Float>> {
        return withContext(Dispatchers.Default) {
            if (csiSamples.isEmpty()) return@withContext emptyMap()
            
            // Group by rounded timestamp (to 0.1 second)
            val groupedByTime = csiSamples.groupBy { 
                (it.first * 10).toInt() / 10.0 // Round to nearest 0.1
            }
            
            // For each time point, average the amplitudes
            val result = mutableMapOf<Double, List<Float>>()
            
            groupedByTime.forEach { (timePoint, samples) ->
                if (samples.isEmpty()) return@forEach
                
                // Get the number of subcarriers/features from the first sample
                val numFeatures = samples.first().second.size
                
                // Initialize array for sums
                val sums = FloatArray(numFeatures) { 0f }
                
                // Sum all values
                samples.forEach { (_, amplitudes) ->
                    amplitudes.forEachIndexed { index, value ->
                        if (index < numFeatures) {
                            sums[index] += value
                        }
                    }
                }
                
                // Calculate average
                val avgAmplitudes = sums.map { it / samples.size }
                
                result[timePoint] = avgAmplitudes
            }
            
            return@withContext result
        }
    }
    
    /**
     * Normalizes bitrate samples by timestamp, summing bitrates for samples at the same time point
     */
    suspend fun normalizeBitrateTimeline(bitrateSamples: List<Pair<Double, Int>>): Map<Double, Int> {
        return withContext(Dispatchers.Default) {
            if (bitrateSamples.isEmpty()) return@withContext emptyMap()
            
            // Group by rounded timestamp (to 0.1 second)
            val groupedByTime = bitrateSamples.groupBy { 
                (it.first * 10).toInt() / 10.0 // Round to nearest 0.1
            }
            
            // For each time point, sum the bitrates
            val result = mutableMapOf<Double, Int>()
            
            groupedByTime.forEach { (timePoint, samples) ->
                // Sum all bitrates at this time point
                val totalBitrate = samples.sumOf { it.second }
                result[timePoint] = totalBitrate
            }
            
            return@withContext result
        }
    }
    
    /**
     * Data class to hold PCA features
     * Currently just holds mean values, but will be extended
     * to include more sophisticated PCA features in the future
     */
    data class PCAFeatures(
        val values: List<Float>,
        val featureType: String = "mean" // In the future, this will identify different feature types
    )
}
