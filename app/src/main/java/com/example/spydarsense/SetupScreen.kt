package com.example.spydarsense

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.example.spydarsense.backend.ShellExecutor
import com.example.spydarsense.components.AppButton
import com.example.spydarsense.components.AppOutlinedButton
import com.example.spydarsense.components.AppProgressIndicator
import com.example.spydarsense.components.AppTopBar
import com.example.spydarsense.components.CollapsibleSection
import com.example.spydarsense.components.LoadingIndicator
import com.example.spydarsense.ui.theme.SpydarSenseTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(navController: NavController) {
    val commands = listOf(
        Command("Check Root Access", "su -c 'echo Root access granted'", "Root access granted"),
        Command("Enable Wi-Fi Interface", "ip link set wlan0 up", ""),
        Command("Enable Monitor Mode", "nexutil -Iwlan0 -m1 && nexutil -m", "monitor: 1")
    )

    val completedCommands = remember { mutableStateListOf<Boolean>() }
    val allCommandsCompleted = remember(completedCommands) { 
        completedCommands.isNotEmpty() && completedCommands.all { it } 
    }
    
    // Track if auto-setup is running
    var isAutoSetupRunning by remember { mutableStateOf(false) }

    // Initialize completedCommands with the correct size and all false
    LaunchedEffect(commands) {
        completedCommands.clear() // Ensure we start fresh
        completedCommands.addAll(List(commands.size) { false })
    }

    val shellExecutor = remember { ShellExecutor() }
    // Initialize progress to 0f explicitly
    val progressAnimation = remember { Animatable(0f) }
    
    // Update progress animation whenever completedCommands changes
    LaunchedEffect(completedCommands) {
        val completedCount = completedCommands.count { it }
        val targetValue = if (commands.isEmpty()) 1f else completedCount.toFloat() / commands.size
        Log.d("SetupScreen", "Updating progress: $completedCount/${commands.size} = $targetValue")
        progressAnimation.animateTo(
            targetValue = targetValue,
            animationSpec = tween(500, easing = FastOutSlowInEasing)
        )
    }

    // Function to execute a command and update its status
    val executeCommand = { index: Int, command: Command ->
        if (index < completedCommands.size && !completedCommands[index]) {
            shellExecutor.execute(command.command) { output, exitCode ->
                if (exitCode == 0) {
                    if (output.contains(command.expectedOutput) || command.expectedOutput.isEmpty()) {
                        completedCommands[index] = true
                    }
                }
            }
        }
    }

    // Coroutine scope for running commands
    val coroutineScope = rememberCoroutineScope()
    
    // Function to execute all commands in sequence
    val executeAllCommands: () -> Unit = {
        isAutoSetupRunning = true
        coroutineScope.launch {
            commands.forEachIndexed { index, command ->
                try {
                    val output = executeCommand(command.command, shellExecutor)
                    if (output.contains(command.expectedOutput) || command.expectedOutput.isEmpty()) {
                        completedCommands[index] = true
                    }
                } catch (e: Exception) {
                    Log.e("SetupScreen", "Error executing command ${command.description}: ${e.message}")
                }
            }
            isAutoSetupRunning = false
        }
        // No need to return anything - the explicit type declaration handles it
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "Initial Setup")
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {



            if (commands.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(commands) { command ->
                        CommandItem(
                            command = command,
                            isCompleted = completedCommands.getOrNull(commands.indexOf(command)) ?: false,
                            isExecuting = isAutoSetupRunning && !completedCommands[commands.indexOf(command)],
                            onExecute = { output ->
                                if (output == command.expectedOutput || command.expectedOutput.isEmpty()) {
                                    completedCommands[commands.indexOf(command)] = true
                                }
                            },
                            shellExecutor = shellExecutor
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No commands to execute.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            // Next button
            AppButton(
                text = "Continue",
                onClick = { navController.navigate("home") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .height(56.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandItem(
    command: Command,
    isCompleted: Boolean,
    isExecuting: Boolean = false, // Add parameter to indicate if this command is being auto-executed
    onExecute: (String) -> Unit,
    shellExecutor: ShellExecutor
) {
    val coroutineScope = rememberCoroutineScope()
    var isManuallyExecuting by remember { mutableStateOf(false) }
    
    // The command is executing if either it's being auto-executed or manually executed
    val isCurrentlyExecuting = isExecuting || isManuallyExecuting
    
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = command.description,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                
                // Status emoji instead of icon
                when {
                    isCompleted -> Text(
                        text = "✅",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(4.dp)
                    )
                    isCurrentlyExecuting -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    else -> Text(
                        text = "⏳",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(4.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Command text - only show if not completed
            AnimatedVisibility(
                visible = !isCompleted,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = command.command,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
            
            // Button row for consistent alignment
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isCompleted) {
                    AppOutlinedButton(
                        text = "Execute",
                        onClick = {
                            isManuallyExecuting = true
                            coroutineScope.launch {
                                try {
                                    val output = executeCommand(command.command, shellExecutor)
                                    onExecute(output)
                                } catch (e: Exception) {
                                    Log.e("CommandItem", "Command execution failed: ${e.message}")
                                } finally {
                                    isManuallyExecuting = false
                                }
                            }
                        },
                        enabled = !isCurrentlyExecuting,
                        modifier = Modifier.height(40.dp)
                    )
                } else {
                    Text(
                        "✓ Completed",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
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