package com.sohailsquad.animalhealthbridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import com.sohailsquad.animalhealthbridge.ui.components.AhbStatusBadge
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
            AhbPrimaryButtonWide(
                text = "Continue",
                onClick = { selectedCode?.let(onContinue) },
                enabled = selectedCode != null,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                modifier = Modifier.padding(LocalAhbDimens.current.padding),
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
                item(span = { GridItemSpan(2) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                            Icon(Icons.Default.Pets, null, Modifier.padding(20.dp).size(44.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        AhbStatusBadge("Offline ready · Local language")
                        Text("Animal Health Bridge", style = MaterialTheme.typography.displaySmall,
                            textAlign = TextAlign.Center)
                        Text("Choose your language / अपनी भाषा चुनें", textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(items = SUPPORTED_LANGUAGES, key = { it.code },
                    span = { GridItemSpan(if (it.code == "en") 2 else 1) }) { language ->
                    AhbSelectableCard(
                        onClick = { selectedCode = language.code },
                        selected = selectedCode == language.code,
                        modifier = Modifier.semantics { selected = selectedCode == language.code },
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(language.nativeName, style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f))
                            if (selectedCode == language.code) Icon(Icons.Default.CheckCircle, "Selected",
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }
                        Text(language.englishName, style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedCode == language.code) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

        }
    }
}
