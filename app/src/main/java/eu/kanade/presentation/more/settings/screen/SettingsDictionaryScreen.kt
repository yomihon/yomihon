package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.domain.dictionary.DictionaryPreferences
import eu.kanade.domain.dictionary.OcrResultPresentation
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceItem
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.ui.setting.dictionary.DictionarySettingsScreenModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.delay
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryMigrationState
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

object SettingsDictionaryScreen : Screen {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val backPress = LocalBackPress.current
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { DictionarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        val lazyListState = rememberLazyListState()
        val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
        val ocrResultPresentationPref = remember(dictionaryPreferences) {
            dictionaryPreferences.ocrResultPresentation()
        }
        val ocrResultPresentation by ocrResultPresentationPref.collectPreferenceAsState()
        val ocrResultPreferences = rememberOcrResultPreferences(
            dictionaryPreferences = dictionaryPreferences,
            isPopup = ocrResultPresentation == OcrResultPresentation.POPUP,
        )

        // Multi-file picker for dictionary import
        val pickMultipleFiles = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                screenModel.importDictionariesFromUris(uris)
            }
        }

        // Folder picker for dictionary import
        val pickFolder = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                screenModel.importDictionariesFromFolder(uri)
            }
        }

        // Show error messages
        LaunchedEffect(state.error) {
            state.error?.let { error ->
                snackbarHostState.showSnackbar(error)
                screenModel.clearError()
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_category_dictionaries),
                    navigateUp = {
                        when {
                            navigator?.canPop == true -> navigator.pop()
                            else -> backPress?.invoke()
                        }
                    },
                    scrollBehavior = it,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            val uriHandler = LocalUriHandler.current

            // URLs leads lines to be over 120 char
            @Suppress("ktlint:standard:max-line-length")
            val recommended = listOf(
                RecommendedDictionary(
                    title = stringResource(MR.strings.recommended_dict_jitendex_title),
                    description = stringResource(MR.strings.recommended_dict_jitendex_description),
                    url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
                ),
                RecommendedDictionary(
                    title = stringResource(MR.strings.recommended_dict_jpdb_title),
                    description = stringResource(MR.strings.recommended_dict_jpdb_description),
                    url = "https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/JPDB_v2.2_Frequency_Kana_2024-10-13.zip",
                ),
                RecommendedDictionary(
                    title = stringResource(MR.strings.recommended_dict_bccwj_title),
                    description = stringResource(MR.strings.recommended_dict_bccwj_description),
                    url = "https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/BCCWJ_SUW_LUW_combined.zip",
                ),
            )

            LazyColumn(
                state = lazyListState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    // Spacer for top padding
                }

                item {
                    OcrResultPreferenceGroup(
                        preferences = ocrResultPreferences,
                    )
                }

                // Recommended Dictionaries
                item {
                    var recommendedExpanded by rememberSaveable { mutableStateOf(false) }
                    val icon = if (recommendedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .animateContentSize(),
                        colors = CardDefaults.outlinedCardColors(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        onClick = { recommendedExpanded = !recommendedExpanded },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(MR.strings.recommended_dictionaries),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            AnimatedVisibility(
                                visible = recommendedExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(top = 8.dp),
                                ) {
                                    recommended.forEach { dict ->
                                        RecommendedDictionaryItem(
                                            dictionary = dict,
                                            enabled = !state.isImporting && !state.isDeleting && !state.isMigrating,
                                            onImport = { screenModel.importDictionaryFromUrl(dict.url) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Guide Card
                item {
                    Card(
                        onClick = {
                            uriHandler.openUri("https://yomihon.github.io/docs/guides/dictionaries")
                        },
                        colors = CardDefaults.outlinedCardColors(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column {
                                Text(
                                    text = stringResource(MR.strings.dictionary_guide),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // Import buttons
                item {
                    var showImportMenu by rememberSaveable { mutableStateOf(false) }
                    val importEnabled = !state.isImporting && !state.isDeleting && !state.isMigrating

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showImportMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = importEnabled,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(stringResource(MR.strings.import_dictionary_file))
                        }

                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.import_dictionary_files)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Add, contentDescription = null)
                                },
                                onClick = {
                                    showImportMenu = false
                                    try {
                                        pickMultipleFiles.launch(arrayOf("application/zip"))
                                    } catch (e: ActivityNotFoundException) {
                                        context.toast(MR.strings.file_picker_error)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.import_dictionary_from_folder)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                                },
                                onClick = {
                                    showImportMenu = false
                                    try {
                                        pickFolder.launch(null)
                                    } catch (e: ActivityNotFoundException) {
                                        context.toast(MR.strings.file_picker_error)
                                    }
                                },
                            )
                        }
                    }
                }

                // Import status
                if (state.isImporting) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (state.batchTotal > 1) {
                                        stringResource(
                                            MR.strings.importing_dictionaries_batch,
                                            state.batchCompleted,
                                            state.batchTotal,
                                        )
                                    } else {
                                        stringResource(MR.strings.importing_dictionary)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(MR.strings.dictionary_import_continues_background),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (state.currentMigrationStatus != null) {
                    item {
                        val currentStatus = state.currentMigrationStatus
                        val total = currentStatus?.totalDictionaries ?: 0
                        val completed = currentStatus?.completedDictionaries ?: 0
                        val currentDictionary = state.dictionaries.firstOrNull { it.id == currentStatus?.dictionaryId }
                        val progress = if (total > 0) {
                            completed.toFloat() / total.toFloat()
                        } else {
                            0f
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(MR.strings.dictionary_migration_card_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                currentDictionary?.let { dictionary ->
                                    Text(
                                        text = stringResource(
                                            MR.strings.dictionary_migration_running_summary,
                                            dictionary.title,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (total > 0) {
                                    Text(
                                        text = stringResource(
                                            MR.strings.dictionary_migration_progress_summary,
                                            completed,
                                            total,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                currentStatus?.progressText?.let { progressText ->
                                    Text(
                                        text = progressText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (currentStatus.state == DictionaryMigrationState.ERROR) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Delete progress
                if (state.isDeleting) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(MR.strings.deleting_dictionary),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                // Dictionaries list
                if (state.isLoading && state.dictionaries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (state.dictionaries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = stringResource(MR.strings.no_dictionaries),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(MR.strings.installed_dictionaries),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            OutlinedButton(
                                onClick = { screenModel.autoSortDictionaries(context) },
                                enabled = !state.isImporting && !state.isDeleting && !state.isMigrating,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Sort,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Text(stringResource(MR.strings.dictionary_auto_sort))
                            }
                        }
                    }

                    itemsIndexed(
                        items = state.dictionaries,
                        key = { _, dict -> dict.id },
                    ) { index, dictionary ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            DictionaryItem(
                                dictionary = dictionary,
                                migrationStatus = state.migrationStatuses.firstOrNull {
                                    it.dictionaryId == dictionary.id
                                },
                                isOperationInProgress = state.isImporting || state.isDeleting || state.isMigrating,
                                isFirst = index == 0,
                                isLast = index == state.dictionaries.size - 1,
                                isHighlighted = dictionary.id == state.highlightedDictionaryId,
                                onHighlightConsumed = { screenModel.clearHighlight() },
                                onToggleEnabled = { enabled ->
                                    screenModel.updateDictionary(context, dictionary.copy(isEnabled = enabled))
                                },
                                onMoveUp = {
                                    screenModel.moveDictionaryUp(dictionary)
                                },
                                onMoveDown = {
                                    screenModel.moveDictionaryDown(dictionary)
                                },
                                onDelete = {
                                    screenModel.deleteDictionary(context, dictionary.id)
                                },
                            )
                        }
                    }
                }

                item {
                    // Spacer for bottom padding (fab overlap etc if needed, or just visual breathing room)
                }
            }
        }
    }
}

@Composable
private fun rememberOcrResultPreferences(
    dictionaryPreferences: DictionaryPreferences,
    isPopup: Boolean,
): List<Preference.PreferenceItem<out Any>> {
    val popupWidthPref = remember(dictionaryPreferences) { dictionaryPreferences.ocrResultPopupWidthDp() }
    val popupWidth by popupWidthPref.collectPreferenceAsState()

    val popupHeightPref = remember(dictionaryPreferences) { dictionaryPreferences.ocrResultPopupHeightDp() }
    val popupHeight by popupHeightPref.collectPreferenceAsState()

    val popupScalePref = remember(dictionaryPreferences) { dictionaryPreferences.ocrResultPopupScalePercent() }
    val popupScale by popupScalePref.collectPreferenceAsState()

    return listOf(
        Preference.PreferenceItem.ListPreference(
            preference = dictionaryPreferences.ocrResultPresentation(),
            entries = persistentMapOf(
                OcrResultPresentation.SHEET to stringResource(MR.strings.pref_dictionary_ocr_result_presentation_sheet),
                OcrResultPresentation.POPUP to stringResource(MR.strings.pref_dictionary_ocr_result_presentation_popup),
            ),
            title = stringResource(MR.strings.pref_dictionary_ocr_result_presentation),
            subtitle = stringResource(MR.strings.pref_dictionary_ocr_result_presentation_summary),
        ),
        Preference.PreferenceItem.SliderPreference(
            value = popupWidth,
            valueRange = 240..520 step 20,
            title = stringResource(MR.strings.pref_dictionary_ocr_result_popup_width),
            subtitle = stringResource(MR.strings.pref_dictionary_ocr_result_popup_width_value, popupWidth),
            enabled = isPopup,
            onValueChanged = {
                popupWidthPref.set(it)
                true
            },
        ),
        Preference.PreferenceItem.SliderPreference(
            value = popupHeight,
            valueRange = 180..640 step 20,
            title = stringResource(MR.strings.pref_dictionary_ocr_result_popup_height),
            subtitle = stringResource(MR.strings.pref_dictionary_ocr_result_popup_height_value, popupHeight),
            enabled = isPopup,
            onValueChanged = {
                popupHeightPref.set(it)
                true
            },
        ),
        Preference.PreferenceItem.SliderPreference(
            value = popupScale,
            valueRange = 60..140 step 5,
            title = stringResource(MR.strings.pref_dictionary_ocr_result_popup_scale),
            subtitle = stringResource(MR.strings.pref_dictionary_ocr_result_popup_scale_value, popupScale),
            enabled = isPopup,
            onValueChanged = {
                popupScalePref.set(it)
                true
            },
        ),
        Preference.PreferenceItem.SwitchPreference(
            preference = dictionaryPreferences.ocrResultDimBackground(),
            title = stringResource(MR.strings.pref_dictionary_ocr_result_dim_background),
            subtitle = stringResource(MR.strings.pref_dictionary_ocr_result_dim_background_summary),
        ),
    )
}

@Composable
private fun OcrResultPreferenceGroup(
    preferences: List<Preference.PreferenceItem<out Any>>,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column {
            eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader(
                title = stringResource(MR.strings.pref_category_dictionary_ocr_results),
            )
            preferences.forEach { item ->
                PreferenceItem(
                    item = item,
                    highlightKey = null,
                )
            }
        }
    }
}

@Immutable
private data class RecommendedDictionary(
    val title: String,
    val description: String,
    val url: String,
)

@Composable
private fun RecommendedDictionaryItem(
    dictionary: RecommendedDictionary,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = dictionary.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = dictionary.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(
                onClick = onImport,
                enabled = enabled,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = stringResource(MR.strings.action_add),
                )
            }
        }
    }
}

@Composable
private fun DictionaryItem(
    dictionary: Dictionary,
    migrationStatus: DictionaryMigrationStatus?,
    isOperationInProgress: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    isHighlighted: Boolean,
    onHighlightConsumed: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    // Create an interaction source to control the ripple
    val interactionSource = remember { MutableInteractionSource() }

    // Store the size of the clickable area for centering the ripple
    var rowSize by remember { mutableStateOf(IntSize.Zero) }

    // Bring item into view and trigger a highlight (ripple) when highlighted
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            try {
                bringIntoViewRequester.bringIntoView()
            } catch (_: Exception) {
                // Ignore: item might not be laid out yet or removed concurrently.
            }

            // Allow layout/scroll to settle before triggering the ripple
            delay(50)

            val center = Offset(rowSize.width / 2f, rowSize.height / 2f)
            val press = PressInteraction.Press(center)
            interactionSource.emit(press)
            delay(300)
            interactionSource.emit(PressInteraction.Release(press))

            onHighlightConsumed()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowSize = it }
                .bringIntoViewRequester(bringIntoViewRequester)
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = !isOperationInProgress,
                    onClick = { onToggleEnabled(!dictionary.isEnabled) },
                )
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = dictionary.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = !isOperationInProgress,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = dictionary.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    dictionary.author?.let { author ->
                        Text(
                            text = "${stringResource(MR.strings.label_author)} $author",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!dictionary.sourceLanguage.isNullOrBlank() || !dictionary.targetLanguage.isNullOrBlank()) {
                        Text(
                            text = "${dictionary.sourceLanguage ?: "?"} -> ${dictionary.targetLanguage ?: "?"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${stringResource(MR.strings.label_date)} ${formatDate(dictionary.dateAdded)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    migrationStatus
                        ?.takeIf { it.state != DictionaryMigrationState.COMPLETE }
                        ?.let { status ->
                            Text(
                                text = status.progressText ?: status.stage.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.state == DictionaryMigrationState.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                }
            }

            IconButton(
                onClick = { showDeleteDialog = true },
                enabled = !isOperationInProgress,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                    disabledContentColor = Color.Gray,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }

            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = !isOperationInProgress && !isFirst,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(MR.strings.action_move_up),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = !isOperationInProgress && !isLast,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(MR.strings.action_move_down),
                    )
                }
            }
        }

        var showDetails by rememberSaveable { mutableStateOf(false) }

        Text(
            text = stringResource(
                if (showDetails) MR.strings.action_hide_details else MR.strings.action_show_details,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { showDetails = !showDetails }
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
        )

        AnimatedVisibility(
            visible = showDetails,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(stringResource(MR.strings.label_priority))
                        }
                        append(" ${dictionary.priority}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(stringResource(MR.strings.label_version))
                        }
                        append(" ${dictionary.revision}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                dictionary.description?.let { description ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(stringResource(MR.strings.label_description))
                            }
                            append(" $description")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                dictionary.url?.let { url ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(stringResource(MR.strings.label_url))
                            }
                            append(" ")
                            withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                append(url)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            try {
                                uriHandler.openUri(url)
                            } catch (_: Exception) {
                                // Handle cases where no browser is installed
                            }
                        },
                    )
                }
                dictionary.attribution?.let { attribution ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(stringResource(MR.strings.label_attribution))
                            }
                            append(" $attribution")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(MR.strings.action_delete)) },
            text = {
                Text(
                    stringResource(
                        MR.strings.delete_confirmation,
                        dictionary.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun rememberDateFormatter() = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

@Composable
private fun formatDate(ts: Long): String =
    rememberDateFormatter().format(Date(ts))
