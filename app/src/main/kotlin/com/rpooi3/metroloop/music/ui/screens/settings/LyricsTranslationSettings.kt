/**
 * Metroloop Project (C) 2026
 * Based on the Metrolist project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rpooi3.metroloop.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rpooi3.metroloop.LocalPlayerAwareWindowInsets
import com.rpooi3.metroloop.R
import com.rpooi3.metroloop.api.GoogleTranslateService
import com.rpooi3.metroloop.constants.LanguageCodeToName
import com.rpooi3.metroloop.constants.TranslateLanguageKey
import com.rpooi3.metroloop.constants.TranslateModeKey
import com.rpooi3.metroloop.ui.component.EnumDialog
import com.rpooi3.metroloop.ui.component.Material3SettingsGroup
import com.rpooi3.metroloop.ui.component.Material3SettingsItem
import com.rpooi3.metroloop.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsTranslationSettings(navController: NavController) {
    var translateLanguage by rememberPreference(TranslateLanguageKey, "en")
    var translateMode by rememberPreference(TranslateModeKey, GoogleTranslateService.MODE_LITERAL)

    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeHelpDialog by rememberSaveable { mutableStateOf(false) }

    if (showTranslateModeHelpDialog) {
        AlertDialog(
            onDismissRequest = { showTranslateModeHelpDialog = false },
            confirmButton = {
                TextButton(onClick = { showTranslateModeHelpDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            icon = { Icon(painterResource(R.drawable.info), null) },
            title = { Text(stringResource(R.string.ai_translation_mode)) },
            text = {
                Column {
                    Text(
                        text = "${stringResource(R.string.ai_translation_literal)}:",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.ai_translation_literal_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    Text(
                        text = "${stringResource(R.string.ai_translation_romanized)}:",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.ai_translation_romanized_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    Text(
                        text = "${stringResource(R.string.ai_translation_transcribed)}:",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.ai_translation_transcribed_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        )
    }

    if (showTranslateModeDialog) {
        EnumDialog(
            onDismiss = { showTranslateModeDialog = false },
            onSelect = {
                translateMode = it
                showTranslateModeDialog = false
            },
            title = stringResource(R.string.ai_translation_mode),
            current = translateMode,
            values = listOf("Literal", "Romanized", "Transcribed"),
            valueText = {
                when (it) {
                    "Literal" -> stringResource(R.string.ai_translation_literal)
                    "Romanized" -> stringResource(R.string.ai_translation_romanized)
                    "Transcribed" -> stringResource(R.string.ai_translation_transcribed)
                    else -> it
                }
            },
        )
    }

    if (showLanguageDialog) {
        EnumDialog(
            onDismiss = { showLanguageDialog = false },
            onSelect = {
                translateLanguage = it
                showLanguageDialog = false
            },
            title = stringResource(R.string.ai_target_language),
            current = translateLanguage,
            values = LanguageCodeToName.keys.sortedBy { LanguageCodeToName[it] },
            valueText = { LanguageCodeToName[it] ?: it },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            ).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top,
                ),
            ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.translation_engine),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.translate),
                        title = { Text(stringResource(R.string.translation_engine_google)) },
                        description = { Text(stringResource(R.string.translation_engine_google_desc)) },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.ai_translation_mode),
            items =
                listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.translate),
                        title = { Text(stringResource(R.string.ai_translation_mode)) },
                        description = {
                            Text(
                                when (translateMode) {
                                    "Literal" -> stringResource(R.string.ai_translation_literal)
                                    "Romanized" -> stringResource(R.string.ai_translation_romanized)
                                    "Transcribed" -> stringResource(R.string.ai_translation_transcribed)
                                    else -> translateMode
                                },
                            )
                        },
                        onClick = { showTranslateModeDialog = true },
                        trailingContent = {
                            IconButton(onClick = { showTranslateModeHelpDialog = true }) {
                                Icon(
                                    painterResource(R.drawable.info),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.language),
                        title = { Text(stringResource(R.string.ai_target_language)) },
                        description = { Text(LanguageCodeToName[translateLanguage] ?: translateLanguage) },
                        onClick = { showLanguageDialog = true },
                    ),
                ),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.translation_google_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.ai_lyrics_translation)) },
        navigationIcon = {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
