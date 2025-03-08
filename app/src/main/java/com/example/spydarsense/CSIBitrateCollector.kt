package com.example.spydarsense

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CSIBitrateCollector {

    private val shellExecutor = ShellExecutor()
    private val _csiOutput = MutableStateFlow<String>("")
    val csiOutput: StateFlow<String> = _csiOutput
    private val tcpdumpManager = TcpdumpManager()

    fun collectCSIBitrate(mac: String, ch: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val csiParams = "BhABEQGIAQCsbJAijzcAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
            Log.d("CSIBitrateCollector", "Collecting CSI with params: $csiParams")
            val nexutilCommand = "nexutil -Iwlan0 -s500 -b -l34 -v$csiParams"
            Log.d("CSIBitrateCollector", "nexutil command: $nexutilCommand")
            shellExecutor.execute(nexutilCommand) { output, exitCode ->
                if (exitCode == 0) {
                    Log.d("CSIBitrateCollector", "nexutil command successful: $output")
                    tcpdumpManager.startCaptures()
                } else {
                    Log.e("CSIBitrateCollector", "nexutil command failed: $output")
                }
            }
        }

    }

    suspend fun makeCSIParams(mac: String, ch: Int): String {
        val command = "makecsiparams -c $ch/20 -C 1 -N 1 -m $mac -b 0x88"
        Log.d("CSIBitrateCollector", "Generating CSI parameters with command: $command")

        return suspendCancellableCoroutine { continuation ->
            shellExecutor.execute(command) { output, exitCode ->
                if (exitCode == 0) {
                    Log.d("CSIBitrateCollector", "CSI parameters generated successfully: $output")
                    continuation.resume(output)
                } else {
                    Log.e("CSIBitrateCollector", "Failed to generate CSI parameters: $output")
                    continuation.resume(output)
                }
            }
        }
    }
}