package com.sohailsquad.animalhealthbridge.navigation

/**
 * Every navigation intent the screens can raise, bundled into one object.
 *
 * Why a bundle instead of eight lambdas per screen:
 *   - a screen that only needs two of them declares two parameters, and the
 *     call site stays a single named argument;
 *   - new intents can be added here without touching every composable;
 *   - the real implementation is constructed once in MainActivity, so route
 *     strings never leak into the screen layer.
 */
data class ScreenCallbacks(
    /** Language chosen on screen 1; also the "continue" action. */
    val onLanguageSelected: (languageCode: String) -> Unit,
    /** Tag/QR/manual entry resolved to a real animal on screen 2. */
    val onAnimalResolved: (tagNumber: String) -> Unit,
    /** Screen 2 "View History" shortcut. */
    val onOpenHealthRecord: () -> Unit,
    /** Final transcript produced by screen 3. */
    val onTranscriptReady: (transcript: String) -> Unit,
    /** Screen 4 "Share with Vet". */
    val onShareWithVet: () -> Unit,
    /** Screen 4/5 "Request more info" - opens the query loop. */
    val onOpenQueries: () -> Unit,
    /** Screen 5 "Approve Treatment" - the ONLY path to a prescription. */
    val onTreatmentApproved: (caseId: String) -> Unit,
    /** Screen 7 "Start New Case" - loops back to animal identification. */
    val onStartNewCase: () -> Unit,
    /** Back affordance shared by every screen. */
    val onBack: () -> Unit,
)