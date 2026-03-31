package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderOcrPageIdentity
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderOcrRegionSelection
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.domain.ocr.repository.OcrRepository
import mihon.domain.panel.interactor.DetectPanels
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.Panel
import tachiyomi.core.common.util.system.ReadingDirection
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    private val ocrRepository: OcrRepository by injectLazy()
    private val detectPanels: DetectPanels by lazy { Injekt.get() }

    private var panels: List<Panel> = emptyList()
    private var currentPanelIndex = -1
    private var panelDetectionJob: Job? = null
    private var panelDetectionGeneration = 0
    private var lastPageReadyDirection: Boolean? = null

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    init {
        setOcrPageIdentity(page.chapter.chapter.id, page.index)
        onOcrRegionClicked = regionTap@{ tap ->
            val chapterId = page.chapter.chapter.id ?: return@regionTap
            viewer.activity.showOcrResult(
                ReaderOcrRegionSelection(
                    page = ReaderOcrPageIdentity(chapterId, page.index),
                    regionOrder = tap.regionOrder,
                    displayText = tap.displayText,
                    queryText = tap.queryText,
                    boundingBox = tap.boundingBox,
                    textOrientation = tap.textOrientation,
                    anchorRectOnScreen = tap.anchorRectOnScreen,
                    initialSelectionOffset = tap.initialSelectionOffset,
                ),
            )
        }
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        clearOcrPageIdentity()
        panelDetectionJob?.cancel()
        panelDetectionJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
        clearCachedOcrResult()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
        clearCachedOcrResult()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
        clearCachedOcrResult()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)
        panels = emptyList()
        currentPanelIndex = -1
        lastPageReadyDirection = null
        panelDetectionJob?.cancel()
        panelDetectionGeneration++

        val streamFn = page.stream ?: return

        try {
            val loadResult = withIOContext {
                val source = streamFn().use { process(item, Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                LoadResult(source, isAnimated, background)
            }
            withUIContext {
                setImage(
                    loadResult.source,
                    loadResult.isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                )
                if (!loadResult.isAnimated) {
                    pageBackground = loadResult.background
                }
                removeErrorLayout()
                loadCachedOcrResult()
                maybeStartPanelDetection(loadResult)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun maybeStartPanelDetection(loadResult: LoadResult) {
        if (!viewer.config.panelNavigation || loadResult.isAnimated) {
            return
        }

        val generation = panelDetectionGeneration
        val cacheKey = panelDetectionCacheKey()
        val detectionSource = loadResult.source.peek()

        panelDetectionJob = scope.launchIO {
            val decoded = decodePanelBitmap(detectionSource) ?: return@launchIO
            val result = try {
                detectPanels.await(
                    cacheKey = cacheKey,
                    image = decoded.bitmap,
                    originalWidth = decoded.originalWidth,
                    originalHeight = decoded.originalHeight,
                    direction = readingDirection(),
                )
            } finally {
                decoded.bitmap.recycle()
            }

            withUIContext {
                if (generation != panelDetectionGeneration) return@withUIContext

                panels = result.panels
                currentPanelIndex = -1
                if (viewer.config.panelNavigation && panels.isNotEmpty() && lastPageReadyDirection != null &&
                    isVisibleOnScreen()
                ) {
                    zoomToFirstPanel(lastPageReadyDirection!!)
                }
            }
        }
    }

    private fun decodePanelBitmap(source: BufferedSource): DecodedPanelBitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        source.peek().inputStream().use {
            BitmapFactory.decodeStream(it, null, options)
        }

        val largestDimension = maxOf(options.outWidth, options.outHeight)
        if (largestDimension <= 0) return null

        val sampleSize = generateSequence(1) { it * 2 }
            .first { largestDimension / it <= 800 }

        val bitmap = source.peek().inputStream().use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } ?: return null

        return DecodedPanelBitmap(
            bitmap = bitmap,
            originalWidth = options.outWidth,
            originalHeight = options.outHeight,
        )
    }

    private fun readingDirection(): ReadingDirection {
        return when (viewer) {
            is R2LPagerViewer -> ReadingDirection.RTL
            is VerticalPagerViewer -> ReadingDirection.VERTICAL
            else -> ReadingDirection.LTR
        }
    }

    fun hasPanels(): Boolean = panels.isNotEmpty()

    fun hasNextPanel(): Boolean = hasPanels() && currentPanelIndex < panels.lastIndex

    fun hasPreviousPanel(): Boolean = hasPanels() && currentPanelIndex > 0

    fun zoomToNextPanel(): Boolean {
        if (!hasNextPanel()) return false
        val nextIndex = currentPanelIndex + 1
        val zoomed = zoomToPanel(panels[nextIndex])
        if (zoomed) {
            currentPanelIndex = nextIndex
        }
        return zoomed
    }

    fun zoomToPreviousPanel(): Boolean {
        if (!hasPreviousPanel()) return false
        val previousIndex = currentPanelIndex - 1
        val zoomed = zoomToPanel(panels[previousIndex])
        if (zoomed) {
            currentPanelIndex = previousIndex
        }
        return zoomed
    }

    fun zoomToFirstPanel(forward: Boolean) {
        if (!hasPanels()) return
        val firstIndex = if (forward) 0 else panels.lastIndex
        if (zoomToPanel(panels[firstIndex])) {
            currentPanelIndex = firstIndex
        }
    }

    protected override fun onPageReady(forward: Boolean) {
        lastPageReadyDirection = forward
        if (viewer.config.panelNavigation && hasPanels()) {
            zoomToFirstPanel(forward)
            return
        }
        super.onPageReady(forward)
    }

    override fun onPageSelected(forward: Boolean) {
        super.onPageSelected(forward)
        lastPageReadyDirection = forward
    }

    private fun panelDetectionCacheKey(): String {
        val pageType = if (page is InsertPage) "insert" else "page"
        return buildString {
            append(page.chapter.chapter.id)
            append(':')
            append(page.index)
            append(':')
            append(pageType)
            append(':')
            append(page.url)
            append(':')
            append(page.imageUrl ?: "")
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
        clearCachedOcrResult()
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    private fun loadCachedOcrResult() {
        val chapterId = page.chapter.chapter.id ?: return clearCachedOcrResult()
        scope.launchIO {
            val cachedResult = ocrRepository.getCachedPage(chapterId, page.index)
            withUIContext {
                setCachedOcrResult(cachedResult)
                viewer.activity.syncActiveOcrOverlay()
            }
        }
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}

private data class LoadResult(
    val source: BufferedSource,
    val isAnimated: Boolean,
    val background: Drawable?,
)

private data class DecodedPanelBitmap(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
)
