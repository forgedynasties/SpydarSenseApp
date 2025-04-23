package com.example.spydarsense.backend

import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Log

class ShellExecutor {

    private var process: Process? = null
    private var outputThread: Thread? = null
    private var errorThread: Thread? = null
    private var isStopped = false
    private var currentCommand: String = ""

    fun execute(command: String, callback: (String, Int) -> Unit) {
        try {
            // Store the command for context in error messages
            currentCommand = command
            
            // Log the command being executed
            Log.d("ShellExecutor", "Executing command: $command")
            
            // Determine command type for better context in logs
            val commandType = when {
                command.contains("tcpdump") -> "TCPDUMP"
                command.contains("nexutil") -> "NEXUTIL"
                command.contains("makecsiparams") -> "CSI_PARAMS"
                command.contains("airodump-ng") -> "AIRODUMP"
                else -> "SHELL"
            }
            
            process = Runtime.getRuntime().exec("su -c $command")
            isStopped = false

            // Capture output in real-time
            outputThread = Thread {
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                val outputBuffer = StringBuilder()
                
                try {
                    var line: String? = reader.readLine()
                    while (!isStopped && line != null) {
                        /*if (commandType != "AIRODUMP") {
                            Log.d("ShellExecutor", "[$commandType] Output: $line")

                        }*/
                        outputBuffer.append(line).append("\n")
                        callback(line, 0) // Stream output line by line
                        line = reader.readLine()
                    }
                    
                    if (!isStopped) {
                        val exitCode = process?.waitFor() ?: -1
                        Log.d("ShellExecutor", "[$commandType] Process exited with code: $exitCode")
                        callback("", exitCode) // Notify completion
                    }
                } catch (e: Exception) {
                    Log.e("ShellExecutor", "[$commandType] Error reading output: ${e.message}")
                    callback("Error reading output: ${e.message}", -1)
                }
            }.apply { start() }

            // Capture errors with improved buffering and context
            errorThread = Thread {
                val reader = BufferedReader(InputStreamReader(process?.errorStream))
                val errorBuffer = StringBuilder()
                
                try {
                    var line: String? = reader.readLine()
                    while (!isStopped && line != null) {
                        // Add command context to error message
                        Log.e("ShellExecutor", "[$commandType] Error: $line")
                        errorBuffer.append(line).append("\n")
                        
                        // Don't call callback for every error line - we'll collect them
                        line = reader.readLine()
                    }
                    
                    // If we have collected error messages, report them with context
                    if (errorBuffer.isNotEmpty() && !isStopped) {
                        val errorMsg = "[$commandType] Command error: $errorBuffer"
                        Log.e("ShellExecutor", errorMsg)
                        callback("Error: $errorMsg", -1)
                    }
                } catch (e: Exception) {
                    Log.e("ShellExecutor", "[$commandType] Error reading error stream: ${e.message}")
                }
            }.apply { start() }

        } catch (e: Exception) {
            Log.e("ShellExecutor", "Exception executing '$currentCommand': ${e.message}")
            callback("Error executing command: ${e.message}", -1)
        }
    }

    fun stop() {
        isStopped = true
        process?.destroy()
        
        // Log that we're stopping the process
        Log.d("ShellExecutor", "Stopping process for command: $currentCommand")
        
        outputThread?.interrupt()
        errorThread?.interrupt()
        try {
            outputThread?.join(1000) // Add timeout to avoid hanging
            errorThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.e("ShellExecutor", "Interrupted while joining threads: ${e.message}")
        }
        Log.d("ShellExecutor", "Process stopped for command: $currentCommand")
        currentCommand = "" // Clear the command reference
    }
}