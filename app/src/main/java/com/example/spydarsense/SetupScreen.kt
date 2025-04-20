package com.example.spydarsense

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.example.spydarsense.components.ThemeToggle
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

    LaunchedEffect(commands) {
        completedCommands.addAll(List(commands.size) { false })
    }

    val shellExecutor = remember { ShellExecutor() }
    val progressAnimation = remember { Animatable(0f) }
    
    LaunchedEffect(completedCommands) {
        val completedCount = completedCommands.count { it }
        val targetValue = if (commands.isEmpty()) 1f else completedCount.toFloat() / commands.size
        progressAnimation.animateTo(
            targetValue = targetValue,
            animationSpec = tween(500, easing = FastOutSlowInEasing)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Initial Setup", 
                        fontWeight = FontWeight.Medium
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    ThemeToggle()
                }
            )
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
            // Progress indicator
            LinearProgressIndicator(
                progress = { progressAnimation.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .height(8.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.primary
            )
            
            Text(
                text = "Complete these steps to set up Spydar Sense",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

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
            Button(
                onClick = {
                    navController.navigate("home")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .height(56.dp),
                enabled = true,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 0.dp
                )
            ) {
                Text(
                    "Continue to Home",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandItem(
    command: Command,
    isCompleted: Boolean,
    onExecute: (String) -> Unit,
    shellExecutor: ShellExecutor
) {
    val coroutineScope = rememberCoroutineScope()
    var isExecuting by remember { mutableStateOf(false) }
    
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
                    isExecuting -> CircularProgressIndicator(
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
                    OutlinedButton(
                        onClick = {
                            isExecuting = true
                            coroutineScope.launch {
                                try {
                                    val output = executeCommand(command.command, shellExecutor)
                                    onExecute(output)
                                } catch (e: Exception) {
                                    Log.e("CommandItem", "Command execution failed: ${e.message}")
                                } finally {
                                    isExecuting = false
                                }
                            }
                        },
                        enabled = !isExecuting,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Text("Execute")
                    }
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