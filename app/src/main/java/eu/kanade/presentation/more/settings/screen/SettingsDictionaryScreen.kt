package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.setting.dictionary.DictionarySettingsScreenModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import mihon.domain.dictionary.model.Dictionary
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SettingsDictionaryScreen : Screen {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val backPress = LocalBackPress.currentOrThrow
        val screenModel = rememberScreenModel { DictionarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val lazyListState = rememberLazyListState()

        // State to control the exit confirmation dialog
        var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
        val isOperationInProgress = state.isImporting || state.isDeleting

        // Intercept System Back Button
        BackHandler(enabled = true) {
            if (isOperationInProgress) {
                showExitConfirmation = true
            } else {
                backPress()
            }
        }

        // Scroll to highlighted dictionary when it changes
        LaunchedEffect(state.highlightedDictionaryId, state.dictionaries) {
            val highlightedId = state.highlightedDictionaryId
            if (highlightedId != null) {
                val index = state.dictionaries.indexOfFirst { it.id == highlightedId }
                if (index >= 0) {
                    val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                    val isVisible = visibleItems.any { it.index == index }
                    if (!isVisible) {
                        lazyListState.animateScrollToItem(index)
                    }
                }
            }
        }

        // File picker for dictionary import
        val pickDictionary = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) {
                screenModel.importDictionaryFromUri(context, uri)
            } else {
                context.toast(MR.strings.file_null_uri_error)
            }
        }

        // Show error messages
        LaunchedEffect(state.error) {
            state.error?.let { error ->
                scope.launch {
                    snackbarHostState.showSnackbar(error)
                    screenModel.clearError()
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_category_dictionaries),
                    // Check if busy before navigating up
                    navigateUp = {
                        if (isOperationInProgress) {
                            showExitConfirmation = true
                        } else {
                            backPress()
                        }
                    },
                    scrollBehavior = it,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Import button
                OutlinedButton(
                    onClick = {
                        try {
                            pickDictionary.launch("application/zip")
                        } catch (e: ActivityNotFoundException) {
                            context.toast(MR.strings.file_picker_error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isImporting && !state.isDeleting,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(MR.strings.import_dictionary))
                }

                // Import progress
                if (state.isImporting) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                text = stringResource(MR.strings.importing_dictionary),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                            )
                            state.importProgress?.let { progress ->
                                Text(
                                    text = progress,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Delete progress
                if (state.isDeleting) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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

                // Dictionaries list
                if (state.isLoading && state.dictionaries.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                } else if (state.dictionaries.isEmpty()) {
                    val uriHandler = LocalUriHandler.current
                    Column(
                        modifier = Modifier.padding(vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.no_dictionaries),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(MR.strings.learn_add_dictionaries),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                uriHandler.openUri("https://mihon-ocr.github.io/docs/guides/dictionaries")
                            },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(MR.strings.installed_dictionaries),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    LazyColumn(
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = state.dictionaries,
                            key = { _, dict -> dict.id },
                        ) { index, dictionary ->
                            DictionaryItem(
                                dictionary = dictionary,
                                isOperationInProgress = state.isImporting || state.isDeleting,
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
            }
        }

        // Exit Confirmation Dialog
        if (showExitConfirmation) {
            AlertDialog(
                onDismissRequest = { showExitConfirmation = false },
                title = { Text(stringResource(MR.strings.exit_screen)) },
                text = { Text(stringResource(MR.strings.confirm_exit_while_busy)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitConfirmation = false
                            backPress()
                        },
                    ) {
                        Text(stringResource(MR.strings.action_leave))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirmation = false }) {
                        Text(stringResource(MR.strings.action_stay))
                    }
                },
            )
        }
    }
}

@Composable
private fun DictionaryItem(
    dictionary: Dictionary,
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

    // Trigger the ripple programmatically
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
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
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = !isOperationInProgress,
                    onClick = { onToggleEnabled(!dictionary.isEnabled) }
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
                    Text(
                        text = "${stringResource(MR.strings.label_date)} ${formatDate(dictionary.dateAdded)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
