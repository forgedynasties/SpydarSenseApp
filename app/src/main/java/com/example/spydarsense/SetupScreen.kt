package com.example.spydarsense

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.example.spydarsense.backend.ShellExecutor
import com.example.spydarsense.ui.theme.SpydarSenseTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.example.spydarsense.components.ThemeToggle
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(navController: NavController) {
    val commands = listOf(
        Command("Check Root Access", "su -c 'echo Root access granted'", "Root access granted"),
        Command("Enable Wi-Fi Interface", "ip link set wlan0 up", ""),
        Command("Enable Monitor Mode", "nexutil -Iwlan0 -m1 && nexutil -m", "monitor: 1")
    )

    val completedCommands = remember { mutableStateListOf<Boolean>() }

    LaunchedEffect(commands) {
        completedCommands.addAll(List(commands.size) { false })
    }

    val shellExecutor = remember { ShellExecutor() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    ThemeToggle()
                }
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    navController.navigate("home")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                enabled = true,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Next", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (commands.isNotEmpty()) {
                LazyColumn {
                    items(commands) { command ->
                        CommandItem(
                            command = command,
                            isCompleted = completedCommands.getOrNull(commands.indexOf(command)) ?: false,
                            onExecute = { output ->
                                if (output == command.expectedOutput || command.expectedOutput.isEmpty()) {
                                    completedCommands[commands.indexOf(command)] = true
                                }
                            },
                            shellExecutor = shellExecutor
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            } else {
                Text(
                    text = "No commands to execute.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun CommandItem(
    command: Command,
    isCompleted: Boolean,
    onExecute: (String) -> Unit,
    shellExecutor: ShellExecutor
) {
    val coroutineScope = rememberCoroutineScope()
    val cardColor = if (isCompleted) 
        MaterialTheme.colorScheme.primaryContainer 
    else 
        MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = command.description,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Command: ${command.command}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isCompleted) "✅ Completed" else "❌ Pending",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCompleted) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val output = executeCommand(command.command, shellExecutor)
                            onExecute(output)
                        } catch (e: Exception) {
                            Log.e("CommandItem", "Command execution failed: ${e.message}")
                        }
                    }
                },
                enabled = !isCompleted,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Execute")
            }
        }
    }
}

// Simulate command execution
suspend fun executeCommand(command: String, shellExecutor: ShellExecutor): String {
    return suspendCancellableCoroutine { continuation ->
        shellExecutor.execute(command) { output, exitCode ->
            // Log the output and exit code
            Log.d("executeCommand", "Command: $command, Output: $output, Exit Code: $exitCode")

            if (continuation.isActive) {
                if (exitCode == 0) {
                    // If the command succeeds, return the output (or a success message if there is no output)
                    if (output.isEmpty()) {
                        continuation.resume("Command executed successfully")
                    } else {
                        continuation.resume(output)
                    }
                } else {
                    // If the command fails, throw an exception
                    continuation.resumeWithException(Exception("Command failed with exit code $exitCode"))
                }
            }
        }
    }
}

// Data class for commands
data class Command(
    val description: String,
    val command: String,
    val expectedOutput: String
)

@Preview(showBackground = false)
@Composable
fun PreviewSetupScreen() {
    SpydarSenseTheme(darkTheme = true) {
        SetupScreen(navController = rememberNavController())
    }
}