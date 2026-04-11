package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.SuggestionChip
import tachiyomi.presentation.core.components.material.SuggestionChipDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedSearchItem(
    savedSearches: ImmutableList<BrowseSourceScreenModel.SavedSearchItem>,
    onSavedSearch: (BrowseSourceScreenModel.SavedSearchItem) -> Unit,
    onSavedSearchPress: (BrowseSourceScreenModel.SavedSearchItem) -> Unit,
    onSavedSearchPressDesc: String,
) {
    if (savedSearches.isEmpty()) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    ) {
        Text(
            text = onSavedSearchPressDesc,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            modifier = Modifier.padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            savedSearches.forEach { savedSearch ->
                SuggestionChip(
                    onClick = { onSavedSearch(savedSearch) },
                    onLongClick = { onSavedSearchPress(savedSearch) },
                    label = {
                        Text(
                            text = savedSearch.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
    }
}
