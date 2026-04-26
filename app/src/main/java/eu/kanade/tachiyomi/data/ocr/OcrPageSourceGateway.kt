package eu.kanade.tachiyomi.data.ocr

import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

internal interface OcrPageSourceGateway {
    suspend fun resolveDownloadedPages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): ResolvedOcrPages

    suspend fun resolveLocalPages(
        source: LocalSource,
        chapter: Chapter,
    ): ResolvedOcrPages
}
