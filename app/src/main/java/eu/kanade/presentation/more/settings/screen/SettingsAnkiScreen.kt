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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.ui.setting.anki.AnkiSettingsScreenModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsAnkiScreen : SearchableSettings {

    private const val CREATE_NEW_ID = -2L

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_ankidroid

    @Composable
    override fun getPreferences(): List<Preference> {
        val screenModel = rememberScreenModel { AnkiSettingsScreenModel() }
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        LaunchedEffect(state.error) {
            state.error?.let { error ->
                context.toast(error)
                screenModel.clearError()
            }
        }

        if (state.isLoading) {
            return listOf(
                Preference.PreferenceItem.CustomPreference(title = "Loading") {
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
                        enabled = false,
                    )
                )
            }
            !state.hasPermission -> {
                preferences.add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.anki_permission_required),
                        subtitle = "Tap to grant permission",
                        onClick = {
                            permissionLauncher.launch("com.ichi2.anki.permission.READ_WRITE_DATABASE")
                        },
                    )
                )
            }
            else -> {
                preferences.add(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.anki_connected),
                    )
                )
            }
        }

        if (state.isApiAvailable && state.hasPermission) {
            preferences.add(getDeckGroup(state, screenModel))
            preferences.add(getModelGroup(state, screenModel))

            if (state.selectedModelId > 0 && state.modelFields.isNotEmpty()) {
                preferences.add(getFieldMappingGroup(state, screenModel))
            }

        }
        return preferences
    }

    @Composable
    private fun getDeckGroup(
        state: AnkiSettingsScreenModel.State,
        screenModel: AnkiSettingsScreenModel,
    ): Preference.PreferenceGroup {
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
            )
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
                }
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.anki_deck_selection),
            preferenceItems = items.toImmutableList(),
        )
    }

    @Composable
    private fun getModelGroup(
        state: AnkiSettingsScreenModel.State,
        screenModel: AnkiSettingsScreenModel,
    ): Preference.PreferenceGroup {
        val models = state.models.mapKeys { it.key.toString() }.toMutableMap()
        models[CREATE_NEW_ID.toString()] = stringResource(MR.strings.anki_create_new_note_type)

        val items = mutableListOf<Preference.PreferenceItem<out Any>>()

        items.add(
            Preference.PreferenceItem.BasicListPreference(
                value = state.selectedModelId.toString(),
                entries = models.toImmutableMap(),
                title = stringResource(MR.strings.anki_note_type_selection),
                onValueChanged = {
                    screenModel.selectModel(it.toLong())
                    true
                },
            )
        )

        if (state.selectedModelId == CREATE_NEW_ID) {
            items.add(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.anki_note_type_name),
                ) {
                    OutlinedTextField(
                        value = state.modelName,
                        onValueChange = { screenModel.updateModelName(it) },
                        label = { Text(stringResource(MR.strings.anki_note_type_name)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        singleLine = true,
                    )
                }
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.anki_note_type_selection),
            preferenceItems = items.toImmutableList(),
        )
    }

    @Composable
    private fun getFieldMappingGroup(
        state: AnkiSettingsScreenModel.State,
        screenModel: AnkiSettingsScreenModel,
    ): Preference.PreferenceGroup {
        val mappingItems = AnkiSettingsScreenModel.APP_FIELDS.map { appField ->
            val options = (listOf("") + state.modelFields).associateWith {
                if (it.isEmpty()) stringResource(MR.strings.anki_field_not_mapped) else it
            }.toImmutableMap()

            Preference.PreferenceItem.BasicListPreference(
                value = state.fieldMappings[appField] ?: "",
                entries = options,
                title = appField.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                subtitle = "Placeholder: {$appField}\n%s",
                onValueChanged = {
                    screenModel.updateFieldMapping(appField, it)
                    true
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.anki_field_mappings),
            preferenceItems = mappingItems.toImmutableList(),
        )
    }
}
