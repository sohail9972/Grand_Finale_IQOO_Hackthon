package com.sohailsquad.animalhealthbridge.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.ui.screens.*
import kotlinx.coroutines.*

object NavArgs {
    const val ANIMAL_ID = "animalId"
    const val CASE_ID = "caseId"
    const val TRANSCRIPT = "transcript"
}
fun caseReviewRoute(animalId: Long, caseId: Long, transcript: String = "") =
    "case_review/$animalId?caseId=$caseId&transcript=" + Uri.encode(transcript)

private fun NavBackStackEntry.identifier(key: String) = arguments?.getLong(key) ?: 0L
private fun longArgument(name: String) = navArgument(name) { type = NavType.LongType; defaultValue = 0L }

@Composable
fun AhbNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val application = LocalContext.current.app()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val back: () -> Unit = { if (!nav.popBackStack()) nav.navigate("animal_id") }
    fun navigate(route: String) {
        try {
            nav.navigate(route) { launchSingleTop = true }
            Log.i("AhbRecord", "Navigation: ${route.substringBefore('?')}")
        } catch (failure: Throwable) {
            error = "This screen could not be opened. Go back and retry."
            Log.e("AhbRecord", "Navigation failed", failure)
        }
    }
    NavHost(navController = nav, startDestination = "language_select", modifier = modifier) {
        composable("language_select") {
            LanguageSelectScreen(onContinue = { language ->
                scope.launch {
                    try {
                        application.preferences.setLanguage(language)
                        navigate("animal_id")
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = "Language could not be saved. Retry."
                        Log.e("AhbAsr", "Language save failed", failure)
                    }
                }
            })
        }
        composable("animal_id") {
            AnimalIdScreen(onAnimalResolved = { id ->
                val animalId = id.toLongOrNull()
                if (animalId != null && animalId > 0) navigate("voice_intake/$animalId")
                else error = "Animal could not be identified."
            }, onOpenHistory = { navigate("health_record/0") }, onBack = back,
                onCheckBehaviour = { id ->
                    val animalId = id.toLongOrNull()
                    if (animalId != null && animalId > 0) navigate("behaviour_check/$animalId")
                    else error = "Animal could not be identified."
                })
        }
        composable("behaviour_check/{animalId}", arguments = listOf(longArgument(NavArgs.ANIMAL_ID))) { entry ->
            val model = behaviourViewModel(entry)
            BehaviourCheckScreen(model, onAnalyse = { navigate("behaviour_result/${entry.identifier(NavArgs.ANIMAL_ID)}") },
                onBack = back)
        }
        composable("behaviour_result/{animalId}", arguments = listOf(longArgument(NavArgs.ANIMAL_ID))) { entry ->
            val animalId = entry.identifier(NavArgs.ANIMAL_ID)
            val owner = remember(entry) {
                runCatching { nav.getBackStackEntry("behaviour_check/{animalId}") }
                    .getOrNull()?.takeIf { it.identifier(NavArgs.ANIMAL_ID) == animalId } ?: entry
            }
            val model = behaviourViewModel(owner)
            BehaviourResultScreen(model, onStartVetCase = { caseId, transcript ->
                navigate(caseReviewRoute(animalId, caseId, transcript))
            }, onDone = { if (!nav.popBackStack("animal_id", false)) navigate("animal_id") }, onBack = back)
        }
        composable("voice_intake/{animalId}", arguments = listOf(longArgument("animalId"))) { entry ->
            val animalId = entry.identifier("animalId")
            VoiceIntakeScreen(animalId, onTranscriptReady = { caseId, text ->
                navigate(caseReviewRoute(animalId, caseId, text))
            }, onBack = back)
        }
        composable("case_review/{animalId}?caseId={caseId}&transcript={transcript}",
            arguments = listOf(longArgument("animalId"), longArgument("caseId"),
                navArgument("transcript") { type = NavType.StringType; defaultValue = "" })) { entry ->
            CaseReviewScreen(entry.identifier("caseId"),
                onShareWithVet = { navigate("vet_approval/$it") },
                onOpenQueries = { caseId, mode -> navigate("vet_query/$caseId/$mode") }, onBack = back,
                transcript = entry.arguments?.getString(NavArgs.TRANSCRIPT).orEmpty())
        }
        composable("vet_approval/{caseId}", arguments = listOf(longArgument("caseId"))) { entry ->
            VetApprovalScreen(entry.identifier("caseId"),
                onRequestMoreInfo = { caseId, mode, vetName, vetId ->
                    navigate("vet_query/$caseId/$mode?vetName=${Uri.encode(vetName)}&vetId=${Uri.encode(vetId)}")
                },
                onTreatmentApproved = { navigate("prescription/$it") }, onBack = back)
        }
        composable("prescription/{caseId}", arguments = listOf(longArgument("caseId"))) { entry ->
            PrescriptionScreen(entry.identifier("caseId"), onSaveToRecord = { navigate("health_record/$it") }, onBack = back)
        }
        composable("vet_query/{caseId}/{viewMode}?vetName={vetName}&vetId={vetId}",
            arguments = listOf(longArgument("caseId"),
                navArgument("viewMode") { type = NavType.StringType; defaultValue = "owner" },
                navArgument("vetName") { type = NavType.StringType; defaultValue = "" },
                navArgument("vetId") { type = NavType.StringType; defaultValue = "" })) { entry ->
            VetQueryScreen(entry.identifier("caseId"), entry.arguments?.getString("viewMode") ?: "owner", back,
                initialVetName = entry.arguments?.getString("vetName").orEmpty(),
                initialVetId = entry.arguments?.getString("vetId").orEmpty())
        }
        composable("health_record/{animalId}", arguments = listOf(longArgument("animalId"))) { entry ->
            HealthRecordScreen(entry.identifier("animalId"), onOpenAnimal = { navigate("health_record/$it") },
                onOpenCase = { animalId, caseId -> navigate(caseReviewRoute(animalId, caseId)) },
                onStartNewCase = { navigate("voice_intake/$it") }, onBack = back)
        }
    }
    error?.let {
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Action unavailable") },
            text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
    }
}

@Composable
private fun behaviourViewModel(owner: NavBackStackEntry): BehaviourViewModel {
    val context = LocalContext.current.applicationContext
    val animalId = owner.identifier(NavArgs.ANIMAL_ID)
    val factory = remember(owner, context) {
        viewModelFactory {
            initializer { BehaviourViewModel(context, animalId, createSavedStateHandle()) }
        }
    }
    return viewModel(viewModelStoreOwner = owner, key = "behaviour-$animalId", factory = factory)
}
