package com.example.spydarsense.backend

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

class CSIBitrateCollector(val tcpdumpManager: TcpdumpManager) {

    private val shellExecutor = ShellExecutor()
    private val _csiOutput = MutableStateFlow<String>("")
    val csiOutput: StateFlow<String> = _csiOutput
    private val outputDir = "/sdcard/Download/Lab"
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

  
    fun collectCSIBitrate(mac: String, ch: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            //val csiParams = "ARABEQGIAQCsbJAijzcAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
            val csiParam = makeCSIParams(mac, ch)

            Log.d("CSIBitrateCollector", "Collecting CSI with params: $csiParam")
            val nexutilCommand = "nexutil -Iwlan0 -s500 -b -l34 -v$csiParam"
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
            val outputBuilder = StringBuilder()
            var hasResumed = false
            
            shellExecutor.execute(command) { output, exitCode ->
                if (exitCode != 0 && output.isEmpty() && !hasResumed) {
                    // This is the final callback with the exit code
                    Log.d("CSIBitrateCollector", "CSI parameters command completed with exitCode: $exitCode")
                    val result = outputBuilder.toString().trim()
                    if (result.isNotEmpty()) {
                        Log.d("CSIBitrateCollector", "CSI parameters generated successfully: $result")
                        hasResumed = true
                        continuation.resume(result)
                    } else {
                        Log.e("CSIBitrateCollector", "Failed to generate CSI parameters: Empty result")
                        hasResumed = true
                        continuation.resume("")
                    }
                } else if (!output.isEmpty()) {
                    // This is an intermediate output line, collect it
                    Log.d("CSIBitrateCollector", "Received CSI parameter output: $output")
                    outputBuilder.append(output).append("\n")
                    
                    // If this is the only line we'll get (shellExecutor might exit immediately),
                    // and we haven't resumed yet, resume with this output
                    if (!hasResumed && exitCode == 0 && output.contains("=")) {
                        val result = output.trim()
                        Log.d("CSIBitrateCollector", "CSI parameters single line result: $result")
                        hasResumed = true
                        continuation.resume(result)
                    }
                }
            }
            
            continuation.invokeOnCancellation {
                Log.d("CSIBitrateCollector", "makeCSIParams was cancelled")
                if (!hasResumed) {
                    hasResumed = true
                    continuation.resume("")
                }
            }
        }
    }
}