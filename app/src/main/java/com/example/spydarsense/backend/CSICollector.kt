package com.example.spydarsense.backend

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Class responsible for CSI (Channel State Information) collection and processing
 */
class CSICollector {
    
    /**
     * Create CSI parameters string for the specified device
     * 
     * @param macAddress MAC address of the device to monitor
     * @param channel WiFi channel to monitor
     * @return CSI parameter string to pass to nexutil
     */
    fun makeCSIParams(macAddress: String, channel: Int): String {
        Log.d("CSICollector", "Creating CSI parameters for MAC: $macAddress on channel: $channel")
        
        // Convert MAC address string to bytes
        val macBytes = convertMacToBytes(macAddress)
        
        // Create parameter buffer
        val buffer = ByteBuffer.allocate(34)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // Set sampling parameters
        buffer.put(0) // Core: 0 for primary WiFi core
        buffer.put(channel.toByte()) // Channel number
        buffer.put(0) // Sample all packets (0) or only with MAC match (1)
        buffer.put(1) // Use MAC address filter
        
        // Add MAC address (reverse order for LE)
        for (i in 5 downTo 0) {
            buffer.put(macBytes[i])
        }
        
        // Additional configuration
        buffer.putShort(1000.toShort()) // BDF calculation interval in ms
        buffer.putShort(0) // Reserved
        buffer.putInt(0) // Reserved flags
        
        // Set output mode and other parameters
        buffer.put(1) // Output mode: 1 for binary
        buffer.put(0) // Reserved
        buffer.putShort(0) // Reserved
        buffer.putInt(0) // Reserved
        buffer.putInt(0) // Reserved
        buffer.putInt(0) // Reserved
        
        // Convert buffer to hex string
        val hexString = buffer.array().joinToString("") { 
            "%02x".format(it) 
        }
        
        Log.d("CSICollector", "Created CSI parameters: $hexString")
        return hexString
    }
    
    /**
     * Convert MAC address string (e.g. "84:36:71:10:A4:64") to byte array
     */
    private fun convertMacToBytes(macAddress: String): ByteArray {
        return macAddress.split(":")
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}