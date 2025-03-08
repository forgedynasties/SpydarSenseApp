package com.example.spydarsense.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * A simple Complex number data class.
 */
data class Complex(val re: Float, val im: Float)

/**
 * Helper object to read CSI samples from a PCAP file and unpack CSI data.
 */
object PcapCSI {

    // Dummy definitions; adjust these indices as needed.
    val nulls = mapOf(
        20 to intArrayOf(0, 31), // For example
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

    // Calculate bandwidth from incl_len bytes (located at file offset 32-36)
    private fun findBandwidth(inclLen: ByteArray): Int {
        val pktLen = ByteBuffer.wrap(inclLen).order(ByteOrder.LITTLE_ENDIAN).int
        // Number of bytes before CSI data in the packet header.
        val nbytesBeforeCSI = 60
        // Adjust packet length (128 is a constant padding factor).
        val adjustedPktLen = pktLen + (128 - nbytesBeforeCSI)
        // 20 * 3.2 * 4 == 256
        val factor = 256
        val bandwidth = 20 * (adjustedPktLen / factor)
        return bandwidth
    }

    // Estimate maximum number of samples in the pcap file
    private fun findNsamplesMax(pcapFileSize: Long, nsub: Int): Int {
        // PCAP global header: 24 bytes
        // PCAP packet header: 12 bytes
        // Ethernet + IP + UDP headers: 46 bytes
        // Nexmon metadata: 18 bytes
        // CSI is nsub * 4 bytes long.
        val samplePacketSize = 12 + 46 + 18 + (nsub * 4)
        return ((pcapFileSize - 24) / samplePacketSize).toInt()
    }

    /**
     * Reads a PCAP file and returns a list of CSI samples.
     * @param pcapFilepath The absolute path to the PCAP file.
     * @param bandwidth Optional: pass bandwidth explicitly.
     */
    fun readPcap(pcapFilepath: String, bandwidth: Int? = null): List<CSISample> {
        val file = File(pcapFilepath)
        val pcapFileSize = file.length()
        val fc = file.readBytes()  // Read entire file into a byte array

        // Determine bandwidth if not provided.
        val bw = bandwidth ?: findBandwidth(fc.sliceArray(32 until 36))
        // Number of OFDM subcarriers.
        val nsub = (bw * 3.2).toInt()
        // Calculate sample size: fixed fields are 38 bytes plus CSI data (nsub*4)
        val sampleSize = 38 + (nsub * 4)
        val nsamplesMax = findNsamplesMax(pcapFileSize, nsub)

        // Pre-allocated buffer to store sample data.
        val sampleBuffer = ByteArray(nsamplesMax * sampleSize)
        var dataIndex = 0
        // Skip global header.
        var ptr = 24
        var nsamples = 0

        while (ptr < pcapFileSize && ptr + 12 < pcapFileSize) {
            // Frame length: 4 bytes at offset ptr+8 in little endian.
            val frameLen = ByteBuffer.wrap(fc, ptr + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int

            // Copy the first 8 bytes (timestamps)
            System.arraycopy(fc, ptr, sampleBuffer, dataIndex, 8)
            // Copy saddr, daddr, sport, dport (12 bytes from offset ptr+42)
            if (ptr + 54 <= pcapFileSize) {
                System.arraycopy(fc, ptr + 42, sampleBuffer, dataIndex + 8, 12)
            }
            // Advance pointer to skip header parts.
            ptr += 58
            // Copy the rest of the sample data (sampleSize - 20 bytes)
            val remaining = sampleSize - 20
            if (ptr + remaining <= pcapFileSize) {
                System.arraycopy(fc, ptr, sampleBuffer, dataIndex + 20, remaining)
            }
            nsamples++
            // Move pointer past this frame; note: 42 bytes already skipped.
            ptr += frameLen - 42
            dataIndex += sampleSize
        }

        // Now parse the raw sampleBuffer to create CSISample objects.
        val samples = mutableListOf<CSISample>()
        var offset = 0
        for (i in 0 until nsamples) {
            val buffer = ByteBuffer.wrap(sampleBuffer, offset, sampleSize)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val tsSec = buffer.int
            val tsUsec = buffer.int
            // Next 12 bytes were copied from offset 8 using big-endian fields.
            buffer.order(ByteOrder.BIG_ENDIAN)
            val saddr = buffer.int
            val daddr = buffer.int
            val sport = buffer.short
            val dport = buffer.short
            // Switch back to little endian for the rest.
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.short
            val rssi = buffer.get()
            val fctl = buffer.get()
            // Read 6 bytes MAC.
            val mac = ByteArray(6)
            buffer.get(mac)
            val seq = buffer.short
            val css = buffer.short
            val csp = buffer.short
            val cvr = buffer.short
            // Read CSI data: nsub*2 short values.
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
        }
        return samples
    }

    /**
     * Unpacks the interleaved CSI data into Complex numbers.
     * @param csi A ShortArray representing interleaved CSI (I, Q, I, Q, ...)
     * @param device A string representing the device name.
     * @param fftshift Whether to perform FFT shift on the array.
     * @param zeroNulls If true, set CSI values at null indices to zero.
     * @param zeroPilots If true, set CSI values at pilot indices to zero.
     */
    fun unpack(
        csi: ShortArray,
        device: String,
        fftshift: Boolean = true,
        zeroNulls: Boolean = false,
        zeroPilots: Boolean = false
    ): List<Complex> {
        // Determine number of subcarriers from length (should be even)
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
        // Convert interleaved shorts to Complex numbers (float conversion)
        val complexSamples = MutableList(nSubcarriers) { idx ->
            val re = csi[2 * idx].toFloat()
            val im = csi[2 * idx + 1].toFloat()
            Complex(re, im)
        }
        // If fftshift is true then shift the array.
        val shiftedSamples = if (fftshift) {
            fftShift(complexSamples)
        } else {
            complexSamples
        }
        // Zero out null or pilot indices when requested.
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

    // Perform an FFT shift (reordering the array by moving the midpoint to the beginning)
    private fun fftShift(list: List<Complex>): MutableList<Complex> {
        val n = list.size
        val mid = n / 2
        return (list.subList(mid, n) + list.subList(0, mid)).toMutableList()
    }

    // Helper to broadcast bandwidth keys matching our dummy maps
    private fun broadcastKey(bandwidth: Int): Int = when (bandwidth) {
        20, 40, 80, 160 -> bandwidth
        else -> 20
    }

    fun processPcap(pcapFilepath: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val samples = readPcap(pcapFilepath)
                Log.d("CSISampleReader", "Parsed CSI samples: ${samples.size}")
                samples.forEachIndexed { _, sample ->
                    processCSISample(csiSample = sample)
                }
            } catch (e: Exception) {
                Log.e("CSISampleReader", "Error parsing pcap file: ${e.message}")
            }
        }
    }

    fun processCSISample(csiSample: CSISample) {
        CoroutineScope(Dispatchers.IO).launch {
            try {

                    // Unpack CSI with FFT shift applied
                    val complexSamples = unpack(csiSample.csi, "default", fftshift = true)
                    // Calculate amplitude for each Complex sample
                    val amplitudes = complexSamples.map { sqrt(it.re * it.re + it.im * it.im) }
                    //Log.d("CSISampleReader", "CSI Sample amplitude: ${amplitudes.joinToString()}")
                val timestamp = csiSample.tsSec * 1_000_000L + csiSample.tsUsec
                Log.d("CSISampleReader", "CSI Sample timestamp: $timestamp")


            } catch (e: Exception) {
                Log.e("CSISampleReader", "Error parsing pcap file: ${e.message}")
            }
        }
    }
}