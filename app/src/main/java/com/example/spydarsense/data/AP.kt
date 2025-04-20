package com.example.spydarsense.data

class AP(
    var essid: String?, // Network name
    val mac: String,    // MAC address (unique identifier)
    var ch: Int,        // Channel
    var pwr: Int = -80  // Signal strength (dBm), default to a weak signal
) {
    companion object {
        private val apList = mutableListOf<AP>()

        fun getAPByMac(mac: String): AP? {
            return apList.find { it.mac == mac }
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
}