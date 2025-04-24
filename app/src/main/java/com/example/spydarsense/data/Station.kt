package com.example.spydarsense.data

class Station(
    val mac: String,
    val bssid: String,
    val power: Int = -80,
    val probedEssids: String = ""
) {
    companion object {
        // Helper function to normalize MAC addresses for consistent comparison
        fun normalizeMac(mac: String): String {
            return mac.lowercase().trim().replace("-", ":")
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Station) return false
        return normalizeMac(mac) == normalizeMac(other.mac)
    }
    
    override fun hashCode(): Int {
        return normalizeMac(mac).hashCode()
    }
}
