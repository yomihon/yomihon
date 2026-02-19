package eu.kanade.tachiyomi.ui.dictionary

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.dictionary.DictionarySearchScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object DictionaryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 5u,
                title = stringResource(MR.strings.label_dictionary),
                icon = rememberVectorPainter(Icons.AutoMirrored.Outlined.LibraryBooks),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Could add functionality here if needed
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val screenModel = rememberScreenModel { DictionarySearchScreenModel() }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        DictionarySearchScreen(
            state = state,
            snackbarHostState = screenModel.snackbarHostState,
            onQueryChange = screenModel::updateQuery,
            onSearch = screenModel::search,
            onTermClick = { term ->
                screenModel.selectTerm(term)
                screenModel.addToAnki(term)
            },
            onOpenDictionarySettings = {
                navigator.push(SettingsScreen(SettingsScreen.Destination.Dictionary))
            },
        )

        // Refresh dictionaries when the screen resumes (e.g., returning from settings)
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    screenModel.refreshDictionaries()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    is DictionarySearchScreenModel.Event.ShowError -> {
                        val message = when (val payload = event.message) {
                            is DictionarySearchScreenModel.UiMessage.Resource -> context.getString(
                                payload.value.resourceId,
                            )
                            is DictionarySearchScreenModel.UiMessage.Text -> payload.value
                        }
                        screenModel.snackbarHostState.showSnackbar(message)
                    }
                    is DictionarySearchScreenModel.Event.ShowMessage -> {
                        val message = when (val payload = event.message) {
                            is DictionarySearchScreenModel.UiMessage.Resource -> context.getString(
                                payload.value.resourceId,
                            )
                            is DictionarySearchScreenModel.UiMessage.Text -> payload.value
                        }
                        screenModel.snackbarHostState.showSnackbar(message)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
