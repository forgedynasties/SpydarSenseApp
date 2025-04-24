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
    
    // Capture file paths
    private var csiCaptureFile: String? = null
    private var brCaptureFile: String? = null
    
    // Add a capture completion state
    private val _captureCompleted = MutableStateFlow(false)
    val captureCompleted: StateFlow<Boolean> = _captureCompleted
    
    // Add a trigger for processing
    private val _processingTrigger = MutableStateFlow(0L)
    val processingTrigger: StateFlow<Long> = _processingTrigger
    
    // Add functions to get the current capture files
    fun getCurrentCsiFile(): String? = csiCaptureFile
    fun getCurrentBrFile(): String? = brCaptureFile

    init {
        File(outputDir).mkdirs()
    }

    private fun runTcpdump(interf: String, filter: String, outputFile: String, captureSeconds: Int = 10, preloadLib: String? = null) {
        // Use timeout command to limit capture duration
        val command = if (preloadLib != null) {
            "LD_PRELOAD=$preloadLib timeout $captureSeconds tcpdump -i $interf $filter -s0 -evvv -xx -w $outputFile"
        } else {
            "timeout $captureSeconds tcpdump -i $interf $filter -s0 -evvv -xx -w $outputFile"
        }
        
        Log.d("TcpdumpManager", "Starting time-limited tcpdump capture for $captureSeconds seconds: $outputFile")
        shellExecutor.execute(command) { output, exitCode ->
            if (exitCode == 0 || exitCode == 124) { // 124 is timeout's exit code for normal termination
                Log.d("TcpdumpManager", "tcpdump capture completed: $output (exit code: $exitCode)")
                
                // Check if both captures have completed
                checkCaptureCompletion()
            } else {
                Log.e("TcpdumpManager", "tcpdump capture failed: $output (exit code: $exitCode)")
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

    // Track completion of individual captures
    private var csiCaptureComplete = false
    private var brCaptureComplete = false
    
    private fun checkCaptureCompletion() {
        CoroutineScope(Dispatchers.Main).launch {
            if (csiCaptureFile != null && brCaptureFile != null) {
                val csiFileExists = File(csiCaptureFile!!).exists() && File(csiCaptureFile!!).length() > 0
                val brFileExists = File(brCaptureFile!!).exists() && File(brCaptureFile!!).length() > 0
                
                if (csiFileExists && brFileExists) {
                    Log.d("TcpdumpManager", "Both captures completed successfully")
                    _captureCompleted.value = true
                    _isRunning.value = false
                    
                    // Trigger processing after captures complete
                    _processingTrigger.value = System.currentTimeMillis()
                    Log.d("TcpdumpManager", "Triggering data processing after capture completion")
                }
            }
        }
    }

    fun startCaptures(captureSeconds: Int = 10) {
        if (_isRunning.value) {
            Log.d("TcpdumpManager", "Captures already running, ignoring start request")
            return
        }

        _isRunning.value = true
        _captureCompleted.value = false
        csiCaptureComplete = false
        brCaptureComplete = false
        
        // Generate unique file names for captures
        val timestamp = dateFormat.format(Date())
        csiCaptureFile = "$outputDir/csi_capture_$timestamp.pcap"
        brCaptureFile = "$outputDir/cam_capture_$timestamp.pcap"
        
        // Start time-limited CSI capture
        runTcpdump("wlan0", "dst port 5500", csiCaptureFile!!, captureSeconds)
        
        // Start time-limited bitrate capture
        runTcpdump("wlan0", "ether src $etherSrc", brCaptureFile!!, captureSeconds, "libnexmon.so")
        
        // Update directory lists immediately with the capture files
        _csiDirs.value = _csiDirs.value + csiCaptureFile!!
        _brDirs.value = _brDirs.value + brCaptureFile!!
        
        Log.d("TcpdumpManager", "Started captures for $captureSeconds seconds")
        
        // Monitor for completion
        CoroutineScope(Dispatchers.IO).launch {
            // Add a safety timeout that's slightly longer than the capture time
            delay((captureSeconds * 1000 + 2000).toLong())
            
            if (_isRunning.value) {
                Log.d("TcpdumpManager", "Safety timeout reached, ensuring captures are complete")
                _captureCompleted.value = true
                _isRunning.value = false
                _processingTrigger.value = System.currentTimeMillis()
            }
        }
    }

    fun stopCaptures() {
        if (!_isRunning.value) {
            return
        }
        
        _isRunning.value = false
        stopTcpdump()
        
        // Wait a moment to make sure processes are actually killed
        Thread.sleep(100)
        
        // Check capture completion
        checkCaptureCompletion()
        
        Log.d("TcpdumpManager", "Captures stopped")
    }

    // Add a new method to reset capture files explicitly
    fun resetCaptureFiles() {
        csiCaptureFile = null
        brCaptureFile = null
        _captureCompleted.value = false
        Log.d("TcpdumpManager", "Capture files reset")
    }

    // Add a new method to clear all data
    fun clearData() {
        stopCaptures()
        
        // Clear the directory lists
        _csiDirs.value = emptyList()
        _brDirs.value = emptyList()
        
        // Reset the trigger and completion state
        _processingTrigger.value = 0L
        _captureCompleted.value = false
        
        // Reset capture files
        resetCaptureFiles()
        
        // Force kill any lingering processes
        val command = "pkill tcpdump"
        shellExecutor.execute(command) { _, _ -> }
        
        Log.d("TcpdumpManager", "All data cleared")
    }
}