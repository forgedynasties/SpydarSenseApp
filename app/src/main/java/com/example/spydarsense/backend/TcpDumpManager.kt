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
    private val shellExecutor: ShellExecutor,
    private val etherSrc: String
) {

    private val _csiDirs = MutableStateFlow<List<String>>(emptyList())
    val csiDirs: StateFlow<List<String>> = _csiDirs

    private val _brDirs = MutableStateFlow<List<String>>(emptyList())
    val brDirs: StateFlow<List<String>> = _brDirs

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    
    // Stream file paths for continuous capture
    private var csiStreamFile: String? = null
    private var brStreamFile: String? = null
    
    // Processing job for monitoring stream file sizes
    private var streamMonitorJob: Job? = null

    // Add a trigger for processing
    private val _processingTrigger = MutableStateFlow(0L)
    val processingTrigger: StateFlow<Long> = _processingTrigger
    
    // Add functions to get the current streaming files
    fun getCurrentCsiFile(): String? = csiStreamFile
    fun getCurrentBrFile(): String? = brStreamFile

    init {
        File(outputDir).mkdirs()
    }

    private fun runTcpdump(interf: String, filter: String, outputFile: String, preloadLib: String? = null) {
        val command = if (preloadLib != null) {
            "LD_PRELOAD=$preloadLib tcpdump -i $interf $filter -s0 -evvv -xx -w $outputFile"
        } else {
            "tcpdump -i $interf $filter -s0 -evvv -xx -w $outputFile"
        }
        Log.d("TcpdumpManager", "Starting continuous tcpdump: $outputFile")
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
        if (_isRunning.value) {
            Log.d("TcpdumpManager", "Captures already running, ignoring start request")
            return
        }

        _isRunning.value = true
        
        // Generate unique file names for continuous streams
        val timestamp = dateFormat.format(Date())
        csiStreamFile = "$outputDir/csi_stream_$timestamp.pcap"
        brStreamFile = "$outputDir/cam_stream_$timestamp.pcap"
        
        // Start continuous CSI capture
        runTcpdump("wlan0", "dst port 5500", csiStreamFile!!)
        
        // Start continuous bitrate capture
        runTcpdump("wlan0", "ether src $etherSrc", brStreamFile!!, "libnexmon.so")
        
        // Update directory lists immediately with the stream files
        _csiDirs.value = _csiDirs.value + csiStreamFile!!
        _brDirs.value = _brDirs.value + brStreamFile!!
        
        // IMPORTANT: Trigger initial processing immediately
        _processingTrigger.value = System.currentTimeMillis()
        
        // Start monitoring job to check file growth and trigger processing
        streamMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            var lastCsiSize = 0L
            var lastBrSize = 0L
            var forceTriggerCounter = 0
            
            while (isActive && _isRunning.value) {
                delay(300) // Check more frequently (300ms)
                
                var shouldTriggerProcessing = false
                forceTriggerCounter++
                
                // Force trigger processing every ~3 seconds even if files don't appear to grow
                if (forceTriggerCounter >= 10) {
                    shouldTriggerProcessing = true
                    forceTriggerCounter = 0
                    Log.d("TcpdumpManager", "Forcing data processing trigger (periodic)")
                }
                
                csiStreamFile?.let { file ->
                    val currentSize = File(file).length()
                    if (currentSize > lastCsiSize + 10) { // Lower threshold to 10 bytes for more sensitivity
                        Log.d("TcpdumpManager", "CSI stream file has grown: $currentSize bytes (+${currentSize - lastCsiSize})")
                        lastCsiSize = currentSize
                        shouldTriggerProcessing = true
                    }
                }
                
                brStreamFile?.let { file ->
                    val currentSize = File(file).length()
                    if (currentSize > lastBrSize + 10) { // Lower threshold to 10 bytes for more sensitivity
                        Log.d("TcpdumpManager", "Bitrate stream file has grown: $currentSize bytes (+${currentSize - lastBrSize})")
                        lastBrSize = currentSize
                        shouldTriggerProcessing = true
                    }
                }
                
                if (shouldTriggerProcessing) {
                    // Trigger processing by emitting a new timestamp
                    _processingTrigger.value = System.currentTimeMillis()
                    Log.d("TcpdumpManager", "Triggering data processing")
                }
            }
        }
    }

    fun stopCaptures() {
        if (!_isRunning.value) {
            return
        }
        
        _isRunning.value = false
        stopTcpdump()
        streamMonitorJob?.cancel()
        
        // Reset stream files when stopping capture
        csiStreamFile = null
        brStreamFile = null
        
        Log.d("TcpdumpManager", "Captures stopped")
    }

    // Add a new method to clear all data
    fun clearData() {
        stopCaptures()
        
        // Clear the directory lists
        _csiDirs.value = emptyList()
        _brDirs.value = emptyList()
        
        // Reset the trigger
        _processingTrigger.value = 0L
        
        Log.d("TcpdumpManager", "All data cleared")
    }
}