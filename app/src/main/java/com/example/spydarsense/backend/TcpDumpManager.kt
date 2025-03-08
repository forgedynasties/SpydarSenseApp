package com.example.spydarsense.backend

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class TcpdumpManager(
    private val outputDir: String,
    private val dateFormat: SimpleDateFormat,
    private val shellExecutor: ShellExecutor
) {

    private val _csiDirs = MutableStateFlow<List<String>>(emptyList())
    val csiDirs: StateFlow<List<String>> = _csiDirs

    private val _brDirs = MutableStateFlow<List<String>>(emptyList())
    val brDirs: StateFlow<List<String>> = _brDirs

    init {
        File(outputDir).mkdirs()
    }

    private fun runTcpdump(interf: String, filter: String, outputFile: String, preloadLib: String? = null) {
        val command = if (preloadLib != null) {
            "LD_PRELOAD=$preloadLib tcpdump -i $interf $filter -s0 -evvv -xx -w $outputFile"
        } else {
            "tcpdump -i $interf $filter -s0 -evvv -xx -w $outputFile"
        }
        Log.d("TcpdumpManager", "Starting tcpdump: $outputFile")
        shellExecutor.execute(command) { output, exitCode ->
            if (exitCode == 0) {
                Log.d("TcpdumpManager", "tcpdump started successfully: $output")
            } else {
                Log.e("TcpdumpManager", "tcpdump failed to start: $output")
            }
        }
    }

    private fun stopTcpdump() {
        val command = "pkill tcpdump"
        shellExecutor.execute(command) { output, exitCode ->
            if (exitCode == 0) {
                Log.d("TcpdumpManager", "Stopped all tcpdump processes")
            } else {
                Log.e("TcpdumpManager", "Failed to stop tcpdump processes: $output")
            }
        }
    }

    fun startCaptures() {
        CoroutineScope(Dispatchers.IO).launch {
            for (i in 1..10) {
                Log.d("TcpdumpManager", "Iteration $i: Starting")

                val now = dateFormat.format(Date())

                // Start CSI capture
                val csiDir = "$outputDir/csi_$now.pcap"
                val brDir = "$outputDir/cam_$now.pcap"
                runTcpdump("wlan0", "dst port 5500", csiDir)

                // Start bitrate capture
                runTcpdump("wlan0", "ether src AC:6C:90:22:8F:37", brDir, "libnexmon.so")

                // Let captures run for 60 seconds
                delay(3000)

                // Stop all tcpdump processes
                stopTcpdump()
                delay(1000)

                Log.d("TcpdumpManager", "Iteration $i: Finished")
                PcapCSI.processPcap(csiDir)

                // Update the lists with new paths
                _csiDirs.value = _csiDirs.value + csiDir
                _brDirs.value = _brDirs.value + brDir
            }
            Log.d("TcpdumpManager", "All iterations completed")
            Log.d("TcpdumpManager", "CSI paths: ${_csiDirs.value}")
            Log.d("TcpdumpManager", "Bitrate paths: ${_brDirs.value}")

        }
    }
}