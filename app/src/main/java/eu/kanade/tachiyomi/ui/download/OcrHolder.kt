package eu.kanade.tachiyomi.ui.download

import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.DownloadItemBinding
import eu.kanade.tachiyomi.util.view.popupMenu
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

internal class OcrHolder(private val view: View, val adapter: OcrAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = DownloadItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.menu.setOnClickListener { it.post { showPopupMenu(it) } }
    }

    private lateinit var item: OcrQueueChapterItem

    fun bind(item: OcrQueueChapterItem) {
        this.item = item
        binding.chapterTitle.text = item.chapterName
        binding.mangaFullTitle.text = item.mangaTitle

        val stateLabel = when (item.state) {
            OcrQueueItemState.Queued -> view.context.stringResource(MR.strings.ocr_preprocess_queued)
            OcrQueueItemState.Error ->
                item.lastError
                    ?: view.context.stringResource(MR.strings.ocr_preprocess_failed, item.chapterName)
            OcrQueueItemState.Scanning -> null
        }

        if (item.state == OcrQueueItemState.Scanning && item.totalPages != null && item.totalPages > 0) {
            binding.downloadProgress.max = item.totalPages * 100
            binding.downloadProgress.setProgressCompat(item.processedPages * 100, true)
            binding.downloadProgressText.text = "${item.processedPages}/${item.totalPages}"
        } else {
            binding.downloadProgress.progress = 0
            binding.downloadProgress.max = 1
            if (stateLabel != null) {
                binding.downloadProgressText.text = stateLabel
            } else {
                binding.downloadProgressText.text = ""
            }
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.ocrItemListener.onItemReleased(position)
        binding.container.isDragged = false
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
        }
    }

    private fun showPopupMenu(view: View) {
        view.popupMenu(
            menuRes = R.menu.download_single,
            initMenu = {
                findItem(R.id.move_to_top).isVisible = bindingAdapterPosition > 0 // Wait, header is present?
                findItem(R.id.move_to_bottom).isVisible = bindingAdapterPosition != adapter.itemCount - 1
            },
            onMenuItemClick = {
                adapter.ocrItemListener.onMenuItemClick(bindingAdapterPosition, this)
            },
        )
    }
}
