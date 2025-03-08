package com.example.spydarsense.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CSISample(
    val tsSec: Int,
    val tsUsec: Int,
    val saddr: Int,
    val daddr: Int,
    val sport: Short,
    val dport: Short,
    val magic: Short,
    val rssi: Byte,
    val fctl: Byte,
    val mac: ByteArray,  // 6 bytes
    val seq: Short,
    val css: Short,
    val csp: Short,
    val cvr: Short,
    val csi: ShortArray  // length = nsub * 2
)