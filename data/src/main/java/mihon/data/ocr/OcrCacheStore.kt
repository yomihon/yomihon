package mihon.data.ocr

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import tachiyomi.data.ocr.OcrCacheDatabase

internal class OcrCacheStore(
    private val context: Context,
) {
    private val mutex = Mutex()

    @Volatile
    private var databaseHandle: DatabaseHandle? = null

    suspend fun upsert(pageResult: OcrPageResult) {
        mutex.withLock {
            val db = getDatabase()

            db.transaction {
                db.ocr_cacheQueries.insertPage(
                    chapterId = pageResult.chapterId,
                    pageIndex = pageResult.pageIndex.toLong(),
                    ocrModel = pageResult.ocrModel.name,
                    imageWidth = pageResult.imageWidth.toLong(),
                    imageHeight = pageResult.imageHeight.toLong(),
                    createdAt = System.currentTimeMillis(),
                )
                val pageId = db.ocr_cacheQueries.selectLastInsertedRowId().executeAsOne()
                pageResult.regions.forEach { region ->
                    val box = region.boundingBox
                    db.ocr_cacheQueries.insertRegion(
                        pageId = pageId,
                        regionOrder = region.order.toLong(),
                        leftNorm = box.left.toDouble(),
                        topNorm = box.top.toDouble(),
                        rightNorm = box.right.toDouble(),
                        bottomNorm = box.bottom.toDouble(),
                        text = region.text,
                        orientation = region.textOrientation.name,
                    )
                }
            }
        }
    }

    suspend fun getPage(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult? {
        return mutex.withLock {
            val db = getDatabase()
            val page = db.ocr_cacheQueries.getPage(
                chapterId = chapterId,
                pageIndex = pageIndex.toLong(),
            ) { _id, _chapterId, _pageIndex, _ocrModel, imageWidth, imageHeight, _createdAt ->
                OcrPageRow(
                    id = _id,
                    chapterId = _chapterId,
                    pageIndex = _pageIndex.toInt(),
                    ocrModel = OcrModel.valueOf(_ocrModel),
                    imageWidth = imageWidth.toInt(),
                    imageHeight = imageHeight.toInt(),
                )
            }.executeAsOneOrNull() ?: return@withLock null

            val regions = db.ocr_cacheQueries.getRegionsForPage(page.id) {
                    _id,
                    _pageId,
                    regionOrder,
                    leftNorm,
                    topNorm,
                    rightNorm,
                    bottomNorm,
                    text,
                    orientation,
                ->
                OcrRegionRow(
                    id = _id,
                    pageId = _pageId,
                    regionOrder = regionOrder.toInt(),
                    region = OcrRegion(
                        order = regionOrder.toInt(),
                        text = text,
                        boundingBox = OcrBoundingBox(
                            left = leftNorm.toFloat(),
                            top = topNorm.toFloat(),
                            right = rightNorm.toFloat(),
                            bottom = bottomNorm.toFloat(),
                        ),
                        textOrientation = OcrTextOrientation.valueOf(orientation),
                    ),
                )
            }.executeAsList()

            OcrPageResult(
                chapterId = page.chapterId,
                pageIndex = page.pageIndex,
                ocrModel = page.ocrModel,
                imageWidth = page.imageWidth,
                imageHeight = page.imageHeight,
                regions = regions.sortedBy(OcrRegionRow::regionOrder).map(OcrRegionRow::region),
            )
        }
    }

    suspend fun getCachedChapterIds(
        chapterIds: Collection<Long>,
    ): Set<Long> {
        if (chapterIds.isEmpty()) {
            return emptySet()
        }

        return mutex.withLock {
            val db = getDatabase()
            db.ocr_cacheQueries.getCachedChapterIds(
                chapter_id = chapterIds.toList(),
            ).executeAsList().toSet()
        }
    }

    suspend fun clearChapter(
        chapterId: Long,
    ) {
        mutex.withLock {
            val db = getDatabase()
            db.ocr_cacheQueries.deleteChapterPages(
                chapterId = chapterId,
            )
        }
    }

    suspend fun clear() {
        mutex.withLock {
            closeDatabaseHandle()
            deleteDatabaseFile()
        }
    }

    fun sizeBytes(): Long {
        val dbName = DB_NAME
        return listOf(
            context.getDatabasePath(dbName),
            context.getDatabasePath("$dbName-wal"),
            context.getDatabasePath("$dbName-shm"),
            context.getDatabasePath("$dbName-journal"),
        ).sumOf { file -> if (file.exists()) file.length() else 0L }
    }

    suspend fun close() {
        mutex.withLock {
            closeDatabaseHandle()
        }
    }

    private fun getDatabase(): OcrCacheDatabase {
        val existing = databaseHandle?.database
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            databaseHandle?.database ?: createDatabaseHandle().also { databaseHandle = it }.database
        }
    }

    private fun createDatabaseHandle(): DatabaseHandle {
        deleteDatabaseIfSchemaOutdated()
        val driver = AndroidSqliteDriver(
            schema = OcrCacheDatabase.Schema,
            context = context,
            name = DB_NAME,
            callback = object : AndroidSqliteDriver.Callback(OcrCacheDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            },
        )
        return DatabaseHandle(
            database = OcrCacheDatabase(driver),
            driver = driver,
        )
    }

    private fun deleteDatabaseIfSchemaOutdated() {
        val databasePath = context.getDatabasePath(DB_NAME)
        if (!databasePath.exists()) {
            return
        }

        val database = SQLiteDatabase.openDatabase(
            databasePath.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        var shouldDelete = false
        try {
            database.rawQuery("PRAGMA table_info(ocr_regions)", null).use { cursor ->
                var hasOrientationColumn = false
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == "orientation") {
                        hasOrientationColumn = true
                        break
                    }
                }
                shouldDelete = !hasOrientationColumn
            }
        } finally {
            database.close()
        }
        if (shouldDelete) {
            deleteDatabaseFile()
        }
    }

    private fun deleteDatabaseFile() {
        context.deleteDatabase(DB_NAME)
        context.getDatabasePath(DB_NAME).delete()
        context.getDatabasePath("$DB_NAME-wal").delete()
        context.getDatabasePath("$DB_NAME-shm").delete()
        context.getDatabasePath("$DB_NAME-journal").delete()
    }

    private fun closeDatabaseHandle() {
        databaseHandle?.close()
        databaseHandle = null
    }

    private data class OcrPageRow(
        val id: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val ocrModel: OcrModel,
        val imageWidth: Int,
        val imageHeight: Int,
    )

    private data class OcrRegionRow(
        val id: Long,
        val pageId: Long,
        val regionOrder: Int,
        val region: OcrRegion,
    )

    private data class DatabaseHandle(
        val database: OcrCacheDatabase,
        val driver: AndroidSqliteDriver,
    ) {
        fun close() {
            driver.close()
        }
    }

    companion object {
        private const val DB_NAME = "ocr_cache.db"
    }
}
