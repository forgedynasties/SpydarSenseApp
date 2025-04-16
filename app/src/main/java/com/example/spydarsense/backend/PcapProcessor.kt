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

            Log.e("PcapCSI", "PCAP file is too small to contain valid data. File size: $pcapFileSize")
            return emptyList()
        }
        val fc = file.readBytes()

        val bw = bandwidth ?: findBandwidth(fc.sliceArray(32 until 36))
        val nsub = (bw * 3.2).toInt()
        val sampleSize = 38 + (nsub * 4)
        val nsamplesMax = findNsamplesMax(pcapFileSize, nsub)

        val sampleBuffer = ByteArray(nsamplesMax * sampleSize)
        var dataIndex = 0
        var ptr = 24
        var nsamples = 0

        while (ptr < pcapFileSize && ptr + 12 < pcapFileSize) {
            val frameLen = ByteBuffer.wrap(fc, ptr + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int

            System.arraycopy(fc, ptr, sampleBuffer, dataIndex, 8)
            if (ptr + 54 <= pcapFileSize) {
                System.arraycopy(fc, ptr + 42, sampleBuffer, dataIndex + 8, 12)
            }
            ptr += 58
            val remaining = sampleSize - 20
            if (ptr + remaining <= pcapFileSize) {
                System.arraycopy(fc, ptr, sampleBuffer, dataIndex + 20, remaining)
            }
            nsamples++
            ptr += frameLen - 42
            dataIndex += sampleSize
        }

        val samples = mutableListOf<CSISample>()
        var offset = 0
        for (i in 0 until nsamples) {
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
        }
        return samples
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
            val samples = readPcap(pcapFilepath)
            Log.d("CSISampleReader", "Parsed CSI samples: ${samples.size}")
            
            // Process samples but don't launch a new coroutine - we want to wait for this to complete
            samples.forEach { sample ->
                val complexSamples = unpack(sample.csi, "default", fftshift = true)
                val amplitudes = complexSamples.map { sqrt(it.re * it.re + it.im * it.im) }
                Log.d("CSISampleReader", "CSI Sample amplitudes: $amplitudes")
            }
            
            return samples
        } catch (e: Exception) {
            Log.e("CSISampleReader", "Error parsing pcap file: ${e.message}")
            return emptyList()
        }
    }

    // If you still need this function for other purposes, rename it
    fun processCSISample(csiSample: CSISample) {
        try {
            val complexSamples = unpack(csiSample.csi, "default", fftshift = true)
            val amplitudes = complexSamples.map { sqrt(it.re * it.re + it.im * it.im) }
            val timestamp = csiSample.tsSec * 1_000_000L + csiSample.tsUsec
            Log.d("CSISampleReader", "CSI Sample amplitudes: $amplitudes")
        } catch (e: Exception) {
            Log.e("CSISampleReader", "Error processing CSI sample: ${e.message}")
        }
    }

    fun processPcapBitrate(pcapFilepath: String): List<BitrateSample> {
        val file = File(pcapFilepath)
        val pcapFileSize = file.length()
        if (pcapFileSize < 36) {
            Log.e("PcapBitrate", "PCAP file is too small to contain valid data.")
            return emptyList()
        }
        val fc = file.readBytes()
        val samples = mutableListOf<BitrateSample>()
        var ptr = 24

        while (ptr < pcapFileSize && ptr + 16 <= pcapFileSize) {
            val tsSec = ByteBuffer.wrap(fc, ptr, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val tsUsec = ByteBuffer.wrap(fc, ptr + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val inclLen = ByteBuffer.wrap(fc, ptr + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val origLen = ByteBuffer.wrap(fc, ptr + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int

            val timestamp = tsSec * 1_000_000L + tsUsec
            if (firstTimestamp == null) {
                firstTimestamp = timestamp
            }
            val relativeTimestamp = (timestamp - firstTimestamp!!) / 1_000_000.0
            
            // No longer rounding here - that will be done in the timeline normalization
            
            val headerLength = 16
            val bitrate = origLen - headerLength

            samples.add(BitrateSample(relativeTimestamp, bitrate))
            Log.d("PcapBitrate", "Bitrate: $bitrate")
            Log.d("PcapBitrate", "Timestamp: $relativeTimestamp sec")

            ptr += 16 + inclLen
        }

        return samples
    }
}