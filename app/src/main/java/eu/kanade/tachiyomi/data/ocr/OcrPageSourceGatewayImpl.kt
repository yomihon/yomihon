package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

internal class OcrPageSourceGatewayImpl(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) : OcrPageSourceGateway {

    override suspend fun resolveDownloadedPages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): ResolvedOcrPages {
        val chapterPath = downloadProvider.findChapterDir(chapter.name, chapter.scanlator, manga.title, source)
        if (chapterPath?.isFile == true) {
            return resolveArchivePages(chapterPath)
        }

        val pages = downloadManager.buildPageList(source, manga, chapter).map { page ->
            OcrPageInput(
                pageIndex = page.index,
                openBitmap = {
                    withIOContext {
                        page.uri?.let { uri ->
                            context.contentResolver.openInputStream(uri)?.use(::decodeBitmap)
                        }
                    }
                },
            )
        }

        return ResolvedOcrPages(pages)
    }

    override suspend fun resolveLocalPages(
        source: LocalSource,
        chapter: Chapter,
    ): ResolvedOcrPages {
        return when (val format = source.getFormat(chapter.toSChapter())) {
            is Format.Directory -> resolveDirectoryPages(format.file)
            is Format.Archive -> resolveArchivePages(format.file)
            is Format.Epub -> resolveEpubPages(format.file)
        }
    }

    private fun resolveDirectoryPages(file: UniFile): ResolvedOcrPages {
        val pages = file.listFiles()
            ?.filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.sortedWith { file1, file2 ->
                file1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(file2.name.orEmpty())
            }
            ?.mapIndexed { index, imageFile ->
                OcrPageInput(
                    pageIndex = index,
                    openBitmap = {
                        withIOContext {
                            imageFile.openInputStream()?.use(::decodeBitmap)
                        }
                    },
                )
            }
            .orEmpty()

        return ResolvedOcrPages(pages)
    }

    private suspend fun resolveArchivePages(file: UniFile): ResolvedOcrPages {
        val reader = file.archiveReader(context)
        val entryNames = withIOContext {
            buildList {
                reader.useEntriesAndStreams { entry, stream ->
                    if (entry.isFile && isArchiveImageEntry(entry.name, stream)) {
                        add(entry.name)
                    }
                }
            }
        }
            .sortedWith { entry1, entry2 ->
                entry1.compareToCaseInsensitiveNaturalOrder(entry2)
            }

        val pages = entryNames.mapIndexed { index, entryName ->
            OcrPageInput(
                pageIndex = index,
                openBitmap = {
                    withIOContext {
                        reader.getInputStream(entryName)?.use(::decodeArchiveBitmap)
                    }
                },
            )
        }

        return ResolvedOcrPages(
            pages = pages,
            closeBlock = reader::close,
        )
    }

    private suspend fun resolveEpubPages(file: UniFile): ResolvedOcrPages {
        val reader = file.epubReader(context)
        val imagePaths = withIOContext { reader.getImagesFromPages() }

        val pages = imagePaths.mapIndexed { index, path ->
            OcrPageInput(
                pageIndex = index,
                openBitmap = {
                    withIOContext {
                        reader.getInputStream(path)?.use(::decodeBitmap)
                    }
                },
            )
        }

        return ResolvedOcrPages(
            pages = pages,
            closeBlock = reader::close,
        )
    }
}
