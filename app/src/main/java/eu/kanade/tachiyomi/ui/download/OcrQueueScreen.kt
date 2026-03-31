package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.collections.immutable.toPersistentList
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.feature.ocr.titleRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.ExtendedFloatingActionButton
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object OcrQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { OcrQueueScreenModel() }
        val state by screenModel.state.collectAsState()
        val isQueueRunning by screenModel.isQueueRunning.collectAsState()
        val hasQueue = state.totalCount > 0
        val ocrPreferences = remember { Injekt.get<OcrPreferences>() }
        val ocrModelPreference = remember { ocrPreferences.ocrModel() }
        val ocrModel by ocrModelPreference.changes().collectAsState(initial = ocrModelPreference.get())
        val autoOcrOnDownloadPreference = remember { ocrPreferences.autoOcrOnDownload() }
        val autoOcrOnDownload by autoOcrOnDownloadPreference
            .changes()
            .collectAsState(initial = autoOcrOnDownloadPreference.get())

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_text_recognition),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.totalCount > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = state.totalCount.toString(),
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                                        .copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        if (hasQueue) {
                            AppBarActions(
                                listOf(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = screenModel::clearQueue,
                                    ),
                                ).toPersistentList(),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = hasQueue,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ExtendedFloatingActionButton(
                        text = {
                            Text(
                                text = stringResource(
                                    if (isQueueRunning) MR.strings.action_pause else MR.strings.action_resume,
                                ),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = if (isQueueRunning) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            if (isQueueRunning) {
                                screenModel.pauseQueue()
                            } else {
                                screenModel.resumeQueue()
                            }
                        },
                        expanded = fabExpanded,
                    )
                }
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .nestedScroll(nestedScrollConnection),
            ) {
                PreferenceGroupHeader(title = stringResource(MR.strings.label_settings))
                ListPreferenceWidget(
                    value = ocrModel,
                    title = stringResource(MR.strings.pref_ocr_model),
                    subtitle = stringResource(ocrModel.titleRes),
                    icon = null,
                    entries = mapOf(
                        OcrModel.LEGACY to stringResource(OcrModel.LEGACY.titleRes),
                        OcrModel.FAST to stringResource(OcrModel.FAST.titleRes),
                        OcrModel.GLENS to stringResource(OcrModel.GLENS.titleRes),
                    ),
                    onValueChange = ocrModelPreference::set,
                )
                SwitchPreferenceWidget(
                    checked = autoOcrOnDownload,
                    title = stringResource(MR.strings.pref_auto_ocr_on_download),
                    onCheckedChanged = autoOcrOnDownloadPreference::set,
                )

                PreferenceGroupHeader(title = stringResource(MR.strings.ocr_queue_header))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (!hasQueue) {
                        EmptyScreen(
                            message = stringResource(MR.strings.ocr_queue_empty),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                screenModel.controllerBinding = DownloadListBinding.inflate(LayoutInflater.from(context))
                                screenModel.adapter = OcrAdapter(screenModel.listener)
                                screenModel.controllerBinding.root.adapter = screenModel.adapter
                                screenModel.adapter?.isHandleDragEnabled = true
                                screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)

                                ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                                screenModel.controllerBinding.root
                            },
                            update = {
                                screenModel.adapter?.updateDataSet(state.items)
                            },
                        )
                    }
                }
            }
        }
    }
}
