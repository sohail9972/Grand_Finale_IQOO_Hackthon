package com.sohailsquad.animalhealthbridge.ui.screens

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Pets
import androidx.compose.ui.graphics.Brush
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.repository.AnimalRepository
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.nfc.NfcReader
import com.sohailsquad.animalhealthbridge.nfc.QrScannerDialog
import com.sohailsquad.animalhealthbridge.ui.components.AhbCard
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButtonWide
import com.sohailsquad.animalhealthbridge.ui.components.AhbScreen
import com.sohailsquad.animalhealthbridge.ui.components.AhbSecondaryButton
import com.sohailsquad.animalhealthbridge.ui.components.AhbStepBar
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButton
import com.sohailsquad.animalhealthbridge.ui.components.AhbStatusBadge
import com.sohailsquad.animalhealthbridge.ui.components.AhbSectionHeading
import com.sohailsquad.animalhealthbridge.ui.components.AhbSelectableCard
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase-1 species. Dairy cattle only for now; the database column is free text
 * so later species need no migration. Declared once so the NFC, QR and manual
 * paths cannot disagree about what they create.
 */
private const val PHASE_ONE_SPECIES = "cattle"

/**
 * Screen 2 - identify the animal.
 *
 * Three identification paths, presented together in fallback order:
 *
 *   1. NFC scan of the ear tag - fastest, and the only path that cannot be
 *      mis-keyed;
 *   2. QR / barcode scan - works when the tag's chip has failed;
 *   3. manual typing - works when the camera and the radio both let you down.
 *
 * All three converge on [AnimalRepository.createOrGet], so scanning the same tag
 * twice always resolves to the same animal regardless of which path was used.
 * Every path logs under "AhbAnimal".
 *
 * @param onAnimalResolved called with the stable Room animal id as a string.
 * @param onOpenHistory "View History" shortcut into screen 7.
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@Composable
fun AnimalIdScreen(
    onAnimalResolved: (animalId: String) -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckBehaviour: ((animalId: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repository = context.app().animalRepository
    val recentAnimals by remember { context.app().database.animalDao().observeAll().catch { failure ->
        if (failure is CancellationException) throw failure
        Log.w(TAG, "Recent animals unavailable", failure)
        emit(emptyList())
    } }.collectAsStateWithLifecycle(initialValue = emptyList())

    var tagNumber by rememberSaveable { mutableStateOf("") }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var showEnableNfcDialog by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    // True while a scan or database write is in flight, so buttons show a
    // spinner and a second tap cannot start a duplicate resolve.
    var isResolving by rememberSaveable { mutableStateOf(false) }
    // True once the owner has explicitly pressed "Start NFC scan". Reader mode
    // is only auto-re-armed after a pause/resume if this is set, so opening the
    // screen alone never starts listening.
    var nfcArmed by rememberSaveable { mutableStateOf(false) }

    val trimmedTag = tagNumber.trim()
    val canContinue = trimmedTag.isNotEmpty() && !isResolving

    // The NFC reader is bound to the Activity, so remember it against that key
    // instead of rebuilding it on every recomposition.
    val nfcReader = remember(activity) {
        activity?.let { host ->
            NfcReader(activity = host) { uid ->
                Log.i(TAG, "NFC tag read uid=$uid")
                resolveTag(
                    rawTag = uid,
                    source = "nfc",
                    repository = repository,
                    scope = scope,
                    setResolving = { isResolving = it },
                    onResolved = onAnimalResolved,
                    onError = { errorMessage = it },
                )
            }
        }
    }

    // rememberUpdatedState so the lifecycle observer below always sees the
    // current value of nfcArmed without having to be torn down and rebuilt
    // every time the owner presses Scan.
    val armedState = rememberUpdatedState(nfcArmed)

    // Arm on resume, disarm on pause. Leaving reader mode on would drain the
    // battery and keep the phone reacting to tags while the owner is elsewhere.
    DisposableEffect(lifecycleOwner, nfcReader) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    if (armedState.value) nfcReader?.start()
                Lifecycle.Event.ON_PAUSE ->
                    nfcReader?.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            nfcReader?.stop()
        }
    }

    // Result contract for "please turn NFC on". Android gives apps no way to
    // toggle the radio, so the correct behaviour is to deep-link the setting.
    val nfcSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        // The result code carries no meaning for Settings, so re-check the
        // adapter instead of trusting it.
        if (nfcReader?.isEnabled() == true) {
            Log.i(TAG, "NFC enabled by user; starting reader")
            nfcArmed = true
            nfcReader.start()
        } else {
            Log.w(TAG, "NFC still disabled after settings round trip")
            errorMessage = "NFC is still off. You can scan a QR code or type the tag number."
        }
    }

    AhbScreen(
        destination = AhbDestination.AnimalId,
        onBack = onBack,
        modifier = modifier,
        bottomBar = {
            AhbStepBar(
                current = AhbDestination.AnimalId,
            )
        },
    ) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(LocalAhbDimens.current.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap),
        ) {
            AhbSectionHeading("Identify animal", "NFC, QR or manual entry · All offline", Icons.Default.Pets)
            // --- Primary path: NFC ------------------------------------------
            AhbCard(
                title = "Scan animal tag",
                subtitle = "Hold the phone against the tag until it beeps.",
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(180.dp)
                    .background(Brush.radialGradient(listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.04f))), CircleShape),
                    contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(64.dp),
                )
                }
                AhbStatusBadge(if (nfcArmed) "NFC reader active" else if (nfcReader?.isEnabled() == true)
                    "NFC ready" else "NFC off or unavailable", icon = Icons.Default.Nfc)
                AhbPrimaryButton(
                    text = if (nfcArmed && !isResolving) "Listening for tag..." else "Start NFC scan",
                    onClick = {
                        val reader = nfcReader
                        when {
                            // No hardware: say so plainly and point at the
                            // fallbacks rather than leaving a dead button.
                            reader == null || !reader.isAvailable() -> {
                                Log.w(TAG, "NFC scan requested but device has no NFC")
                                errorMessage = "This phone has no NFC reader. " +
                                    "Scan a QR code or type the tag number instead."
                            }

                            // Hardware present, radio off.
                            !reader.isEnabled() -> {
                                Log.i(TAG, "NFC scan requested but NFC is disabled")
                                showEnableNfcDialog = true
                            }

                            else -> {
                                Log.i(TAG, "NFC scan armed")
                                nfcArmed = true
                                reader.start()
                            }
                        }
                    },
                    icon = Icons.Filled.Nfc,
                    modifier = Modifier.fillMaxWidth(),
                    loading = isResolving,
                    enabled = !isResolving,
                )
            }

            // --- Fallback 1: QR ---------------------------------------------
            AhbCard(
                title = "Scan QR code",
                subtitle = "Use this if the tag cannot be read.",
            ) {
                AhbSecondaryButton(
                    text = "Open QR scanner",
                    onClick = {
                        Log.i(TAG, "QR scanner opened")
                        // Stop listening for NFC while the camera owns the
                        // screen; otherwise a tag on the desk would resolve
                        // first and the scan would appear to do nothing.
                        nfcArmed = false
                        nfcReader?.stop()
                        showQrScanner = true
                    },
                    icon = Icons.Filled.QrCodeScanner,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isResolving,
                )
            }

            // --- Fallback 2: manual entry -----------------------------------
            AhbCard(
                title = "Type the tag number",
                subtitle = "Always available, even with no camera and no NFC.",
            ) {
                OutlinedTextField(
                    value = tagNumber,
                    onValueChange = { tagNumber = it },
                    label = { Text("Tag number") },
                    singleLine = true,
                    enabled = !isResolving,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        // Ear-tag numbers are alphanumeric and conventionally
                        // upper-case, e.g. "IN-MH-0042".
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            AhbPrimaryButtonWide(
                text = "Continue",
                onClick = {
                    resolveTag(
                        rawTag = trimmedTag,
                        source = "manual",
                        repository = repository,
                        scope = scope,
                        setResolving = { isResolving = it },
                        onResolved = onAnimalResolved,
                        onError = { errorMessage = it },
                    )
                },
                enabled = canContinue,
                loading = isResolving,
            )

            onCheckBehaviour?.let { checkBehaviour ->
                AhbCard(title = "Check Behaviour", subtitle = "Explore observations without a diagnosis.") {
                    Text("Enter the tag number above, or choose a recent animal below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AhbSecondaryButton("Check Behaviour", onClick = {
                        nfcArmed = false
                        nfcReader?.stop()
                        resolveTag(trimmedTag, "behaviour", repository, scope,
                            { isResolving = it }, checkBehaviour, { errorMessage = it })
                    }, enabled = canContinue, loading = isResolving, icon = Icons.Default.Pets,
                        modifier = Modifier.fillMaxWidth())
                }
            }

            AhbSecondaryButton(
                text = "View history",
                onClick = onOpenHistory,
                icon = Icons.Filled.Timeline,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isResolving,
            )

            AhbSectionHeading("Recent animals", "${recentAnimals.size} stored on this phone")
            recentAnimals.take(3).forEach { animal ->
                AhbSelectableCard(onClick = {
                    if (!isResolving) resolveTag(animal.tagNumber, "recent", repository, scope,
                        { isResolving = it }, onAnimalResolved, { errorMessage = it })
                }, title = animal.name.ifBlank { animal.tagNumber },
                    subtitle = "#${animal.tagNumber} · ${animal.species}") {
                    Text("Added ${com.sohailsquad.animalhealthbridge.ui.components.displayDate(animal.createdAt)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    onCheckBehaviour?.let { checkBehaviour ->
                        AhbSecondaryButton("Check Behaviour", onClick = {
                            nfcArmed = false
                            nfcReader?.stop()
                            checkBehaviour(animal.id.toString())
                        }, enabled = !isResolving, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Text(
                text = "Animals you scan are saved on this phone and stay " +
                    "available offline.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalAhbColors.current.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // --- QR scanner, shown as a full-screen dialog --------------------------
    if (showQrScanner) {
        QrScannerDialog(
            onResult = { scanned ->
                // Close before resolving so the camera is released while the
                // database write happens.
                showQrScanner = false
                Log.i(TAG, "QR scan returned value=$scanned")
                resolveTag(
                    rawTag = scanned,
                    source = "qr",
                    repository = repository,
                    scope = scope,
                    setResolving = { isResolving = it },
                    onResolved = onAnimalResolved,
                    onError = { errorMessage = it },
                )
            },
            onCancel = {
                Log.i(TAG, "QR scanner cancelled by user")
                showQrScanner = false
            },
        )
    }

    // --- "Turn NFC on" explanation -----------------------------------------
    if (showEnableNfcDialog) {
        AlertDialog(
            onDismissRequest = { showEnableNfcDialog = false },
            title = { Text("NFC is turned off") },
            text = {
                Text(
                    "Open Settings and switch NFC on to scan the ear tag. " +
                        "You can also scan a QR code or type the tag number.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableNfcDialog = false
                        // Android has no public API to toggle NFC, so handing
                        // the user the setting is the correct behaviour.
                        nfcSettingsLauncher.launch(Intent(Settings.ACTION_NFC_SETTINGS))
                    },
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showEnableNfcDialog = false }) {
                    Text("Not now")
                }
            },
        )
    }

    // --- Error surface ------------------------------------------------------
    // Every failure path (no hardware, radio off, empty scan, storage error)
    // lands here rather than throwing, per "no feature may crash the app".
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Could not identify animal") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("OK") }
            },
        )
    }
}

/**
 * Resolves a raw tag to a stored animal, then hands the tag to the caller.
 *
 * Shared by all three identification paths so their behaviour cannot diverge.
 * The database call is `suspend`, so Room performs it off the main thread while
 * the coroutine keeps the state writes on the main dispatcher.
 *
 * @param rawTag the scanned or typed value; normalised inside the repository.
 * @param source short label for the log line: "nfc", "qr" or "manual".
 * @param onResolved called with the resolved tag on success.
 * @param onError called with a human-readable message on failure.
 */
private fun resolveTag(
    rawTag: String,
    source: String,
    repository: AnimalRepository,
    scope: CoroutineScope,
    setResolving: (Boolean) -> Unit,
    onResolved: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val tag = rawTag.trim()

    // An empty read happens when a tag is tapped too briefly. Treat it as a
    // normal, recoverable outcome rather than an exception.
    if (tag.isEmpty()) {
        Log.w(TAG, "Empty tag from source=$source; ignoring")
        onError("That tag could not be read. Try again, or type the tag number.")
        return
    }

    setResolving(true)
    scope.launch {
        try {
            val profile = repository.createOrGet(
                tag = tag,
                species = PHASE_ONE_SPECIES,
                age = "",
                owner = "",
            )
            Log.i(TAG, "Resolved source=$source tag=${profile.tagNumber} id=${profile.id}")
            onResolved(profile.id.toString())
        } catch (t: Throwable) {
            // Catch Throwable, not Exception: a corrupt database file can
            // surface as an Error, and the owner must still get a usable screen.
            Log.e(TAG, "Failed to resolve tag from source=$source", t)
            onError("Could not save this animal on the phone. Please try again.")
        } finally {
            setResolving(false)
        }
    }
}

/** Logcat tag; filter with: adb logcat -s AhbAnimal */
private const val TAG = "AhbAnimal"
