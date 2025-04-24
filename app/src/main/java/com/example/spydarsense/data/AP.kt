package com.example.spydarsense.data

class AP(
    var essid: String?, // Network name
    val mac: String,    // MAC address (unique identifier)
    var ch: Int = 0,        // Channel
    var pwr: Int = -80,  // Signal strength (dBm), default to a weak signal
    var enc: String = "Unknown", // Encryption type
    var cipher: String = "Unknown", // Cipher
    var auth: String = "Unknown", // Authentication
    var beacons: Int = 0, // Number of beacons
    var data: Int = 0, // Data packets
    var ivs: Int = 0 // Number of IVs

) {
    companion object {
        private val apList = mutableListOf<AP>()

        fun getAPByMac(mac: String): AP? {
            return apList.find { it.mac == mac }
        }
        
        // Helper function to normalize MAC addresses for consistent comparison
        fun normalizeMac(mac: String): String {
            return mac.lowercase().trim().replace("-", ":")
        }
    }

    fun update(
        essid: String?,
        ch: Int,
        pwr: Int = this.pwr
    ) {
        this.essid = essid
        this.ch = ch
        this.pwr = pwr
    }
    
    // Override equals and hashCode for proper duplicate detection
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AP) return false
        return normalizeMac(mac) == normalizeMac(other.mac)
    }
    
    override fun hashCode(): Int {
        return normalizeMac(mac).hashCode()
    }
    
    private fun normalizeMac(mac: String): String {
        return mac.lowercase().trim().replace("-", ":")
    }
    
    // Override equals and hashCode for proper duplicate detection
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AP) return false
        return normalizeMac(mac) == normalizeMac(other.mac)
    }
    
    override fun hashCode(): Int {
        return normalizeMac(mac).hashCode()
    }
    
    private fun normalizeMac(mac: String): String {
        return mac.lowercase().trim().replace("-", ":")
    }
}