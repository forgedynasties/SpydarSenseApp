package com.example.spydarsense.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// Custom vertical scrollbar implementation
@Composable
fun CustomVerticalScrollbar(
    modifier: Modifier,
    state: ScrollState,
    reverseLayout: Boolean = false,
    thickness: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
) {
    val coroutineScope = rememberCoroutineScope()
    
    val scrollerHeight by remember(state) {
        derivedStateOf {
            val scrollableArea = state.maxValue + state.viewportSize
            val scrollerSize = (state.viewportSize / scrollableArea) * 100f
            scrollerSize.coerceIn(10f, 100f) // Minimum and maximum size
        }
    }

    val scrollerPosition by remember(state) {
        derivedStateOf {
            if (state.maxValue == 0) 0f
            else (state.value.toFloat() / state.maxValue.toFloat()) *
                    (100f - scrollerHeight) * if (reverseLayout) -1f else 1f
        }
    }

    Box(
        modifier = modifier
            .width(thickness)
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .align(if (reverseLayout) Alignment.TopEnd else Alignment.TopStart)
                .width(thickness)
                .fillMaxHeight(scrollerHeight / 100f)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val yPosition = (scrollerPosition / 100f * constraints.maxHeight)
                        .coerceIn(0f, constraints.maxHeight - placeable.height.toFloat())

                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, yPosition.toInt())
                    }
                }
                .clip(RoundedCornerShape(thickness / 2))
                .background(color)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val scrollDelta = (delta / (100f - scrollerHeight)) * state.maxValue
                        coroutineScope.launch {
                            state.scrollTo((state.value + scrollDelta).toInt().coerceIn(0, state.maxValue))
                        }
                    }
                )
        )
    }
}

// Helper function for ScrollState
@Composable
fun CustomScrollbar(
    modifier: Modifier,
    scrollState: ScrollState,
    reverseLayout: Boolean = false,
    thickness: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
) {
    CustomVerticalScrollbar(
        modifier = modifier,
        state = scrollState,
        reverseLayout = reverseLayout,
        thickness = thickness,
        color = color
    )
}

// Helper function for LazyListState
@Composable
fun LazyListScrollbar(
    modifier: Modifier,
    listState: LazyListState,
    reverseLayout: Boolean = false,
    thickness: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
) {
    val coroutineScope = rememberCoroutineScope()
    
    // Create a derived scroll state from LazyListState
    val scrollFraction by remember(listState) {
        derivedStateOf {
            if (listState.layoutInfo.totalItemsCount == 0) {
                0f
            } else {
                val visibleItemsInfo = listState.layoutInfo.visibleItemsInfo
                val firstItem = visibleItemsInfo.firstOrNull()?.index ?: 0
                val lastItem = visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalCount = listState.layoutInfo.totalItemsCount

                firstItem / totalCount.toFloat()
            }
        }
    }

    // Convert to a percentage
    val scrollPercent by remember(scrollFraction) {
        derivedStateOf { scrollFraction * 100f }
    }

    // Handle empty state to avoid division by zero
    if (listState.layoutInfo.totalItemsCount > 0) {
        Box(
            modifier = modifier
                .width(thickness)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .align(if (reverseLayout) Alignment.TopEnd else Alignment.TopStart)
                    .width(thickness)
                    .fillMaxHeight(0.2f) // 20% of container height
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val maxY = constraints.maxHeight - placeable.height
                        val yPosition = (scrollPercent / 100f * maxY).toInt()

                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(0, yPosition)
                        }
                    }
                    .clip(RoundedCornerShape(thickness / 2))
                    .background(color)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            val viewportHeight = listState.layoutInfo.viewportEndOffset -
                                    listState.layoutInfo.viewportStartOffset
                            val viewportSize = listState.layoutInfo.viewportSize.height
                            val scrollBy = delta * (viewportHeight / viewportSize)
                            coroutineScope.launch {
                                listState.scrollBy(-scrollBy)
                            }
                        }
                    )
            )
        }
    }
}