package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.color.MaterialColors
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import logcat.LogPriority
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.flattenOcrTextForQuery
import mihon.domain.ocr.model.normalizeOcrTextForDisplay
import mihon.domain.panel.model.DebugPanelDetection
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.Panel
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.google.android.material.R as MaterialR

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV().get()
    }

    private var pageView: View? = null
    private val panelDebugOverlay = PanelDebugOverlayView(context)

    private var config: Config? = null
    private var cachedOcrResult: OcrPageResult? = null
    private var ocrPageIdentity: ReaderOcrPageIdentity? = null
    private var activeOcrOverlay: ReaderActiveOcrOverlay? = null
    private var activeOverlayLayout: ReaderOcrOverlayLayout? = null
    private var pendingOnPageReadyDirection: Boolean? = null

    private val ocrOverlayBackgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(176, 16, 16, 16)
        }
    private val ocrOverlayStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.5f
        }
    private val ocrOverlayTextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
        }
    private val ocrOverlayRenderer by lazy {
        ReaderOcrOverlayRenderer(
            textPaint = ocrOverlayTextPaint,
            density = resources.displayMetrics.density,
            scaledDensity = resources.displayMetrics.density * resources.configuration.fontScale,
            highlightColor = MaterialColors.getColor(
                context,
                MaterialR.attr.colorPrimaryContainer,
                Color.argb(255, 255, 214, 10),
            ),
        )
    }

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null
    var onOcrRegionClicked: ((ReaderPageOcrRegionTap) -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    init {
        addView(panelDebugOverlay, MATCH_PARENT, MATCH_PARENT)
        panelDebugOverlay.isVisible = false
    }

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
        invalidateActiveOverlayLayout()
        invalidate()
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                onPageReady(forward)
            } else {
                pendingOnPageReadyDirection = forward
            }
        }
    }

    protected open fun onPageReady(forward: Boolean) {
        (pageView as? SubsamplingScaleImageView)?.landscapeZoom(forward)
    }

    fun zoomToPanel(panel: Panel): Boolean {
        val view = pageView as? SubsamplingScaleImageView ?: return false
        if (!view.isReady) {
            logcat(LogPriority.VERBOSE) {
                "Panel nav zoomToPanel skipped: view not ready rect=${panel.rect.flattenToString()}"
            }
            return false
        }

        val scaleX = view.width.toFloat() / panel.rect.width().coerceAtLeast(1)
        val scaleY = view.height.toFloat() / panel.rect.height().coerceAtLeast(1)
        val targetScale = minOf(scaleX, scaleY) * 0.95f
        val clampedScale = targetScale.coerceIn(view.minScale, view.maxScale)
        val targetCenter = PointF(panel.rect.centerX().toFloat(), panel.rect.centerY().toFloat())

        // Compute where the view will actually end up after pan-limit clamping
        val clampedCenter = clampCenter(
            targetCenter,
            clampedScale,
            view.width,
            view.height,
            view.sWidth,
            view.sHeight,
        )

        // Compare current visible rect vs target visible rect
        val currentCenter = view.center
        val overlap = if (currentCenter != null) {
            visibleRectOverlap(
                currentCenter,
                view.scale,
                clampedCenter,
                clampedScale,
                view.width,
                view.height,
                view.sWidth,
                view.sHeight,
            )
        } else {
            0f
        }

        logcat(LogPriority.VERBOSE) {
            "Panel nav zoomToPanel rect=${panel.rect.flattenToString()} " +
                "view=${view.width}x${view.height} " +
                "targetScale=$targetScale clampedScale=$clampedScale currentScale=${view.scale} " +
                "clampedCenter=${clampedCenter.x},${clampedCenter.y} overlap=$overlap"
        }

        if (overlap > SKIP_ZOOM_OVERLAP_THRESHOLD) {
            logcat(LogPriority.VERBOSE) { "Panel nav zoomToPanel skipped: view barely changes (overlap=$overlap)" }
            return false
        }

        view.animateScaleAndCenter(
            clampedScale,
            targetCenter,
        )!!
            .withDuration(400)
            .withEasing(EASE_IN_OUT_QUAD)
            .withInterruptible(true)
            .start()

        return true
    }

    /**
     * Replicates SubsamplingScaleImageView's PAN_LIMIT_INSIDE clamping to predict
     * where the view will actually end up for a given center and scale.
     */
    private fun clampCenter(
        requested: PointF,
        scale: Float,
        viewWidth: Int,
        viewHeight: Int,
        sWidth: Int,
        sHeight: Int,
    ): PointF {
        val scaledWidth = sWidth * scale
        val scaledHeight = sHeight * scale
        val vCenterX = viewWidth / 2f
        val vCenterY = viewHeight / 2f

        var vTranslateX = vCenterX - requested.x * scale
        var vTranslateY = vCenterY - requested.y * scale

        if (scaledWidth <= viewWidth) {
            vTranslateX = (viewWidth - scaledWidth) / 2f
        } else {
            vTranslateX = vTranslateX.coerceIn(viewWidth - scaledWidth, 0f)
        }
        if (scaledHeight <= viewHeight) {
            vTranslateY = (viewHeight - scaledHeight) / 2f
        } else {
            vTranslateY = vTranslateY.coerceIn(viewHeight - scaledHeight, 0f)
        }

        return PointF(
            (vCenterX - vTranslateX) / scale,
            (vCenterY - vTranslateY) / scale,
        )
    }

    /**
     * Computes how much the visible source rects overlap between two view states.
     * Returns 0..1 where 1 means identical views.
     */
    private fun visibleRectOverlap(
        centerA: PointF,
        scaleA: Float,
        centerB: PointF,
        scaleB: Float,
        viewWidth: Int,
        viewHeight: Int,
        sWidth: Int,
        sHeight: Int,
    ): Float {
        fun visibleRect(center: PointF, scale: Float): RectF {
            val halfW = viewWidth / (2f * scale)
            val halfH = viewHeight / (2f * scale)
            return RectF(
                (center.x - halfW).coerceAtLeast(0f),
                (center.y - halfH).coerceAtLeast(0f),
                (center.x + halfW).coerceAtMost(sWidth.toFloat()),
                (center.y + halfH).coerceAtMost(sHeight.toFloat()),
            )
        }

        val rectA = visibleRect(centerA, scaleA)
        val rectB = visibleRect(centerB, scaleB)

        val interLeft = maxOf(rectA.left, rectB.left)
        val interTop = maxOf(rectA.top, rectB.top)
        val interRight = minOf(rectA.right, rectB.right)
        val interBottom = minOf(rectA.bottom, rectB.bottom)

        if (interLeft >= interRight || interTop >= interBottom) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = rectA.width() * rectA.height()
        val areaB = rectB.width() * rectB.height()
        val unionArea = areaA + areaB - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    fun setPanelDebugDetections(
        detections: List<DebugPanelDetection>,
        bubbles: List<DebugPanelDetection> = emptyList(),
    ) {
        panelDebugOverlay.setDetections(detections, bubbles, pageView as? SubsamplingScaleImageView)
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() = pageView?.let {
        clearOcrPageIdentity()
        clearCachedOcrResult()
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
        panelDebugOverlay.setDetections(emptyList(), emptyList(), null)
    }

    fun setOcrPageIdentity(
        chapterId: Long?,
        pageIndex: Int,
    ) {
        ocrPageIdentity = chapterId?.let { ReaderOcrPageIdentity(it, pageIndex) }
    }

    fun clearOcrPageIdentity() {
        ocrPageIdentity = null
        setActiveOcrOverlay(null)
    }

    fun matchesOcrPage(pageIdentity: ReaderOcrPageIdentity): Boolean {
        return ocrPageIdentity == pageIdentity
    }

    fun sourceRectForScreenRect(screenRect: RectF): Rect? {
        val selectionLocalRect = screenRectToLocalRect(screenRect) ?: return null
        val imageLocalRect = displayedImageLocalRect() ?: return null
        val clampedLocalRect = RectF(selectionLocalRect).apply {
            if (!intersect(imageLocalRect)) {
                return null
            }
        }
        val topLeftSource = localPointToSourcePoint(clampedLocalRect.left, clampedLocalRect.top) ?: return null
        val bottomRightSource = localPointToSourcePoint(clampedLocalRect.right, clampedLocalRect.bottom) ?: return null

        val left = min(topLeftSource.x, bottomRightSource.x).toInt()
        val top = min(topLeftSource.y, bottomRightSource.y).toInt()
        val right = max(topLeftSource.x, bottomRightSource.x).toInt()
        val bottom = max(topLeftSource.y, bottomRightSource.y).toInt()

        return Rect(left, top, right, bottom).takeIf { it.width() > 0 && it.height() > 0 }
    }

    fun setActiveOcrOverlay(overlay: ReaderActiveOcrOverlay?) {
        activeOcrOverlay = overlay
        invalidateActiveOverlayLayout()
        invalidate()
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                        panelDebugOverlay.invalidate()
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        panelDebugOverlay.invalidate()
                        invalidate()
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
        bringChildToFront(panelDebugOverlay)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    val direction = pendingOnPageReadyDirection
                    pendingOnPageReadyDirection = null
                    when {
                        direction != null -> onPageReady(direction)
                        isVisibleOnScreen() -> onPageReady(true)
                    }
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon || alwaysDecodeLongStripWithSSIV) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                setOnMatrixChangeListener {
                    invalidate()
                }
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
        bringChildToFront(panelDebugOverlay)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    fun setCachedOcrResult(result: OcrPageResult?) {
        cachedOcrResult = result
        invalidateActiveOverlayLayout()
        invalidate()
    }

    fun clearCachedOcrResult() {
        cachedOcrResult = null
        invalidateActiveOverlayLayout()
        invalidate()
    }

    fun tryConsumeOcrTap(rawX: Float, rawY: Float): Boolean {
        val localPoint = rawPointToLocalPoint(rawX, rawY) ?: return false
        return tryConsumeOcrTapLocal(localPoint.x, localPoint.y)
    }

    fun tryConsumeActiveOcrOverlayTap(rawX: Float, rawY: Float): ReaderActiveOcrTapResult? {
        val localPoint = rawPointToLocalPoint(rawX, rawY) ?: return null
        return tryConsumeActiveOcrOverlayTapLocal(localPoint.x, localPoint.y)
    }

    fun tryConsumeOcrTapLocal(localX: Float, localY: Float): Boolean {
        val result = cachedOcrResult ?: return false
        val sourcePoint = localPointToSourcePoint(localX, localY) ?: return false
        val region = result.findRegionAt(sourcePoint.x, sourcePoint.y) ?: return false

        val displayText = normalizeOcrTextForDisplay(region.text)

        onOcrRegionClicked?.invoke(
            ReaderPageOcrRegionTap(
                regionOrder = region.order,
                displayText = displayText,
                queryText = flattenOcrTextForQuery(region.text),
                boundingBox = region.boundingBox,
                textOrientation = region.textOrientation,
                anchorRectOnScreen = boundingBoxToScreenRect(region.boundingBox, result),
                initialSelectionOffset = resolveInitialSelectionOffset(region, displayText, result, localX, localY),
            ),
        )
        return true
    }

    fun tryConsumeActiveOcrOverlayTapLocal(
        localX: Float,
        localY: Float,
    ): ReaderActiveOcrTapResult? {
        val overlayLayout = getOrBuildActiveOverlayLayout() ?: return null
        if (!overlayLayout.bubbleRect.contains(localX, localY)) return null
        return if (ocrOverlayRenderer.isPointNearText(overlayLayout, localX, localY)) {
            ReaderActiveOcrTapResult.SelectWord(
                ocrOverlayRenderer.resolveQueryOffset(overlayLayout, localX, localY),
            )
        } else {
            ReaderActiveOcrTapResult.BubbleTap
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawActiveOcrOverlay(canvas)
    }

    private fun drawActiveOcrOverlay(canvas: Canvas) {
        val overlayLayout = getOrBuildActiveOverlayLayout() ?: return
        canvas.drawRect(overlayLayout.bubbleRect, ocrOverlayBackgroundPaint)
        canvas.drawRect(overlayLayout.bubbleRect, ocrOverlayStrokePaint)
        ocrOverlayRenderer.drawOverlay(canvas, overlayLayout)
    }

    private fun resolveInitialSelectionOffset(
        region: mihon.domain.ocr.model.OcrRegion,
        normalizedDisplayText: String,
        pageResult: OcrPageResult,
        localX: Float,
        localY: Float,
    ): Int {
        val overlayLayout = ocrOverlayRenderer.buildLayout(
            bubbleRect = boundingBoxToLocalRect(region.boundingBox, pageResult) ?: return 0,
            displayText = normalizedDisplayText,
            textOrientation = region.textOrientation,
            highlightRange = null,
        ) ?: return 0
        return if (ocrOverlayRenderer.isPointNearText(overlayLayout, localX, localY)) {
            ocrOverlayRenderer.resolveQueryOffset(overlayLayout, localX, localY)
        } else {
            0
        }
    }

    private fun getOrBuildActiveOverlayLayout(): ReaderOcrOverlayLayout? {
        activeOverlayLayout?.let { return it }

        val overlay = activeOcrOverlay ?: return null
        val result = cachedOcrResult ?: return null
        val bubbleRect = boundingBoxToLocalRect(overlay.boundingBox, result) ?: return null
        return ocrOverlayRenderer.buildLayout(
            bubbleRect = bubbleRect,
            displayText = overlay.displayText,
            textOrientation = overlay.textOrientation,
            highlightRange = overlay.highlightRange,
        )?.also {
            activeOverlayLayout = it
        }
    }

    private fun boundingBoxToLocalRect(
        boundingBox: OcrBoundingBox,
        pageResult: OcrPageResult,
    ): RectF? {
        val localRect = when (val currentPageView = pageView) {
            is SubsamplingScaleImageView -> {
                if (!currentPageView.isReady) return null
                val sourceRect = boundingBox.toSourceRect(pageResult)
                val topLeft = currentPageView.sourceToViewCoord(sourceRect.left, sourceRect.top) ?: return null
                val bottomRight = currentPageView.sourceToViewCoord(sourceRect.right, sourceRect.bottom) ?: return null
                RectF(
                    minOf(topLeft.x, bottomRight.x),
                    minOf(topLeft.y, bottomRight.y),
                    maxOf(topLeft.x, bottomRight.x),
                    maxOf(topLeft.y, bottomRight.y),
                )
            }
            is ImageView -> {
                val drawable = currentPageView.drawable ?: return null
                val sourceRect = boundingBox.toSourceRect(
                    imageWidth = drawable.intrinsicWidth,
                    imageHeight = drawable.intrinsicHeight,
                )
                RectF(sourceRect).also(currentPageView.imageMatrix::mapRect)
            }
            else -> return null
        }

        return RectF(
            localRect.left.coerceIn(0f, width.toFloat()),
            localRect.top.coerceIn(0f, height.toFloat()),
            localRect.right.coerceIn(0f, width.toFloat()),
            localRect.bottom.coerceIn(0f, height.toFloat()),
        ).takeIf { it.width() > 0f && it.height() > 0f }
    }

    private fun invalidateActiveOverlayLayout() {
        activeOverlayLayout = null
    }

    private fun rawPointToLocalPoint(rawX: Float, rawY: Float): PointF? {
        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        getLocationOnScreen(screenLocation)
        getLocationInWindow(windowLocation)

        return PointF(
            rawX - screenLocation[0] + windowLocation[0],
            rawY - screenLocation[1] + windowLocation[1],
        )
    }

    private fun screenRectToLocalRect(screenRect: RectF): RectF? {
        val topLeftLocal = rawPointToLocalPoint(screenRect.left, screenRect.top) ?: return null
        val bottomRightLocal = rawPointToLocalPoint(screenRect.right, screenRect.bottom) ?: return null
        return RectF(
            min(topLeftLocal.x, bottomRightLocal.x),
            min(topLeftLocal.y, bottomRightLocal.y),
            max(topLeftLocal.x, bottomRightLocal.x),
            max(topLeftLocal.y, bottomRightLocal.y),
        )
    }

    private fun displayedImageLocalRect(): RectF? {
        return when (val currentPageView = pageView) {
            is SubsamplingScaleImageView -> {
                if (!currentPageView.isReady) return null
                val topLeft = currentPageView.sourceToViewCoord(0f, 0f) ?: return null
                val bottomRight = currentPageView.sourceToViewCoord(
                    currentPageView.sWidth.toFloat(),
                    currentPageView.sHeight.toFloat(),
                ) ?: return null
                RectF(
                    min(topLeft.x, bottomRight.x),
                    min(topLeft.y, bottomRight.y),
                    max(topLeft.x, bottomRight.x),
                    max(topLeft.y, bottomRight.y),
                )
            }
            is ImageView -> {
                val drawable = currentPageView.drawable ?: return null
                RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat()).also(
                    currentPageView.imageMatrix::mapRect,
                )
            }
            else -> null
        }
    }

    private fun localPointToSourcePoint(localX: Float, localY: Float): PointF? {
        return when (val currentPageView = pageView) {
            is SubsamplingScaleImageView -> {
                if (!currentPageView.isReady) return null
                currentPageView.viewToSourceCoord(localX, localY)
            }
            is ImageView -> {
                val drawable = currentPageView.drawable ?: return null
                val inverse = Matrix()
                if (!currentPageView.imageMatrix.invert(inverse)) return null

                val points = floatArrayOf(localX, localY)
                inverse.mapPoints(points)
                val sourceX = points[0]
                val sourceY = points[1]
                if (sourceX < 0f || sourceY < 0f ||
                    sourceX > drawable.intrinsicWidth.toFloat() ||
                    sourceY > drawable.intrinsicHeight.toFloat()
                ) {
                    null
                } else {
                    PointF(sourceX, sourceY)
                }
            }
            else -> null
        }
    }

    private fun boundingBoxToScreenRect(
        boundingBox: OcrBoundingBox,
        pageResult: OcrPageResult,
    ): RectF? {
        val localRect = when (val currentPageView = pageView) {
            is SubsamplingScaleImageView -> {
                if (!currentPageView.isReady) return null
                val sourceRect = boundingBox.toSourceRect(pageResult)
                val topLeft = currentPageView.sourceToViewCoord(sourceRect.left, sourceRect.top) ?: return null
                val bottomRight = currentPageView.sourceToViewCoord(sourceRect.right, sourceRect.bottom) ?: return null
                RectF(
                    minOf(topLeft.x, bottomRight.x),
                    minOf(topLeft.y, bottomRight.y),
                    maxOf(topLeft.x, bottomRight.x),
                    maxOf(topLeft.y, bottomRight.y),
                )
            }
            is ImageView -> {
                val drawable = currentPageView.drawable ?: return null
                val sourceRect = boundingBox.toSourceRect(
                    imageWidth = drawable.intrinsicWidth,
                    imageHeight = drawable.intrinsicHeight,
                )
                RectF(sourceRect).also(currentPageView.imageMatrix::mapRect)
            }
            else -> return null
        }

        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        getLocationOnScreen(screenLocation)
        getLocationInWindow(windowLocation)

        return RectF(
            localRect.left + screenLocation[0] - windowLocation[0],
            localRect.top + screenLocation[1] - windowLocation[1],
            localRect.right + screenLocation[0] - windowLocation[0],
            localRect.bottom + screenLocation[1] - windowLocation[1],
        )
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
private const val SKIP_ZOOM_OVERLAP_THRESHOLD = 0.85F

private fun OcrBoundingBox.toSourceRect(pageResult: OcrPageResult): RectF {
    return toSourceRect(
        imageWidth = pageResult.imageWidth,
        imageHeight = pageResult.imageHeight,
    )
}

private fun OcrBoundingBox.toSourceRect(
    imageWidth: Int,
    imageHeight: Int,
): RectF {
    return RectF(
        left * imageWidth,
        top * imageHeight,
        right * imageWidth,
        bottom * imageHeight,
    )
}

private class PanelDebugOverlayView(
    context: Context,
) : View(context) {

    private var detections: List<DebugPanelDetection> = emptyList()
    private var bubbles: List<DebugPanelDetection> = emptyList()
    private var pageView: SubsamplingScaleImageView? = null

    private val panelBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 120)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
    }
    private val bubbleBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 60, 60)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.resources.displayMetrics.density * 12f
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textBounds = RectF()

    fun setDetections(
        detections: List<DebugPanelDetection>,
        bubbles: List<DebugPanelDetection>,
        pageView: SubsamplingScaleImageView?,
    ) {
        this.detections = detections
        this.bubbles = bubbles
        this.pageView = pageView
        isVisible = (detections.isNotEmpty() || bubbles.isNotEmpty()) && pageView != null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pageView = pageView ?: return
        if (!isVisible || !pageView.isReady) return

        drawDetections(canvas, pageView, detections, panelBoxPaint, "P")
        drawDetections(canvas, pageView, bubbles, bubbleBoxPaint, "B")
    }

    private fun drawDetections(
        canvas: Canvas,
        pageView: SubsamplingScaleImageView,
        items: List<DebugPanelDetection>,
        boxPaint: Paint,
        prefix: String,
    ) {
        items.forEachIndexed { index, detection ->
            val topLeft = pageView.sourceToViewCoord(
                detection.rect.left.toFloat(),
                detection.rect.top.toFloat(),
            ) ?: return@forEachIndexed
            val bottomRight = pageView.sourceToViewCoord(
                detection.rect.right.toFloat(),
                detection.rect.bottom.toFloat(),
            ) ?: return@forEachIndexed

            val left = minOf(topLeft.x, bottomRight.x)
            val top = minOf(topLeft.y, bottomRight.y)
            val right = maxOf(topLeft.x, bottomRight.x)
            val bottom = maxOf(topLeft.y, bottomRight.y)

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "$prefix${index + 1} ${(detection.confidence * 100).roundToInt()}%"
            val textWidth = labelPaint.measureText(label)
            val textHeight = labelPaint.fontMetrics.let { it.descent - it.ascent }
            val padding = context.resources.displayMetrics.density * 4f
            textBounds.set(
                left,
                (top - textHeight - padding * 2).coerceAtLeast(0f),
                left + textWidth + padding * 2,
                top,
            )
            canvas.drawRect(textBounds, labelBackgroundPaint)
            canvas.drawText(
                label,
                textBounds.left + padding,
                textBounds.bottom - padding - labelPaint.fontMetrics.descent,
                labelPaint,
            )
        }
    }
}
