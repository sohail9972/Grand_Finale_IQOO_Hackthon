package com.sohailsquad.animalhealthbridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButtonWide
import com.sohailsquad.animalhealthbridge.ui.components.AhbScreen
import com.sohailsquad.animalhealthbridge.ui.components.AhbSelectableCard
import com.sohailsquad.animalhealthbridge.ui.components.AhbStepBar
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens

/**
 * One selectable language.
 *
 * @param code short code persisted to DataStore (e.g. "hi", "kn").
 * @param nativeName the language in its own script - what a farmer recognises
 *   on sight. Shown first and largest.
 * @param englishName the English name, shown small underneath, so a judge or a
 *   para-vet who does not read the script can still find the right entry.
 */
private data class LanguageOption(
    val code: String,
    val nativeName: String,
    val englishName: String,
)

/**
 * The 13 languages the architecture supports.
 *
 * Phase 1 ships Hindi -> English end to end; the other twelve are listed so the
 * selection UI is complete and the language-code plumbing is exercised from the
 * very first step. Step 2 moves this list behind the repository and gates the
 * entries that are not yet backed by an ASR + translation model.
 */
private val SUPPORTED_LANGUAGES = listOf(
    LanguageOption("hi", "हिन्दी", "Hindi"),
    LanguageOption("kn", "ಕನ್ನಡ", "Kannada"),
    LanguageOption("ta", "தமிழ்", "Tamil"),
    LanguageOption("te", "తెలుగు", "Telugu"),
    LanguageOption("ml", "മലയാളം", "Malayalam"),
    LanguageOption("mr", "मराठी", "Marathi"),
    LanguageOption("bn", "বাংলা", "Bengali"),
    LanguageOption("gu", "ગુજરાતી", "Gujarati"),
    LanguageOption("pa", "ਪੰਜਾਬੀ", "Punjabi"),
    LanguageOption("or", "ଓଡ଼ିଆ", "Odia"),
    LanguageOption("as", "অসমীয়া", "Assamese"),
    LanguageOption("ur", "اردو", "Urdu"),
    LanguageOption("en", "English", "English"),
)

/**
 * Screen 1 - choose the language the owner speaks.
 *
 * A two-column grid of native-script cards. The selection is local state for
 * now; Step 2 persists it to DataStore so the app reopens in the same language.
 *
 * @param onContinue receives the selected language code.
 * @param modifier layout modifier.
 */
@Composable
fun LanguageSelectScreen(
    onContinue: (languageCode: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable so the choice survives rotation or a low-memory process
    // kill while the user is still deciding.
    var selectedCode by rememberSaveable { mutableStateOf<String?>(null) }

    AhbScreen(
        destination = AhbDestination.LanguageSelect,
        // Nothing to go back to: this is the entry screen.
        onBack = null,
        modifier = modifier,
        bottomBar = {
            AhbStepBar(
                current = AhbDestination.LanguageSelect,
                onStepClick = { /* Step 1 has no completed steps to jump back to. */ },
            )
        },
    ) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                // weight(1f) lets the grid take the space the button does not,
                // so the button is pinned to the bottom without an extra
                // Box/Spacer arrangement.
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(LocalAhbDimens.current.padding),
                horizontalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap),
                verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap),
            ) {
                items(items = SUPPORTED_LANGUAGES, key = { it.code }) { language ->
                    AhbSelectableCard(
                        onClick = { selectedCode = language.code },
                        selected = selectedCode == language.code,
                        title = language.nativeName,
                        subtitle = language.englishName,
                    ) {}
                }
            }

            AhbPrimaryButtonWide(
                text = "Continue",
                onClick = { selectedCode?.let(onContinue) },
                // Disabled until a language is chosen: continuing without one
                // would leave the ASR step with no model to load.
                enabled = selectedCode != null,
                modifier = Modifier.padding(
                    start = LocalAhbDimens.current.padding,
                    end = LocalAhbDimens.current.padding,
                    bottom = LocalAhbDimens.current.padding,
                ),
            )
        }
    }
}