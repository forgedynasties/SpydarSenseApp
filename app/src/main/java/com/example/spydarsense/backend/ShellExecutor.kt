/*package com.example.spydarsense

import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Log

class ShellExecutor {

    private var process: Process? = null
    private var outputThread: Thread? = null
    private var errorThread: Thread? = null

    fun execute(command: String, callback: (String, Int) -> Unit) {
        try {
            process = Runtime.getRuntime().exec("su -c $command")

            // Capture output in real-time
            outputThread = Thread {
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d("ShellExecutor", "Output: $line")
                    callback(line ?: "", 0) // Stream output line by line
                }
                val exitCode = process?.waitFor() ?: -1
                Log.d("ShellExecutor", "Process exited with code: $exitCode")
                callback("", exitCode) // Notify completion
            }.apply { start() }

            // Capture errors
            errorThread = Thread {
                val reader = BufferedReader(InputStreamReader(process?.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.e("ShellExecutor", "Error: $line")
                }
            }.apply { start() }

        } catch (e: Exception) {
            Log.e("ShellExecutor", "Exception: ${e.message}")
            callback("Error: ${e.message}", -1)
        }
    }

    fun stop() {
        process?.destroy()
        outputThread?.interrupt()
        errorThread?.interrupt()
        Log.d("ShellExecutor", "Process stopped")
    }
}
*/
package com.example.spydarsense.backend

import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Log

class ShellExecutor {

    private var process: Process? = null
    private var outputThread: Thread? = null
    private var errorThread: Thread? = null
    private var isStopped = false

    fun execute(command: String, callback: (String, Int) -> Unit) {
        try {
            process = Runtime.getRuntime().exec("su -c $command")
            isStopped = false

            // Capture output in real-time
            outputThread = Thread {
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                var line: String? = reader.readLine() // Initialize 'line' here
                while (!isStopped && line != null) {
                    Log.d("ShellExecutor", "Output: $line")
                    callback(line, 0) // Stream output line by line
                    line = reader.readLine() // Read the next line
                }
                if (!isStopped) {
                    val exitCode = process?.waitFor() ?: -1
                    Log.d("ShellExecutor", "Process exited with code: $exitCode")
                    callback("", exitCode) // Notify completion
                }
            }.apply { start() }

            // Capture errors
            errorThread = Thread {
                val reader = BufferedReader(InputStreamReader(process?.errorStream))
                var line: String? = reader.readLine() // Initialize 'line' here
                while (!isStopped && line != null) {
                    Log.e("ShellExecutor", "Error: $line")
                    line = reader.readLine() // Read the next line
                }
            }.apply { start() }

        } catch (e: Exception) {
            Log.e("ShellExecutor", "Exception: ${e.message}")
            callback("Error: ${e.message}", -1)
        }
    }

    fun stop() {
        isStopped = true
        process?.destroy()
        outputThread?.interrupt()
        errorThread?.interrupt()
        try {
            outputThread?.join()
            errorThread?.join()
        } catch (e: InterruptedException) {
            Log.e("ShellExecutor", "Interrupted while joining threads: ${e.message}")
        }
        Log.d("ShellExecutor", "Process stopped")
    }
}