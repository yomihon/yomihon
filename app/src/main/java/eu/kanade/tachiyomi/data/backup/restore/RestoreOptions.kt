package eu.kanade.tachiyomi.data.backup.restore

import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR

data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appSettings: Boolean = true,
    val extensionRepoSettings: Boolean = true,
    val sourceSettings: Boolean = true,
    val savedSearches: Boolean = true,
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appSettings,
        extensionRepoSettings,
        sourceSettings,
        savedSearches,
    )

    fun canRestore() =
        libraryEntries || categories || appSettings || extensionRepoSettings || sourceSettings || savedSearches

    companion object {
        val options = persistentListOf(
            Entry(
                label = MR.strings.label_library,
                getter = RestoreOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.categories,
                getter = RestoreOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.app_settings,
                getter = RestoreOptions::appSettings,
                setter = { options, enabled -> options.copy(appSettings = enabled) },
            ),
            Entry(
                label = MR.strings.extensionRepo_settings,
                getter = RestoreOptions::extensionRepoSettings,
                setter = { options, enabled -> options.copy(extensionRepoSettings = enabled) },
            ),
            Entry(
                label = MR.strings.source_settings,
                getter = RestoreOptions::sourceSettings,
                setter = { options, enabled -> options.copy(sourceSettings = enabled) },
            ),
            Entry(
                label = MR.strings.saved_searches,
                getter = RestoreOptions::savedSearches,
                setter = { options, enabled -> options.copy(savedSearches = enabled) },
            ),
        )

        fun fromBooleanArray(array: BooleanArray) = RestoreOptions(
            libraryEntries = array.getOrElse(0) { true },
            categories = array.getOrElse(1) { true },
            appSettings = array.getOrElse(2) { true },
            extensionRepoSettings = array.getOrElse(3) { true },
            sourceSettings = array.getOrElse(4) { true },
            savedSearches = array.getOrElse(5) { true },
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (RestoreOptions) -> Boolean,
        val setter: (RestoreOptions, Boolean) -> RestoreOptions,
    )
}
