package mihon.core.migration.migrations

import eu.kanade.domain.dictionary.OcrResultPresentation
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.util.lang.withIOContext

class SetupDictionaryOcrPresentationMigration : Migration {
    override val version: Float = 20f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return@withIOContext false

        val presentationPreference = preferenceStore.getEnum(
            key = "pref_dictionary_ocr_result_presentation",
            defaultValue = OcrResultPresentation.POPUP,
        )
        if (!presentationPreference.isSet()) {
            presentationPreference.set(OcrResultPresentation.SHEET)
        }

        val dimBackgroundPreference = preferenceStore.getBoolean(
            key = "pref_dictionary_ocr_result_dim_background",
            defaultValue = true,
        )
        if (!dimBackgroundPreference.isSet()) {
            dimBackgroundPreference.set(true)
        }

        return@withIOContext true
    }
}
