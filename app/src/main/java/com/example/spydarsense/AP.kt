package com.example.spydarsense

class AP(
    var essid: String?, // Changed to var
    val mac: String,
    var enc: String, // Changed to var
    var cipher: String, // Changed to var
    var auth: String, // Changed to var
    var pwr: Int,
    var beacons: Int,
    var data: Int,
    var ivs: Int,
    var ch: Int
) {
    companion object {
        private val apList = mutableListOf<AP>()

        fun getAPByMac(mac: String): AP? {
            return apList.find { it.mac == mac }
        }

    }

    fun update(
        essid: String?,
        enc: String,
        cipher: String,
        auth: String,
        pwr: Int,
        beacons: Int,
        data: Int,
        ivs: Int,
        ch: Int
    ) {
        this.essid = essid
        this.enc = enc
        this.cipher = cipher
        this.auth = auth
        this.pwr = pwr
        this.beacons = beacons
        this.data = data
        this.ivs = ivs
        this.ch = ch
    }
}