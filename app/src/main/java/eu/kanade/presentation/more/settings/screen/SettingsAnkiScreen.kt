package eu.kanade.presentation.more.settings.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.setting.anki.AnkiSettingsScreenModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsAnkiScreen : SearchableSettings {

    private const val CREATE_NEW_ID = -2L

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_ankidroid

    @Composable
    override fun getPreferences(): List<Preference> {
        val screenModel = rememberScreenModel { AnkiSettingsScreenModel() }
        val state by screenModel.state.collectAsState()
        val context = LocalContext.current

        LaunchedEffect(state.error) {
            state.error?.let { error ->
                context.toast(error)
                screenModel.clearError()
            }
        }

        if (state.isLoading) {
            return listOf(
                Preference.PreferenceItem.CustomPreference(title = stringResource(MR.strings.loading)) {
                    CircularProgressIndicator()
                },
            )
        }

        val preferences = mutableListOf<Preference>()

        // AnkiDroid API access status
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                screenModel.loadAnkiData()
            }
        }

        when {
            !state.isApiAvailable -> {
                preferences.add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.anki_add_not_available),
                        subtitle = stringResource(MR.strings.anki_not_available),
                    ),
                )
            }
            !state.hasPermission -> {
                preferences.add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.anki_permission_required),
                        subtitle = stringResource(MR.strings.anki_permission_grant),
                        onClick = {
                            permissionLauncher.launch("com.ichi2.anki.permission.READ_WRITE_DATABASE")
                        },
                    ),
                )
            }
            else -> {
                preferences.add(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.anki_connected),
                    ),
                )
            }
        }

        if (state.isApiAvailable && state.hasPermission) {
            preferences.add(getDeckNoteConfig(state, screenModel))
            preferences.add(
                Preference.PreferenceGroup(
                    title = stringResource(MR.strings.anki_media_settings),
                    preferenceItems = listOf(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = remember(screenModel) { screenModel.audioPrefillPreference() },
                            title = stringResource(MR.strings.anki_dictionary_audio_prefill),
                            subtitle = stringResource(MR.strings.anki_dictionary_audio_prefill_summary),
                        ),
                        Preference.PreferenceItem.SwitchPreference(
                            preference = remember(screenModel) { screenModel.croppedImageExportPreference() },
                            title = stringResource(MR.strings.anki_cropped_image_export),
                            subtitle = stringResource(MR.strings.anki_cropped_image_export_summary),
                        ),
                    ).toImmutableList(),
                ),
            )

            if (state.selectedModelId > 0 && state.modelFields.isNotEmpty()) {
                preferences.add(getFieldMappingGroup(state, screenModel))
            }
        }
        return preferences
    }

    @Composable
    private fun getDeckNoteConfig(
        state: AnkiSettingsScreenModel.State,
        screenModel: AnkiSettingsScreenModel,
    ): Preference.PreferenceGroup {
        val models = state.models.mapKeys { it.key.toString() }.toMutableMap()
        val decks = state.decks.mapKeys { it.key.toString() }.toMutableMap()
        decks[CREATE_NEW_ID.toString()] = stringResource(MR.strings.anki_create_new_deck)

        val items = mutableListOf<Preference.PreferenceItem<out Any>>()

        items.add(
            Preference.PreferenceItem.BasicListPreference(
                value = state.selectedDeckId.toString(),
                entries = decks.toImmutableMap(),
                title = stringResource(MR.strings.anki_deck_selection),
                onValueChanged = {
                    screenModel.selectDeck(it.toLong())
                    true
                },
            ),
        )

        if (state.selectedDeckId == CREATE_NEW_ID) {
            items.add(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.anki_deck_name),
                ) {
                    OutlinedTextField(
                        value = state.deckName,
                        onValueChange = { screenModel.updateDeckName(it) },
                        label = { Text(stringResource(MR.strings.anki_deck_name)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        singleLine = true,
                    )
                },
            )
        }

        items.add(
            Preference.PreferenceItem.BasicListPreference(
                value = state.selectedModelId.toString(),
                entries = models.toImmutableMap(),
                title = stringResource(MR.strings.anki_note_type_selection),
                onValueChanged = {
                    screenModel.selectModel(it.toLong())
                    true
                },
            ),
        )

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.anki_deck_note_config),
            preferenceItems = items.toImmutableList(),
        )
    }

    @Composable
    private fun getFieldMappingGroup(
        state: AnkiSettingsScreenModel.State,
        screenModel: AnkiSettingsScreenModel,
    ): Preference.PreferenceGroup {
        val mappingItems = mutableListOf<Preference.PreferenceItem<out Any>>()

        mappingItems.add(
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(MR.strings.anki_field_mapping_info),
            ),
        )

        // Static field labels
        val appFieldResources = mapOf(
            "audio" to MR.strings.anki_field_audio,
            "expression" to MR.strings.anki_field_expression,
            "frequency" to MR.strings.anki_field_frequency,
            "freqAvgValue" to MR.strings.anki_field_frequency_average_value,
            "freqLowestValue" to MR.strings.anki_field_frequency_lowest_value,
            "furigana" to MR.strings.anki_field_furigana,
            "glossary-first" to MR.strings.anki_field_glossary_first,
            "glossary-all" to MR.strings.anki_field_glossary_all,
            "picture" to MR.strings.anki_field_picture,
            "pitchAccent" to MR.strings.anki_field_pitch_accent,
            "reading" to MR.strings.anki_field_reading,
            "sentence" to MR.strings.anki_field_sentence,
        )

        // Dynamic fields: per-frequency-dictionary and per-term-dictionary glossary
        val freqDynamicFields = state.dictionaries
            .filter { it.id in state.freqDictionaryIds }
            .map { "freqSingleValue_${it.id}" }

        val glossaryDynamicFields = state.dictionaries
            .filter { it.id in state.termDictionaryIds }
            .map { "glossary-${it.title.trim()}" }

        // Build final ordered list: insert freq fields after freqLowestValue, glossary fields after glossary-all
        val allAppFields = AnkiSettingsScreenModel.APP_FIELDS.flatMap { field ->
            when (field) {
                "freqLowestValue" -> listOf(field) + freqDynamicFields
                "glossary-all" -> listOf(field) + glossaryDynamicFields
                else -> listOf(field)
            }
        }

        /**
         * Resolves a human-readable label for any app field name.
         */
        @Composable
        fun resolveFieldLabel(appField: String): String = when {
            appField.isEmpty() -> stringResource(MR.strings.anki_field_empty)
            appField.startsWith("freqSingleValue_") -> {
                val dictId = appField.substringAfter("freqSingleValue_").toLongOrNull()
                val dict = state.dictionaries.find { it.id == dictId }
                if (dict != null) {
                    stringResource(MR.strings.anki_field_frequency_single_value, dict.title)
                } else {
                    appField
                }
            }
            appField.startsWith("glossary-") && appField !in appFieldResources -> {
                val dictName = appField.removePrefix("glossary-")
                stringResource(MR.strings.anki_field_glossary_dictionary, dictName)
            }
            else -> {
                val resId = appFieldResources[appField]
                if (resId != null) stringResource(resId) else appField
            }
        }

        state.modelFields.forEach { ankiField ->
            // Backward compat: migrate old "glossary" mapping -> "glossary-first"
            val rawMapping = state.fieldMappings[ankiField] ?: ""
            val currentMapping = if (rawMapping == "glossary") "glossary-first" else rawMapping

            val options = (listOf("") + allAppFields).associateWith { appField ->
                resolveFieldLabel(appField)
            }.toImmutableMap()

            val subtitleLabel = resolveFieldLabel(currentMapping)

            mappingItems.add(
                Preference.PreferenceItem.BasicListPreference(
                    value = currentMapping,
                    entries = options,
                    title = ankiField,
                    subtitle = if (currentMapping.isEmpty()) {
                        stringResource(MR.strings.anki_field_empty)
                    } else {
                        stringResource(MR.strings.anki_fill_with, subtitleLabel)
                    },
                    onValueChanged = {
                        screenModel.updateFieldMapping(ankiField, it)
                        true
                    },
                ),
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.anki_field_mappings),
            preferenceItems = mappingItems.toImmutableList(),
        )
    }
}
