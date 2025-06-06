package com.example.spydarsense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.spydarsense.backend.SpyCameraDetector
import com.example.spydarsense.ui.theme.SpydarSenseTheme
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.spydarsense.components.*
import com.example.spydarsense.backend.AlignedFeature
import kotlinx.coroutines.delay
import kotlin.math.min
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import com.example.spydarsense.backend.SpyCamClassifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectSpyCamScreen(sessionId: String, stationMac: String, apMac: String, pwr: Int, ch: Int) {
    val detector = remember { SpyCameraDetector.getInstance(stationMac) }
    var isCollecting by remember { mutableStateOf(false) }
    var showExtraStats by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val df = remember { DecimalFormat("#.##") }
    val scrollState = rememberScrollState()

    // Get state from detector
    val captureCompleted by detector.captureCompleted.collectAsState()
    val isProcessing by detector.isProcessing.collectAsState()
    
    // Add state for capture duration and processing stage
    var selectedDuration by remember { mutableStateOf(10) } // Default 10 seconds
    var showDurationDropdown by remember { mutableStateOf(false) }
    
    // Get processing stage from detector
    val processingStage by detector.processingStage.collectAsState()
    val processingProgress by detector.processingProgress.collectAsState()
    
    // State for showing loading dialog
    var showLoadingDialog by remember { mutableStateOf(false) }
    
    // Add state for classification results
    var classificationResult by remember { mutableStateOf<SpyCamClassifier.ClassificationResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }
    
    // Create classifier instance
    val classifier = remember { SpyCamClassifier() }

    // Clear previous detection data when this screen is first shown
    LaunchedEffect(sessionId) {
        // Stop any ongoing detection and clear buffers with a small delay
        // to ensure all processes are properly terminated
        detector.stopDetection()
        delay(200) // Small delay to ensure cleanup completes
        detector.clearBuffers()
        Log.d("DetectSpyCamScreen", "New detection session started: $sessionId for MAC: $stationMac")
    }

    // Handle screen lifecycle - stop collecting when screen is disposed
    DisposableEffect(key1 = sessionId) {
        onDispose {
            Log.d("DetectSpyCamScreen", "Cleaning up detection session: $sessionId")
            // Stop any ongoing detection when navigating away
            if (isCollecting) {
                detector.stopDetection()
                isCollecting = false
            }
            
            // Explicitly kill any lingering processes
            detector.forceReset()
        }
    }

    // Get all directories and the latest for display
    val csiDirs by detector.csiDirs.collectAsState(emptyList())
    val brDirs by detector.brDirs.collectAsState(emptyList())
    
    // Collect statistics
    val csiStats by detector.csiStats.collectAsState()
    val bitrateStats by detector.bitrateStats.collectAsState()
    
    // Collect PCA features and timeline data
    val csiPcaFeatures by detector.csiPcaFeatures.collectAsState()
    val bitratePcaFeatures by detector.bitratePcaFeatures.collectAsState()
    val csiTimeline by detector.csiTimeline.collectAsState()
    val bitrateTimeline by detector.bitrateTimeline.collectAsState()
    
    // Collect aligned features
    val alignedFeatures by detector.alignedFeatures.collectAsState()
    
    // Collect the processing trigger to update UI when new data is available
    val processingTrigger by detector.processingTrigger.collectAsState()
    
    // Update capturing state when completed
    LaunchedEffect(captureCompleted) {
        if (captureCompleted && isCollecting) {
            isCollecting = false
        }
    }
    
    // Update loading dialog visibility based on processing state
    LaunchedEffect(isCollecting, isProcessing, captureCompleted) {
        showLoadingDialog = isCollecting || isProcessing
    }
    
    // Show loading dialog if needed
    if (showLoadingDialog) {
        ProcessingDialog(
            processingStage = processingStage,
            progress = processingProgress,
            onDismiss = { 
                if (!isCollecting) {
                    showLoadingDialog = false
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            topBar = {
                AppTopBar(title = "Detection")
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Target device info card
                AppElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Target Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        // Device information
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Station MAC:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stationMac,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Associated AP:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = apMac,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            InfoChip(label = "Channel", value = "$ch")
                            InfoChip(label = "Signal", value = "$pwr dBm")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Add capture duration selector
                        Text(
                            text = "Capture Duration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Duration selector as segmented buttons
                            DurationSelector(
                                selectedDuration = selectedDuration,
                                onDurationSelected = { selectedDuration = it }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Start/Stop Button - update based on capture state
                        val buttonScale = animateFloatAsState(
                            targetValue = if (isCollecting) 1.05f else 1f,
                            label = "buttonScale"
                        )
                        
                        // Button text depends on the current state
                        val buttonText = when {
                            isCollecting -> "Stop Detection (${if (captureCompleted) "Complete" else "Recording..."})"
                            captureCompleted -> "Start New Detection"
                            isProcessing -> "Processing Data..."
                            else -> "Start Detection"
                        }
                        
                        // Button color depends on the current state
                        val buttonColor = when {
                            isCollecting -> Color.Red
                            isProcessing -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        
                        // Button is disabled during processing
                        Button(
                            onClick = {
                                if (isCollecting) {
                                    detector.stopDetection()
                                    isCollecting = false
                                } else {
                                    coroutineScope.launch {
                                        detector.startDetection(stationMac, ch, selectedDuration)
                                        isCollecting = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(buttonScale.value),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor
                            ),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isProcessing
                        ) {
                            Text(buttonText)
                        }
                        
                        // Show capture status message
                        if (captureCompleted) {
                            Text(
                                text = "capture completed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                textAlign = TextAlign.Center
                            )
                        } else if (isProcessing) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Processing data...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
                
                // Stats summary card
                AppElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Detection Statistics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        // CSI Data
                        Text(
                            text = "CSI Data",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatsInfoItem(
                                label = "Samples",
                                value = "${csiStats?.sampleCount ?: 0}"
                            )
                            StatsInfoItem(
                                label = "Avg Amplitude",
                                value = df.format(csiStats?.avgAmplitude ?: 0f)
                            )
                            StatsInfoItem(
                                label = "Max Amplitude",
                                value = df.format(csiStats?.maxAmplitude ?: 0f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Bitrate Data
                        Text(
                            text = "Bitrate Data",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatsInfoItem(
                                label = "Samples",
                                value = "${bitrateStats?.sampleCount ?: 0}"
                            )
                            StatsInfoItem(
                                label = "Avg Bitrate",
                                value = "${df.format(bitrateStats?.avgBitrate ?: 0f)} Mbps"
                            )
                            StatsInfoItem(
                                label = "Max Bitrate",
                                value = "${bitrateStats?.maxBitrate ?: 0} Mbps"
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Remove progress indicator and replace with analysis status
                        val hasData = (csiStats?.sampleCount ?: 0) > 0 || (bitrateStats?.sampleCount ?: 0) > 0
                        
                        if (isCollecting) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (hasData) "Collecting data..." else "Waiting for data...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (hasData) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Analysis complete",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        // Action button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            AppOutlinedButton(
                                text = "Clear Data",
                                onClick = { 
                                    detector.clearBuffers()
                                    if (isCollecting) {
                                        isCollecting = false
                                    }
                                }
                            )
                        }
                    }
                }
                
                // Data visualization section
                AppElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Signal Patterns",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        // Show processing or results status
                        if (isProcessing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Processing capture data...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else if (captureCompleted && alignedFeatures.isNotEmpty()) {
                            // Raw Bitrate Data Visualization
                            if (bitrateTimeline.isNotEmpty()) {
                                Text(
                                    text = "Raw Bitrate Data",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                
                                BitrateTimelineChart(
                                    title = "",
                                    data = bitrateTimeline.toList(),
                                    height = 150.dp
                                )
                                
                                Divider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                            
                            // Aligned Features Visualization
                            Text(
                                text = "CSI and Bitrate Combined View",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            AlignedFeaturesChart(
                                title = "",
                                features = alignedFeatures,
                                height = 200.dp
                            )
                            
                            // Add textual representation of features
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = "Feature Values",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            // Display feature data in a scrollable row
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(alignedFeatures.take(15)) { feature ->
                                    FeatureDataCard(feature = feature)
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Swipe to see more data points →",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        } else {
                            EmptyDataIndicator(
                                text = if (captureCompleted) 
                                    "Capture completed but no features detected" 
                                else 
                                    "Start a 10-second detection to see results"
                            )
                        }
                        
                        // Show classification results if available
                        if (classificationResult != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            ClassificationResultCard(
                                result = classificationResult!!,
                                onDismiss = { classificationResult = null }
                            )
                            
                            // Show correlation visualization
                            if (classificationResult!!.detectionPoints.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    text = "Correlation Analysis",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                CorrelationVisualization(
                                    features = alignedFeatures,
                                    classificationResult = classificationResult!!,
                                    height = 180.dp
                                )
                            }
                        }
                    }
                }
                
                // Action buttons at the bottom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AppOutlinedButton(
                        text = "Clear Data",
                        onClick = { 
                            detector.clearBuffers()
                            classificationResult = null
                            if (isCollecting) {
                                isCollecting = false
                            }
                        }
                    )
                    
                    if (captureCompleted && alignedFeatures.isNotEmpty()) {
                        AppOutlinedButton(
                            text = "Analyze Results",
                            onClick = {
                                // Use the classifier to analyze the aligned features
                                coroutineScope.launch {
                                    // Show loading first
                                    showLoadingDialog = true
                                    detector.updateProcessingStage("Analyzing patterns...")
                                    detector.updateProcessingProgress(0.5f)
                                    
                                    // Small delay for UI feedback
                                    delay(500)
                                    
                                    // Run the classifier
                                    val result = classifier.analyze(alignedFeatures)
                                    classificationResult = result
                                    
                                    // Hide loading dialog
                                    showLoadingDialog = false
                                    
                                    // Show result dialog
                                    showResultDialog = true
                                }
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    
    // Show result dialog if needed
    if (showResultDialog && classificationResult != null) {
        ClassificationResultDialog(
            result = classificationResult!!,
            onDismiss = { showResultDialog = false }
        )
    }
}

@Composable
fun InfoChip(label: String, value: String) {
    Card(
        modifier = Modifier.padding(end = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label: ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun StatsInfoItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun EmptyDataIndicator(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun TimelineChart(
    title: String,
    data: List<Pair<Double, List<Float>>>,
    height: Dp
) {
    // Extract the color from MaterialTheme before entering the Canvas scope
    val primaryColor = MaterialTheme.colorScheme.primary
    
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(vertical = 8.dp)
        ) {
            if (data.isEmpty()) {
                // Use direct drawing APIs instead of composable functions
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                
                drawContext.canvas.nativeCanvas.drawText(
                    "Insufficient data for visualization",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
                return@Canvas
            }
            
            val width = size.width
            val canvasHeight = size.height
            
            // Flatten the data for visualization
            val flatData = data.flatMap { (time, values) -> 
                values.map { value -> Pair(time, value) }
            }
            
            // Find min/max values
            val timeRange = if (flatData.size > 1) {
                flatData.maxOf { it.first } - flatData.minOf { it.first }
            } else {
                1.0 // Default to avoid division by zero
            }
            
            val maxValue = if (flatData.isNotEmpty()) {
                flatData.maxOf { it.second }
            } else {
                1.0f // Default to avoid division by zero
            }
            
            // Draw the timeline
            if (timeRange > 0 && maxValue > 0 && flatData.size > 1) {
                val minTime = flatData.minOf { it.first }
                
                flatData.forEachIndexed { index, (time, value) ->
                    if (index > 0) {
                        val prev = flatData[index - 1]
                        
                        val x1 = ((prev.first - minTime) / timeRange * width).toFloat()
                        val y1 = (canvasHeight - (prev.second / maxValue * canvasHeight * 0.8f) - canvasHeight * 0.1f).toFloat()
                        
                        val x2 = ((time - minTime) / timeRange * width).toFloat()
                        val y2 = (canvasHeight - (value / maxValue * canvasHeight * 0.8f) - canvasHeight * 0.1f).toFloat()
                        
                        drawLine(
                            color = primaryColor, // Use the captured color
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            } else {
                // Draw text using the native canvas API
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "Insufficient data for visualization",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
            }
        }
    }
}

@Composable
fun BitrateTimelineChart(
    title: String,
    data: List<Pair<Double, Int>>,
    height: Dp
) {
    // Extract the color from MaterialTheme before entering the Canvas scope
    val secondaryColor = MaterialTheme.colorScheme.secondary
    
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(vertical = 8.dp)
        ) {
            if (data.isEmpty()) {
                // Draw text using the native canvas API
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "Insufficient data for visualization",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
                return@Canvas
            }
            
            val width = size.width
            val canvasHeight = size.height
            
            // Find min/max values
            val timeRange = if (data.size > 1) {
                data.maxOf { it.first } - data.minOf { it.first }
            } else {
                1.0 // Default to avoid division by zero
            }
            
            val maxValue = if (data.isNotEmpty()) {
                data.maxOf { it.second }.toFloat()
            } else {
                1.0f // Default to avoid division by zero
            }
            
            // Draw the timeline
            if (timeRange > 0 && maxValue > 0 && data.size > 1) {
                val minTime = data.minOf { it.first }
                
                data.forEachIndexed { index, (time, value) ->
                    if (index > 0) {
                        val prev = data[index - 1]
                        
                        val x1 = ((prev.first - minTime) / timeRange * width).toFloat()
                        val y1 = (canvasHeight - (prev.second / maxValue * canvasHeight * 0.8f) - canvasHeight * 0.1f).toFloat()
                        
                        val x2 = ((time - minTime) / timeRange * width).toFloat()
                        val y2 = (canvasHeight - (value / maxValue * canvasHeight * 0.8f) - canvasHeight * 0.1f).toFloat()
                        
                        drawLine(
                            color = secondaryColor, // Use the captured color
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            } else {
                // Draw text using the native canvas API
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "Insufficient data for visualization",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
            }
        }
    }
}

// Add new chart composable for aligned features
@Composable
fun AlignedFeaturesChart(
    title: String,
    features: List<AlignedFeature>,
    height: Dp
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(vertical = 8.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                )
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            if (features.isEmpty()) {
                // Draw text for empty data
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 14f * density
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "No aligned feature data available",
                    size.width / 2f,
                    size.height / 2f,
                    paint
                )
                return@Canvas
            }
            
            val width = size.width
            val canvasHeight = size.height
            
            // Find max values for scaling
            val maxCsiValue = features.maxOf { it.csiFeature }.coerceAtLeast(0.01f)
            val maxBitrateValue = features.maxOf { it.bitrateFeature }.coerceAtLeast(1)
            
            // Get the max timestamp to correctly scale the x-axis
            val maxTimestamp = features.maxOf { it.timestamp }
            
            // Add padding to avoid drawing on the edges
            val padding = 16f
            val drawWidth = width - 2 * padding
            val drawHeight = canvasHeight - 2 * padding
            
            // Draw horizontal center line (separator)
            drawLine(
                color = Color.Gray.copy(alpha =.2f),
                start = Offset(padding, canvasHeight / 2),
                end = Offset(width - padding, canvasHeight / 2),
                strokeWidth = 1f
            )
            
            // Draw CSI feature line (top half)
            for (i in 1 until features.size) {
                val prev = features[i-1]
                val curr = features[i]
                
                // Scale x position by max timestamp to ensure full range is displayed
                val x1 = padding + (prev.timestamp / maxTimestamp * drawWidth).toFloat()
                val y1 = padding + (drawHeight / 2) * (1 - prev.csiFeature / maxCsiValue)
                
                val x2 = padding + (curr.timestamp / maxTimestamp * drawWidth).toFloat()
                val y2 = padding + (drawHeight / 2) * (1 - curr.csiFeature / maxCsiValue)
                
                drawLine(
                    color = primaryColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
                
                // Draw circle at each data point for CSI
                drawCircle(
                    color = primaryColor.copy(alpha = 0.7f),
                    radius = 2f,
                    center = Offset(x2, y2)
                )
            }
            
            // Draw Bitrate feature line (bottom half)
            for (i in 1 until features.size) {
                val prev = features[i-1]
                val curr = features[i]
                
                // Scale x position by max timestamp to ensure full range is displayed
                val x1 = padding + (prev.timestamp / maxTimestamp * drawWidth).toFloat()
                val y1 = canvasHeight / 2 + padding + (drawHeight / 2) * (prev.bitrateFeature.toFloat() / maxBitrateValue)
                
                val x2 = padding + (curr.timestamp / maxTimestamp * drawWidth).toFloat()
                val y2 = canvasHeight / 2 + padding + (drawHeight / 2) * (curr.bitrateFeature.toFloat() / maxBitrateValue)
                
                drawLine(
                    color = secondaryColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
                
                // Draw circle at each data point for Bitrate
                drawCircle(
                    color = secondaryColor.copy(alpha = 0.7f),
                    radius = 2f,
                    center = Offset(x2, y2)
                )
            }
            
            // Draw time markers - adjusted to use maxTimestamp instead of features.last().timestamp
            val timeMarkers = 5
            for (i in 0..timeMarkers) {
                val time = (maxTimestamp * i / timeMarkers)
                val x = padding + (time / maxTimestamp * drawWidth).toFloat()
                
                // Draw tick marks
                drawLine(
                    color = Color.Gray.copy(alpha = 0.5f),
                    start = Offset(x, canvasHeight - padding/2),
                    end = Offset(x, canvasHeight),
                    strokeWidth = 1f
                )
                
                // Draw time labels
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 10f * density
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "%.1f".format(time),
                    x,
                    canvasHeight,
                    paint
                )
            }
            
            // Draw legend
            val legendPaint = android.graphics.Paint().apply {
                textAlign = android.graphics.Paint.Align.LEFT
                textSize = 10f * density
            }
            
            // CSI legend
            legendPaint.color = primaryColor.toArgb()
            drawContext.canvas.nativeCanvas.drawText("CSI", padding, padding - 2, legendPaint)
            
            // Bitrate legend
            legendPaint.color = secondaryColor.toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                "Bitrate", 
                padding, 
                canvasHeight / 2 + padding - 2, 
                legendPaint
            )
        }
    }
}

@Composable
fun FeatureDataCard(feature: AlignedFeature) {
    Card(
        modifier = Modifier
            .width(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "T: ${String.format("%.1f", feature.timestamp)}s",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Divider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
            
            Text(
                text = "CSI: ${String.format("%.2f", feature.csiFeature)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            Text(
                text = "BR: ${feature.bitrateFeature} Mbps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun DurationSelector(
    selectedDuration: Int,
    onDurationSelected: (Int) -> Unit
) {
    // Declare dropdown state at the component level
    var showDurationDropdown by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Dropdown trigger button - fix the onClick handler to update the state
        OutlinedButton(
            onClick = { showDurationDropdown = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$selectedDuration seconds" + if (selectedDuration == 10) " (recommended)" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select duration"
                )
            }
        }

        // Dropdown menu - removed redundant declaration of showDurationDropdown
        DropdownMenu(
            expanded = showDurationDropdown,
            onDismissRequest = { showDurationDropdown = false },
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Duration options
            val durations = listOf(5, 10, 15)
            
            durations.forEach { duration ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$duration seconds",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (duration == 10) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "(recommended)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary, // Highlight color for recommended option
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    },
                    onClick = {
                        onDurationSelected(duration)
                        showDurationDropdown = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = if (duration == selectedDuration) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}

@Composable
fun ProcessingDialog(
    processingStage: String,
    progress: Float,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text(
                text = "Processing",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Just show a rotating loading animation without progress indication
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp)
                )
                
                Text(
                    text = processingStage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun ClassificationResultCard(
    result: SpyCamClassifier.ClassificationResult,
    onDismiss: () -> Unit
) {
    val backgroundColor = when {
        result.isSpyCam && result.confidence > 0.7f -> MaterialTheme.colorScheme.errorContainer
        result.isSpyCam -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    
    val textColor = when {
        result.isSpyCam && result.confidence > 0.7f -> MaterialTheme.colorScheme.onErrorContainer
        result.isSpyCam -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (result.isSpyCam) "Spy Camera Detected" else "Not a Spy Camera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                
                // Add confidence indicator
                if (result.isSpyCam) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "Confidence: ${(result.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            
            Divider(color = textColor.copy(alpha = 0.3f))
            
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
            
            // Add detection statistics
            if (result.csiChangePoints.isNotEmpty() || result.bitrateChangePoints.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Detection statistics:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                
                BulletText(
                    text = "${result.csiChangePoints.size} CSI change points detected",
                    textColor = textColor
                )
                
                BulletText(
                    text = "${result.bitrateChangePoints.size} bitrate change points detected",
                    textColor = textColor
                )
                
                BulletText(
                    text = "${result.detectionPoints.size} correlated changes detected",
                    textColor = textColor
                )
            }
            
            // Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
fun BulletText(
    text: String,
    textColor: Color
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(start = 8.dp)
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor
        )
    }
}

@Composable
fun ClassificationResultDialog(
    result: SpyCamClassifier.ClassificationResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (result.isSpyCam) "Spy Camera Detected" else "Not a Spy Camera",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (result.isSpyCam) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                if (result.isSpyCam) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "What should you do?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Inspect the device more carefully\n" +
                               "• Consider turning it off or disconnecting it\n" +
                               "• Look for unusual features or holes\n" +
                               "• If possible, contact the device owner",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (result.isSpyCam) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Understood")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun CorrelationVisualization(
    features: List<AlignedFeature>,
    classificationResult: SpyCamClassifier.ClassificationResult,
    height: Dp
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val correlationColor = MaterialTheme.colorScheme.error
    
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 8.dp)
            .border(
                width = 1.dp, 
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        if (features.isEmpty()) {
            // Draw text for empty data
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.GRAY
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 14f * density
            }
            drawContext.canvas.nativeCanvas.drawText(
                "No data available for visualization",
                size.width / 2f,
                size.height / 2f,
                paint
            )
            return@Canvas
        }
        
        val width = size.width
        val canvasHeight = size.height
        
        // Find max values for scaling
        val maxCsiValue = features.maxOf { it.csiFeature }.coerceAtLeast(0.01f)
        val maxBitrateValue = features.maxOf { it.bitrateFeature }.coerceAtLeast(1)
        
        // Get the max timestamp for scaling
        val maxTimestamp = features.maxOf { it.timestamp }
        
        // Add padding to avoid drawing on the edges
        val padding = 16f
        val drawWidth = width - 2 * padding
        val drawHeight = canvasHeight - 2 * padding
        
        // Draw horizontal center line (separator)
        drawLine(
            color = Color.Gray.copy(alpha =.2f),
            start = Offset(padding, canvasHeight / 2),
            end = Offset(width - padding, canvasHeight / 2),
            strokeWidth = 1f
        )
        
        // First, draw CSI and bitrate lines
        // CSI line (top half)
        for (i in 1 until features.size) {
            val prev = features[i-1]
            val curr = features[i]
            
            val x1 = padding + (prev.timestamp / maxTimestamp * drawWidth).toFloat()
            val y1 = padding + (drawHeight / 2) * (1 - prev.csiFeature / maxCsiValue)
            
            val x2 = padding + (curr.timestamp / maxTimestamp * drawWidth).toFloat()
            val y2 = padding + (drawHeight / 2) * (1 - curr.csiFeature / maxCsiValue)
            
            drawLine(
                color = primaryColor,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
        
        // Bitrate line (bottom half)
        for (i in 1 until features.size) {
            val prev = features[i-1]
            val curr = features[i]
            
            val x1 = padding + (prev.timestamp / maxTimestamp * drawWidth).toFloat()
            val y1 = canvasHeight / 2 + padding + (drawHeight / 2) * (prev.bitrateFeature.toFloat() / maxBitrateValue)
            
            val x2 = padding + (curr.timestamp / maxTimestamp * drawWidth).toFloat()
            val y2 = canvasHeight / 2 + padding + (drawHeight / 2) * (curr.bitrateFeature.toFloat() / maxBitrateValue)
            
            drawLine(
                color = secondaryColor,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
        
        // Now highlight CSI change points
        for (timestamp in classificationResult.csiChangePoints) {
            // Find matching feature
            val closestFeature = features.minByOrNull { 
                Math.abs(it.timestamp - timestamp) 
            } ?: continue
            
            val x = padding + (closestFeature.timestamp / maxTimestamp * drawWidth).toFloat()
            val y = padding + (drawHeight / 2) * (1 - closestFeature.csiFeature / maxCsiValue)
            
            // Draw a circle to highlight the change point
            drawCircle(
                color = primaryColor.copy(alpha = 0.7f),
                radius = 5f,
                center = Offset(x, y)
            )
        }
        
        // Highlight bitrate change points
        for (timestamp in classificationResult.bitrateChangePoints) {
            // Find matching feature
            val closestFeature = features.minByOrNull { 
                Math.abs(it.timestamp - timestamp) 
            } ?: continue
            
            val x = padding + (closestFeature.timestamp / maxTimestamp * drawWidth).toFloat()
            val y = canvasHeight / 2 + padding + (drawHeight / 2) * (closestFeature.bitrateFeature.toFloat() / maxBitrateValue)
            
            // Draw a circle to highlight the change point
            drawCircle(
                color = secondaryColor.copy(alpha = 0.7f),
                radius = 5f,
                center = Offset(x, y)
            )
        }
        
        // Finally, highlight correlated change points
        for (timestamp in classificationResult.detectionPoints) {
            // Find closest feature to this timestamp
            val closestIndex = features.indices.minByOrNull { 
                Math.abs(features[it].timestamp - timestamp) 
            } ?: continue
            
            val closestFeature = features[closestIndex]
            
            val x = padding + (closestFeature.timestamp / maxTimestamp * drawWidth).toFloat()
            
            // Draw a vertical line connecting the CSI and bitrate points
            drawLine(
                color = correlationColor,
                start = Offset(x, padding),
                end = Offset(x, canvasHeight - padding),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
            )
            
            // Draw error markers at both CSI and bitrate positions
            val yTop = padding + (drawHeight / 2) * (1 - closestFeature.csiFeature / maxCsiValue)
            val yBottom = canvasHeight / 2 + padding + (drawHeight / 2) * (closestFeature.bitrateFeature.toFloat() / maxBitrateValue)
            
            // CSI correlation point
            drawCircle(
                color = correlationColor,
                radius = 8f,
                center = Offset(x, yTop)
            )
            
            // Bitrate correlation point
            drawCircle(
                color = correlationColor,
                radius = 8f,
                center = Offset(x, yBottom)
            )
        }
        
        // Draw legend
        val legendPaint = android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.LEFT
            textSize = 10f * density
        }
        
        // CSI legend
        legendPaint.color = primaryColor.toArgb()
        drawContext.canvas.nativeCanvas.drawText("CSI", padding, padding - 2, legendPaint)
        
        // Bitrate legend
        legendPaint.color = secondaryColor.toArgb()
        drawContext.canvas.nativeCanvas.drawText(
            "Bitrate", 
            padding, 
            canvasHeight / 2 + padding - 2, 
            legendPaint
        )
        
        // Correlation legend
        legendPaint.color = correlationColor.toArgb()
        drawContext.canvas.nativeCanvas.drawText(
            "Correlated Changes", 
            width - 120 * density, 
            padding - 2, 
            legendPaint
        )
    }
}

@Preview(showBackground = true, name = "DetectSpyCamScreen Preview")
@Composable
fun PreviewDetectSpyCamScreen() {
    SpydarSenseTheme(darkTheme = true) {
        DetectSpyCamScreen(
            sessionId = "session_123",
            stationMac = "84:36:71:10:A4:64",
            apMac = "AA:BB:CC:DD:EE:FF",
            pwr = -60,
            ch = 6
        )
    }
}