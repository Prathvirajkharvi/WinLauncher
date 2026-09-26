package com.winlauncher.app.ui.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.winlauncher.app.domain.controller.InputMapper
import com.winlauncher.app.domain.controller.XInputButton
import kotlin.math.sqrt

/**
 * All controls here call straight into InputMapper's virtual-touch setters --
 * the same instance GamepadManager feeds from physical input, so downstream
 * consumers see one unified stream (architecture doc section 9).
 *
 * @param opacity overall HUD transparency, sourced from the active
 *   ControllerProfile.touchOpacity so it's configurable per-profile.
 */
@Composable
fun TouchOverlay(inputMapper: InputMapper, opacity: Float = 0.6f, modifier: Modifier = Modifier) {
    Box(modifier = modifier.graphicsLayer(alpha = opacity.coerceIn(0.2f, 1f))) {
        VirtualStick(
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            onMove = { x, y -> inputMapper.setLeftStick(x, y) },
        )
        VirtualStick(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            onMove = { x, y -> inputMapper.setRightStick(x, y) },
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FaceButton("Y", inputMapper, XInputButton.Y)
            FaceButton("X", inputMapper, XInputButton.X)
            FaceButton("B", inputMapper, XInputButton.B)
            FaceButton("A", inputMapper, XInputButton.A)
        }

        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FaceButton("LB", inputMapper, XInputButton.BUMPER_LEFT)
            FaceButton("RB", inputMapper, XInputButton.BUMPER_RIGHT)
        }

        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FaceButton("Menu", inputMapper, XInputButton.MENU)
            FaceButton("Start", inputMapper, XInputButton.START)
        }

        DPad(modifier = Modifier.align(Alignment.Center), inputMapper = inputMapper)
    }
}

@Composable
private fun FaceButton(label: String, inputMapper: InputMapper, button: XInputButton) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { inputMapper.setButton(button, pressed) }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun DPad(modifier: Modifier, inputMapper: InputMapper) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DPadButton("<", inputMapper, XInputButton.DPAD_LEFT)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DPadButton("^", inputMapper, XInputButton.DPAD_UP)
            DPadButton("v", inputMapper, XInputButton.DPAD_DOWN)
        }
        DPadButton(">", inputMapper, XInputButton.DPAD_RIGHT)
    }
}

@Composable
private fun DPadButton(label: String, inputMapper: InputMapper, button: XInputButton) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { inputMapper.setButton(button, pressed) }

    Box(
        modifier = Modifier
            .size(36.dp)
            .background(if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun VirtualStick(modifier: Modifier, onMove: (Float, Float) -> Unit) {
    val radiusDp = 56.dp
    var knobOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .size(radiusDp * 2)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .pointerInput(Unit) {
                val radiusPx = radiusDp.toPx()
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val proposed = knobOffset + dragAmount
                        val distance = sqrt(proposed.x * proposed.x + proposed.y * proposed.y)
                        val clamped = if (distance > radiusPx) {
                            proposed * (radiusPx / distance)
                        } else {
                            proposed
                        }
                        knobOffset = clamped
                        onMove(clamped.x / radiusPx, clamped.y / radiusPx)
                    },
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .offset(
                    x = with(androidx.compose.ui.platform.LocalDensity.current) { knobOffset.x.toDp() },
                    y = with(androidx.compose.ui.platform.LocalDensity.current) { knobOffset.y.toDp() },
                ),
        )
    }
}
