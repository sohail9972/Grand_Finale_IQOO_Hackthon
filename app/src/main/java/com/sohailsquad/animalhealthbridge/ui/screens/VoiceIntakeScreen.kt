package com.sohailsquad.animalhealthbridge.ui.screens

import android.Manifest
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.ml.asr.AsrEngine
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.AhbCard
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButtonWide
import com.sohailsquad.animalhealthbridge.ui.components.AhbScreen
import com.sohailsquad.animalhealthbridge.ui.components.AhbSecondaryButton
import com.sohailsquad.animalhealthbridge.ui.components.AhbStepBar
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import kotlinx.coroutines.launch

/**
 * Screen 3 - voice intake.
 *
 * The owner speaks freely in their own language; on-device ASR produces a
 * transcript. Nothing is uploaded and no vocabulary is suggested, because the
 * whole point is that the owner can say anything - there are no symptom buttons.
 *
 * ---------------------------------------------------------------------------
 * EVERY FAILURE LEADS SOMEWHERE USABLE
 * ---------------------------------------------------------------------------
 * Microphone denied, no microphone, model not side-loaded, recording error -
 * each one switches the screen into typing mode automatically rather than
 * leaving a dead mic button. The owner can always finish describing the problem.
 *
 * @param animalId the animal this case belongs to, or null for an unsaved draft.
 * @param onTranscriptReady called with the final transcript.
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VoiceIntakeScreen(
    animalId: String?,
    onTranscriptReady: (transcript: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val microphonePermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // The engine is bound to this screen. `remember` keyed on nothing means one
    // instance per screen entry; onDispose releases the recogniser so the model
    // is not held while the owner is elsewhere.
    val asrEngine = remember { AsrEngine(context) }

    var transcript by rememberSaveable { mutableStateOf("") }
    // Once the owner types, ASR results must not overwrite their words.
    var manualOverride by rememberSaveable { mutableStateOf(false) }
    // True when the owner chose to type rather than speak.
    var typingMode by rememberSaveable { mutableStateOf(false) }

    // collectAsStateWithLifecycle stops collecting when the screen is not in
    // the foreground, so a paused screen cannot drive recomposition.
    val engineState by asrEngine.state.collectAsStateWithLifecycle()
    val isListening = engineState is AsrEngine.State.Listening

    // --- Collect partial and final transcripts ---------------------------------
    LaunchedEffect(asrEngine) {
        asrEngine.partialTranscript.collect { partial ->
            // Only follow the microphone while the owner has not taken over.
            if (!manualOverride && partial.isNotBlank()) {
                transcript = partial
            }
        }
    }
    LaunchedEffect(asrEngine) {
        asrEngine.finalTranscript.collect { final ->
            if (!manualOverride && final.isNotBlank()) {
                transcript = final
                Log.i(TAG, "Final transcript received (${final.length} chars)")
            }
        }
    }

    // --- Force typing mode whenever speech is unusable -------------------------
    LaunchedEffect(engineState) {
        when (val state = engineState) {
            is AsrEngine.State.Unavailable -> {
                Log.w(TAG, "ASR unavailable: ${state.reason}")
                typingMode = true
            }
            else -> Unit
        }
    }

    // --- Stop the mic when the screen leaves the foreground --------------------
    DisposableEffect(lifecycleOwner, asrEngine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                // A backgrounded screen must not keep the microphone open.
                asrEngine.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            asrEngine.release()
        }
    }

    val canContinue = transcript.isNotBlank()

    AhbScreen(
        destination = AhbDestination.VoiceIntake,
        onBack = onBack,
        modifier = modifier,
        bottomBar = {
            AhbStepBar(
                current = AhbDestination.VoiceIntake,
                onStepClick = { /* Backward jumps land with the health record. */ },
            )
        },
    ) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(LocalAhbDimens.current.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // --- Language + animal chips -----------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gapTight),
            ) {
                AhbChip(text = "Hindi")
                AhbChip(text = animalId?.let { "Tag $it" } ?: "No tag yet")
            }

            // --- Microphone -------------------------------------------------
            if (!typingMode) {
                MicButton(
                    isListening = isListening,
                    onClick = {
                        when {
                            // Permission first: starting without it throws.
                            !microphonePermission.status.isGranted -> {
                                Log.i(TAG, "Requesting microphone permission")
                                microphonePermission.launchPermissionRequest()
                            }
                            isListening -> {
                                Log.i(TAG, "Stop requested")
                                asrEngine.stop()
                            }
                            else -> {
                                Log.i(TAG, "Start requested")
                                manualOverride = false
                                asrEngine.start()
                            }
                        }
                    },
                )

                Text(
                    text = when (val state = engineState) {
                        is AsrEngine.State.Listening -> "Listening... tap to stop"
                        is AsrEngine.State.Preparing -> "Loading speech model..."
                        is AsrEngine.State.Finished -> "Tap to speak again"
                        is AsrEngine.State.Unavailable -> state.reason
                        AsrEngine.State.Idle -> "Tap the microphone and speak freely"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (engineState) {
                        is AsrEngine.State.Unavailable -> LocalAhbColors.current.warning
                        else -> LocalAhbColors.current.textDim
                    },
                    textAlign = TextAlign.Center,
                )

                // Explain a denied permission rather than silently doing nothing.
                if (!microphonePermission.status.isGranted) {
                    Text(
                        text = if (microphonePermission.status.shouldShowRationale) {
                            "Microphone access is needed to hear the symptoms. " +
                                "Nothing is uploaded - everything stays on this phone."
                        } else {
                            "Microphone access was denied. You can type the symptoms instead."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAhbColors.current.textDim,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // --- Transcript / typing area -----------------------------------
            AhbCard(
                title = if (typingMode) "Type the symptoms" else "What we heard",
                subtitle = if (typingMode) {
                    "Describe what you are seeing, in any words you like."
                } else {
                    "You can correct anything before continuing."
                },
            ) {
                OutlinedTextField(
                    value = transcript,
                    onValueChange = {
                        transcript = it
                        // Any manual edit wins from here on; the microphone must
                        // never clobber words the owner typed.
                        manualOverride = true
                    },
                    label = { Text(if (typingMode) "Symptoms" else "Transcript") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "For example: she has not eaten since yesterday and " +
                                "the left leg is swollen.",
                        )
                    },
                )
            }

            // --- Mode switch -------------------------------------------------
            if (typingMode) {
                AhbSecondaryButton(
                    text = "Speak instead",
                    onClick = {
                        Log.i(TAG, "Switching back to voice")
                        typingMode = false
                    },
                    icon = Icons.Filled.Mic,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AhbSecondaryButton(
                    text = "Type instead",
                    onClick = {
                        Log.i(TAG, "Switching to typing")
                        asrEngine.stop()
                        typingMode = true
                    },
                    icon = Icons.Filled.Keyboard,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AhbPrimaryButtonWide(
                text = "Continue",
                onClick = {
                    asrEngine.stop()
                    onTranscriptReady(transcript.trim())
                },
                enabled = canContinue,
            )

            Text(
                text = "Your words are processed on this phone. Nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalAhbColors.current.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The 120dp circular microphone button.
 *
 * Pulsing while listening gives the owner a glanceable "it can hear me" signal
 * in a noisy shed, where a colour change alone would be easy to miss.
 *
 * @param isListening drives the pulse and the icon.
 * @param onClick toggles recording.
 */
@Composable
private fun MicButton(
    isListening: Boolean,
    onClick: () -> Unit,
) {
    // A slow, linear pulse. Infinite transitions must be remembered, not
    // recreated, or the animation restarts on every recomposition.
    val transition = rememberInfiniteTransition(label = "micPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micScale",
    )

    Box(
        modifier = Modifier.size(LocalAhbDimens.current.micButton),
        contentAlignment = Alignment.Center,
    ) {
        // Halo, drawn behind the button so the pulse reads as radiating out.
        Surface(
            modifier = Modifier
                .size(LocalAhbDimens.current.micButton)
                .scale(pulse),
            shape = CircleShape,
            color = if (isListening) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            } else {
                Color.Transparent
            },
        ) {}

        Surface(
            onClick = onClick,
            modifier = Modifier.size(LocalAhbDimens.current.micButton * 0.78f),
            shape = CircleShape,
            color = if (isListening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                    // The adjacent status line carries the meaning, so the icon
                    // is decorative to avoid a duplicated announcement.
                    contentDescription = null,
                    tint = if (isListening) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        LocalAhbColors.current.onAccent
                    },
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

/**
 * Small pill label used for the language and animal chips.
 *
 * @param text label content.
 */
@Composable
private fun AhbChip(text: String) {
    Surface(
        shape = RoundedCornerShape(LocalAhbDimens.current.radiusChip),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.height(LocalAhbDimens.current.touchTarget / 2),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = LocalAhbDimens.current.gap),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Logcat tag; filter with: adb logcat -s AhbVoice */
private const val TAG = "AhbVoice"