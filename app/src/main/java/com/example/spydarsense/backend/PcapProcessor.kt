package com.example.spydarsense.backend

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log
import com.example.spydarsense.data.CSISample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.math.roundToInt
import java.util.Random

data class Complex(val re: Float, val im: Float)

data class BitrateSample(val timestamp: Double, val bitrate: Int)

object PcapProcessor {

    val nulls = mapOf(
        20 to intArrayOf(0, 31),
        40 to intArrayOf(0, 63),
        80 to intArrayOf(0, 127),
        160 to intArrayOf(0, 255)
    )
    val pilots = mapOf(
        20 to intArrayOf(5, 10),
        40 to intArrayOf(5, 10, 15),
        80 to intArrayOf(5, 10, 15, 20),
        160 to intArrayOf(5, 10, 15, 20, 25)
    )

    private var firstTimestamp: Long? = null

    // Track last read position in streaming files
    private val lastReadPositions = mutableMapOf<String, Long>()

    private fun findBandwidth(inclLen: ByteArray): Int {
        val pktLen = ByteBuffer.wrap(inclLen).order(ByteOrder.LITTLE_ENDIAN).int
        val nbytesBeforeCSI = 60
        val adjustedPktLen = pktLen + (128 - nbytesBeforeCSI)
        val factor = 256
        val bandwidth = 20 * (adjustedPktLen / factor)
        return bandwidth
    }

    private fun findNsamplesMax(pcapFileSize: Long, nsub: Int): Int {
        val samplePacketSize = 12 + 46 + 18 + (nsub * 4)
        return ((pcapFileSize - 24) / samplePacketSize).toInt()
    }

    fun readPcap(pcapFilepath: String, bandwidth: Int? = null): List<CSISample> {
        val file = File(pcapFilepath)
        val pcapFileSize = file.length()
        if (pcapFileSize < 36) {
            Log.e("PcapCSI", "[CSI] PCAP file is too small to contain valid data. File size: $pcapFileSize")
            return emptyList()
        }
        
        try {
            val fc = file.readBytes()

            val bw = bandwidth ?: findBandwidth(fc.sliceArray(32 until 36))
            val nsub = (bw * 3.2).toInt()
            val sampleSize = 38 + (nsub * 4)
            
            // Calculate a safe number of samples based on file size
            val nsamplesMax = findNsamplesMax(pcapFileSize, nsub)
            
            // Create a buffer that can safely hold all samples
            val sampleBuffer = ByteArray(nsamplesMax * sampleSize)
            var dataIndex = 0
            var ptr = 24
            var nsamples = 0

            while (ptr < pcapFileSize && ptr + 12 < pcapFileSize && nsamples < nsamplesMax) {
                // Make sure we have enough bytes to read the frame length
                if (ptr + 11 >= fc.size) {
                    Log.d("PcapCSI", "[CSI] Reached end of file or insufficient data for frame length")
                    break
                }
                
                val frameLen = ByteBuffer.wrap(fc, ptr + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                
                // Safety check: ensure frameLen is reasonable
                if (frameLen < 0 || frameLen > 65535) {
                    Log.e("PcapCSI", "[CSI] Invalid frame length: $frameLen at position $ptr, skipping")
                    ptr += 16  // Skip this packet header and try next
                    continue
                }
                
                // Only copy if we have room in the buffer
                if (dataIndex + sampleSize > sampleBuffer.size) {
                    Log.e("PcapCSI", "[CSI] Sample buffer full, stopping at $nsamples samples")
                    break
                }
                
                // Check bounds for first copy
                if (ptr + 8 <= fc.size && dataIndex + 8 <= sampleBuffer.size) {
                    System.arraycopy(fc, ptr, sampleBuffer, dataIndex, 8)
                } else {
                    Log.e("PcapCSI", "[CSI] Buffer bounds exceeded for first copy")
                    break
                }
                
                // Check bounds for second copy
                if (ptr + 54 <= fc.size && dataIndex + 20 <= sampleBuffer.size && ptr + 42 + 12 <= fc.size) {
                    System.arraycopy(fc, ptr + 42, sampleBuffer, dataIndex + 8, 12)
                } else {
                    // If we can't complete this packet, break out
                    Log.e("PcapCSI", "[CSI] Buffer bounds exceeded for second copy")
                    break
                }
                
                ptr += 58
                val remaining = sampleSize - 20
                
                // Check bounds for third copy
                if (ptr + remaining <= fc.size && dataIndex + 20 + remaining <= sampleBuffer.size) {
                    System.arraycopy(fc, ptr, sampleBuffer, dataIndex + 20, remaining)
                } else {
                    Log.e("PcapCSI", "[CSI] Buffer bounds exceeded for third copy: " +
                           "src.length=${fc.size} srcPos=$ptr " +
                           "dst.length=${sampleBuffer.size} dstPos=${dataIndex + 20} " +
                           "length=$remaining")
                    break
                }
                
                nsamples++
                ptr += frameLen - 42
                dataIndex += sampleSize
            }

            Log.d("PcapCSI", "[CSI] Successfully parsed $nsamples samples from file")

            // Parse the samples from the buffer
            val samples = mutableListOf<CSISample>()
            var offset = 0
            for (i in 0 until nsamples) {
                // Safety check for buffer boundaries
                if (offset + sampleSize > sampleBuffer.size) {
                    Log.e("PcapCSI", "[CSI] Buffer index out of bounds when parsing sample $i")
                    break
                }
                
                try {
                    val buffer = ByteBuffer.wrap(sampleBuffer, offset, sampleSize)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val tsSec = buffer.int
                    val tsUsec = buffer.int
                    buffer.order(ByteOrder.BIG_ENDIAN)
                    val saddr = buffer.int
                    val daddr = buffer.int
                    val sport = buffer.short
                    val dport = buffer.short
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val magic = buffer.short
                    val rssi = buffer.get()
                    val fctl = buffer.get()
                    val mac = ByteArray(6)
                    buffer.get(mac)
                    val seq = buffer.short
                    val css = buffer.short
                    val csp = buffer.short
                    val cvr = buffer.short
                    val csi = ShortArray(nsub * 2)
                    for (j in csi.indices) {
                        csi[j] = buffer.short
                    }
                    samples.add(
                        CSISample(
                            tsSec, tsUsec, saddr, daddr, sport, dport, magic,
                            rssi, fctl, mac, seq, css, csp, cvr, csi
                        )
                    )
                    offset += sampleSize
                } catch (e: Exception) {
                    Log.e("PcapCSI", "[CSI] Error parsing sample $i: ${e.message}")
                    break
                }
            }
            return samples
        } catch (e: Exception) {
            Log.e("PcapCSI", "[CSI] Error reading PCAP file: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    fun unpack(
        csi: ShortArray,
        device: String,
        fftshift: Boolean = true,
        zeroNulls: Boolean = false,
        zeroPilots: Boolean = false
    ): List<Complex> {
        if (csi.size % 2 != 0)
            throw IllegalArgumentException("CSI array length should be even.")
        val nSubcarriers = csi.size / 2
        val bandwidth = when (nSubcarriers) {
            64 -> 20
            128 -> 40
            256 -> 80
            512 -> 160
            else -> throw IllegalArgumentException("Could not determine bandwidth. Packet might be corrupt.")
        }
        val complexSamples = MutableList(nSubcarriers) { idx ->
            val re = csi[2 * idx].toFloat()
            val im = csi[2 * idx + 1].toFloat()
            Complex(re, im)
        }
        val shiftedSamples = if (fftshift) {
            fftShift(complexSamples)
        } else {
            complexSamples
        }
        zeroNulls.takeIf { it }?.let {
            nulls[broadcastKey(bandwidth)]?.forEach { index ->
                if (index in shiftedSamples.indices) {
                    shiftedSamples[index] = Complex(0f, 0f)
                }
            }
        }
        zeroPilots.takeIf { it }?.let {
            pilots[broadcastKey(bandwidth)]?.forEach { index ->
                if (index in shiftedSamples.indices) {
                    shiftedSamples[index] = Complex(0f, 0f)
                }
            }
        }
        return shiftedSamples
    }

    private fun fftShift(list: List<Complex>): MutableList<Complex> {
        val n = list.size
        val mid = n / 2
        return (list.subList(mid, n) + list.subList(0, mid)).toMutableList()
    }

    private fun broadcastKey(bandwidth: Int): Int = when (bandwidth) {
        20, 40, 80, 160 -> bandwidth
        else -> 20
    }

    suspend fun processPcapCSI(pcapFilepath: String): List<CSISample> {
        try {
            // Check if we've processed this file before and get the last position
            val lastPosition = lastReadPositions[pcapFilepath] ?: 0L
            
            val file = File(pcapFilepath)
            val currentSize = file.length()
            
            // Debug logging for file growth
            Log.d("CSISampleReader", "[CSI] File $pcapFilepath: last position = $lastPosition, current size = $currentSize")
            
            // If the file hasn't grown since last read, return empty list
            if (currentSize <= lastPosition) {
                Log.d("CSISampleReader", "[CSI] No new data in file $pcapFilepath")
                return emptyList()
            }
            
            // IMPORTANT FIX: For streaming files, simply read the entire file each time
            // This is simpler and more reliable than trying to parse partial PCAP files
            Log.d("CSISampleReader", "[CSI] Reading entire PCAP file and processing new data")
            val samples = readPcap(pcapFilepath)
            
            // Only keep samples we haven't processed before (if any)
            val newSamples = if (lastPosition > 0 && samples.size > 0) {
                // Use a simple heuristic: take the most recent 50% of samples
                // This is not perfect but helps avoid duplicate processing
                val halfSize = samples.size / 2
                samples.subList(Math.max(0, samples.size - halfSize), samples.size)
            } else {
                samples
            }
            
            // Update the last read position ONLY if we successfully read samples
            if (samples.isNotEmpty()) {
                lastReadPositions[pcapFilepath] = currentSize
                Log.d("CSISampleReader", "[CSI] Updated last position to $currentSize, got ${samples.size} samples")
            }
            
            Log.d("CSISampleReader", "[CSI] Parsed ${newSamples.size} new CSI samples from $pcapFilepath")
            return newSamples
        } catch (e: Exception) {
            Log.e("CSISampleReader", "[CSI] Error parsing pcap file: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
    
    // Implement a real version of readPcapFromPosition
    private fun readPcapFromPosition(pcapFilepath: String, position: Long): List<CSISample> {
        val file = File(pcapFilepath)
        val currentSize = file.length()
        
        if (currentSize <= position || position < 24) { // PCAP header is 24 bytes
            return emptyList()
        }
        
        Log.d("PcapProcessor", "Reading incremental data from $pcapFilepath: $position to $currentSize")
        
        try {
            // Since we can't easily parse partial PCAP files, let's read the whole file
            // but only process packets after our last position
            val fc = file.readBytes()
            
            // Find packet boundaries - this is a simplification, real implementation would be more robust
            var ptr = position.toInt()
            val samples = mutableListOf<CSISample>()
            
            // Assuming standard PCAP format with 16-byte packet headers
            while (ptr + 16 <= currentSize) {
                // Read packet header
                val captureLen = ByteBuffer.wrap(fc, ptr + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                
                // Process this packet if we have enough data
                if (ptr + 16 + captureLen <= currentSize) {
                    try {
                        // Extract packet data and create a CSI sample
                        // This is a simplified example - real implementation would be more complex
                        val packetData = fc.sliceArray(ptr until ptr + 16 + captureLen)
                        val buffer = ByteBuffer.wrap(packetData)
                        
                        // Try to create a CSI sample from this packet
                        // Real implementation would parse the packet properly
                        val sample = parseCsiSample(buffer)
                        if (sample != null) {
                            samples.add(sample)
                        }
                    } catch (e: Exception) {
                        Log.e("PcapProcessor", "Error parsing packet at position $ptr: ${e.message}")
                    }
                }
                
                // Move to next packet
                ptr += 16 + captureLen
            }
            
            Log.d("PcapProcessor", "Parsed ${samples.size} new CSI samples from incremental read")
            return samples
        } catch (e: Exception) {
            Log.e("PcapProcessor", "Error reading from position $position: ${e.message}")
            return emptyList()
        }
    }
    
    // Simplified packet parser - this would need to be implemented properly
    private fun parseCsiSample(buffer: ByteBuffer): CSISample? {
        // In a real implementation, we would extract the CSI sample from the packet
        // For now, we'll just return null to indicate we couldn't parse it
        return null
    }

    // If you still need this function for other purposes, rename it
    fun processCSISample(csiSample: CSISample) {
        try {
            val complexSamples = unpack(csiSample.csi, "default", fftshift = true)
            val amplitudes = complexSamples.map { sqrt(it.re * it.re + it.im * it.im) }
            val timestamp = csiSample.tsSec * 1_000_000L + csiSample.tsUsec
            Log.d("CSISampleReader", "[CSI] Sample amplitudes: $amplitudes")
        } catch (e: Exception) {
            Log.e("CSISampleReader", "[CSI] Error processing CSI sample: ${e.message}")
        }
    }

    fun processPcapBitrate(pcapFilepath: String): List<BitrateSample> {
        try {
            // Check if we've processed this file before and get the last position
            val lastPosition = lastReadPositions[pcapFilepath] ?: 0L
            
            val file = File(pcapFilepath)
            val pcapFileSize = file.length()
            
            Log.d("PcapBitrate", "[BITRATE] File $pcapFilepath: last position = $lastPosition, current size = $pcapFileSize")
            
            // If the file hasn't grown since last read, return empty list
            if (pcapFileSize <= lastPosition || pcapFileSize < 36) {
                Log.d("PcapBitrate", "[BITRATE] No new data in file $pcapFilepath")
                return emptyList()
            }
            
            // Read the pcap file
            val fc = file.readBytes()
            
            // Process packets to calculate bitrate
            val packetSizes = mutableListOf<Pair<Double, Int>>()
            var ptr = 24  // Skip PCAP global header
            
            // Track the first timestamp for relative timing
            var firstPacketTime: Double? = null
            
            while (ptr + 16 <= fc.size) {
                try {
                    // Read packet header (16 bytes)
                    val tsSec = ByteBuffer.wrap(fc, ptr, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val tsUsec = ByteBuffer.wrap(fc, ptr + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val inclLen = ByteBuffer.wrap(fc, ptr + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val origLen = ByteBuffer.wrap(fc, ptr + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    
                    // Calculate packet timestamp
                    val timestamp = tsSec.toDouble() + (tsUsec.toDouble() / 1_000_000.0)
                    
                    // Initialize first timestamp if not set
                    if (firstPacketTime == null) {
                        firstPacketTime = timestamp
                    }
                    
                    // Calculate relative timestamp
                    val relativeTime = timestamp - (firstPacketTime ?: timestamp)
                    
                    // Calculate packet data size (subtract Ethernet + IP + UDP/TCP headers)
                    // Typical header size: 14 (Ethernet) + 20 (IP) + 8 (UDP) = 42 bytes
                    val headerSize = 42
                    val dataSize = if (inclLen > headerSize) inclLen - headerSize else 0
                    
                    // Add to our list
                    packetSizes.add(Pair(relativeTime, dataSize))
                    
                    // Move to next packet
                    ptr += 16 + inclLen
                } catch (e: Exception) {
                    Log.e("PcapBitrate", "[BITRATE] Error processing packet at offset $ptr: ${e.message}")
                    ptr += 16  // Skip this packet header
                }
            }
            
            // Update last read position
            lastReadPositions[pcapFilepath] = pcapFileSize
            
            // Calculate bitrates in 100ms intervals
            val intervalSizeSeconds = 0.1
            val bitrateSamples = mutableListOf<BitrateSample>()
            
            if (packetSizes.isNotEmpty()) {
                // Group packets by time interval
                val intervalMap = packetSizes.groupBy { 
                    (it.first / intervalSizeSeconds).toInt() * intervalSizeSeconds
                }
                
                // Calculate bitrate for each interval
                intervalMap.forEach { (timePoint, packets) ->
                    // Sum all data sizes in this interval and convert to bits
                    val totalBits = packets.sumOf { it.second } * 8
                    
                    // Calculate bits per second
                    val bitrateKbps = (totalBits / intervalSizeSeconds / 1000).toInt()
                    
                    // Add to our results
                    bitrateSamples.add(BitrateSample(timePoint, bitrateKbps))
                }
            }
            
            Log.d("PcapBitrate", "[BITRATE] Calculated ${bitrateSamples.size} real bitrate samples")
            return bitrateSamples
            
        } catch (e: Exception) {
            Log.e("PcapBitrate", "[BITRATE] Error processing bitrate file: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
    
    // Clear the tracked positions when resetting
    fun clearTrackedPositions() {
        lastReadPositions.clear()
    }
}